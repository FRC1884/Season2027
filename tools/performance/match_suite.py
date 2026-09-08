#!/usr/bin/env python3
"""Season2027 finite, localhost-first match qualification. See docs/performance/match-suite.md."""
from __future__ import annotations
import argparse
import csv
import datetime as dt
import hashlib
import json
import os
from pathlib import Path
import platform
import shutil
import signal
import socket
import subprocess
import sys
import time
import zipfile

ROOT = Path(__file__).resolve().parents[2]
HERE = Path(__file__).resolve().parent
PROFILES = {
    'IDLE': (0, 0, 0, 0), 'IDLE_DASHBOARD': (1, .2, 0, .5),
    'EASY': (1, 2, 100, .5), 'NORMAL': (2, 5, 500, 1),
    'HARD': (4, 20, 2000, 4), 'HARD_DEBUG': (4, 20, 2000, 4),
    'OVERLOAD_RECOVERY': (8, 20, 2000, 8),
}
PHASES = [('warmup', 30), ('disabled_before_auto', 5), ('auto_fixture', 15),
          ('disabled_transition', 1), ('teleop', 135), ('disabled_recovery', 10)]
MIB = 1024 * 1024


def write_json(path, data):
    path.write_text(json.dumps(data, indent=2, allow_nan=False) + '\n')


def hash_file(path):
    digest = hashlib.sha256()
    with path.open('rb') as stream:
        for chunk in iter(lambda: stream.read(65536), b''):
            digest.update(chunk)
    return digest.hexdigest()


def git(*args):
    return subprocess.check_output(['git', '-C', str(ROOT), *args]).decode().strip()


def cpu_model():
    if sys.platform == 'darwin':
        result = subprocess.run(['sysctl','-n','machdep.cpu.brand_string'],capture_output=True,text=True,timeout=2)
        return result.stdout.strip()[:256] or None
    if sys.platform.startswith('linux'):
        try:
            with open('/proc/cpuinfo') as source:
                for line in source.read(65536).splitlines():
                    if line.lower().startswith(('model name','hardware')):
                        return line.partition(':')[2].strip()[:256]
        except OSError:
            pass
    return platform.processor() or None


def identity():
    digest = hashlib.sha256(subprocess.check_output(['git', '-C', str(ROOT), 'diff', '--binary', 'HEAD']))
    for name in git('ls-files', '--others', '--exclude-standard', '-z').split('\0'):
        if name and (ROOT / name).is_file():
            digest.update(name.encode()); digest.update(hash_file(ROOT / name).encode())
    return {'git_sha': git('rev-parse', 'HEAD'), 'branch': git('branch', '--show-current'),
            'dirty_diff_hash': digest.hexdigest(), 'platform': 'DESKTOP_MATCH_SIM',
            'os': platform.platform(), 'architecture': platform.machine(),
            'cpu_capacity': os.cpu_count(), 'cpu_model':cpu_model(),
            'host_id':hashlib.sha256(platform.node().encode()).hexdigest(), 'python': sys.version,
            'python_implementation':platform.python_implementation(),
            'roborio_measurements': 'NOT MEASURED', 'compute_only': 'UNSUPPORTED'}


def finite_float(low, high):
    def parse(s):
        x = float(s)
        if not low <= x <= high:
            raise argparse.ArgumentTypeError(f'must be finite in [{low}, {high}]')
        return x
    return parse


def finite_int(low, high):
    def parse(s):
        x = int(s)
        if not low <= x <= high:
            raise argparse.ArgumentTypeError(f'must be in [{low}, {high}]')
        return x
    return parse


def local_port():
    with socket.socket() as sock:
        sock.bind(('127.0.0.1', 0))
        return sock.getsockname()[1]


