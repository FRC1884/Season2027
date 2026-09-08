"""Host command boundaries; no robot processes or network targets are contacted."""
import argparse
import json
from pathlib import Path
import tempfile
import unittest
from unittest.mock import patch
import match_suite


class SuiteTests(unittest.TestCase):
    def args(self, **changes):
        values = dict(timeline=None, command='run', cycles=2)
        return argparse.Namespace(**(values | changes))

    def test_full_timeline_and_soak_are_finite_and_preserve_phases(self):
        phases = match_suite.phase_config(self.args(), 'NORMAL')
        self.assertEqual(196, sum(p['durationSeconds'] for p in phases))
        self.assertEqual(135, next(p['durationSeconds'] for p in phases if p['name']=='teleop'))
        soak = match_suite.phase_config(self.args(command='soak'), 'HARD')
        self.assertEqual(phases*2, soak)
        self.assertEqual(100, sum(p['durationSeconds'] for p in match_suite.phase_config(self.args(),'IDLE')))

    def test_nonfinite_and_oversized_timelines_are_rejected(self):
        with tempfile.TemporaryDirectory() as temporary:
            path = Path(temporary)/'timeline.json'
            for duration in [float('nan'),float('inf'),-1,0,3601]:
                path.write_text(json.dumps([dict(name='teleop',durationSeconds=duration)]))
                with self.assertRaises(ValueError):match_suite.phase_config(self.args(timeline=path),'NORMAL')
            path.write_text(json.dumps([dict(name='teleop',durationSeconds=2000)]*2))
            with self.assertRaises(ValueError):match_suite.phase_config(self.args(timeline=path),'NORMAL')

    def test_load_delimiter_is_not_forwarded_as_an_argument(self):
        with patch('match_suite.subprocess.call',return_value=0) as call:
            self.assertEqual(0,match_suite.main(['load','--','--help']))
            self.assertEqual('--help',call.call_args.args[0][-1])
            self.assertNotIn('--',call.call_args.args[0])

    def test_asset_copy_rejects_symlinks_without_reading_them(self):
        with tempfile.TemporaryDirectory() as temporary:
            root=Path(temporary);source=root/'src/main/deploy';source.mkdir(parents=True)
            (source/'escape').symlink_to('/etc/passwd')
            with patch('match_suite.ROOT',root), self.assertRaises(ValueError):
                match_suite.copy_deploy(root/'copy')
            self.assertFalse((root/'copy').exists())

    def test_unsupported_process_probe_stays_unavailable(self):
        with patch.dict('sys.modules',{'psutil':None}):
            self.assertEqual({},match_suite.sample_process(123))

class ArtifactTests(unittest.TestCase):
    def test_executed_classes_must_match_the_recorded_jar(self):
        import zipfile
        with tempfile.TemporaryDirectory() as temporary:
            root=Path(temporary);classes=root/'build/classes/java/main';classes.mkdir(parents=True)
            item=classes/'Example.class';item.write_bytes(b'class-fixture-only')
            jar=root/'build/libs/Season2027.jar';jar.parent.mkdir(parents=True)
            with zipfile.ZipFile(jar,'w') as archive:archive.writestr('Example.class',item.read_bytes())
            command=['java','-cp',str(classes),'Example']
            with patch('match_suite.ROOT',root):
                self.assertTrue(match_suite.executed_artifacts(command)['main_classes_match_jar'])
                item.write_bytes(b'changed-fixture')
                with self.assertRaises(ValueError):match_suite.executed_artifacts(command)

    def test_reporting_blocked_returns_failure_even_with_valid_capture(self):
        summary={'report_status':'BLOCKED','workload_validity':{'status':'PASS'},
                 'measurement_completeness':{'status':'PASS'},'functional_correctness':'PASS','performance':'PASS'}
        self.assertEqual(2,match_suite.result_exit(summary))
        with patch('match_suite.analyze_run',return_value=summary):
            self.assertEqual(2,match_suite.main(['report','/not-accessed-fixture']))

    def test_qualification_failures_return_nonzero(self):
        for performance,validity,expected in [('FAIL','PASS',1),('INVALID','INVALID',2),('PASS','PASS',0)]:
            with tempfile.TemporaryDirectory() as temporary:
                root=Path(temporary);run=root/'run';run.mkdir();(run/'manifest.json').write_text('{"completion":"COMPLETE"}')
                summary={'workload_validity':{'status':validity},'measurement_completeness':{'status':'PASS'},'functional_correctness':'PASS','performance':performance}
                with patch('match_suite.run_one',return_value=(run,summary)),patch('match_report.generate_suite_reports'):
                    self.assertEqual(expected,match_suite.main(['run','--output',str(root)]))

if __name__=='__main__':unittest.main()
