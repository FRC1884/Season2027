#!/usr/bin/env python3
"""Finite read-only HTTP load. Scheduling is independent of request completion."""
import argparse
import csv
import http.client
import ipaddress
import json
import os
from pathlib import Path
import queue
import resource
import threading
import sys
import socket
import time
from urllib.parse import urlsplit

ASSETS = ('/', '/index.css', '/index.js', '/NT4.js', '/msgpack.js', '/field-2026.png')
FIELDS = ('kind', 'scheduled_ns', 'start_ns', 'end_ns', 'status', 'bytes',
          'latency_ms', 'scheduling_delay_ms', 'detail')


def validate_target(target, allow_host=None, acknowledge=False):
    parsed = urlsplit(target)
    if parsed.scheme != 'http' or not parsed.hostname or parsed.username or parsed.password:
        raise ValueError('Target must be an explicit http://host:port origin without credentials')
    if parsed.path not in ('', '/') or parsed.query or parsed.fragment or parsed.port is None:
        raise ValueError('Target must specify only host and port')
    try:
        local = ipaddress.ip_address(parsed.hostname).is_loopback
    except ValueError:
        raise ValueError('Use a literal IP address; DNS resolution has no bounded deadline here')
    if not local and not (allow_host == parsed.hostname and acknowledge):
        raise ValueError('Off-host traffic requires exact --allow-host and --acknowledge-offhost')
    return parsed


def rate_phases(duration, base_rate, ladder='', delay=0, cap=80):
    if delay < 0 or delay > duration:
        raise ValueError('Ladder delay must be within duration')
    if not ladder:
        return [(base_rate, duration)]
    phases = [(base_rate, delay)] if delay else []
    for item in ladder.split(','):
        rate, hold = map(float, item.split(':'))
        if not (0 <= rate <= cap and 0 < hold <= 7200):
            raise ValueError('Each ladder step requires bounded rate and positive hold')
        phases.append((rate, hold))
    if len(phases) > 16:
        raise ValueError('At most 16 ladder steps')
    if sum(hold for _, hold in phases) > duration:
        raise ValueError('Ladder exceeds total duration')
    remainder = duration - sum(hold for _, hold in phases)
    if remainder > 0:
        phases.append((0, remainder))
    return phases