def java_command(args, main):
    classpath_file = ROOT / 'build/performance/classpath.txt'
    if args.classpath:
        classpath = args.classpath
    elif classpath_file.exists():
        classpath = classpath_file.read_text().strip()
    else:
        raise ValueError('Missing desktop classpath. Run Gradle performanceClasspath first; see preflight.')
    java = args.java or (str(Path(os.environ['JAVA_HOME']) / 'bin/java') if os.environ.get('JAVA_HOME') else shutil.which('java'))
    if not java:
        raise ValueError('Java 17 not found; pass --java /path/to/java17')
    return [java, '-Xmx256m', '-Djava.library.path=' + str(ROOT / 'build/jni/release'),
            '-cp', classpath, main]


def executed_artifacts(command):
    """Bind the actual classpath and reject stale main classes before launching a workload."""
    classpath=command[command.index('-cp')+1]
    jar=ROOT/'build/libs/Season2027.jar'
    if not jar.is_file():raise ValueError('Missing robot JAR; run the documented build first')
    main=ROOT/'build/classes/java/main'
    if command[-1].startswith('org.Griffins1884.frc2027.performance.'):
        for resource,expected_directory in [
            ('org/Griffins1884/frc2027/Robot.class',main),
            ('org/Griffins1884/frc2027/performance/DesktopMatchMain.class',ROOT/'build/classes/java/performance')]:
            selected=None
            for entry in classpath.split(os.pathsep):
                path=Path(entry)
                if path.is_dir() and (path/resource).is_file():selected=path.resolve();break
                if path.is_file() and zipfile.is_zipfile(path):
                    with zipfile.ZipFile(path) as candidate:
                        if resource in candidate.namelist():selected=path.resolve();break
            allowed={expected_directory.resolve()}
            if expected_directory==main:allowed.add(jar.resolve())
            if selected not in allowed:
                raise ValueError('Classpath shadows or omits measured application classes; run from the matching checkout: '+resource)
    classes=sorted(main.rglob('*.class'))
    if not classes:raise ValueError('Missing compiled application classes')
    main_digest=hashlib.sha256()
    with zipfile.ZipFile(jar) as archive:
        for path in classes:
            name=path.relative_to(main).as_posix()
            content=path.read_bytes()
            if archive.read(name)!=content:
                raise ValueError('Stale robot JAR differs from executed main classes; rebuild: '+name)
            main_digest.update(name.encode());main_digest.update(hashlib.sha256(content).digest())
    dependencies=[]
    for entry in classpath.split(os.pathsep):
        path=Path(entry)
        if path.is_file():dependencies.append({'name':path.name,'sha256':hash_file(path)})
    return {'main_classes_hash':main_digest.hexdigest(),
            'main_classes_match_jar':True,'classpath_dependencies':dependencies,
            'classpath_dependencies_hash':hashlib.sha256(json.dumps(dependencies,sort_keys=True).encode()).hexdigest()}


def stop_owned(process):
    if process.poll() is not None:
        return
    process.terminate()
    try:
        process.wait(timeout=5)
    except subprocess.TimeoutExpired:
        process.kill(); process.wait(timeout=5)


def tree_size(path):
    return sum(p.stat().st_size for p in path.rglob('*') if p.is_file() and not p.is_symlink())


def copy_deploy(destination):
    """Only the existing deploy tree; no synthetic paths are installed into competition assets."""
    source = ROOT / 'src/main/deploy'
    files = list(source.rglob('*'))
    if len(files) > 1024 or any(p.is_symlink() for p in files):
        raise ValueError('Deploy tree exceeds bound or contains symlink')
    if sum(p.stat().st_size for p in files if p.is_file()) > 64 * MIB:
        raise ValueError('Deploy assets exceed 64 MiB')
    shutil.copytree(source, destination)


