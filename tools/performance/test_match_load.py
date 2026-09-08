import argparse
import contextlib
import http.server
import importlib.util
import io
import json
from pathlib import Path
import tempfile
import threading
import unittest
from unittest import mock

spec = importlib.util.spec_from_file_location('match_load', Path(__file__).with_name('match_load.py'))
load = importlib.util.module_from_spec(spec)
spec.loader.exec_module(load)


class LoadTests(unittest.TestCase):
    def test_explicit_target_authority(self):
        self.assertEqual(load.validate_target('http://127.0.0.1:8080').port, 8080)
        for target in ('http://localhost:8080', 'http://10.18.84.2:5800',
                       'http://127.0.0.1:8080/path', 'https://127.0.0.1:8080',
                       'http://user:secret@127.0.0.1:8080'):
            with self.assertRaises(ValueError):
                load.validate_target(target)
        self.assertEqual(load.validate_target('http://10.18.84.2:5800', '10.18.84.2', True).hostname,
                         '10.18.84.2')

    def test_offhost_abort_policy_without_network(self):
        self.assertIsNone(load.offhost_abort_reason(False, 'TIMEOUT', 2000, 1000))
        self.assertIsNone(load.offhost_abort_reason(True, 200, 999, 1000))
        self.assertIsNone(load.offhost_abort_reason(True, 304, 0, 1000))
        self.assertIn('failure', load.offhost_abort_reason(True, 503, 1, 1000))
        self.assertIn('failure', load.offhost_abort_reason(True, 'ERROR', 1, 1000))
        self.assertIn('threshold', load.offhost_abort_reason(True, 'TIMEOUT', 1000, 1000))
        self.assertIn('threshold', load.offhost_abort_reason(True, 200, 1001, 1000))

    def test_offhost_error_aborts_remaining_offers_using_fake_io(self):
        class FakeSocket:
            def settimeout(self, _): pass
            def shutdown(self, _): pass
            def close(self): pass
        class Response:
            status = 503
            def close(self): pass
        class Connection:
            def __init__(self, *_args, **_kwargs): self.sock = None
            def connect(self): self.sock = FakeSocket()
            def request(self, *_args, **_kwargs): pass
            def getresponse(self): return Response()
            def close(self): self.sock = None
        with tempfile.TemporaryDirectory() as temp:
            output = Path(temp) / 'offhost-abort.csv'
            args = argparse.Namespace(target='http://192.0.2.1:8080', allow_host='192.0.2.1',
                acknowledge_offhost=True, duration=10, rps=10, clients=1,
                max_bytes_per_second=1024, output=str(output), mode='cold')
            # TEST-NET address never contacts a socket: all connection IO is replaced.
            with mock.patch.object(load.http.client, 'HTTPConnection', Connection), \
                 contextlib.redirect_stdout(io.StringIO()):
                self.assertEqual(load.run(args), 2)
            summary = json.loads(output.with_suffix('.summary.json').read_text())
            self.assertTrue(summary['aborted'])
            self.assertIn('failure', summary['abort_reason'])
            self.assertGreater(summary['cancelled_before_schedule'], 0)
            self.assertEqual(summary['worker_threads_remaining'], 0)

    def test_bounded_budget_recovery(self):
        clock = [0.0]
        budget = load.ByteBudget(16, lambda: clock[0])
        self.assertTrue(budget.take(16))
        self.assertFalse(budget.take(1))
        clock[0] = 1.0
        self.assertTrue(budget.take(1))

    def test_finite_ladder_offered_count_and_zero_recovery(self):
        phases = load.rate_phases(5, 2, '4:1,8:1,0:1', 1)
        self.assertEqual(phases, [(2, 1), (4, 1), (8, 1), (0, 1), (0, 1)])
        due = list(load.scheduled_requests(0, phases))
        self.assertEqual(len(due), 14)
        self.assertLess(max(due), 3_000_000_000)
        with self.assertRaises(ValueError):
            load.rate_phases(2, 2, '81:1')
        with self.assertRaises(ValueError):
            load.rate_phases(2, 2, '10:3')

    def test_queue_bound_and_recovery(self):
        jobs = load.queue.Queue(64)
        for index in range(64):
            jobs.put_nowait(index)
        with self.assertRaises(load.queue.Full):
            jobs.put_nowait(65)
        for index in range(64):
            self.assertEqual(jobs.get_nowait(), index)
            jobs.task_done()
        jobs.put_nowait(66)
        self.assertEqual(jobs.get_nowait(), 66)

    def test_total_deadline_and_abort_interrupt_blocked_response(self):
        for injected_abort in (False, True):
            entered, interrupted = threading.Event(), threading.Event()
            clock = [1_000_000_000]
            class FakeSocket:
                def settimeout(self, _): pass
                def shutdown(self, _): interrupted.set()
                def close(self): pass
            class Response:
                status = 200
                def close(self): pass
                def getheader(self, key):
                    return '3' if key == 'Content-Length' else None
                def read(self, _):
                    entered.set()
                    if not injected_abort:
                        clock[0] += 3_000_000_000
                    if not interrupted.wait(3):
                        raise AssertionError('Deadline watchdog did not interrupt socket')
                    return b''
            class Connection:
                def __init__(self, *_args, **_kwargs): self.sock = None
                def connect(self): self.sock = FakeSocket()
                def request(self, *_args, **_kwargs): pass
                def getresponse(self): return Response()
                def close(self): self.sock = None
            def schedule(*_):
                yield clock[0]
                if injected_abort:
                    self.assertTrue(entered.wait(3))
                    raise KeyboardInterrupt()
            with tempfile.TemporaryDirectory() as temp:
                output = Path(temp) / 'deadline.csv'
                args = argparse.Namespace(target='http://127.0.0.1:8080',
                    allow_host=None, acknowledge_offhost=False, duration=.1, rps=10,
                    clients=1, max_bytes_per_second=1024, output=str(output), mode='cold')
                with mock.patch.object(load.http.client, 'HTTPConnection', Connection), \
                     mock.patch.object(load.time, 'monotonic_ns', lambda: clock[0]), \
                     mock.patch.object(load, 'scheduled_requests', schedule), \
                     contextlib.redirect_stdout(io.StringIO()):
                    self.assertEqual(load.run(args), 2)
                summary = json.loads(output.with_suffix('.summary.json').read_text())
                self.assertTrue(interrupted.is_set())
                self.assertEqual(summary['aborted'], injected_abort)
                self.assertEqual(summary['failed'], 1)
                if not injected_abort:
                    self.assertEqual(summary['timed_out'], 1)

    def test_ready_marker_precedes_offered_start(self):
        with tempfile.TemporaryDirectory() as temp:
            ready, start = Path(temp) / 'ready.json', Path(temp) / 'start'
            result = []
            thread = threading.Thread(target=lambda: result.append(load.await_start(ready, start)))
            thread.start()
            # Synchronise on the explicit ready file, with a bounded safety timeout.
            import time
            deadline = time.monotonic() + 5
            while not ready.is_file() and time.monotonic() < deadline:
                time.sleep(.001)
            self.assertTrue(ready.is_file())
            self.assertEqual(result, [])
            release = time.monotonic_ns()
            start.touch()
            thread.join(5)
            self.assertFalse(thread.is_alive())
            self.assertGreaterEqual(result[0][0], release)
            self.assertTrue(json.loads(ready.read_text())['ready'])

    def test_bursts_preserve_offered_count(self):
        due = list(load.scheduled_requests(0, [(8, 2)], 4))
        self.assertEqual(len(due), 16)
        self.assertEqual(due[:4], [0] * 4)
        self.assertEqual(due[4:8], [500_000_000] * 4)

    def test_slow_disconnect_and_recovery(self):
        class Handler(http.server.BaseHTTPRequestHandler):
            protocol_version = 'HTTP/1.1'
            def do_GET(self):
                body = b'a' * 100_000 if self.path == '/Other.png' else b'recovered'
                self.send_response(200)
                self.send_header('Content-Length', str(len(body)))
                self.end_headers()
                self.wfile.write(body)
            def log_message(self, *_):
                pass
        class Server(http.server.ThreadingHTTPServer):
            def handle_error(self, *_):
                pass
        server = Server(('127.0.0.1', 0), Handler)
        thread = threading.Thread(target=server.serve_forever)
        thread.start()
        try:
            with tempfile.TemporaryDirectory() as temp:
                output = Path(temp) / 'fault.csv'
                args = argparse.Namespace(target=f'http://127.0.0.1:{server.server_port}',
                    allow_host=None, acknowledge_offhost=False, duration=.5, rps=4,
                    clients=1, max_bytes_per_second=1024*1024, output=str(output),
                    mode='slow-disconnected')
                with contextlib.redirect_stdout(io.StringIO()):
                    self.assertEqual(load.run(args), 0)
                summary = json.loads(output.with_suffix('.summary.json').read_text())
                self.assertEqual(summary['scheduled'], 2)
                self.assertEqual(summary['intentional_disconnects'], 1)
                self.assertEqual(summary['recovery_responses'], 1)
                self.assertEqual(summary['completed'], 1)
                self.assertEqual(summary['failed'], 0)
                self.assertIn('EXPECTED_DISCONNECT', output.read_text())
        finally:
            server.shutdown()
            server.server_close()
            thread.join()

    def test_conditional_bodyless_responses(self):
        class Handler(http.server.BaseHTTPRequestHandler):
            protocol_version = 'HTTP/1.1'
            def do_GET(self):
                cached = self.headers.get('If-None-Match') == 'W/"test"'
                self.send_response(304 if cached else 200)
                self.send_header('ETag', 'W/"test"')
                if not cached:
                    self.send_header('Content-Length', '3')
                self.end_headers()
                if not cached:
                    self.wfile.write(b'abc')
            def log_message(self, *_):
                pass
        server = http.server.ThreadingHTTPServer(('127.0.0.1', 0), Handler)
        thread = threading.Thread(target=server.serve_forever)
        thread.start()
        try:
            with tempfile.TemporaryDirectory() as temp:
                output = Path(temp) / 'warm.csv'
                args = argparse.Namespace(target=f'http://127.0.0.1:{server.server_port}',
                    allow_host=None, acknowledge_offhost=False, duration=.15, rps=80,
                    clients=1, max_bytes_per_second=1024, output=str(output), mode='warm')
                with contextlib.redirect_stdout(io.StringIO()):
                    self.assertEqual(load.run(args), 0)
                summary = json.loads(output.with_suffix('.summary.json').read_text())
                self.assertEqual(summary['status_200'], 6)
                self.assertEqual(summary['status_304'], 6)
                self.assertEqual(summary['bytes'], 18)
        finally:
            server.shutdown()
            server.server_close()
            thread.join()

    def test_success_and_http_error_accounting(self):
        class Handler(http.server.BaseHTTPRequestHandler):
            protocol_version = 'HTTP/1.1'
            failure = False
            def do_GET(self):
                status = 404 if self.failure else 200
                self.send_response(status)
                self.send_header('Content-Length', '3')
                self.end_headers()
                self.wfile.write(b'abc')
            def log_message(self, *_):
                pass
        class Server(http.server.ThreadingHTTPServer):
            def handle_error(self, *_):
                pass  # Rejected body connections are intentionally closed.
        server = Server(('127.0.0.1', 0), Handler)
        thread = threading.Thread(target=server.serve_forever)
        thread.start()
        try:
            with tempfile.TemporaryDirectory() as temp:
                for failure in (False, True):
                    Handler.failure = failure
                    output = Path(temp) / ('failure.csv' if failure else 'success.csv')
                    args = argparse.Namespace(target=f'http://127.0.0.1:{server.server_port}',
                        allow_host=None, acknowledge_offhost=False, duration=.05, rps=40,
                        clients=1, max_bytes_per_second=1024, output=str(output), mode='cold')
                    with contextlib.redirect_stdout(io.StringIO()):
                        code = load.run(args)
                    summary = json.loads(output.with_suffix('.summary.json').read_text())
                    self.assertEqual(summary['scheduled'], 2)
                    self.assertEqual(summary['started'], 2)
                    self.assertEqual(summary['completed'] + summary['failed'], 2)
                    self.assertEqual(summary['completed'], 0 if failure else 2)
                    self.assertEqual(code, 2 if failure else 0)
        finally:
            server.shutdown()
            server.server_close()
            thread.join()

if __name__ == '__main__':
    unittest.main()
