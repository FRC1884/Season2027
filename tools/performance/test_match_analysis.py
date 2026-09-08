"""Deterministic validator tests: fixture numbers are not measured runs."""
import csv
import json
import struct
import tempfile
import unittest
from pathlib import Path
from match_analysis import (stats, quantile, cpu_percent, loop_metrics, analyze_run,
                            compare_runs, wpilog_records, traffic_summary, resource_summary, import_roborio, verify_recovery, resource_capture_coverage, clock_domain_guard, spike_correlations, driver_station_phase, read_csv, enrich_from_wpilog, bounded_write_csv, OutputBudgetExceeded, csv_storage_bytes)


class AnalysisTests(unittest.TestCase):
    def test_quantiles_and_missing(self):
        self.assertEqual(quantile(range(1,101),.95),95)
        self.assertEqual(quantile([1,2],.5),1)
        self.assertEqual(stats([1,None,float('nan')])['missing_count'],2)
        self.assertIsNone(stats([])['mean'])

    def test_slow_and_late_short_loop(self):
        result = loop_metrics([{'start_ns':0,'end_ns':21_000_000,'scheduled_ns':0},
                               {'start_ns':41_000_000,'end_ns':42_000_000,'scheduled_ns':20_000_000}],20_000_000)
        self.assertEqual(result['execution_overruns'],1)
        self.assertEqual(result['deadline_misses'],2)
        self.assertEqual(result['longest_miss_streak'],2)
        self.assertEqual(result['headroom_ms']['minimum'],-2)

    def test_different_clock_epochs(self):
        row = dict(start_ns=999000000000,end_ns=999001000000,execution_ms=1,
                   start_us=50000,end_us=51000,original_due_us=30000,period_us=20000)
        self.assertEqual(loop_metrics([row])['deadline_misses'],1)
        self.assertEqual(loop_metrics([row])['headroom_ms']['minimum'],-1)

    def test_cpu_units(self):
        self.assertEqual(cpu_percent(2_000_000,1_000_000,4),{'one_core_percent':200,'machine_percent':50})
        self.assertIsNone(cpu_percent(-1,1)['one_core_percent'])
        self.assertIsNone(cpu_percent(None,1)['one_core_percent'])

    def fixture(self, path, **updates):
        manifest = {'run_id':'fixture','platform':'FIXTURE_DESKTOP','profile':'NORMAL','period_ns':20000000,
            'capture_complete':True,'expected_loop_samples':1,'functional_correctness':'PASS',
            'workload_evidence':{k:True for k in ('phases_reached','commands_executed','sensor_samples',
                'odometry_samples','readiness','drive_inputs','output_changes','pose_evolved')}}
        manifest.update(updates)
        (path/'manifest.json').write_text(json.dumps(manifest))
        (path/'loop-samples.csv').write_text('cycle,phase,start_ns,end_ns,scheduled_ns\n0,teleop,0,1000000,0\n')
        for name in ('subsystem-timings','resource-samples','http-nt-results','failures-and-events'):
            (path/(name+'.csv')).write_text('kind,status\n')
        (path/'subsystem-timings.csv').write_text('cycle,phase,scope,elapsed_ms\n0,teleop,fixture,1\n')
        (path/'resource-samples.csv').write_text('monotonic_ns,process_cpu_ns\n0,\n')
        return analyze_run(path)

    def test_valid_fixture_then_inhibited_path(self):
        with tempfile.TemporaryDirectory() as folder:
            path=Path(folder)
            self.assertEqual(self.fixture(path)['performance'],'PASS')
            result=self.fixture(path,workload_evidence={'readiness':False})
            self.assertEqual(result['workload_validity']['status'],'INVALID')
            self.assertEqual(result['performance'],'INVALID')

    def test_missing_replay_sensor_and_log_records(self):
        with tempfile.TemporaryDirectory() as folder:
            result=self.fixture(Path(folder),execution_mode='REPLAY',expected_loop_samples=2,dropped_records=1,truncated=True)
            self.assertEqual(result['measurement_completeness']['status'],'FAIL')
            self.assertEqual(result['workload_validity']['status'],'INVALID')

    def test_abort_preserves_partial_metrics(self):
        with tempfile.TemporaryDirectory() as folder:
            result=self.fixture(Path(folder),aborted=True,capture_complete=False)
            self.assertEqual(result['performance'],'ABORTED')
            self.assertEqual(result['loop']['execution_ms']['sample_count'],1)

    def test_overload_requires_recovery(self):
        with tempfile.TemporaryDirectory() as folder:
            self.assertEqual(self.fixture(Path(folder),profile='OVERLOAD_RECOVERY',recovery_verified=False)['functional_correctness'],'FAIL')

    def test_underachieved_http(self):
        result=traffic_summary([dict(kind='HTTP',scheduled=10,completed=8,failed=2,elapsed_s=2)])['HTTP']
        self.assertEqual(result['status'],'INVALID')
        self.assertEqual(result['achieved_per_s'],4)

    def test_event_http_failures(self):
        result=traffic_summary([dict(kind='HTTP',status='LOCAL_DROP',scheduled_ns=1,end_ns=1000000001,latency_ms=1000)])['HTTP']
        self.assertEqual(result['status'],'INVALID')
        self.assertEqual(result['dropped'],1)

    def test_comparison_rejects_unknown_or_different_platform(self):
        with tempfile.TemporaryDirectory() as folder:
            a=self.fixture(Path(folder))
            self.assertEqual(compare_runs(a,a)['status'],'INVALID')
            metadata=dict(platform='desktop',execution_mode='SIM',pacing='realtime',profile='NORMAL',seed=1,
                          scenario_hash='x',logging_mode='COMP',instrumentation='light',load_config={},period_ns=20000000,
                          host_id='fixture-host',cpu_model='fixture-cpu',cpu_capacity=4,os='fixture-os',
                          architecture='fixture-arch',java_runtime='fixture-java17',run_kind='FIXTURE',artifact_hash='fixture-artifact')
            a['manifest'].update(metadata)
            self.assertEqual(compare_runs(a,a)['status'],'PASS')
            for key in ('host_id','cpu_model','cpu_capacity','os','architecture','java_runtime'):
                changed=dict(a,manifest=dict(a['manifest']))
                changed['manifest'][key]=None
                self.assertEqual(compare_runs(a,changed)['status'],'INVALID')
                changed['manifest'][key]='different-target-value'
                self.assertEqual(compare_runs(a,changed)['status'],'INVALID')
            changed=dict(a,manifest=dict(a['manifest'],artifact_hash='new-candidate-artifact'))
            self.assertEqual(compare_runs(a,changed)['artifact_differences']['artifact_hash']['candidate'],'new-candidate-artifact')
            changed=dict(a,loop={'execution_ms':dict(a['loop']['execution_ms'],median=99)},
                         steady_state_loop={'execution_ms':dict(a['steady_state_loop']['execution_ms'],median=2)})
            compared=compare_runs(a,changed)
            self.assertEqual(compared['steady_state_execution']['median']['change_ms'],1)
            self.assertEqual(compared['all_captured_execution']['median']['change_ms'],98)
            b=dict(a,manifest=dict(a['manifest'],platform='roborio'))
            self.assertEqual(compare_runs(a,b)['status'],'INVALID')

    def test_missing_or_aborted_required_nt_is_invalid(self):
        with tempfile.TemporaryDirectory() as folder:
            path=Path(folder)
            self.fixture(path,load_config={'clients':1,'http_rps':2})
            (path/'http.summary.json').write_text(json.dumps({'offered_rps':2,'scheduled':2,
                'started':2,'completed':2,'duration_s':1,'valid':True}))
            self.assertEqual(analyze_run(path)['workload_validity']['status'],'INVALID')
            (path/'nt.summary.json').write_text(json.dumps({'scheduled':1,'published':1,
                'delivered':1,'expected_deliveries':1,'valid':True,'aborted':True,'duration_s':1}))
            self.assertEqual(analyze_run(path)['workload_validity']['status'],'INVALID')

    def test_overload_queue_and_freshness_recovery(self):
        manifest={'recovery_start_s':1,'period_ns':20000000,'odometry_hz':250,**self.clock_manifest()}
        resources=[{'kind':'robot_observation','monotonic_ns':2000000000,'queued_tasks':2,'odom_consumed':10,'sensor_age_s':.1},
                   {'kind':'robot_observation','monotonic_ns':3000000000,'queued_tasks':0,'odom_consumed':20,'sensor_age_s':.004}]
        traffic=[{'kind':'HTTP','status':'200','scheduled_ns':2000000000},
                 {'kind':'NT','status':'SUMMARY','end_ns':2000000000,'detail':'telemetryReceived=10'},
                 {'kind':'NT','status':'SUMMARY','end_ns':3000000000,'detail':'telemetryReceived=20'}]
        self.assertEqual(verify_recovery(manifest,resources,traffic,{'HTTP':{'offered_start_monotonic_ns':0}})['status'],'PASS')
        resources[-1]['queued_tasks']=1
        self.assertEqual(verify_recovery(manifest,resources,traffic,{'HTTP':{'offered_start_monotonic_ns':0}})['status'],'FAIL')

    def test_report_workbook_tabs_numeric_chart_and_nulls(self):
        try:
            import xlsxwriter
        except ImportError:
            self.skipTest('approved host XlsxWriter environment not active')
        import zipfile
        import xml.etree.ElementTree as ET
        import contextlib
        import io
        from match_report import generate_reports
        with tempfile.TemporaryDirectory() as folder:
            path=Path(folder);self.fixture(path)
            with contextlib.redirect_stdout(io.StringIO()):
                generate_reports(path)
            with zipfile.ZipFile(path/'match-performance.xlsx') as archive:
                ns={'s':'http://schemas.openxmlformats.org/spreadsheetml/2006/main',
                    'c':'http://schemas.openxmlformats.org/drawingml/2006/chart'}
                root=ET.fromstring(archive.read('xl/workbook.xml'))
                names=[node.attrib['name'] for node in root.findall('s:sheets/s:sheet',ns)]
                self.assertEqual(names,['Summary','Run Metadata','Match Phases','Subsystem Timings',
                    'CPU and Memory','API and NetworkTables','Overruns and Failures','Baseline Comparison'])
                chart=ET.fromstring(archive.read('xl/charts/chart1.xml'))
                self.assertEqual([float(n.text) for n in chart.findall('.//c:numCache/c:pt/c:v',ns)],[1])
                for filename in archive.namelist():
                    if filename.startswith('xl/worksheets/') and filename.endswith('.xml'):
                        sheet=ET.fromstring(archive.read(filename))
                        self.assertFalse([node for node in sheet.findall('.//s:c',ns) if node.attrib.get('t')=='e'])
            self.assertIn('UNAVAILABLE',(path/'match-performance.html').read_text())

    def test_resource_cadence_uses_actual_eligible_loops(self):
        # Deliberately coarse 0.6 s loop intervals demonstrate normal schedule drift.
        loops=[{'start_ns':n*600000000,'end_ns':n*600000000+1000000} for n in range(10)]
        samples=[{'monotonic_ns':n*600000000+500000} for n in range(0,10,2)]
        result=resource_capture_coverage(loops,samples)
        self.assertEqual(result['status'],'PASS')
        self.assertEqual(result['expected_samples'],5)
        self.assertEqual(result['nominal_slippage_ms']['mean'],200)
        self.assertAlmostEqual(result['achieved_hz'],1/1.2)
        self.assertEqual(resource_capture_coverage(loops,samples[:2]+samples[3:])['missing_samples'],1)
        self.assertEqual(resource_capture_coverage(loops,samples+[samples[0]])['status'],'FAIL')
        self.assertEqual(resource_capture_coverage(loops,[])['status'],'FAIL')

    @staticmethod
    def clock_manifest():
        return {'cross_process_clock_alignment':{'verified':True,'basis':'source_verified_same_host_mach_absolute_time'},
                'execution_mode':'DESKTOP_MATCH_SIM','load_config':{'offhost':False},'host_id':'fixture-host',
                'os':'macOS-26.5-arm64-arm-64bit','architecture':'arm64','java_runtime':'Temurin build 17.0.18+8',
                'python':'3.11.9 (fixture)','python_implementation':'CPython'}

    def test_off_observation_host_capture_count_and_cpu_evidence(self):
        rows=[{'monotonic_ns':0,'process_cpu_ns':0},{'monotonic_ns':1100000000,'process_cpu_ns':20000000},
              {'monotonic_ns':2200000000,'process_cpu_ns':40000000}]
        self.assertEqual(resource_capture_coverage([],rows,False,3)['status'],'PASS')
        lost=resource_capture_coverage([],rows[:1]+rows[2:],False,3)
        self.assertEqual(lost['status'],'FAIL')
        self.assertEqual(lost['missing_samples'],1)
        self.assertEqual(resource_capture_coverage([],rows,False,None)['status'],'FAIL')
        unavailable=[dict(row,process_cpu_ns=None) for row in rows]
        self.assertEqual(resource_capture_coverage([],unavailable,False,3)['status'],'FAIL')

    def test_unverified_cross_process_clock_has_null_http_overlap(self):
        manifest=self.clock_manifest()
        self.assertEqual(clock_domain_guard(manifest)['status'],'VERIFIED')
        for key,value in [('java_runtime','17.0.19+8'),('python','3.11.10 (fixture)'),('os','Linux'),('host_id',None)]:
            changed=dict(manifest,**{key:value})
            self.assertEqual(clock_domain_guard(changed)['status'],'UNVERIFIED')
        self.assertEqual(clock_domain_guard(dict(manifest,execution_mode='ROBORIO_OBSERVE'))['status'],'UNVERIFIED')
        loops=[{'cycle':0,'start_ns':1,'end_ns':20000001,'execution_ms':20}]
        requests=[{'kind':'HTTP','start_ns':2,'end_ns':19000000,'bytes':100}]
        unverified=spike_correlations(loops,requests,[],20000000)
        self.assertIsNone(unverified['spikes'][0]['overlapping_http_requests'])
        verified=spike_correlations(loops,requests,[],20000000,clock_alignment=clock_domain_guard(manifest))
        self.assertEqual(verified['spikes'][0]['overlapping_http_requests'],1)

    def test_human_phase_joins_completed_cycle_and_real_hal_rejects_sim(self):
        def string(value):
            raw=value.encode();return struct.pack('<I',len(raw))+raw
        def record(entry,payload,timestamp):
            return bytes([0,entry,len(payload),timestamp])+payload
        raw=bytearray(b'WPILOG'+struct.pack('<HI',0x100,0))
        entries={}
        def value(name,datatype,item,timestamp):
            if name not in entries:
                entry=len(entries)+1;entries[name]=entry
                raw.extend(record(0,b'\0'+struct.pack('<I',entry)+string(name)+string(datatype)+string(''),timestamp))
            payload=item.encode() if datatype=='string' else struct.pack({'int64':'<q','boolean':'<?'}[datatype],item)
            raw.extend(record(entries[name],payload,timestamp))
        prefix='/RealOutputs/MatchPerformance/'
        for key,datatype,item in [('PeriodUs','int64',20000),('CurrentCycle','int64',0),
                ('CurrentPhase','string','HUMAN_OPERATED'),('Platform','string','ROBORIO_OBSERVE'),
                ('HALRuntimeType','string','kRoboRIO')]:
            value(prefix+key,datatype,item,10)
        value('/RealMetadata/RuntimeMode','string','SIM',10)
        for key,item in [('Enabled',True),('Autonomous',True),('Test',False),('EmergencyStop',False),('DSAttached',True)]:
            value('/DriverStation/'+key,'boolean',item,10)
        value(prefix+'CurrentCycle','int64',1,20)
        value('/DriverStation/Enabled','boolean',False,20)
        value('/DriverStation/Autonomous','boolean',False,20)
        value(prefix+'PreviousCycle','int64',0,20)
        value(prefix+'PreviousPhase','string','HUMAN_OPERATED',20)
        # Final flush can observe new DS flags without executing CurrentCycle again.
        value('/DriverStation/Enabled','boolean',True,30)
        value(prefix+'PreviousCycle','int64',1,30)
        value(prefix+'CaptureComplete','boolean',True,30)
        value(prefix+'CompletedSamples','int64',2,30)
        with tempfile.TemporaryDirectory() as folder:
            root=Path(folder);source=root/'fixture.wpilog';source.write_bytes(raw)
            metadata=import_roborio(source,None,root/'import')
            rows=read_csv(root/'import'/'loop-samples.csv')
            self.assertEqual([row['phase'] for row in rows],['autonomous','disabled'])
            self.assertEqual(metadata['driver_station_phase_inference']['inferred_cycles'],2)
            self.assertEqual(metadata['logged_runtime_mode'],'SIM')
            self.assertTrue(any('not a production hardware workload' in reason for reason in analyze_run(root/'import')['workload_validity']['reasons']))

    def test_missing_driver_station_fields_never_assume_teleop(self):
        self.assertFalse(driver_station_phase({'/DriverStation/Enabled':True})['verified'])

    def test_missing_or_ambiguous_akit_source_persists_failure(self):
        with tempfile.TemporaryDirectory() as folder:
            root=Path(folder)
            self.fixture(root,execution_mode='DESKTOP_MATCH_SIM')
            absent=analyze_run(root)
            self.assertTrue(any('lacks verified WPILOG extraction' in reason for reason in absent['measurement_completeness']['reasons']))
            result=enrich_from_wpilog(root)
            self.assertEqual(result['status'],'NOT RUN')
            self.assertEqual(json.loads((root/'log-extraction.json').read_text())['status'],'NOT RUN')
            (root/'logs').mkdir()
            for name in ('akit-a.wpilog','akit-b.wpilog'):(root/'logs'/name).write_bytes(b'fixture')
            self.assertEqual(enrich_from_wpilog(root)['status'],'INVALID')
            self.assertEqual(json.loads((root/'log-extraction.json').read_text())['status'],'INVALID')
            self.assertEqual(analyze_run(root)['measurement_completeness']['status'],'FAIL')

    def test_bounded_csv_preserves_original_and_partial_evidence(self):
        with tempfile.TemporaryDirectory() as folder:
            root=Path(folder);target=root/'samples.csv';target.write_text('original\n')
            with self.assertRaises(OutputBudgetExceeded) as blocked:
                bounded_write_csv(target,['value'],[{'value':'x'*100}],50)
            self.assertEqual(target.read_text(),'original\n')
            self.assertTrue(Path(blocked.exception.partial_file).exists())
            self.assertLessEqual(csv_storage_bytes([root]),50)
            self.assertEqual(json.loads((root/'output-budget-status.json').read_text())['status'],'BLOCKED')

    def test_csv_budget_combines_temporary_and_final_roots(self):
        with tempfile.TemporaryDirectory() as folder:
            root=Path(folder);other=root/'extract';other.mkdir()
            (other/'intermediate.csv').write_bytes(b'x'*80)
            with self.assertRaises(OutputBudgetExceeded):
                bounded_write_csv(root/'final.csv',['value'],[{'value':'y'*30}],100,(root,other))
            self.assertLessEqual(csv_storage_bytes([root,other]),100)

    def test_import_and_enrichment_budget_failure_persists_blocked_state(self):
        with tempfile.TemporaryDirectory() as folder:
            root=Path(folder);source=root/'fixture.wpilog'
            raw=b'WPILOG'+struct.pack('<HI',0x100,0);source.write_bytes(raw)
            with self.assertRaises(OutputBudgetExceeded):
                import_roborio(source,None,root/'import',max_output_bytes=64)
            manifest=json.loads((root/'import'/'manifest.json').read_text())
            self.assertEqual(manifest['completion'],'BLOCKED')
            self.assertFalse(manifest['capture_complete'])
            self.assertEqual(source.read_bytes(),raw)
            self.assertLessEqual(csv_storage_bytes([root/'import']),64)
            run=root/'run';run.mkdir();self.fixture(run)
            (run/'logs').mkdir();(run/'logs'/'akit-fixture.wpilog').write_bytes(raw)
            with self.assertRaises(OutputBudgetExceeded) as failure:
                enrich_from_wpilog(run,max_output_bytes=64)
            self.assertEqual(json.loads((run/'log-extraction.json').read_text())['status'],'BLOCKED')
            # Only remove the explicit test-owned temporary extraction directory.
            import shutil
            shutil.rmtree(Path(failure.exception.partial_file).parent)

    def test_reenrichment_does_not_rewrite_unchanged_csv(self):
        from unittest.mock import patch
        import hashlib
        def string(value):
            raw=value.encode();return struct.pack('<I',len(raw))+raw
        def record(entry,payload):
            return bytes([0,entry,len(payload),1])+payload
        fields=[('MatchPerformance/PeriodUs','int64',20000),('MatchPerformance/CurrentCycle','int64',0),
                ('MatchPerformance/PreviousCycle','int64',0),('MatchPerformance/PreviousPhase','string','teleop'),
                ('MatchPerformance/StartNanos','int64',0),('MatchPerformance/EndNanos','int64',1000000),
                ('MatchPerformance/OriginalDueUs','int64',0),('MatchPerformance/StartUs','int64',0),
                ('MatchPerformance/EndUs','int64',1000),('MatchPerformance/CaptureComplete','boolean',True),
                ('MatchPerformance/CompletedSamples','int64',1),('LoggedRobot/GCTimeMS','double',0.0)]
        raw=b'WPILOG'+struct.pack('<HI',0x100,0)
        for entry,(name,datatype,item) in enumerate(fields,1):
            raw+=record(0,b'\0'+struct.pack('<I',entry)+string('/RealOutputs/'+name)+string(datatype)+string(''))
            value=item.encode() if datatype=='string' else struct.pack({'int64':'<q','double':'<d','boolean':'<?'}[datatype],item)
            raw+=record(entry,value)
        with tempfile.TemporaryDirectory() as folder:
            root=Path(folder);self.fixture(root)
            (root/'loop-samples.csv').write_text('cycle,phase,start_ns,end_ns,original_due_us,start_us,end_us,period_us\n0,teleop,0,1000000,0,0,1000,20000\n')
            (root/'logs').mkdir();(root/'logs'/'akit-fixture.wpilog').write_bytes(raw)
            first=enrich_from_wpilog(root)
            self.assertEqual(first['status'],'PASS')
            names=('subsystem-timings.csv','resource-samples.csv')
            before={name:(hashlib.sha256((root/name).read_bytes()).hexdigest(),(root/name).stat().st_mtime_ns) for name in names}
            original_writer=bounded_write_csv
            def forbid_rewrite(path,*args,**kwargs):
                self.assertNotEqual(Path(path).parent,root,'unchanged final CSV must not be rewritten')
                return original_writer(path,*args,**kwargs)
            with patch('match_analysis.bounded_write_csv',side_effect=forbid_rewrite):
                second=enrich_from_wpilog(root)
            self.assertEqual(second['status'],'PASS')
            self.assertGreater(second['framing']['records'],0)
            self.assertEqual(second['added_scope_samples'],0)
            self.assertEqual(second['added_resource_samples'],0)
            after={name:(hashlib.sha256((root/name).read_bytes()).hexdigest(),(root/name).stat().st_mtime_ns) for name in names}
            self.assertEqual(before,after)

    def test_off_mode_rebinds_nonempty_import_cycle_and_skips_flushes(self):
        from unittest.mock import patch
        with tempfile.TemporaryDirectory() as folder:
            root=Path(folder);self.fixture(root)
            (root/'scenario.json').write_text(json.dumps({'recording':False}))
            (root/'loop-samples.csv').write_text('cycle,phase,start_us,end_us\n0,teleop,10,20\n')
            (root/'logs').mkdir();(root/'logs'/'akit-fixture.wpilog').write_bytes(b'fixture')
            def imported(source,metadata,directory,*args,**kwargs):
                directory=Path(directory)
                (directory/'subsystem-timings.csv').write_text('cycle,phase,scope,elapsed_ms,timestamp_us\n5,startup,logged,999,5\n15,timestamp-fallback,logged,2,15\n30,finalflush,logged,0,30\n')
                (directory/'resource-samples.csv').write_text('kind,cycle,timestamp_us,gc_time_ms_delta\nrobot_logger,5,5,999\nrobot_logger,15,15,2\nrobot_logger,30,30,0\n')
                (directory/'loop-samples.csv').write_text('cycle\n')
                return {'truncated':False,'capture_complete':False,'source_log_sha256':'fixture-hash',
                        'expected_loop_samples':None,'dropped_records':None,'import_framing':{'records':3}}
            with patch('match_analysis.import_roborio',side_effect=imported):
                result=enrich_from_wpilog(root)
            self.assertEqual(result['status'],'PASS')
            added=[r for r in read_csv(root/'subsystem-timings.csv') if r['scope']=='logged']
            self.assertEqual(len(added),1)
            self.assertEqual((added[0]['cycle'],added[0]['phase'],added[0]['elapsed_ms']),('0','teleop','2'))
            resources=[r for r in read_csv(root/'resource-samples.csv') if r.get('kind')=='robot_logger']
            self.assertEqual(len(resources),1)
            self.assertEqual(resources[0]['cycle'],'0')

    def test_filtered_cycle_gap_breaks_miss_streak_and_intervals(self):
        rows=[{'cycle':1,'start_ns':0,'end_ns':21000000,'scheduled_ns':0},
              {'cycle':3,'start_ns':40000000,'end_ns':61000000,'scheduled_ns':40000000}]
        result=loop_metrics(rows,20000000)
        self.assertEqual(result['deadline_misses'],2)
        self.assertEqual(result['longest_miss_streak'],1)
        self.assertEqual(result['start_interval_ms']['sample_count'],0)
        self.assertEqual(result['execution_ms']['mean'],21)

    def test_run_change_breaks_miss_streak_even_with_adjacent_cycle_ids(self):
        rows=[{'run_id':'a','cycle':1,'start_ns':0,'end_ns':21000000,'scheduled_ns':0},
              {'run_id':'b','cycle':2,'start_ns':22000000,'end_ns':43000000,'scheduled_ns':22000000}]
        result=loop_metrics(rows,20000000)
        self.assertEqual(result['longest_miss_streak'],1)
        self.assertEqual(result['start_interval_ms']['sample_count'],0)

    def test_suite_manifest_and_raw_index_reference_original_files(self):
        from match_report import write_suite_index
        with tempfile.TemporaryDirectory() as folder:
            root=Path(folder);run=root/'run';run.mkdir();suite=root/'suite';suite.mkdir()
            summary=self.fixture(run,run_id='fixture-one')
            write_suite_index(suite,[run],[summary])
            manifest=json.loads((suite/'manifest.json').read_text())
            index=json.loads((suite/'raw-file-index.json').read_text())
            self.assertEqual(manifest['run_count'],1)
            self.assertEqual(manifest['run_ids'],['fixture-one'])
            self.assertEqual(index['runs'][0]['statuses']['performance'],'PASS')
            self.assertFalse(index['raw_files_copied'])
            reference=index['runs'][0]['raw_files'][0]
            self.assertEqual((suite/reference['path']).resolve(),(run/'loop-samples.csv').resolve())
            self.assertFalse((suite/'loop-samples.csv').exists())

    def test_physics_scope_requirement_matches_recording_and_io_model(self):
        with tempfile.TemporaryDirectory() as folder:
            root=Path(folder)
            def run(recording,pacing,model):
                self.fixture(root,execution_mode='DESKTOP_MATCH_SIM',fixture_data=True,
                             observation_enabled=recording,pacing=pacing,io_model=model,
                             host_resource_sample_counts={'robot':2})
                (root/'subsystem-timings.csv').write_text('cycle,phase,scope,elapsed_ms\n0,teleop,Swerve periodic,1\n0,teleop,Command scheduler,1\n0,teleop,RobotContainer,1\n')
                (root/'host-resource-samples.csv').write_text('kind,monotonic_ns,process_cpu_ns\nrobot,0,0\nrobot,1000000000,1000\n')
                return analyze_run(root)
            off=run(False,'paced','MAPLESIM')
            self.assertEqual(off['measurement_completeness']['status'],'PASS')
            self.assertEqual(off['physics_timing']['status'],'NOT MEASURED')
            self.assertNotIn('Desktop MapleSim physics',off['subsystems_ms'])
            on=run(True,'paced','MAPLESIM')
            self.assertEqual(on['measurement_completeness']['status'],'FAIL')
            self.assertTrue(any('MapleSim' in reason for reason in on['measurement_completeness']['reasons']))
            stepped=run(True,'stepped','DETERMINISTIC_IDEAL_SENSOR_FIXTURE')
            self.assertEqual(stepped['measurement_completeness']['status'],'PASS')
            self.assertEqual(stepped['performance'],'NOT MEASURED')

    def test_verified_desktop_import_needs_no_copied_log_and_rejects_source_changes(self):
        def string(value):
            raw=value.encode();return struct.pack('<I',len(raw))+raw
        def record(entry,payload):
            return bytes([0,entry,len(payload),1])+payload
        fields=[('MatchPerformance/PeriodUs','int64',20000),('MatchPerformance/CurrentCycle','int64',0),
                ('MatchPerformance/CurrentPhase','string','teleop'),('MatchPerformance/PreviousCycle','int64',0),
                ('MatchPerformance/PreviousPhase','string','teleop'),('MatchPerformance/StartNanos','int64',0),
                ('MatchPerformance/EndNanos','int64',1000000),('MatchPerformance/OriginalDueUs','int64',0),
                ('MatchPerformance/StartUs','int64',0),('MatchPerformance/EndUs','int64',1000),
                ('MatchPerformance/CaptureComplete','boolean',True),('MatchPerformance/CompletedSamples','int64',1),
                ('MatchPerformance/Platform','string','DESKTOP_MATCH_SIM'),
                ('MatchPerformance/Resources/MonotonicNanos','int64',500000),
                ('MatchPerformance/Resources/ProcessCpuAvailable','boolean',False),
                ('Swerve/Performance/PeriodicMS','double',.5),('Robot/Performance/SchedulerMS','double',.7),
                ('Robot/Performance/ContainerMS','double',.1)]
        raw=b'WPILOG'+struct.pack('<HI',0x100,0)
        for entry,(name,datatype,item) in enumerate(fields,1):
            raw+=record(0,b'\0'+struct.pack('<I',entry)+string('/RealOutputs/'+name)+string(datatype)+string(''))
            payload=item.encode() if datatype=='string' else struct.pack({'int64':'<q','double':'<d','boolean':'<?'}[datatype],item)
            raw+=record(entry,payload)
        with tempfile.TemporaryDirectory() as folder:
            root=Path(folder);source=root/'original.wpilog';source.write_bytes(raw)
            imported=root/'import';import_roborio(source,None,imported)
            result=analyze_run(imported)
            self.assertEqual(result['manifest']['execution_mode'],'DESKTOP_MATCH_SIM')
            self.assertFalse((imported/'logs').exists())
            self.assertEqual(result['import_source_verification']['status'],'PASS')
            self.assertEqual(result['measurement_completeness']['status'],'PASS',result['measurement_completeness'])
            source.write_bytes(raw+b'x')
            self.assertEqual(analyze_run(imported)['measurement_completeness']['status'],'FAIL')
            source.unlink()
            self.assertEqual(analyze_run(imported)['import_source_verification']['status'],'FAIL')

    def test_empty_scope_and_resource_files_are_incomplete(self):
        with tempfile.TemporaryDirectory() as folder:
            path=Path(folder);self.fixture(path)
            (path/'resource-samples.csv').write_text('monotonic_ns,process_cpu_ns\n')
            (path/'subsystem-timings.csv').write_text('cycle,scope,elapsed_ms\n')
            self.assertEqual(analyze_run(path)['measurement_completeness']['status'],'FAIL')

    def test_warmup_cannot_hide_empty_teleop_or_auto(self):
        with tempfile.TemporaryDirectory() as folder:
            path=Path(folder)
            for phase in ('teleop','auto_fixture'):
                result=self.fixture(path,phase_evidence=[{'name':phase,'evidence':{'loops':10,
                    'enabledLoops':0,'readyLoops':10,'consumedSamples':50,
                    'distanceMeters':0,'maxDesiredSpeed':0,'maxMeasuredSpeed':0,
                    'maxDriveVoltage':0,'appliedDriveRequests':0}}])
                self.assertEqual(result['workload_validity']['status'],'INVALID')
                self.assertTrue(any(phase in reason for reason in result['workload_validity']['reasons']))

    def test_nt_discrepancy_is_not_a_successful_primary_load(self):
        with tempfile.TemporaryDirectory() as folder:
            path = Path(folder)
            self.fixture(path)
            (path / 'nt.summary.json').write_text(json.dumps({'scheduled': 100, 'published': 100,
                'delivered': 99, 'expected_deliveries': 100, 'undelivered': 1,
                'valid': True, 'duration_s': 1}))
            result = analyze_run(path)
            self.assertEqual(result['traffic']['NT']['status'], 'INVALID')
            self.assertEqual(result['traffic']['NT']['delivery_fraction'], .99)
            self.assertEqual(result['workload_validity']['status'], 'INVALID')

    def test_resources_separate_generator_and_robot(self):
        result = resource_summary([{'monotonic_ns':0,'process_cpu_ns':0},
                                   {'monotonic_ns':1000000,'process_cpu_ns':500000}],
                                  [{'kind':'http_generator','elapsed_s':0,'process_cpu_ns':0},
                                   {'kind':'http_generator','elapsed_s':1,'process_cpu_ns':1000000000}])
        self.assertEqual(result['robot_jvm']['one_core_cpu_percent']['mean'],50)
        self.assertEqual(result['host_http_generator']['one_core_cpu_percent']['mean'],100)
        self.assertIsNone(result['robot_jvm']['machine_process_percent']['mean'])

    def test_import_changed_only_frames_keeps_previous_fields(self):
        def string(value):
            raw=value.encode(); return struct.pack('<I',len(raw))+raw
        def record(entry,payload,timestamp):
            return bytes([0])+bytes([entry,len(payload),timestamp])+payload
        names = [('PeriodUs','int64',20000),('PreviousCycle','int64',0),
                 ('StartNanos','int64',1000000000),('EndNanos','int64',1001000000),
                 ('OriginalDueUs','int64',0),('StartUs','int64',0),('EndUs','int64',1000),
                 ('ExecutionMS','double',1.0),('CaptureComplete','boolean',True),
                 ('CompletedSamples','int64',1),('DroppedSamples','int64',0)]
        raw=b'WPILOG'+struct.pack('<HI',0x100,0)
        for index,(name,datatype,value) in enumerate(names,1):
            raw+=record(0,b'\0'+struct.pack('<I',index)+string('/RealOutputs/MatchPerformance/'+name)+string(datatype)+string(''),1)
            payload=struct.pack({'int64':'<q','double':'<d','boolean':'<?'}[datatype],value)
            raw+=record(index,payload,2)
        with tempfile.TemporaryDirectory() as folder:
            path=Path(folder); source=path/'source.wpilog';source.write_bytes(raw)
            imported=import_roborio(source,None,path/'import')
            self.assertTrue(imported['capture_complete'])
            self.assertEqual(imported['expected_loop_samples'],1)
            self.assertEqual(source.read_bytes(),raw)
            result=analyze_run(path/'import')
            self.assertEqual(result['loop']['execution_ms']['median'],1)
            self.assertEqual(result['measurement_completeness']['status'],'FAIL')
            self.assertEqual(result['workload_validity']['status'],'INVALID')

    def test_framing_detects_truncated_header_body_and_valid_scalar(self):
        def string(value):
            raw=value.encode();return struct.pack('<I',len(raw))+raw
        def record(entry,payload):
            return bytes([0])+bytes([entry,len(payload),1])+payload
        start=b'\0'+struct.pack('<I',1)+string('Robot/Performance/TestMS')+string('double')+string('')
        raw=b'WPILOG'+struct.pack('<HI',0x100,0)+record(0,start)+record(1,struct.pack('<d',2.5))
        with tempfile.TemporaryDirectory() as folder:
            path=Path(folder)/'fixture.wpilog'
            path.write_bytes(raw)
            state={}; rows=list(wpilog_records(path,state))
            self.assertEqual(rows[0][2],2.5)
            self.assertTrue(state['framing_complete'])
            path.write_bytes(raw[:-1]); state={};list(wpilog_records(path,state))
            self.assertTrue(state['truncated'])
            self.assertFalse(state['framing_complete'])


if __name__=='__main__':
    unittest.main()