def phase_config(args, profile):
    if args.timeline:
        phases = json.loads(Path(args.timeline).read_text())
        if not isinstance(phases, list) or not 1 <= len(phases) <= 64:
            raise ValueError('timeline must be a list of 1..64 phase objects')
        allowed = {x[0] for x in PHASES}
        for phase in phases:
            if phase.get('name') not in allowed or not 0 < float(phase.get('durationSeconds', 0)) <= 3600:
                raise ValueError('invalid phase name or nonfinite/nonpositive duration')
    else:
        base = PHASES if not profile.startswith('IDLE') else [('warmup', 30), ('disabled_before_auto', 60), ('disabled_recovery', 10)]
        if args.command == 'smoke':
            base = [(name, seconds) for name, seconds in zip([p[0] for p in PHASES], [2, 1, 3, 1, 6, 2])]
        phases = [{'name': name, 'durationSeconds': seconds} for name, seconds in base]
    if args.command == 'soak':
        phases = phases * args.cycles
    duration = sum(float(p['durationSeconds']) for p in phases)
    if not 0 < duration <= 3600:
        raise ValueError('total timeline must be finite, positive and <=3600 seconds')
    return phases


def sample_process(pid):
    try:
        import psutil
        process = psutil.Process(pid)
        cpu = process.cpu_times()
        result = {'process_cpu_ns': int((cpu.user + cpu.system) * 1e9),
                  'rss_bytes': process.memory_info().rss, 'thread_count': process.num_threads()}
        if hasattr(process, 'num_fds'):
            result['file_descriptors'] = process.num_fds()
        return result
    except (ImportError, OSError):
        return {}
    except Exception as exc:
        # Process exit/access races are unavailable; no zero-valued invented measurements.
        return {'resource_unavailable': type(exc).__name__}


def merge_traffic(out):
    rows = []
    fields = []
    for path in sorted(out.glob('load-*.csv')):
        with path.open() as stream:
            reader = csv.DictReader(stream)
            for field in reader.fieldnames or []:
                if field not in fields: fields.append(field)
            rows.extend(reader)
    if not fields:
        fields = ['kind', 'scheduled_ns', 'start_ns', 'end_ns', 'status', 'bytes', 'latency_ms', 'scheduling_delay_ms', 'detail']
    with (out / 'http-nt-results.csv').open('w') as stream:
        writer = csv.DictWriter(stream, fields); writer.writeheader(); writer.writerows(rows)


def analyze_run(out):
    from match_analysis import analyze_run as analyze
    from match_report import generate_reports
    import match_analysis
    manifest=json.loads((out/'manifest.json').read_text())
    remaining=128*MIB-tree_size(out)
    free=shutil.disk_usage(out).free
    storage_abort='Storage' in manifest.get('abort_reason','') or 'storage' in manifest.get('abort_reason','')
    reporting_allowed=(remaining>=MIB and free>=1024*MIB+MIB and not storage_abort)
    imported_capture=bool(manifest.get('source_log') and manifest.get('import_framing'))
    if reporting_allowed and not imported_capture:
        try:
            # CSV writer accounts for existing, temporary and atomic-replacement files together.
            existing_csv_bytes=sum(p.stat().st_size for p in out.glob('*.csv'))
            extraction_budget=max(0,min(existing_csv_bytes+remaining-MIB,existing_csv_bytes+free-1024*MIB-MIB))
            match_analysis.enrich_from_wpilog(out,max_output_bytes=extraction_budget)
        except (OSError,ValueError,RuntimeError) as error:
            write_json(out/'log-extraction.json',{'status':'BLOCKED','reason':str(error)})
    elif not reporting_allowed:
        write_json(out/'log-extraction.json',{'status':'BLOCKED','reason':'Reporting storage budget/reserve reached; raw capture retained'})
    summary=analyze(out)
    write_json(out/'run-summary.json',summary)
    if reporting_allowed and 128*MIB-tree_size(out)>=MIB and shutil.disk_usage(out).free>=1024*MIB+MIB:
        generate_reports(out,summary)
        manifest=json.loads((out/'manifest.json').read_text())
        manifest['report_status']='PASS'
        manifest.pop('report_reason',None)
        write_json(out/'manifest.json',manifest)
    else:
        manifest['report_status']='BLOCKED'
        manifest['report_reason']='Insufficient reporting budget; no heavy extraction/workbook writing attempted'
        write_json(out/'manifest.json',manifest)
        print('REPORT BLOCKED: storage reserve; raw capture and JSON summary retained',flush=True)
    manifest=json.loads((out/'manifest.json').read_text())
    manifest['actual_storage_bytes']=tree_size(out)
    manifest['storage_status']='PASS' if manifest['actual_storage_bytes']<=128*MIB else 'FAIL'
    write_json(out/'manifest.json',manifest)
    summary['report_status']=manifest.get('report_status','BLOCKED')
    summary['manifest']=manifest
    write_json(out/'run-summary.json',summary)
    return summary


