#!/usr/bin/env python3
"""Finite localhost-only HTTP comparison; requires a built robot fat JAR and Java 17 JDK.

The JAR is used only as a classpath: this runs WebServerBenchmarkMain, never Robot.
Server and load generator run in separate processes. See --help for invocation.
"""
import argparse
import concurrent.futures
import hashlib
import http.client
import json
import math
import pathlib
import platform
import queue
import shutil
import socket
import subprocess
import sys
import tempfile
import threading
import time

ASSETS = ['/', '/index.css', '/index.js', '/NT4.js', '/msgpack.js', '/field-2026.png']
SCENARIOS = ['idle', 'cold', 'warm_revalidation', 'forced_reload', 'concurrent',
             'slow_disconnected']


def bounded_int(low, high):
    def parse(value):
        result = int(value)
        if not low <= result <= high:
            raise argparse.ArgumentTypeError(f'must be between {low} and {high}')
        return result
    return parse


def quantile(values, fraction):
    ordered = sorted(values)
    return ordered[max(0, math.ceil(fraction * len(ordered)) - 1)] if ordered else None


def copy_assets(source, destination):
    """Reject symlinks; bound the copied input to 512 files and 64 MiB total."""
    destination.mkdir()
    inventory = []
    total = 0
    entries = 0
    for path in source.rglob('*'):
        entries += 1
        if entries > 1024 or path.is_symlink():
            raise ValueError('asset tree exceeds 1024 entries or contains a symlink')
        relative = path.relative_to(source)
        target = destination / relative
        if path.is_dir():
            target.mkdir(exist_ok=True)
            continue
        if not path.is_file():
            raise ValueError(f'not a regular asset: {relative}')
        size = path.stat().st_size
        total += size
        if len(inventory) >= 512 or total > 64 * 1024 * 1024:
            raise ValueError('asset tree exceeds 512 files or 64 MiB')
        shutil.copyfile(path, target)
        inventory.append({'path': str(relative), 'bytes': size,
                          'sha256': hashlib.sha256(target.read_bytes()).hexdigest()})
    for url in ASSETS:
        if not (destination / ('index.html' if url == '/' else url[1:])).is_file():
            raise ValueError(f'missing workload asset: {url}')
    with (destination / '__benchmark_large.bin').open('wb') as fixture:
        for _ in range(256):
            fixture.write(bytes(65536))
    return sorted(inventory, key=lambda item: item['path'])


class Server:
    def __init__(self, command, stderr_path):
        self.stderr = stderr_path.open('w')
        self.process = subprocess.Popen(command, stdin=subprocess.PIPE, stdout=subprocess.PIPE,
                                        stderr=self.stderr, text=True)
        self.lines = queue.Queue(maxsize=128)
        def collect():
            for line in self.process.stdout:
                try:
                    self.lines.put_nowait(line.rstrip())
                except queue.Full:
                    break
        self.reader = threading.Thread(target=collect, daemon=True)
        self.reader.start()
        try:
            line = self.read_line()
            if not line.startswith('PORT='):
                raise RuntimeError(f'unexpected server startup: {line}')
            self.port = int(line[5:])
        except BaseException:
            self.close()
            raise

    def read_line(self):
        try:
            return self.lines.get(timeout=10)
        except queue.Empty as error:
            raise RuntimeError('server protocol timed out') from error

    def metrics(self):
        self.process.stdin.write('metrics\n')
        self.process.stdin.flush()
        result = json.loads(self.read_line())
        try:
            result['rssKiB'] = int(subprocess.check_output(
                ['ps', '-o', 'rss=', '-p', str(self.process.pid)], text=True, timeout=3))
        except (OSError, ValueError, subprocess.SubprocessError):
            result['rssKiB'] = None
        return result

    def close(self):
        try:
            if self.process.poll() is None:
                self.process.stdin.write('stop\n')
                self.process.stdin.flush()
                self.process.wait(timeout=8)
        except (BrokenPipeError, OSError, subprocess.TimeoutExpired):
            self.process.kill()
            self.process.wait(timeout=5)
        finally:
            self.process.stdin.close()
            self.reader.join(timeout=2)
            self.process.stdout.close()
            self.stderr.close()