def scheduled_requests(start, phases, burst_size=1):
    offset = 0
    for rate, hold in phases:
        for index in range(int(rate * hold)):
            yield start + int((offset + (index // burst_size) * burst_size / rate) * 1e9)
        offset += hold


def await_start(ready_file=None, start_marker=None, warmup_seconds=0):
    if not 0 <= warmup_seconds <= 10:
        raise ValueError('Warm-up seconds must be 0..10')
    before = time.monotonic_ns()
    if warmup_seconds:
        time.sleep(warmup_seconds)
    if ready_file:
        path = Path(ready_file)
        path.parent.mkdir(parents=True, exist_ok=True)
        temporary = path.with_name(path.name + '.tmp.' + str(os.getpid()))
        temporary.write_text(json.dumps(dict(ready=True, pid=os.getpid(),
            ready_monotonic_ns=time.monotonic_ns(), warmup_seconds=warmup_seconds)))
        temporary.replace(path)
    if start_marker:
        deadline = time.monotonic() + 60
        while not Path(start_marker).is_file():
            if time.monotonic() >= deadline:
                raise TimeoutError('Start marker not received within 60 seconds')
            time.sleep(.005)
    return time.monotonic_ns(), time.monotonic_ns() - before


class ByteBudget:
    """Shared fixed-window body budget; excess work is dropped, never queued elsewhere."""
    def __init__(self, rate, clock=time.monotonic):
        self.rate, self.clock, self.window, self.used = rate, clock, clock(), 0
        self.lock = threading.Lock()

    def take(self, amount):
        with self.lock:
            now = self.clock()
            if now - self.window >= 1:
                self.window, self.used = now, 0
            if self.used + amount > self.rate:
                return False
            self.used += amount
            return True


def offhost_abort_reason(offhost, status, latency_ms, limit_ms):
    """Host HTTP abort policy only; never changes Driver Station or robot state."""
    if not offhost:
        return None
    if latency_ms >= limit_ms:
        return 'HTTP scheduling-inclusive latency reached configured abort threshold'
    if status not in (200, 304):
        return 'Unexpected HTTP failure: ' + str(status)
    return None


def run(args):
    parsed = validate_target(args.target, args.allow_host, args.acknowledge_offhost)
    if not (0 < args.duration <= 7200 and 0 < args.rps <= 80 and 1 <= args.clients <= 8):
        raise ValueError('Limits: duration (0,7200], rps (0,80], clients [1,8]')
    if not 1 <= args.max_bytes_per_second <= 8 * 1024 * 1024:
        raise ValueError('Body budget must be 1..8388608 bytes/s')
    if not 0 <= getattr(args, 'warmup_seconds', 0) <= 10:
        raise ValueError('Warm-up seconds must be 0..10')
    burst_size = getattr(args, 'burst_size', 1)
    if not 1 <= burst_size <= 8:
        raise ValueError('Burst size must be 1..8')
    offhost = not ipaddress.ip_address(parsed.hostname).is_loopback
    abort_latency_ms = getattr(args, 'abort_latency_ms', 1000)
    if not 1 <= abort_latency_ms <= 2000:
        raise ValueError('Abort latency must be 1..2000 ms')
    slow_fault = args.mode == 'slow-disconnected'
    if slow_fault and not ipaddress.ip_address(parsed.hostname).is_loopback:
        raise ValueError('Intentional client faults are desktop loopback only')
    if slow_fault and int(args.duration * args.rps) < 2:
        raise ValueError('Slow/disconnected needs at least two scheduled requests')
    output = Path(args.output)
    output.parent.mkdir(parents=True, exist_ok=True)
    jobs = queue.Queue(64)
    lock = threading.Lock()
    counts = dict(scheduled=0, started=0, completed=0, failed=0, timed_out=0,
                  locally_dropped=0, bytes=0, status_200=0, status_304=0,
                  intentional_disconnects=0, recovery_responses=0)
    budget = ByteBudget(args.max_bytes_per_second)
    abort = threading.Event()
    abort_reason = [None]
    active = {}
    watchdog_stop = threading.Event()
    cpu_start = time.process_time_ns()
    start = time.monotonic_ns()
    coordination_ns = 0
    phases = rate_phases(args.duration, args.rps, getattr(args, 'ladder', ''), getattr(args, 'ladder_delay', 0))
    with output.open('w', newline='') as stream:
        writer = csv.DictWriter(stream, fieldnames=FIELDS)
        writer.writeheader()

        def record(due, began, ended, status, size, detail):
            with lock:
                writer.writerow(dict(kind='http', scheduled_ns=due, start_ns=began,
                    end_ns=ended, status=status, bytes=size,
                    latency_ms=(ended-due)/1e6,
                    scheduling_delay_ms=(began-due)/1e6 if began else '', detail=detail))
                stream.flush()

        def request_abort(reason):
            with lock:
                if abort_reason[0] is None:
                    abort_reason[0] = reason
            abort.set()

        def interrupt_socket(sock):
            try:
                sock.shutdown(socket.SHUT_RDWR)
            except OSError:
                pass
            sock.close()

        def watchdog():
            while not watchdog_stop.wait(.01):
                with lock:
                    snapshot = list(active.values())
                now = time.monotonic_ns()
                for deadline, sock in snapshot:
                    if abort.is_set() or now >= deadline:
                        interrupt_socket(sock)

        def worker():
            conn = http.client.HTTPConnection(parsed.hostname, parsed.port, timeout=2)
            validators = {}
            try:
                while True:
                    job = jobs.get()
                    if job is None:
                        jobs.task_done()
                        break
                    index, due = job
                    if abort.is_set():
                        with lock:
                            counts['locally_dropped'] += 1
                        record(due, 0, time.monotonic_ns(), 'LOCAL_DROP', 0, 'run aborted before start')
                        jobs.task_done()
                        continue
                    began = time.monotonic_ns()
                    request_deadline = began + 2_000_000_000
                    if offhost:
                        request_deadline = min(request_deadline, due + int(abort_latency_ms * 1e6))
                    path = ('/planner-autos/index.json' if index % 19 == 18 else
                            '/rebuilt-spots.json' if index % 17 == 16 else ASSETS[index % 6])
                    inject_disconnect = slow_fault and index % 2 == 0
                    if slow_fault:
                        path = '/Other.png' if inject_disconnect else '/'
                    headers = {}
                    mode = args.mode if args.mode != 'mixed' else ('cold','warm','forced')[index // 6 % 3]
                    if mode == 'warm' and path in validators:
                        headers['If-None-Match'] = validators[path]
                    if mode == 'forced':
                        headers['Cache-Control'] = 'no-cache'
                    size, status, detail = 0, 'ERROR', path
                    response = None
                    with lock:
                        counts['started'] += 1
                    try:
                        if time.monotonic_ns() >= request_deadline:
                            raise TimeoutError('Scheduling-inclusive request deadline exceeded')
                        if inject_disconnect:
                            conn.close()
                        conn.timeout = max(.001, (request_deadline-time.monotonic_ns())/1e9)
                        if conn.sock is None:
                            conn.connect()
                        conn.sock.settimeout(conn.timeout)
                        if inject_disconnect:
                            conn.sock.setsockopt(socket.SOL_SOCKET, socket.SO_RCVBUF, 4096)
                        with lock:
                            active[threading.get_ident()] = (request_deadline, conn.sock)
                        conn.request('GET', path, headers=headers)
                        response = conn.getresponse()
                        status = response.status
                        if status not in (200, 304):
                            raise ValueError('Unexpected HTTP status %s' % status)
                        if status == 304 and 'If-None-Match' not in headers:
                            raise ValueError('Unsolicited 304')
                        if status == 200 and 'If-None-Match' in headers and headers['If-None-Match'] == response.getheader('ETag'):
                            raise ValueError('Unchanged validator returned full body')
                        declared = response.getheader('Content-Length')
                        if declared is not None and (int(declared) < 0 or int(declared) > 1024*1024):
                            raise ValueError('Invalid or excessive response length')
                        expected = int(declared) if declared is not None else None
                        if inject_disconnect:
                            if status != 200 or expected is None or expected <= 4096:
                                raise ValueError('Fault requires a nonempty large fixed-length asset')
                            for _ in range(4):
                                block = response.read(1024)
                                if len(block) != 1024 or not budget.take(len(block)):
                                    raise ValueError('Fault body truncated or bandwidth exhausted')
                                size += len(block)
                                time.sleep(0.05)
                            conn.sock.shutdown(socket.SHUT_RDWR)
                            conn.close()
                            response.close()
                            status = 'EXPECTED_DISCONNECT'
                            detail += ': desktop intentional 4x1024B reads with 50ms stalls then close'
                            with lock:
                                counts['intentional_disconnects'] += 1
                            continue
                        while True:
                            if time.monotonic_ns() >= request_deadline or abort.is_set():
                                raise TimeoutError('Total request deadline or run abort')
                            block = response.read(16384)
                            if not block:
                                break
                            size += len(block)
                            if size > 1024*1024:
                                raise ValueError('Response exceeds 1 MiB')
                            if not budget.take(len(block)):
                                raise ValueError('Body bandwidth budget exhausted')
                        if status == 304 and size:
                            raise ValueError('304 has a body')
                        if status == 200 and (size == 0 or (expected is not None and expected != size)):
                            raise ValueError('Empty/truncated asset response')
                        if (path.startswith('/planner-autos/') or path == '/rebuilt-spots.json') and response.getheader('Cache-Control') != 'no-store':
                            raise ValueError('Mutable route lost no-store policy')
                        etag = response.getheader('ETag')
                        if etag:
                            validators[path] = etag
                        with lock:
                            counts['completed'] += 1
                            counts['status_' + str(status)] += 1
                            if slow_fault and counts['intentional_disconnects'] > 0:
                                counts['recovery_responses'] += 1
                                detail += ': recovery after intentional disconnect'
                    except TimeoutError as exc:
                        status, detail = 'TIMEOUT', path + ': ' + str(exc)
                        with lock:
                            counts['failed'] += 1
                            counts['timed_out'] += 1
                        conn.close()
                    except (OSError, http.client.HTTPException, ValueError) as exc:
                        if time.monotonic_ns() >= request_deadline:
                            status = 'TIMEOUT'
                            with lock:
                                counts['timed_out'] += 1
                        detail = path + ': ' + str(exc)
                        if status in (200, 304):
                            status = 'INVALID_RESPONSE'
                        with lock:
                            counts['failed'] += 1
                        conn.close()
                    finally:
                        if response is not None:
                            response.close()
                        with lock:
                            active.pop(threading.get_ident(), None)
                            counts['bytes'] += size
                        ended = time.monotonic_ns()
                        reason = offhost_abort_reason(offhost, status, (ended-due)/1e6, abort_latency_ms)
                        if reason:
                            request_abort(reason)
                        record(due, began, ended, status, size, detail)
                        jobs.task_done()
            finally:
                conn.close()

        threads = [threading.Thread(target=worker, name=f'MatchHttpLoad-{i}', daemon=True) for i in range(args.clients)]
        guard = threading.Thread(target=watchdog, name='MatchHttpDeadline', daemon=True)
        guard.start()
        for thread in threads:
            thread.start()
        try:
            start, coordination_ns = await_start(getattr(args, 'ready_file', None),
                getattr(args, 'start_marker', None), getattr(args, 'warmup_seconds', 0))
            budget.window = time.monotonic()
            for index, due in enumerate(scheduled_requests(start, phases, burst_size)):
                remaining = (due - time.monotonic_ns()) / 1e9
                if abort.is_set() or (remaining > 0 and abort.wait(remaining)):
                    break
                counts['scheduled'] += 1
                try:
                    jobs.put_nowait((index, due))
                except queue.Full:
                    counts['locally_dropped'] += 1
                    record(due, 0, time.monotonic_ns(), 'LOCAL_DROP', 0, 'queue capacity 64')
        except (KeyboardInterrupt, TimeoutError) as exc:
            request_abort('Human interrupt' if isinstance(exc, KeyboardInterrupt) else 'Start coordination timed out')
        finally:
            if not abort.is_set():
                remaining = (start + int(args.duration * 1e9) - time.monotonic_ns()) / 1e9
                if remaining > 0:
                    try:
                        abort.wait(remaining)
                    except KeyboardInterrupt:
                        request_abort('Human interrupt')
            # Stop accepting queued work after the finite offered window.
            # Allow only already-started transfers their bounded request deadline.
            while True:
                try:
                    index, due = jobs.get_nowait()
                except queue.Empty:
                    break
                counts['locally_dropped'] += 1
                record(due, 0, time.monotonic_ns(), 'LOCAL_DROP', 0, 'offered window ended before start')
                jobs.task_done()
            for _ in threads:
                jobs.put_nowait(None)
            shutdown_deadline = time.monotonic() + 3
            for thread in threads:
                thread.join(max(0, shutdown_deadline-time.monotonic()))
            alive = [thread for thread in threads if thread.is_alive()]
            if alive:
                request_abort('Worker shutdown exceeded bounded wait')
                with lock:
                    sockets = [sock for _, sock in active.values()]
                for sock in sockets:
                    interrupt_socket(sock)
                for thread in alive:
                    thread.join(.1)
            watchdog_stop.set()
            guard.join(.2)
    end = time.monotonic_ns()
    summary = dict(counts, abort_reason=abort_reason[0], offhost_abort_latency_ms=abort_latency_ms if offhost else None,
        cancelled_before_schedule=max(0, sum(int(rate*hold) for rate, hold in phases)-counts['scheduled']),
        offered_start_monotonic_ns=start,
        offered_stop_monotonic_ns=start + int(args.duration * 1e9),
        coordination_ns=coordination_ns, warmup_seconds=getattr(args, 'warmup_seconds', 0), aborted=abort.is_set(), duration_s=(end-start)/1e9,
        offered_rps=args.rps, achieved_rps=counts['completed']/((end-start)/1e9) if end > start else None,
        generator_cpu_ns=time.process_time_ns()-cpu_start,
        generator_cpu_scope="startup_through_shutdown",
        worker_threads_remaining=sum(thread.is_alive() for thread in threads),
        generator_maxrss_native=resource.getrusage(resource.RUSAGE_SELF).ru_maxrss,
        generator_maxrss_units='bytes' if sys.platform == 'darwin' else 'KiB',
        generator_peak_rss_bytes=resource.getrusage(resource.RUSAGE_SELF).ru_maxrss * (1 if sys.platform == 'darwin' else 1024), pid=os.getpid(),
        queue_capacity=64, response_limit_bytes=1024*1024, timeout_s=2, request_deadline_s=2, shutdown_budget_s=3, retries=0,
        target=args.target, rate_phases=phases, burst_size=burst_size, intentional_fault_scenario=slow_fault,
        valid=not abort.is_set() and counts['failed']==0 and counts['locally_dropped']==0
        and (not slow_fault or (counts['intentional_disconnects'] > 0 and counts['recovery_responses'] > 0)))
    output.with_suffix('.summary.json').write_text(json.dumps(summary, indent=2)+'\n')
    print(json.dumps(summary))
    return 0 if summary['valid'] else 2


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument('--target', required=True)
    parser.add_argument('--output', required=True)
    parser.add_argument('--duration', type=float, default=10)
    parser.add_argument('--rps', type=float, default=2)
    parser.add_argument('--clients', type=int, default=1)
    parser.add_argument('--mode', choices=('cold','warm','forced','mixed','slow-disconnected'), default='mixed')
    parser.add_argument('--max-bytes-per-second', type=int, default=4*1024*1024)
    parser.add_argument('--abort-latency-ms', type=float, default=1000,
                        help='Off-host abort threshold including scheduling delay (1..2000 ms); human retains DS/fault stop responsibility')
    parser.add_argument('--ready-file')
    parser.add_argument('--start-marker')
    parser.add_argument('--warmup-seconds', type=float, default=0)
    parser.add_argument('--burst-size', type=int, default=1)
    parser.add_argument('--ladder', default='')
    parser.add_argument('--ladder-delay', type=float, default=0)
    parser.add_argument('--allow-host')
    parser.add_argument('--acknowledge-offhost', action='store_true')
    args = parser.parse_args()
    try:
        return run(args)
    except ValueError as exc:
        parser.error(str(exc))

if __name__ == '__main__':
    raise SystemExit(main())