def result_exit(summary):
    if summary.get('report_status',summary.get('manifest',{}).get('report_status','PASS')) != 'PASS':
        return 2
    if summary.get('workload_validity',{}).get('status')!='PASS' or summary.get('measurement_completeness',{}).get('status')!='PASS':
        return 2
    if summary.get('functional_correctness')!='PASS' or summary.get('performance')=='FAIL':
        return 1
    return 0


def run_one(args, profile, repetition, suite_dir):
    stamp = dt.datetime.now(dt.timezone.utc).strftime('%Y%m%dT%H%M%S')
    run_id = f'{stamp}-{profile.lower()}-{args.seed}-{repetition}'
    out = suite_dir / run_id
    out.mkdir(parents=True, exist_ok=False)
    phases = phase_config(args, profile)
    duration = sum(p['durationSeconds'] for p in phases)
    clients, rps, nt_rate, mib_rate = PROFILES[profile]
    if args.timing == 'stepped':
        # Accelerated simulation correctness is not a rated wall-paced network benchmark.
        clients = rps = nt_rate = 0
    config = {'profile': profile, 'seed': args.seed, 'timing': args.timing,
              'detailed': not args.minimal, 'recording': not args.observation_off, 'phases': phases,
              'ntPort': local_port(), 'runId': run_id, 'waitForStart': True, 'fault':args.fault}
    config_hash = hashlib.sha256(json.dumps({k:v for k,v in config.items() if k not in ('ntPort','runId')}, sort_keys=True).encode()).hexdigest()
    manifest = identity() | {'run_id': run_id, 'utc_timestamp': stamp, 'configuration_hash': config_hash,
                'scenario_seed': args.seed, 'profile': profile, 'timing': args.timing,
                'run_kind':args.command.upper(), 'planned_repetitions':args.repetitions,
                'observation_enabled':not args.observation_off,
                'instrumentation': 'off_with_shared_witness' if args.observation_off else 'minimal' if args.minimal else 'detailed',
                'execution_mode':'DESKTOP_MATCH_SIM', 'pacing':args.timing,
                'seed':args.seed, 'scenario_hash':config_hash,
                'logging_mode':'DEBUG' if profile=='HARD_DEBUG' else 'COMP',
                'load_required':bool(clients),
                'expected_load_duration_s':max(.1,duration-2),
                'recovery_start_s':111 if profile=='OVERLOAD_RECOVERY' else None,
                'phase_timeline': phases, 'warmup_policy': 'warmup separately reported, never pooled into steady state',
                'load_configuration': {'clients': clients, 'http_rps': rps, 'synthetic_nt_updates_per_second': nt_rate,
                     'http_body_mib_per_second': mib_rate, 'offhost': False},
                'completion': 'RUNNING', 'hardware_coverage': 'NOT RUN', 'source_logs': [],
                'limits': {'run_bytes':128*MIB, 'suite_bytes':2048*MIB, 'reserve_bytes':1024*MIB}}
    write_json(out / 'manifest.json', manifest); write_json(out / 'scenario.json', config)
    owned = []; logs = []; failure = None
    host_resource_counts={}
    resource_fields = ['elapsed_s','monotonic_ns','kind','pid','process_cpu_ns','rss_bytes','thread_count','file_descriptors','log_bytes','resource_unavailable']
    resource_path = out / 'host-resource-samples.csv'
    try:
        if shutil.disk_usage(out).free < 1024*MIB + 4*MIB:
            raise RuntimeError('Storage preflight: less than 1 GiB reserve plus 4 MiB reporting margin available')
        work = out / 'working'; (work / 'src/main').mkdir(parents=True)
        copy_deploy(work / 'src/main/deploy')
        cmd = java_command(args, 'org.Griffins1884.frc2027.performance.DesktopMatchMain')
        cmd.insert(1, '-Dfrc.performance.runId=' + run_id)
        manifest['java_command'] = cmd
        manifest.update(executed_artifacts(cmd))
        manifest['java_runtime'] = subprocess.run([cmd[0], '-version'], capture_output=True, text=True,timeout=10).stderr.strip()
        if '17.0.' not in manifest['java_runtime']:
            raise ValueError('The suite requires the project Java 17 runtime; pass --java or JAVA_HOME')
        compatible=(sys.platform=='darwin' and platform.machine()=='arm64' and sys.version_info[:3]==(3,11,9) and platform.python_implementation()=='CPython' and '17.0.18+8' in manifest['java_runtime'])
        manifest['cross_process_clock_alignment']={'verified':compatible,'basis':'source_verified_same_host_mach_absolute_time' if compatible else 'UNVERIFIED'}
        jar = ROOT / 'build/libs/Season2027.jar'
        manifest['artifact_hash'] = hash_file(jar) if jar.exists() else None
        if manifest['artifact_hash']:cmd.insert(1,'-Dfrc.performance.artifactSha256='+manifest['artifact_hash'])
        manifest['performance_class_hash'] = hashlib.sha256(''.join(hash_file(p) for p in sorted((ROOT/'build/classes/java/performance').rglob('*.class'))).encode()).hexdigest()
        write_json(out/'manifest.json', manifest)
        log = (out/'robot-process.log').open('w'); logs.append(log)
        robot = subprocess.Popen(cmd + ['--config',str(out/'scenario.json'),'--output',str(out)], cwd=work, stdout=log, stderr=subprocess.STDOUT)
        owned.append(('robot',robot)); started = time.monotonic(); next_resource = started
        with resource_path.open('w') as resource_stream:
            writer = csv.DictWriter(resource_stream,resource_fields); writer.writeheader()
            ready = None; load_started=False; robot_ready_elapsed=None
            while robot.poll() is None:
                now = time.monotonic()
                if now-started > duration+120:
                    raise TimeoutError('Robot run exceeded finite startup + scenario allowance')
                ready_path=out/'desktop-ready.json'
                if ready is None and ready_path.exists():
                    try: ready=json.loads(ready_path.read_text())
                    except json.JSONDecodeError: ready=None
                    if ready is not None: robot_ready_elapsed=now-started
                if ready and not load_started:
                    load_started=True
                    port=ready['httpPort']; nt_port=ready.get('ntPort',config['ntPort'])
                    # Readiness emitted before timeline; traffic starts only after actual ports are known.
                    if clients and rps:
                        http_cmd=[sys.executable,str(HERE/'match_load.py'),'--target',f'http://127.0.0.1:{port}',
                            '--output',str(out/'load-http.csv'),'--duration',str(max(.1,duration-2)), '--rps',str(rps),
                            '--clients',str(clients),'--max-bytes-per-second',str(int(mib_rate*MIB)),
                            '--ready-file',str(out/'http-ready.json'),'--start-marker',str(out/'start.marker'),'--warmup-seconds','2']
                        if profile == 'OVERLOAD_RECOVERY':
                            http_cmd += ['--ladder',f'20:20,40:20,80:20,1:{max(.1,duration-2-111)}','--ladder-delay','51']
                        f=(out/'http-generator.log').open('w');logs.append(f)
                        owned.append(('http_generator',subprocess.Popen(http_cmd,stdout=f,stderr=subprocess.STDOUT)))
                    if clients:
                        nt_cmd=java_command(args,'org.Griffins1884.frc2027.performance.NtLoadMain') + [
                            '--host','127.0.0.1','--port',str(nt_port),'--output',str(out/'load-nt.csv'),
                            '--duration',str(max(.1,duration-2)),'--updates-per-second',str(nt_rate), '--clients',str(clients), '--run-id',run_id,'--ready-file',str(out/'nt-ready.json'),'--start-marker',str(out/'start.marker'),'--warmup-seconds','2']
                        if profile == 'OVERLOAD_RECOVERY':
                            nt_cmd += ['--ladder','2000:20,5000:20,10000:20,0:20','--ladder-delay','51']
                        f=(out/'nt-generator.log').open('w');logs.append(f)
                        owned.append(('nt_generator',subprocess.Popen(nt_cmd,stdout=f,stderr=subprocess.STDOUT)))
                if load_started and not (out/'start.marker').exists():
                    if (not clients or (out/'nt-ready.json').exists()) and (not rps or (out/'http-ready.json').exists()):
                        (out/'start.marker').touch()
                        manifest['load_start_elapsed_s']=now-started
                        manifest['period_ns']=int(ready['periodSeconds']*1e9)
                        manifest['odometry_target_hz']=ready['odometryTargetHz']
                        manifest['odometry_hz']=ready['odometryTargetHz']
                        manifest['load_configuration']['duration_s']=max(.1,duration-2)
                if now >= next_resource:
                    size=tree_size(out)
                    for kind, process in owned:
                        writer.writerow({'elapsed_s':now-started,'monotonic_ns':time.monotonic_ns(),'kind':kind,'pid':process.pid,'log_bytes':size if kind=='robot' else '',**sample_process(process.pid)})
                        host_resource_counts[kind]=host_resource_counts.get(kind,0)+1
                    writer.writerow({'elapsed_s':now-started,'monotonic_ns':time.monotonic_ns(),'kind':'orchestrator','pid':os.getpid(),**sample_process(os.getpid())})
                    host_resource_counts['orchestrator']=host_resource_counts.get('orchestrator',0)+1
                    resource_stream.flush(); next_resource=now+1
                    if size>124*MIB or tree_size(suite_dir)>2044*MIB or shutil.disk_usage(out).free<1024*MIB+4*MIB:
                        raise RuntimeError('Storage budget/reserve reached; preserving partial evidence')
                time.sleep(.05)
            if robot.returncode:
                evidence_path=out/'desktop-evidence.json'
                evidence=json.loads(evidence_path.read_text()) if evidence_path.exists() else {}
                if robot.returncode==1 and evidence.get('completed'):
                    manifest['workload_exit_code']=1
                else:
                    raise RuntimeError(f'Robot process exited {robot.returncode}; see robot-process.log')
            for kind,process in owned[1:]:
                try: process.wait(timeout=5)
                except subprocess.TimeoutExpired: stop_owned(process)
                if process.returncode and process.returncode != 2:
                    raise RuntimeError(f'{kind} exited {process.returncode}; partial evidence retained')
                if process.returncode == 2:
                    manifest.setdefault('load_validity_failures', []).append(kind + ': offered load not achieved; inspect summary')
        manifest['completion']='COMPLETE'
        manifest['completed_utc_timestamp']=dt.datetime.now(dt.timezone.utc).isoformat()
        manifest['robot_ready_elapsed_s']=robot_ready_elapsed
        manifest['capture_process_elapsed_s']=time.monotonic()-started
    except (Exception,KeyboardInterrupt) as exc:
        failure=f'{type(exc).__name__}: {exc}'
        manifest['completion']='ABORTED'; manifest['abort_reason']=failure
    finally:
        for _,process in reversed(owned): stop_owned(process)
        for log in logs: log.close()
        manifest['source_logs']=[{'path':str(p.relative_to(out)), 'bytes':p.stat().st_size,'sha256':hash_file(p)} for p in out.rglob('*.wpilog')]
        manifest['actual_storage_bytes']=tree_size(out)
        manifest['host_resource_sample_counts']=host_resource_counts
        write_json(out/'manifest.json',manifest)
        merge_traffic(out)
        event=out/'failures-and-events.csv'
        if not event.exists():
            with event.open('w') as f:
                w=csv.writer(f);w.writerow(['elapsed_s','kind','detail'])
                if failure:w.writerow(['','ABORTED',failure])
        # This alias preserves distinct JVM resource data when the desktop collector writes it.
        if not (out/'resource-samples.csv').exists(): shutil.copyfile(resource_path,out/'resource-samples.csv') if resource_path.exists() else (out/'resource-samples.csv').write_text('elapsed_s,kind\n')
    summary=analyze_run(out)
    print(json.dumps({'run':str(out),'completion':manifest['completion'],'workload_validity':summary.get('workload_validity'),'performance':summary.get('performance'),'loop':summary.get('loop')},allow_nan=False),flush=True)
    return out,summary