class Client:
    def __init__(self, port):
        self.connection = http.client.HTTPConnection('127.0.0.1', port, timeout=5)

    def get(self, path, headers=None):
        begin = time.perf_counter()
        count = 0
        try:
            self.connection.request('GET', path, headers=headers or {})
            response = self.connection.getresponse()
            while chunk := response.read(16384):
                count += len(chunk)
            return {'ms': (time.perf_counter() - begin) * 1000, 'status': response.status,
                    'bytes': count, 'etag': response.getheader('ETag'), 'error': None}
        except (OSError, http.client.HTTPException) as error:
            # The next request reconnects; never retry failed requests invisibly.
            self.connection.close()
            return {'ms': (time.perf_counter() - begin) * 1000, 'status': None,
                    'bytes': count, 'etag': None, 'error': type(error).__name__}

    def close(self):
        self.connection.close()


def run_trial(server, trial, warmup):
    client = Client(server.port)
    rows = []
    try:
        for _ in range(warmup):
            for path in ASSETS:
                result = client.get(path)
                if result['status'] != 200:
                    raise RuntimeError(f'warmup failed: {result}')
        for scenario in SCENARIOS:
            validators = ({path: client.get(path)['etag'] for path in ASSETS}
                          if scenario == 'warm_revalidation' else {})
            before = server.metrics()
            begin = time.perf_counter()
            responses = []
            during = None
            if scenario == 'idle':
                time.sleep(0.3)
            elif scenario == 'concurrent':
                def bundle(_):
                    connection = Client(server.port)
                    try:
                        return [connection.get(path) for path in ASSETS]
                    finally:
                        connection.close()
                with concurrent.futures.ThreadPoolExecutor(max_workers=8) as pool:
                    for group in pool.map(bundle, range(8)):
                        responses.extend(group)
            elif scenario == 'slow_disconnected':
                slow = []
                try:
                    for _ in range(4):
                        connection = socket.socket()
                        slow.append(connection)
                        connection.setsockopt(socket.SOL_SOCKET, socket.SO_RCVBUF, 1024)
                        connection.settimeout(3)
                        connection.connect(('127.0.0.1', server.port))
                        connection.sendall(b'GET /__benchmark_large.bin HTTP/1.1\r\n'
                                           b'Host: localhost\r\nConnection: close\r\n\r\n')
                    time.sleep(0.15)
                    during = server.metrics()
                finally:
                    for connection in slow:
                        connection.close()
                for _ in range(4):
                    with socket.create_connection(('127.0.0.1', server.port), timeout=3) as abrupt:
                        abrupt.sendall(b'GET /__benchmark_large.bin HTTP/1.1\r\n'
                                       b'Host: localhost\r\n\r\n')
                responses.append(client.get('/'))
            else:
                for _ in range(3):
                    for path in ASSETS:
                        headers = ({'Cache-Control': 'no-cache', 'Pragma': 'no-cache'}
                                   if scenario == 'forced_reload' else
                                   {'If-None-Match': validators[path]} if validators.get(path) else {})
                        responses.append(client.get(path, headers))
            elapsed = time.perf_counter() - begin
            after = server.metrics()
            times = [response['ms'] for response in responses]
            row = {'trial': trial, 'scenario': scenario, 'requests': len(responses),
                   'bodyBytes': sum(r['bytes'] for r in responses if not r['error']),
                   'responses200': sum(r['status'] == 200 for r in responses),
                   'responses304': sum(r['status'] == 304 for r in responses),
                   'errors': sum(bool(r['error']) or (r['status'] or 0) >= 400 for r in responses),
                   'clientErrors': [r['error'] for r in responses if r['error']],
                   'elapsedMs': elapsed * 1000,
                   'serverCpuMs': (after['cpuNs'] - before['cpuNs']) / 1e6,
                   'gcCount': after['gcCount'] - before['gcCount'],
                   'gcMs': after['gcMs'] - before['gcMs'],
                   'before': before, 'after': after,
                   'latencyMedianMs': quantile(times, .5), 'latencyP95Ms': quantile(times, .95),
                   'latencyP99Ms': quantile(times, .99), 'latencyMaxMs': max(times) if times else None}
            if during is not None:
                row['duringSlow'] = during
                row['bodyByteScope'] = 'completed recovery request only; aborted bytes not measured'
            rows.append(row)
    finally:
        client.close()
    return rows


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument('--jar', required=True, type=pathlib.Path)
    parser.add_argument('--assets', required=True, type=pathlib.Path)
    parser.add_argument('--java', required=True, type=pathlib.Path, help='Java 17 JDK bin/java')
    parser.add_argument('--output', required=True, type=pathlib.Path)
    parser.add_argument('--runs', type=bounded_int(1, 10), default=3)
    parser.add_argument('--warmup', type=bounded_int(0, 200), default=20, help='six-asset bundles')
    args = parser.parse_args()
    args.jar = args.jar.resolve(strict=True)
    args.java = args.java.resolve(strict=True)
    args.assets = args.assets.resolve(strict=True)
    version = subprocess.check_output([str(args.java), '-version'], stderr=subprocess.STDOUT,
                                      text=True, timeout=10)
    if 'version "17.' not in version:
        parser.error('--java must point to Java 17')
    rows = []
    with tempfile.TemporaryDirectory(prefix='season2027-http-benchmark-') as temporary:
        root = pathlib.Path(temporary)
        inventory = copy_assets(args.assets, root / 'assets')
        compile_command = [str(args.java.with_name('javac')), '-cp', str(args.jar), '-d', str(root),
                           str(pathlib.Path(__file__).with_name('WebServerBenchmarkMain.java'))]
        subprocess.run(compile_command, check=True, timeout=30)
        command = [str(args.java), '-Xms64m', '-Xmx128m', '-cp', f'{root}:{args.jar}',
                   'WebServerBenchmarkMain', str(root / 'assets')]
        for trial in range(1, args.runs + 1):
            stderr = root / f'server-{trial}.stderr'
            server = Server(command, stderr)
            try:
                rows.extend(run_trial(server, trial, args.warmup))
            finally:
                server.close()
            print(f'Completed localhost trial {trial}/{args.runs}', file=sys.stderr)
        output = {'environment': {'platform': platform.platform(), 'python': sys.version,
                                  'javaVersion': version, 'invocation': sys.argv,
                                  'compileCommand': compile_command, 'serverCommand': command,
                                  'jarSha256': hashlib.sha256(args.jar.read_bytes()).hexdigest()},
                  'assets': inventory, 'workloadPaths': ASSETS, 'warmupBundles': args.warmup,
                  'quantiles': 'nearest rank, all attempted measured HTTP requests', 'results': rows,
                  'limitations': ['Desktop localhost only; roboRIO performance NOT MEASURED.',
                    'Heap/RSS are boundary snapshots, not allocation totals or memory peaks.',
                    'CPU includes the same metrics snapshot mechanism; optional application counters add overhead.',
                    'No aborted network-byte accounting, robot-loop timing, or browser execution measurement.',
                    'Cold means requests without validators after JVM warmup, not a cold filesystem/JVM.',
                    'Warm revalidation is synthetic HTTP; browser verification is separate.',
                    'Application high-water fields, when present, are cumulative since server startup.',
                    'Latency includes failed attempts; errors are reported separately.']}
        args.output.write_text(json.dumps(output, indent=2) + '\n')
    print(f'Wrote {len(rows)} scenario rows to {args.output}')


if __name__ == '__main__':
    main()