def main(argv=None):
    parser=argparse.ArgumentParser(description=__doc__)
    subs=parser.add_subparsers(dest='command',required=True)
    pre=subs.add_parser('preflight')
    pre.add_argument('--java');pre.add_argument('--classpath')
    for name in ['smoke','run','suite','overload','soak','overhead']:
        p=subs.add_parser(name)
        p.add_argument('--output',type=Path,default=ROOT/'build/performance/runs')
        p.add_argument('--profile',choices=PROFILES,default='NORMAL')
        p.add_argument('--seed',type=finite_int(0,2**31-1),default=1884)
        p.add_argument('--timing',choices=['paced','stepped'],default='paced')
        p.add_argument('--repetitions',type=finite_int(1,10),default=5 if name=='suite' else 3 if name=='overhead' else 1)
        p.add_argument('--cycles',type=finite_int(1,10),default=2)
        p.add_argument('--first-repetition',type=finite_int(1,10),default=1)
        p.add_argument('--profiles',help='Comma-separated primary-suite subset for finite resumed collection')
        p.add_argument('--timeline',type=Path);p.add_argument('--minimal',action='store_true')
        p.add_argument('--observation-off',action='store_true',help='Disable runtime observation telemetry; retain identical outer-loop and workload witness capture')
        p.add_argument('--fault',choices=['none','blocked_teleop','empty_auto'],default='none')
        p.add_argument('--java');p.add_argument('--classpath')
    report=subs.add_parser('report');report.add_argument('run_directory',type=Path)
    compare=subs.add_parser('compare');compare.add_argument('baseline',type=Path);compare.add_argument('candidate',type=Path)
    compare.add_argument('--output',type=Path)
    imp=subs.add_parser('import-roborio');imp.add_argument('log',type=Path);imp.add_argument('--metadata',type=Path,required=True);imp.add_argument('--output',type=Path,required=True)
    load=subs.add_parser('load');load.add_argument('--nt',action='store_true',help='Use the separate Java read-only NT client (synthetic publishing is loopback-only)');load.add_argument('--java');load.add_argument('--classpath');load.add_argument('arguments',nargs=argparse.REMAINDER)
    args=parser.parse_args(argv)
    if args.command=='preflight':
        data=identity();data['free_bytes']=shutil.disk_usage(ROOT).free;data['java_home']=os.environ.get('JAVA_HOME')
        data['classpath_available']=(ROOT/'build/performance/classpath.txt').exists()
        data['native_libraries_available']=(ROOT/'build/jni/release').exists()
        data['gradle_wrapper_present']=(ROOT/'gradlew').exists();data['system_gradle']=shutil.which('gradle')
        data['metrics_psutil_available']=__import__('importlib.util').util.find_spec('psutil') is not None
        data['xlsxwriter_available']=__import__('importlib.util').util.find_spec('xlsxwriter') is not None
        print(json.dumps(data,indent=2));return 0
    if args.command=='report': return result_exit(analyze_run(args.run_directory.resolve()))
    if args.command=='compare':
        from match_analysis import compare_runs
        comparison=compare_runs(args.baseline.resolve(),args.candidate.resolve())
        output=args.output.resolve() if args.output else args.candidate.resolve()/'baseline-comparison.json'
        write_json(output,comparison)
        from match_report import report
        report(args.candidate.resolve(),comparison=comparison)
        print(json.dumps(comparison,indent=2,allow_nan=False));return 0 if comparison.get('status')=='PASS' else 2
    if args.command=='import-roborio':
        from match_analysis import import_roborio
        import_roborio(args.log.resolve(),args.metadata.resolve(),args.output.resolve());return result_exit(analyze_run(args.output.resolve()))
    if args.command=='load':
        forwarded=args.arguments[1:] if args.arguments[:1]==['--'] else args.arguments
        command=java_command(args,'org.Griffins1884.frc2027.performance.NtLoadMain') if args.nt else [sys.executable,str(HERE/'match_load.py')]
        return subprocess.call([*command,*forwarded])
    suite_dir=args.output.resolve();suite_dir.mkdir(parents=True,exist_ok=True)
    profiles=['IDLE','IDLE_DASHBOARD','EASY','NORMAL','HARD'] if args.command=='suite' else [('OVERLOAD_RECOVERY' if args.command=='overload' else args.profile)]
    if args.profiles:
        if args.command!='suite':raise ValueError('--profiles is only available for suite')
        profiles=args.profiles.split(',')
        if not profiles or len(profiles)>8 or len(set(profiles))!=len(profiles) or any(p not in PROFILES for p in profiles):raise ValueError('Invalid or duplicate suite profiles')
    if args.first_repetition+args.repetitions-1>10:raise ValueError('Repetition indices must remain1..10')
    estimated=sum(sum(p['durationSeconds'] for p in phase_config(args,profile))*args.repetitions for profile in profiles)
    if estimated>7200:raise ValueError('Suite timeline exceeds finite 7200 second cap')
    outputs=[];suite_started=time.monotonic()
    if args.command=='overhead':
        if estimated*2>7200:raise ValueError('Paired overhead timeline exceeds7200seconds')
        before_paths=[];after_paths=[]
        for repetition in range(args.first_repetition,args.first_repetition+args.repetitions):
            # Alternate order; both sides retain the identical outer timing and workload witness.
            for observation_off in ([True,False] if repetition%2 else [False,True]):
                args.observation_off=observation_off
                out,summary=run_one(args,args.profile,repetition,suite_dir)
                outputs.append({'path':str(out),'summary':summary})
                (before_paths if observation_off else after_paths).append(out)
                if json.loads((out/'manifest.json').read_text())['completion']=='ABORTED':
                    write_json(suite_dir/'suite-summary.json',{'completion':'ABORTED','runs':outputs});return 2
        from match_analysis import compare_overhead
        from match_report import generate_suite_reports,report
        result=compare_overhead(before_paths,after_paths)
        write_json(suite_dir/'overhead-summary.json',result)
        generate_suite_reports(suite_dir,[Path(item['path']) for item in outputs])
        for path in after_paths:report(path,comparison=result)
        print(json.dumps(result,indent=2,allow_nan=False))
        return 0 if result.get('status')=='PASS' else 2
    for repetition in range(args.first_repetition,args.first_repetition+args.repetitions):
        for profile in profiles:
            if time.monotonic()-suite_started>7200:raise RuntimeError('Suite wall-clock cap reached')
            out,summary=run_one(args,profile,repetition,suite_dir);outputs.append({'path':str(out),'summary':summary})
            if json.loads((out/'manifest.json').read_text())['completion']=='ABORTED':
                write_json(suite_dir/'suite-summary.json',{'completion':'ABORTED','runs':outputs});return 2
    write_json(suite_dir/'suite-summary.json',{'completion':'COMPLETE','primary_repetitions':args.repetitions,'runs':outputs})
    from match_report import generate_suite_reports
    generate_suite_reports(suite_dir,[Path(item['path']) for item in outputs])
    invalid = any(result_exit(item['summary'])==2 for item in outputs)
    failed = any(item['summary'].get('functional_correctness') != 'PASS' or item['summary'].get('performance') == 'FAIL' for item in outputs)
    return 2 if invalid else 1 if failed else 0

if __name__=='__main__':
    try:raise SystemExit(main())
    except (ValueError,RuntimeError,OSError) as error:print(f'ERROR: {error}',file=sys.stderr);raise SystemExit(2)
