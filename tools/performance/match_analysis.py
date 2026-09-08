"""Host-only match capture analysis. Raw files remain authoritative and unmodified."""
from __future__ import annotations

import csv
import hashlib
import json
import math
from pathlib import Path
from collections import defaultdict

CSV_FILES = ('loop-samples.csv', 'subsystem-timings.csv', 'resource-samples.csv',
             'http-nt-results.csv', 'failures-and-events.csv')


def number(value):
    try:
        result = float(value)
        return result if math.isfinite(result) else None
    except (TypeError, ValueError):
        return None


def quantile(values, fraction):
    """Nearest rank: sorted values[ceil(p*n)-1], with p=0 selecting minimum."""
    ordered = sorted(v for x in values if (v := number(x)) is not None)
    if not ordered:
        return None
    if not 0 <= fraction <= 1:
        raise ValueError('quantile must be between 0 and 1')
    return ordered[max(0, math.ceil(fraction * len(ordered)) - 1)]


def stats(values):
    values = list(values)
    valid = [v for x in values if (v := number(x)) is not None]
    return {'sample_count': len(valid), 'missing_count': len(values) - len(valid),
            'mean': sum(valid) / len(valid) if valid else None,
            'median': quantile(valid, .5), 'p95': quantile(valid, .95),
            'p99': quantile(valid, .99), 'maximum': max(valid) if valid else None,
            'minimum': min(valid) if valid else None}


def cpu_percent(cpu_delta_ns, wall_delta_ns, cpu_capacity=None):
    cpu, wall, capacity = map(number, (cpu_delta_ns, wall_delta_ns, cpu_capacity))
    if cpu is None or wall is None or cpu < 0 or wall <= 0:
        return {'one_core_percent': None, 'machine_percent': None}
    one = 100 * cpu / wall
    return {'one_core_percent': one,
            'machine_percent': one / capacity if capacity and capacity > 0 else None}


def read_csv(path):
    if not path.exists():
        return []
    with path.open(newline='') as source:
        return list(csv.DictReader(source))


def first_number(row, *names):
    return next((v for key in names if (v := number(row.get(key))) is not None), None)


def loop_metrics(rows, period_ns=None):
    executions, lateness, intervals, headroom = [], [], [], []
    overruns = misses = streak = longest = missing = skipped = additional_expired = 0
    previous_start = previous_cycle = None
    previous_run = None
    for row in rows:
        cycle = first_number(row, 'cycle')
        run = row.get('run_id')
        if ((cycle is not None and previous_cycle is not None and cycle != previous_cycle + 1)
                or run != previous_run):
            previous_start = None
            streak = 0
        previous_cycle, previous_run = cycle, run
        period = first_number(row, 'period_ns') or number(period_ns)
        if period is None and number(row.get('period_us')) is not None:
            period = float(row['period_us']) * 1000
        start = first_number(row, 'start_ns', 'actual_start_ns')
        end = first_number(row, 'end_ns', 'actual_end_ns')
        due = first_number(row, 'scheduled_ns', 'due_ns', 'original_due_ns')
        execution = first_number(row, 'execution_ns', 'elapsed_ns')
        if execution is None and number(row.get('execution_ms')) is not None:
            execution = float(row['execution_ms']) * 1e6
        if execution is None and start is not None and end is not None:
            execution = end - start
        late = first_number(row, 'lateness_ns')
        if late is None and number(row.get('lateness_ms')) is not None:
            late = float(row['lateness_ms']) * 1e6
        if late is None and number(row.get('start_us')) is not None and number(row.get('original_due_us')) is not None:
            late = (float(row['start_us']) - float(row['original_due_us'])) * 1000
        if late is None and start is not None and due is not None:
            late = start - due
        executions.append(execution / 1e6 if execution is not None else None)
        lateness.append(late / 1e6 if late is not None else None)
        if previous_start is not None and start is not None:
            intervals.append((start - previous_start) / 1e6)
        previous_start = start
        if period is None or period <= 0 or execution is None or late is None or execution < 0:
            missing += 1
            headroom.append(None)
            streak = 0
            continue
        margin = period - late - execution
        # HAL scheduling timestamps define due/deadline; nanoTime defines execution.
        # They have different epochs and must never be subtracted from each other.
        if number(row.get('end_us')) is not None and number(row.get('original_due_us')) is not None:
            margin = (float(row['original_due_us']) - float(row['end_us'])) * 1000 + period
        headroom.append(margin / 1e6)
        miss = margin < 0
        overruns += execution > period
        misses += miss
        streak = streak + 1 if miss else 0
        longest = max(longest, streak)
        rebase_slots = int(first_number(row, 'skipped_releases') or 0)
        skipped += rebase_slots
        additional_expired += max(0,rebase_slots-1)
    count = len(rows)
    return {'execution_ms': stats(executions), 'lateness_ms': stats(lateness),
            'start_interval_ms': stats(intervals), 'headroom_ms': stats(headroom),
            'execution_overruns': overruns, 'deadline_misses': misses,
            'executed_cycle_deadline_misses':misses,
            'additional_expired_slots_at_rebase':additional_expired,
            'total_missed_release_slots':misses+additional_expired,
            'observed_release_slots':count+additional_expired,
            'missed_release_slot_fraction':(misses+additional_expired)/(count+additional_expired) if count else None,
            'release_accounting':'Expired slots inferred at scheduler rebase: raw k=floor((effectiveDue-originalDue)/period); max(0,k-1) excludes the original slot already counted with its executed cycle. Not a fixed global schedule.',
            'deadline_miss_fraction': misses / count if count else None,
            'executed_cycle_deadline_miss_fraction':misses/count if count else None,
            'execution_overrun_fraction': overruns / count if count else None,
            'longest_miss_streak': longest, 'missing_deadline_samples': missing,
            'skipped_releases': skipped}


def workload_validity(manifest):
    """Evidence must be supplied explicitly; absence is not success."""
    evidence = manifest.get('workload_evidence', {})
    reasons = []
    required = manifest.get('required_evidence', ['phases_reached', 'commands_executed',
                'sensor_samples', 'odometry_samples', 'readiness'])
    if manifest.get('profile', '').upper() not in ('IDLE', 'IDLE_NO_DASHBOARD', 'IDLE_DASHBOARD'):
        required = list(dict.fromkeys(required + ['drive_inputs', 'output_changes', 'pose_evolved']))
    reasons.extend(manifest.get('phase_validity_failures', []))
    for key in required:
        value = evidence.get(key)
        if value is not True and not (isinstance(value, (int, float)) and value > 0):
            reasons.append(f'missing or failed workload evidence: {key}')
    for phase in manifest.get('phase_evidence', []):
        name = str(phase.get('name','unknown')).lower()
        observed = phase.get('evidence',{})
        loops = number(observed.get('loops'))
        def positive(key):
            return (number(observed.get(key)) or 0) > 0
        if not loops or not positive('consumedSamples') or number(observed.get('readyLoops')) != loops:
            reasons.append(f'{name}: missing loops, fresh odometry, or readiness')
        moving = ('teleop' in name or 'auto' in name) and 'disabled' not in name
        if moving:
            for key in ('enabledLoops','appliedDriveRequests','distanceMeters','maxMeasuredSpeed','maxDesiredSpeed','maxDriveVoltage'):
                if not positive(key):
                    reasons.append(f'{name}: missing {key}')
            if number(observed.get('enabledLoops')) != loops:
                reasons.append(f'{name}: actual Driver Station state was not enabled throughout')
            command_key = 'autonomousCommandExecutions' if 'auto' in name else 'driveCommandExecutions'
            if not positive(command_key):
                reasons.append(f'{name}: expected command never executed')
            if 'teleop' in name and not positive('nonzeroInputLoops'):
                reasons.append(f'{name}: driver inputs never reached drive path')
        elif 'disabled' in name or manifest.get('profile','').startswith('IDLE'):
            if number(observed.get('enabledLoops')) != 0:
                reasons.append(f'{name}: expected disabled Driver Station state was not observed')
    if (manifest.get('execution_mode') == 'ROBORIO_OBSERVE' or manifest.get('hal_runtime_type') in ('kRoboRIO','kRoboRIO2')) and manifest.get('logged_runtime_mode') in ('SIM','REPLAY'):
        reasons.append('real-HAL capture used '+manifest['logged_runtime_mode']+' application IO mode; not a production hardware workload')
    if manifest.get('execution_mode') == 'REPLAY' and not evidence.get('replay_inputs_restored'):
        reasons.append('replay sensor restoration is unverified')
    return {'status': 'INVALID' if reasons else 'PASS', 'reasons': reasons}


def traffic_summary(rows):
    result = {}
    for kind in sorted(set(row.get('kind', 'unknown').upper() for row in rows)):
        group = [row for row in rows if row.get('kind', 'unknown').upper() == kind]
        if group and 'status' in group[0] and not any('scheduled' in row for row in group):
            normalized = []
            first_start = min((number(row.get('scheduled_ns')) or 0 for row in group), default=0)
            for row in group:
                status = str(row.get('status'))
                if status == 'SUMMARY':
                    continue  # Cumulative NT summaries are joined from the companion JSON.
                ok = status in ('200','304')
                normalized.append(dict(row, scheduled=1, started=int(status != 'LOCAL_DROP'),
                    completed=int(ok), failed=int(not ok and status not in ('LOCAL_DROP','TIMEOUT')),
                    timed_out=int(status == 'TIMEOUT'), dropped=int(status == 'LOCAL_DROP'),
                    responses_200=int(status == '200'), responses_304=int(status == '304'),
                    elapsed_s=((number(row.get('end_ns')) or first_start)-first_start)/1e9,
                    latency_ns=(number(row.get('latency_ms')) or 0)*1e6))
            group = normalized
        totals = {}
        for field in ('scheduled', 'started', 'completed', 'failed', 'timed_out', 'dropped',
                      'bytes', 'published', 'received', 'coalesced', 'responses_200', 'responses_304'):
            values = [number(row.get(field)) for row in group]
            totals[field] = sum(v for v in values if v is not None) if any(v is not None for v in values) else None
        scheduled, completed = totals['scheduled'], totals['completed']
        totals['completion_fraction'] = completed / scheduled if scheduled and completed is not None else None
        elapsed = max((first_number(row, 'elapsed_s') or 0 for row in group), default=0)
        totals['achieved_per_s'] = completed / elapsed if elapsed and completed is not None else None
        totals['latency_ms'] = stats((n / 1e6 if (n := number(row.get('latency_ns'))) is not None else None) for row in group)
        totals['status'] = 'INVALID' if scheduled and (completed is None or completed < scheduled) else 'PASS'
        if any((totals.get(key) or 0) > 0 for key in ('failed', 'timed_out', 'dropped')):
            totals['status'] = 'INVALID'
        result[kind] = totals
    return result


def analyze_run(directory):
    directory = Path(directory)
    manifest = json.loads((directory / 'manifest.json').read_text())
    for canonical, alias in {'pacing':'timing','seed':'scenario_seed','scenario_hash':'configuration_hash','load_config':'load_configuration'}.items():
        if canonical not in manifest and alias in manifest:
            manifest[canonical] = manifest[alias]
    scenario_file = directory/'scenario.json'
    scenario = json.loads(scenario_file.read_text()) if scenario_file.exists() else {}
    manifest.setdefault('observation_enabled',scenario.get('recording',True))
    manifest['aborted'] = manifest.get('aborted', manifest.get('completion') == 'ABORTED')
    evidence_path = directory / 'desktop-evidence.json'
    if evidence_path.exists():
        captured = json.loads(evidence_path.read_text())
        for key in ('workload_evidence','capture_complete','dropped_records','expected_loop_samples','functional_correctness','logger_queue_fault_cycles','io_model'):
            if key in captured:
                manifest[key] = captured[key]
        manifest['phase_evidence'] = captured.get('phases', [])
        manifest['phase_validity_failures'] = captured.get('phase_validity_failures', [])
        if 'phase_obligations' in captured.get('workload_evidence', {}):
            manifest['required_evidence'] = list(dict.fromkeys(manifest.get('required_evidence', ['phases_reached','commands_executed','sensor_samples','odometry_samples','readiness']) + ['phase_obligations']))
        manifest.setdefault('capture_complete', captured.get('completed', False))
        manifest.setdefault('dropped_records', captured.get('droppedFrames'))
        manifest.setdefault('expected_loop_samples', captured.get('loops'))
        if captured.get('failure') or captured.get('writerFailure'):
            manifest['capture_complete'] = False
            manifest['functional_correctness'] = 'FAIL'
    data = {name: read_csv(directory / name) for name in CSV_FILES}
    rows = data['loop-samples.csv']
    period = manifest.get('period_ns', (manifest.get('period_s') or 0) * 1e9) or None
    if period is None and rows:
        period = first_number(rows[0], 'period_ns')
        if period is None and number(rows[0].get('period_us')) is not None:
            period = float(rows[0]['period_us']) * 1000
        if period is not None:
            manifest['period_ns'] = period
    completeness = []
    import_proof = verify_import_source(manifest)
    verified_import = import_proof['status'] == 'PASS'
    if import_proof['status'] == 'FAIL':
        completeness.append('import source verification failed: '+import_proof['reason'])
    extraction = directory/'log-extraction.json'
    if (manifest.get('execution_mode') == 'DESKTOP_MATCH_SIM' and manifest['observation_enabled']
            and manifest.get('fixture_data') is not True and not verified_import):
        sources = list((directory/'logs').glob('akit*.wpilog'))
        if len(sources) != 1:
            completeness.append('observation-on desktop capture requires exactly one AKit source log')
        if not extraction.exists():
            completeness.append('observation-on desktop capture lacks verified WPILOG extraction')
    if not verified_import and extraction.exists() and json.loads(extraction.read_text()).get('status') != 'PASS':
        completeness.append('WPILOG extraction or CSV/log agreement failed')
    for name in CSV_FILES:
        if not (directory / name).exists():
            completeness.append(f'missing {name}')
    expected = manifest.get('expected_loop_samples')
    if expected is not None and len(rows) != expected:
        completeness.append(f'loop sample count {len(rows)} != expected {expected}')
    cycles = [first_number(row, 'cycle') for row in rows]
    if any(a is not None and b is not None and b != a + 1 for a, b in zip(cycles, cycles[1:])):
        completeness.append('loop sequence has gaps or duplicates')
    if not rows:
        completeness.append('no loop samples')
    if not data['subsystem-timings.csv']:
        completeness.append('no subsystem timing samples')
    jvm_resources = [r for r in data['resource-samples.csv'] if r.get('kind') not in ('robot_logger','robot_observation')]
    if not manifest['observation_enabled']:
        jvm_resources = [r for r in read_csv(directory/'host-resource-samples.csv') if r.get('kind')=='robot']
    if not jvm_resources:
        completeness.append('no resource sampling records (unsupported values must be explicit)')
    resource_capture = resource_capture_coverage(rows, jvm_resources, manifest['observation_enabled'], manifest.get('host_resource_sample_counts',{}).get('robot'))
    if resource_capture['status'] == 'FAIL':
        completeness.append('resource probe coverage failed: '
                            f"{resource_capture['missing_samples']} missing, "
                            f"{resource_capture['unexpected_samples']} unexpected, "
                            f"{resource_capture['invalid_timestamp_samples']} invalid timestamps")
    if not manifest.get('capture_complete', False):
        completeness.append('capture has no verified completion marker')
    if (manifest.get('logger_queue_fault_cycles') or 0) > 0:
        completeness.append('AdvantageKit receiver queue overflow occurred')
    if (manifest.get('dropped_records') or 0) > 0:
        completeness.append('measurement records were dropped')
    if manifest.get('truncated', False):
        completeness.append('source log is truncated')
    if (manifest.get('driver_station_phase_inference',{}).get('unverified_cycles') or 0) > 0:
        completeness.append('completed cycles lack recorded Driver Station phase evidence')
    metrics = loop_metrics(rows, period)
    steady_rows = [r for r in rows if str(r.get('phase','')).lower() not in ('startup','warmup','warm-up','transition','disabled-transition','disabled_transition')]
    steady_metrics = loop_metrics(steady_rows, period)
    if metrics['missing_deadline_samples']:
        completeness.append('deadline inputs are missing')
    validity = workload_validity(manifest)
    traffic = traffic_summary(data['http-nt-results.csv'])
    for companion in sorted(directory.glob('*.summary.json')):
        load = json.loads(companion.read_text())
        kind = 'NT' if 'published' in load else 'HTTP' if 'offered_rps' in load else None
        if kind is None:
            continue
        item = traffic.setdefault(kind, {})
        item.update(load)
        item['source_summary'] = companion.name
        item['completed'] = load.get('completed', load.get('delivered'))
        item['dropped'] = load.get('locally_dropped')
        item['achieved_per_s'] = load.get('achieved_rps')
        if kind == 'NT':
            duration = number(load.get('duration_s'))
            item['completed'] = load.get('published')
            item['received'] = load.get('delivered')
            item['achieved_per_s'] = load.get('active_window_achieved_publish_updates_per_second',load.get('achieved_publish_updates_per_second'))
            item['delivered_updates_per_s'] = load.get('delivered') / duration if duration and load.get('delivered') is not None else None
            item['coalesced_or_lost'] = load.get('undelivered')
            item['delivery_fraction'] = load.get('delivered') / load.get('expected_deliveries') if load.get('expected_deliveries') else None
        item['status'] = 'PASS' if load.get('valid') is True else 'INVALID'
        expected_duration = manifest.get('expected_load_duration_s', sum(number(p.get('durationSeconds')) or 0 for p in manifest.get('phase_timeline', [])))
        offered_duration = load.get('offered_duration_s')
        expected_scheduled = None
        if kind == 'HTTP' and isinstance(load.get('rate_phases'), list):
            offered_duration = sum(float(hold) for _,hold in load['rate_phases'])
            expected_scheduled = sum(int(float(rate)*float(hold)) for rate,hold in load['rate_phases'])
        elif kind == 'NT' and manifest.get('profile') != 'OVERLOAD_RECOVERY' and number(offered_duration) is not None:
            configured_rate = first_number(manifest.get('load_config',{}), 'synthetic_nt_updates_per_second')
            if configured_rate is not None:
                expected_scheduled = int(configured_rate*float(offered_duration))
        item['expected_scheduled'] = expected_scheduled
        item['offered_duration_matches_timeline'] = None if not expected_duration else (number(offered_duration) is not None and abs(float(offered_duration)-expected_duration) < .000001)
        if expected_duration and item['offered_duration_matches_timeline'] is not True:
            item['status'] = 'INVALID'
        if expected_scheduled is not None and load.get('scheduled') != expected_scheduled:
            item['status'] = 'INVALID'
        if kind == 'NT' and ((load.get('scheduled') is not None and load.get('published') != load.get('scheduled')) or (load.get('undelivered') or 0) > 0):
            item['status'] = 'INVALID'
        if load.get('aborted'):
            item['status'] = 'ABORTED'
    load_config = manifest.get('load_config', {})
    required_kinds = []
    if manifest.get('pacing') != 'stepped':
        if (number(load_config.get('http_rps')) or 0) > 0:
            required_kinds.append('HTTP')
        if (number(load_config.get('clients')) or 0) > 0:
            required_kinds.append('NT')
    for kind in required_kinds:
        if kind not in traffic or not traffic[kind].get('source_summary'):
            validity['reasons'].append(f'required {kind} load has no completed companion summary')
        elif traffic[kind].get('status') != 'PASS':
            validity['reasons'].append(f'required {kind} load is invalid or aborted')
    if manifest.get('load_required') and not traffic:
        validity['reasons'].append('required network load has no records')
    if any(item['status'] in ('INVALID','ABORTED') for item in traffic.values()) and manifest.get('profile') != 'OVERLOAD_RECOVERY':
        validity['reasons'].append('offered network load was not achieved')
    validity['status'] = 'INVALID' if validity['reasons'] else 'PASS'
    functional = manifest.get('functional_correctness', 'NOT RUN')
    if isinstance(functional, bool):
        functional = 'PASS' if functional else 'FAIL'
    recovery = verify_recovery(manifest, data['resource-samples.csv'], data['http-nt-results.csv'], traffic) if manifest.get('profile') == 'OVERLOAD_RECOVERY' else {'status':'NOT RUN'}
    if manifest.get('profile') == 'OVERLOAD_RECOVERY':
        manifest['recovery_verified'] = recovery['status'] == 'PASS'
        if recovery['status'] != 'PASS':
            functional = 'FAIL'
    phases = {phase: loop_metrics([r for r in rows if r.get('phase') == phase], period)
              for phase in sorted(set(r.get('phase', 'unknown') for r in rows))}
    scopes = defaultdict(list)
    scope_cycles = defaultdict(set)
    for row in data['subsystem-timings.csv']:
        value = first_number(row, 'elapsed_ns', 'duration_ns')
        if value is None and number(row.get('elapsed_ms')) is not None:
            value = float(row['elapsed_ms']) * 1e6
        scopes[row.get('scope', 'unknown')].append(value / 1e6 if value is not None else None)
        if value is not None:
            scope_cycles[row.get('scope','unknown')].add(row.get('cycle'))
    expected_cycles = {row.get('cycle') for row in rows}
    scope_capture = {name:{'expected_loop_samples':len(expected_cycles),'observed_unique_loop_samples':len(expected_cycles.intersection(scope_cycles[name])),
        'missing_loop_samples':len(expected_cycles.difference(scope_cycles[name]))} for name in scopes}
    required_scopes = manifest.get('required_scopes')
    if required_scopes is None and manifest.get('execution_mode') == 'DESKTOP_MATCH_SIM':
        required_scopes = ['Swerve periodic','Command scheduler','RobotContainer']
        if (manifest['observation_enabled'] and manifest.get('pacing') == 'paced'
                and manifest.get('io_model') == 'MAPLESIM'):
            required_scopes.append('Desktop MapleSim physics')
    physics_timing = {'status':'MEASURED' if 'Desktop MapleSim physics' in scope_capture else 'NOT MEASURED'}
    if 'Desktop MapleSim physics' not in scope_capture:
        physics_timing['reason'] = ('Runtime observation disabled; MapleSim still runs inside outer-loop elapsed time'
                                    if not manifest['observation_enabled'] and manifest.get('io_model') == 'MAPLESIM'
                                    else 'Ideal sensor fixture has no MapleSim physics execution'
                                    if manifest.get('io_model') == 'DETERMINISTIC_IDEAL_SENSOR_FIXTURE'
                                    else 'No separate physics timing samples were captured')
    scope_aliases = {'Swerve periodic':'/RealOutputs/Swerve/Performance/PeriodicMS',
                     'Command scheduler':'/RealOutputs/Robot/Performance/SchedulerMS',
                     'RobotContainer':'/RealOutputs/Robot/Performance/ContainerMS'}
    for name in required_scopes or []:
        measured_name = name if name in scope_capture else scope_aliases.get(name,name)
        if measured_name not in scope_capture or scope_capture[measured_name]['missing_loop_samples']:
            completeness.append(f'required scope {name} has missing loop samples')
    performance = 'FAIL' if steady_metrics['deadline_misses'] else 'PASS'
    if manifest.get('pacing') == 'stepped':
        performance = 'NOT MEASURED'
    elif completeness or validity['status'] != 'PASS' or functional != 'PASS':
        performance = 'INVALID'
    if manifest.get('aborted'):
        performance = 'ABORTED'
    return {'schema_version': 1, 'run_id': manifest.get('run_id'), 'platform': manifest.get('platform'),
            'profile': manifest.get('profile'), 'quantile_convention': 'nearest rank ceil(p*n), minimum for p=0',
            'workload_validity': validity,
            'measurement_completeness': {'status': 'FAIL' if completeness else 'PASS', 'reasons': completeness},
            'functional_correctness': functional, 'performance': performance,
            'hardware_validation': 'NOT MEASURED' if manifest.get('execution_mode') != 'ROBORIO_OBSERVE' else ('OBSERVED' if manifest.get('hardware_identity_verified') else 'IDENTITY UNVERIFIED'),
            'scope_capture':scope_capture,'physics_timing':physics_timing,'import_source_verification':import_proof,
            'resource_capture':resource_capture,
            'loop': metrics, 'steady_state_loop': steady_metrics, 'phases': phases, 'subsystems_ms': {k: stats(v) for k, v in scopes.items()},
            'traffic': traffic, 'spike_correlations':spike_correlations(rows,data['http-nt-results.csv'],data['resource-samples.csv'],period,manifest.get('warning_margin',.8),clock_domain_guard(manifest)), 'recovery': recovery, 'selected_threads': thread_summary(read_csv(directory/'thread-resource-samples.csv')), 'resources': resource_summary(data['resource-samples.csv'], read_csv(directory / 'host-resource-samples.csv')), 'manifest': manifest,
            'source_hashes': {n: hashlib.sha256((directory / n).read_bytes()).hexdigest()
                              for n in CSV_FILES if (directory / n).exists()}}


def compare_runs(baseline, candidate):
    """Compare equivalent measured workloads on the same identified target/runtime."""
    if not isinstance(baseline, dict):
        baseline = analyze_run(baseline)
    if not isinstance(candidate, dict):
        candidate = analyze_run(candidate)
    for run in (baseline, candidate):
        if (run.get('workload_validity', {}).get('status') != 'PASS'
                or run.get('measurement_completeness', {}).get('status') != 'PASS'
                or run.get('functional_correctness') != 'PASS'):
            return {'status': 'INVALID', 'reason': 'Baseline or candidate lacks valid workload, complete measurement, or correct behavior'}
    a, b = baseline['manifest'], candidate['manifest']
    fields = ('platform', 'execution_mode', 'pacing', 'profile', 'seed', 'scenario_hash',
              'logging_mode', 'instrumentation', 'load_config', 'period_ns', 'run_kind',
              'host_id', 'cpu_model', 'cpu_capacity', 'os', 'architecture', 'java_runtime')
    def unavailable(value):
        return value is None or (isinstance(value, str) and value.strip().upper() in ('', 'UNAVAILABLE', 'UNKNOWN', 'NOT MEASURED'))
    mismatch = [key for key in fields if unavailable(a.get(key)) or a.get(key) != b.get(key)]
    if a.get('execution_mode') == 'ROBORIO_OBSERVE':
        for key in ('hardware_model', 'firmware_versions'):
            if not a.get(key) or a.get(key) != b.get(key):
                mismatch.append(key)
        if not (a.get('hardware_identity_verified') is True and b.get('hardware_identity_verified') is True):
            mismatch.append('hardware_identity_verified')
    for key in ('artifact_hash',):
        if unavailable(a.get(key)) or unavailable(b.get(key)):
            mismatch.append(key + '_missing')
    if mismatch:
        return {'status': 'INVALID', 'reason': 'incomparable or missing target/runtime metadata', 'mismatched_fields': mismatch}
    def changes(old_metrics, new_metrics):
        result = {}
        for field in ('mean', 'median', 'p95', 'p99', 'maximum'):
            old, new = old_metrics.get(field), new_metrics.get(field)
            result[field] = {'baseline_ms': old, 'candidate_ms': new,
                             'change_ms': new - old if old is not None and new is not None else None,
                             'change_percent': 100 * (new - old) / old if old and new is not None else None}
        return result
    steady = changes(baseline.get('steady_state_loop', {}).get('execution_ms', {}),
                     candidate.get('steady_state_loop', {}).get('execution_ms', {}))
    phases = {phase: changes(baseline['phases'].get(phase, {}).get('execution_ms', {}),
                             candidate['phases'].get(phase, {}).get('execution_ms', {}))
              for phase in sorted(set(baseline['phases']) | set(candidate['phases']))}
    return {'status': 'PASS', 'execution': steady, 'execution_scope': 'steady-state only; startup/warm-up/transition samples remain separate',
            'steady_state_execution': steady,
            'all_captured_execution': changes(baseline['loop']['execution_ms'], candidate['loop']['execution_ms']),
            'phase_execution': phases,
            'artifact_differences': {key: {'baseline': a.get(key), 'candidate': b.get(key)}
                                     for key in ('git_sha', 'dirty_diff_hash', 'artifact_hash', 'performance_class_hash')
                                     if a.get(key) != b.get(key)}}


def wpilog_records(source, state, max_record_bytes=16 * 1024 * 1024):
    """Streaming WPILOG 1.x framing, based on pinned WPILib 2026.2.1 reader.

    Unlike DataLogReader.isValid(), validates every frame length through EOF.
    Unknown large payloads are skipped without allocating their declared size.
    """
    import struct
    entries = {}
    state.update(truncated=False, framing_complete=False, records=0)
    with Path(source).open('rb') as stream:
        header = stream.read(12)
        if len(header) != 12 or header[:6] != b'WPILOG':
            raise ValueError('Not a WPILOG header')
        version, extra = struct.unpack('<HI', header[6:])
        if version >> 8 != 1:
            raise ValueError(f'Unsupported WPILOG major version {version >> 8}')
        if extra > max_record_bytes or len(stream.read(extra)) != extra:
            raise ValueError('Invalid or truncated WPILOG extra header')
        def string(payload, offset):
            if offset + 4 > len(payload):
                raise ValueError('truncated control string length')
            size = int.from_bytes(payload[offset:offset+4], 'little')
            offset += 4
            if size > len(payload) - offset:
                raise ValueError('truncated control string')
            return payload[offset:offset+size].decode('utf-8'), offset + size
        while True:
            first = stream.read(1)
            if not first:
                state['framing_complete'] = True
                return
            byte = first[0]
            if byte & 0x80:
                state['truncated'] = True
                state['error'] = 'reserved record-header bit set'
                return
            e, s, t = (byte & 3) + 1, ((byte >> 2) & 3) + 1, ((byte >> 4) & 7) + 1
            rest = stream.read(e+s+t)
            if len(rest) != e+s+t:
                state['truncated'] = True
                return
            entry = int.from_bytes(rest[:e], 'little')
            size = int.from_bytes(rest[e:e+s], 'little')
            timestamp = int.from_bytes(rest[e+s:], 'little')
            if size > max_record_bytes:
                remaining = size
                while remaining:
                    chunk = stream.read(min(65536, remaining))
                    if not chunk:
                        state['truncated'] = True
                        return
                    remaining -= len(chunk)
                state['skipped_large_records'] = state.get('skipped_large_records', 0) + 1
                continue
            payload = stream.read(size)
            if len(payload) != size:
                state['truncated'] = True
                return
            state['records'] += 1
            try:
                if entry == 0:
                    if len(payload) < 5:
                        raise ValueError('short control record')
                    target = int.from_bytes(payload[1:5], 'little')
                    if payload[0] == 0:
                        name, offset = string(payload, 5)
                        datatype, offset = string(payload, offset)
                        _, offset = string(payload, offset)
                        if offset != len(payload):
                            raise ValueError('invalid control record length')
                        if len(entries) >= 100000 and target not in entries:
                            raise ValueError('entry count exceeds host import bound')
                        entries[target] = name, datatype
                    elif payload[0] == 1:
                        entries.pop(target, None)
                    elif payload[0] != 2:
                        raise ValueError('unknown control record')
                    continue
                name, datatype = entries.get(entry, (None, None))
                if name is None:
                    state['unknown_entries'] = state.get('unknown_entries', 0) + 1
                    continue
                formats = {'double': ('<d',8), 'int64': ('<q',8), 'float': ('<f',4), 'boolean': ('<?',1)}
                if datatype in formats:
                    fmt, length = formats[datatype]
                    if len(payload) != length:
                        raise ValueError(f'invalid {datatype} record size')
                    value = struct.unpack(fmt, payload)[0]
                elif datatype == 'string':
                    value = payload.decode('utf-8')
                elif name.endswith('Swerve/Performance/ModuleAcquisitionMS') and datatype == 'double[]':
                    if len(payload) != 32:
                        raise ValueError('ModuleAcquisitionMS must contain four doubles')
                    value = list(struct.unpack('<dddd',payload))
                elif 'MatchPerformance/Resources/' in name and datatype in ('int64[]','boolean[]','string[]'):
                    if datatype == 'int64[]':
                        if len(payload) % 8:
                            raise ValueError('invalid integer array size')
                        value = list(struct.unpack('<' + 'q' * (len(payload)//8), payload))
                    elif datatype == 'boolean[]':
                        value = [bool(v) for v in payload]
                    else:
                        if len(payload) < 4:
                            raise ValueError('short string array')
                        count = int.from_bytes(payload[:4], 'little')
                        if count > len(payload)//4:
                            raise ValueError('invalid string array count')
                        value, offset = [], 4
                        for _ in range(count):
                            text, offset = string(payload, offset)
                            value.append(text)
                else:
                    continue
                yield timestamp, name, value
            except (ValueError, UnicodeError) as exc:
                state['truncated'] = True
                state['error'] = str(exc)
                return


def _import_roborio(source, metadata_path, directory, max_output_bytes, budget_roots):
    """Read a human-collected local log; performs no network access or robot action."""
    metadata = json.loads(Path(metadata_path).read_text()) if metadata_path is not None else {}
    source, directory = Path(source).resolve(), Path(directory)
    directory.mkdir(parents=True, exist_ok=True)
    if source.stat().st_size > 2 * 1024**3:
        raise ValueError('Source log exceeds the 2 GiB host import bound; select a finite human-collected session')
    if (directory / 'manifest.json').exists():
        raise ValueError('Import output already contains a manifest; choose a new directory')
    state, current, current_timestamp, touched = {}, {}, None, set()
    loop_rows, scope_rows, resources = [], [], []
    final, last_cycle = {}, None
    last_scope_cycle = object()
    phase_by_cycle = {}  # Current and previous completed cycle only, never a growing history.
    phase_inference = {'inferred_cycles':0,'unverified_cycles':0}
    def flush():
        nonlocal last_cycle, last_scope_cycle
        fields = {key.split('MatchPerformance/', 1)[1]: value for key, value in current.items() if 'MatchPerformance/' in key}
        for key in ('CaptureComplete', 'CompletedSamples', 'DroppedSamples', 'LoggerQueueFaultCycles', 'Aborted', 'PeriodUs'):
            if key in fields:
                final[key] = fields[key]
        current_cycle_id = fields.get('CurrentCycle')
        if current_cycle_id is not None and current_cycle_id not in phase_by_cycle:
            # DS inputs in this LogTable belong to CurrentCycle. PreviousCycle timings do not.
            phase_by_cycle[current_cycle_id] = driver_station_phase(current)
            while len(phase_by_cycle)>2:
                del phase_by_cycle[min(phase_by_cycle)]
        inferred_current_phase = fields.get('CurrentPhase','startup')
        if inferred_current_phase == 'HUMAN_OPERATED':
            inferred_current_phase = phase_by_cycle.get(current_cycle_id,{}).get('phase','UNVERIFIED_DRIVER_STATION')
        cycle = fields.get('PreviousCycle')
        if cycle is not None and cycle != last_cycle:
            names = {'cycle':'PreviousCycle','phase':'PreviousPhase','start_ns':'StartNanos','end_ns':'EndNanos',
                     'original_due_us':'OriginalDueUs','effective_due_us':'EffectiveDueUs','start_us':'StartUs',
                     'end_us':'EndUs','interval_ns':'IntervalNanos','skipped_releases':'SkippedReleases',
                     'execution_ms':'ExecutionMS','headroom_ms':'HeadroomMS','deadline_miss':'DeadlineMiss'}
            row = {out: fields.get(key) for out,key in names.items()}
            row['period_us'] = final.get('PeriodUs')
            if row['phase'] == 'HUMAN_OPERATED':
                phase = phase_by_cycle.get(cycle,{'phase':'UNVERIFIED_DRIVER_STATION','verified':False})
                row['phase'] = phase['phase']
                row['phase_verified'] = phase['verified']
                for key in ('Enabled','Autonomous','Test','EmergencyStop','DSAttached'):
                    row['ds_'+key.lower()] = phase.get(key)
                phase_inference['inferred_cycles' if phase['verified'] else 'unverified_cycles'] += 1
            if len(loop_rows) >= 200000:
                raise ValueError('Observer sample count exceeds the 200000-record host import bound')
            loop_rows.append(row)
            last_cycle = cycle
        logger_values = {}
        current_cycle = fields.get('CurrentCycle',current_timestamp)
        scope_names = list(current) if current_cycle != last_scope_cycle else []
        simulation_nanos = number(fields.get('SimulationNanos'))
        if scope_names and simulation_nanos is not None and simulation_nanos >= 0:
            scope_rows.append({'cycle':fields.get('CurrentCycle'),'phase':inferred_current_phase,
                               'scope':'Desktop MapleSim physics','elapsed_ms':simulation_nanos/1e6,
                               'inclusive':True,'timestamp_us':current_timestamp})
        last_scope_cycle = current_cycle
        for name in scope_names:
            value = current[name]
            logger_key = next((key for key in ('Logger/QueuedCycles','LoggedRobot/GCTimeMS','LoggedRobot/GCCounts') if name.endswith(key)),None)
            if logger_key:
                logger_values[{'Logger/QueuedCycles':'logger_queued_cycles','LoggedRobot/GCTimeMS':'gc_time_ms_delta','LoggedRobot/GCCounts':'gc_count_delta'}[logger_key]] = number(value)
            if name.endswith('Swerve/Performance/ModuleAcquisitionMS') and isinstance(value,list):
                for index, elapsed in enumerate(value):
                    scope_rows.append({'cycle':fields.get('CurrentCycle'),'phase':inferred_current_phase,
                        'scope':f'Swerve module {index} input acquisition','elapsed_ms':number(elapsed),'inclusive':True,'timestamp_us':current_timestamp})
                continue
            # Keys carry explicit MS suffix in the pinned AKit and current robot schema.
            if ('Performance/' in name or 'LoggedRobot/' in name or 'Logger/' in name) and name.endswith('MS') and 'MatchPerformance/' not in name:
                scope_rows.append({'cycle':fields.get('CurrentCycle'), 'phase':inferred_current_phase,
                                   'scope':name,'elapsed_ms':number(value),'inclusive':True,'timestamp_us':current_timestamp})
        if logger_values:
            resources.append(dict(logger_values,kind='robot_logger',timestamp_us=current_timestamp,cycle=fields.get('CurrentCycle')))
        resource = {key.split('Resources/',1)[1]: value for key,value in fields.items() if key.startswith('Resources/')}
        if resource and any('Resources/MonotonicNanos' in key for key in touched):
            mapping = {'MonotonicNanos':'monotonic_ns','ProcessCpuNanos':'process_cpu_ns','MainThreadCpuNanos':'current_thread_cpu_ns','HeapUsedBytes':'heap_used_bytes','HeapCommittedBytes':'heap_committed_bytes','HeapMaxBytes':'heap_max_bytes','Threads':'threads','AvailableProcessors':'available_processors','ThreadIds':'thread_ids','ThreadGroups':'thread_groups','ThreadCpuNanos':'thread_cpu_ns','ThreadCpuValueAvailable':'thread_cpu_available','SelectedThreadsTruncated':'selected_threads_truncated','GCCollections':'gc_collections','GCTimeMS':'gc_time_ms'}
            normalized = {out:resource.get(key) for key,out in mapping.items()}
            if resource.get('ProcessCpuAvailable') is not True:
                normalized['process_cpu_ns'] = None
            if resource.get('MainThreadCpuAvailable') is not True:
                normalized['current_thread_cpu_ns'] = None
            normalized['kind'] = 'robot_jvm'
            normalized['timestamp_us'] = current_timestamp
            resources.append(normalized)
            sensor_timestamp = number(fields.get('Drive/LastSampleTimestampSeconds'))
            resources.append({'kind':'robot_observation','monotonic_ns':resource.get('MonotonicNanos'),
                'timestamp_us':current_timestamp,'phase':inferred_current_phase,
                'queued_tasks':fields.get('HTTP/QueuedTasks'),'active_workers':fields.get('HTTP/ActiveWorkers'),
                'http_requests':fields.get('HTTP/Requests'),'odom_consumed':fields.get('Drive/ConsumedSamples'),
                'last_sensor_timestamp_s':sensor_timestamp,
                'sensor_age_s':current_timestamp/1e6-sensor_timestamp if sensor_timestamp is not None else None})
    # Keep full state for WPILOG changed-only values, but flush only fresh cycle/scope records.
    for timestamp, name, value in wpilog_records(source,state):
        if current_timestamp is not None and timestamp != current_timestamp:
            flush()
            touched.clear()
        current_timestamp = timestamp
        current[name] = value
        touched.add(name)
    if current:
        flush()
    def write(name, rows, fallback):
        columns = list(dict.fromkeys(key for row in rows for key in row)) or fallback
        bounded_write_csv(directory/name, columns,
                          ({key:json.dumps(value) if isinstance(value,(list,dict)) else value
                            for key,value in row.items()} for row in rows),
                          max_output_bytes, budget_roots)
    write('loop-samples.csv', loop_rows, ['cycle','phase','execution_ms'])
    write('subsystem-timings.csv', scope_rows, ['cycle','phase','scope','elapsed_ms'])
    write('resource-samples.csv', resources, ['timestamp_us','status'])
    thread_rows = []
    for row in resources:
        if isinstance(row.get('thread_ids'),list):
            ids,groups,cpus,available = (row.get(k) or [] for k in ('thread_ids','thread_groups','thread_cpu_ns','thread_cpu_available'))
            for thread_id,group,cpu,supported in zip(ids,groups,cpus,available):
                thread_rows.append({'monotonic_ns':row.get('monotonic_ns'),'thread_id':thread_id,'group':group,'cpu_ns':cpu if supported else None})
    write('thread-resource-samples.csv',thread_rows,['monotonic_ns','thread_id','group','cpu_ns'])
    write('http-nt-results.csv', [], ['kind','status'])
    write('failures-and-events.csv', [{'kind':'import','message': 'Original local WPILOG preserved; missing observer or human workload evidence remains unverified'}], ['kind','message'])
    digest = hashlib.sha256()
    with source.open('rb') as raw:
        for block in iter(lambda: raw.read(65536), b''):
            digest.update(block)
    manifest = dict(metadata or {})
    # AKit 26.0.2 Logger uses RealMetadata for live logging and ReplayMetadata for replay.
    metadata_values = {key.lstrip('/'):value for key,value in current.items()}
    logged_mode = metadata_values.get('ReplayMetadata/RuntimeMode', metadata_values.get('RealMetadata/RuntimeMode'))
    if logged_mode is not None:
        manifest['logged_runtime_mode'] = logged_mode
    manifest['driver_station_phase_inference'] = phase_inference
    for key, value in current.items():
        if 'MatchPerformance/' in key:
            field = key.split('MatchPerformance/',1)[1]
            mapped = {'RunId':'run_id','ArtifactSha256':'artifact_hash','JavaVersion':'java_version','Platform':'platform','OS':'os','OSVersion':'os_version','Architecture':'architecture','StartedUTC':'utc_timestamp','HALRuntimeType':'hal_runtime_type','HardwareIdentityAvailable':'hardware_identity_available','FPGAVersion':'fpga_version','FPGARevision':'fpga_revision'}.get(field)
            if mapped:
                manifest.setdefault(mapped,value)
    manifest.update(schema_version=1, execution_mode=('DESKTOP_MATCH_SIM' if manifest.get('platform') == 'DESKTOP_MATCH_SIM' else 'ROBORIO_OBSERVE'),
                    source_log=str(source), source_log_sha256=digest.hexdigest(), import_framing=state,
                    capture_complete=bool(final.get('CaptureComplete')) and state.get('framing_complete',False),
                    expected_loop_samples=final.get('CompletedSamples'),
                    dropped_records=final.get('DroppedSamples'), logger_queue_fault_cycles=final.get('LoggerQueueFaultCycles'),
                    truncated=state.get('truncated'), aborted=final.get('Aborted',False))
    manifest.setdefault('platform', 'ROBORIO_IDENTITY_UNVERIFIED')
    manifest.setdefault('run_id', f'import-{digest.hexdigest()[:12]}')
    manifest.setdefault('profile', 'HUMAN_SESSION')
    manifest.setdefault('pacing', 'real')
    (directory / 'manifest.json').write_text(json.dumps(manifest,indent=2,allow_nan=False)+'\n')
    return manifest


def resource_summary(jvm_rows, host_rows):
    """CPU deltas and sampled memory by producer; never combine host and JVM CPU."""
    groups = defaultdict(list)
    for row in jvm_rows:
        groups[row.get('kind') or 'robot_jvm'].append(row)
    for row in host_rows:
        groups['host_' + row.get('kind','unknown')].append(row)
    result = {}
    for kind, rows in groups.items():
        counters = {}
        for key in ('heap_used_bytes','heap_committed_bytes','heap_max_bytes','rss_bytes','threads','thread_count','file_descriptors','log_bytes','logger_queued_cycles','gc_count_delta','gc_time_ms_delta','gc_collections','gc_time_ms'):
            if any(key in row for row in rows):
                counters[key] = stats(row.get(key) for row in rows)
        cpu = []
        for before, after in zip(rows, rows[1:]):
            start = first_number(before, 'monotonic_ns', 'MonotonicNanos')
            end = first_number(after, 'monotonic_ns', 'MonotonicNanos')
            if start is None or end is None:
                a,b = first_number(before,'elapsed_s'), first_number(after,'elapsed_s')
                start,end = (a*1e9 if a is not None else None),(b*1e9 if b is not None else None)
            a,b = first_number(before,'process_cpu_ns','ProcessCpuNanos'),first_number(after,'process_cpu_ns','ProcessCpuNanos')
            capacity=first_number(after,'available_processors','AvailableProcessors')
            measured=cpu_percent(b-a if a is not None and b is not None else None,end-start if start is not None and end is not None else None,capacity)
            cpu.append(measured)
        counters['one_core_cpu_percent']=stats(r['one_core_percent'] for r in cpu)
        counters['machine_process_percent']=stats(r['machine_percent'] for r in cpu)
        counters['sample_count']=len(rows)
        # Sampled endpoints describe observations, not proof that a backlog is bounded.
        for key in ('heap_used_bytes','rss_bytes','threads','thread_count','file_descriptors','log_bytes','logger_queued_cycles','gc_count_delta','gc_time_ms_delta','gc_collections','gc_time_ms'):
            if rows and any(key in row for row in rows):
                counters[key+'_first']=number(rows[0].get(key))
                counters[key+'_last']=number(rows[-1].get(key))
        result[kind]=counters
    return result


def _enrich_from_wpilog(directory, max_output_bytes):
    """Add recorded AKit scopes to desktop CSV after capture, without replacing loop data."""
    import tempfile
    directory = Path(directory)
    sources = sorted((directory / 'logs').glob('akit*.wpilog'))
    if not sources:
        return {'status':'NOT RUN','reason':'No AKit source log'}
    if len(sources) != 1:
        return {'status':'INVALID','reason':'Select one run log; automatic multi-log joining is unsupported'}
    with preserving_extraction_directory() as temporary:
        imported = Path(temporary)
        meta = import_roborio(sources[0], None, imported, max_output_bytes, budget_roots=(directory,imported))
        rows = read_csv(directory / 'subsystem-timings.csv')
        original_loops = read_csv(directory/'loop-samples.csv')
        scenario_file = directory/'scenario.json'
        observation_on = json.loads(scenario_file.read_text()).get('recording',True) if scenario_file.exists() else True
        from bisect import bisect_right
        starts = [number(row.get('start_us')) or 0 for row in original_loops]
        existing = {(r.get('cycle'),r.get('scope')) for r in rows}
        added = 0
        for row in read_csv(imported / 'subsystem-timings.csv'):
            if not observation_on:
                stamp = number(row.get('timestamp_us'))
                index = bisect_right(starts,stamp)-1 if stamp is not None else -1
                if index < 0 or stamp > (number(original_loops[index].get('end_us')) or -1):
                    continue  # Startup/final logger flushes are not executed application cycles.
                row['cycle'] = original_loops[index].get('cycle')
                row['phase'] = original_loops[index].get('phase')
            identity = row.get('cycle'),row.get('scope')
            if identity not in existing:
                rows.append(row)
                existing.add(identity)
                added += 1
        columns = list(dict.fromkeys(k for row in rows for k in row))
        if columns and added:
            bounded_write_csv(directory/'subsystem-timings.csv', columns, rows, max_output_bytes, (directory,imported))
        canonical_loops = read_csv(directory/'loop-samples.csv')
        imported_loops = read_csv(imported/'loop-samples.csv')
        canonical_by_cycle = {r.get('cycle'):r for r in canonical_loops}
        mismatches = 0
        for row in imported_loops:
            original = canonical_by_cycle.get(row.get('cycle'),{})
            for key in ('start_ns','end_ns','original_due_us','start_us','end_us','period_us'):
                if number(row.get(key)) != number(original.get(key)):
                    mismatches += 1
                    break
        exact_loops = len(canonical_loops)==len(imported_loops) and mismatches==0
        existing_resources = read_csv(directory/'resource-samples.csv')
        # Keep the runner JVM samples; add the logger's actual loop-rate counters separately.
        existing_keys = {(r.get('kind'),r.get('timestamp_us')) for r in existing_resources}
        added_resources = 0
        for row in read_csv(imported/'resource-samples.csv'):
            if not observation_on:
                stamp = number(row.get('timestamp_us'))
                index = bisect_right(starts,stamp)-1 if stamp is not None else -1
                if index < 0 or stamp > (number(original_loops[index].get('end_us')) or -1):
                    continue
                row['cycle'] = original_loops[index].get('cycle')
                row['phase'] = original_loops[index].get('phase')
            if row.get('kind') in ('robot_logger','robot_observation') and (row.get('kind'),row.get('timestamp_us')) not in existing_keys:
                existing_resources.append(row)
                existing_keys.add((row.get('kind'),row.get('timestamp_us')))
                added_resources += 1
        resource_columns = list(dict.fromkeys(k for row in existing_resources for k in row))
        if resource_columns and added_resources:
            bounded_write_csv(directory/'resource-samples.csv', resource_columns, existing_resources, max_output_bytes, (directory,imported))
        result={'status':'PASS' if not meta['truncated'] and ((meta['capture_complete'] and exact_loops) or not observation_on) else 'INVALID',
                'outer_loop_log_comparison':'PASS' if exact_loops else 'NOT RUN: runtime observation explicitly off' if not observation_on else 'FAIL',
                'loop_csv_matches_wpilog':exact_loops,'loop_field_mismatches':mismatches,
                'csv_loops':len(canonical_loops),'wpilog_loops':len(imported_loops),
                'added_scope_samples':added,'added_resource_samples':added_resources,'source_log_sha256':meta['source_log_sha256'],
                'source_log':str(sources[0]),'completed_samples':meta['expected_loop_samples'],
                'dropped_samples':meta['dropped_records'],'framing':meta['import_framing']}
        return result


def verify_recovery(manifest, resource_rows, traffic_rows, traffic):
    """Containment evidence after an explicitly recorded overload-removal boundary."""
    alignment = clock_domain_guard(manifest)
    if alignment['status'] != 'VERIFIED':
        return {'status':'NOT MEASURED','reason':'Cross-process recovery timestamps lack verified alignment','clock_alignment':alignment}
    removal = number(manifest.get('recovery_start_s'))
    http = traffic.get('HTTP', {})
    origin = number(http.get('offered_start_monotonic_ns'))
    if removal is None or origin is None:
        return {'status':'NOT MEASURED','reason':'Missing explicit recovery boundary/load monotonic origin'}
    boundary = origin + removal*1e9
    after = [r for r in resource_rows if r.get('kind')=='robot_observation' and (number(r.get('monotonic_ns')) or -1)>boundary]
    successes = [r for r in traffic_rows if r.get('kind','').upper()=='HTTP' and str(r.get('status'))=='200' and (number(r.get('scheduled_ns')) or -1)>boundary]
    nt = []
    for row in traffic_rows:
        if row.get('kind','').upper()=='NT' and row.get('status')=='SUMMARY':
            detail=dict(part.split('=',1) for part in row.get('detail','').split(';') if '=' in part)
            if (number(row.get('end_ns')) or -1)>boundary:
                value=number(detail.get('telemetryReceived',detail.get('telemetry')))
                if value is not None:nt.append(value)
    period_s=(number(manifest.get('period_ns')) or 0)/1e9
    odometry_hz=number(manifest.get('odometry_hz'))
    freshness=period_s+1/odometry_hz if period_s>0 and odometry_hz and odometry_hz>0 else None
    last=after[-1] if after else {}
    age=number(last.get('sensor_age_s'))
    obligations={'postload_http_200':bool(successes),
                 'postload_nt_telemetry_increased':len(nt)>1 and nt[-1]>nt[0],
                 'queue_drained':number(last.get('queued_tasks'))==0,
                 'odometry_consumed_increased':len(after)>1 and number(last.get('odom_consumed')) is not None and number(after[0].get('odom_consumed')) is not None and float(last['odom_consumed'])>float(after[0]['odom_consumed']),
                 'last_sensor_fresh':freshness is not None and age is not None and 0<=age<=freshness}
    return {'status':'PASS' if all(obligations.values()) else 'FAIL','obligations':obligations,
            'freshness_limit_s':freshness,'last_sensor_age_s':age,'postload_http_successes':len(successes),
            'postload_resource_samples':len(after),'last_queued_tasks':number(last.get('queued_tasks')),
            'memory_recovery':'NOT INFERRED; inspect separate sampled memory endpoints and trend'}


def thread_summary(rows):
    histories=defaultdict(list)
    for row in rows:
        histories[(row.get('thread_id'),row.get('group'))].append(row)
    output=[]
    for (thread_id,group), samples in histories.items():
        values=[]
        for before,after in zip(samples,samples[1:]):
            a,b=number(before.get('cpu_ns')),number(after.get('cpu_ns'))
            start,end=number(before.get('monotonic_ns')),number(after.get('monotonic_ns'))
            values.append(cpu_percent(b-a if a is not None and b is not None else None,
                                      end-start if start is not None and end is not None else None)['one_core_percent'])
        output.append({'thread_id':thread_id,'group':group,'one_core_cpu_percent':stats(values),
                       'scope':'same thread ID only; native/unselected threads excluded'})
    return output


def compare_overhead(baselines, candidates):
    """Explicit instrumentation experiment; all other scenario knobs must match."""
    if isinstance(baselines,(str,Path)):baselines=[baselines]
    if isinstance(candidates,(str,Path)):candidates=[candidates]
    if len(baselines)!=len(candidates) or not baselines:
        return {'status':'INVALID','reason':'Need equal nonempty paired runs'}
    pairs=[]
    for before_path,after_path in zip(baselines,candidates):
        before_path,after_path=Path(before_path),Path(after_path)
        before,after=analyze_run(before_path),analyze_run(after_path)
        def scenario(path):
            value=json.loads((path/'scenario.json').read_text())
            return {k:v for k,v in value.items() if k not in ('detailed','recording','runId','ntPort')}
        if scenario(before_path)!=scenario(after_path):
            return {'status':'INVALID','reason':'A scenario setting other than detailed/recording instrumentation differs'}
        a,b=before['manifest'],after['manifest']
        if a.get('instrumentation')==b.get('instrumentation'):
            return {'status':'INVALID','reason':'No instrumentation difference recorded'}
        for key in ('artifact_hash','performance_class_hash'):
            if not a.get(key) or a.get(key)!=b.get(key):
                return {'status':'INVALID','reason':'Artifact identity differs'}
        # Hash difference is explained only by verified scenario.json detailed toggle.
        before=dict(before,manifest=dict(a,scenario_hash='verified-overhead-scenario',instrumentation='paired'))
        after=dict(after,manifest=dict(b,scenario_hash='verified-overhead-scenario',instrumentation='paired'))
        comparison=compare_runs(before,after)
        if comparison['status']!='PASS':return comparison
        pairs.append(dict(comparison,baseline=str(before_path),candidate=str(after_path),
                          baseline_instrumentation=a.get('instrumentation'),candidate_instrumentation=b.get('instrumentation'),
                          baseline_recording=a.get('observation_enabled'),candidate_recording=b.get('observation_enabled')))
    return {'status':'PASS','kind':'instrumentation overhead experiment','paired_repetitions':len(pairs),
            'pairs':pairs,'paired_median_execution_change_ms':stats(p['execution']['median']['change_ms'] for p in pairs),
            'limitation':'Both variants retain outer timing, desktop frame writer, and workload witness probes. Difference measures additional runtime observation/resource telemetry, not absolute zero-probe overhead, and includes run variability. Not a roboRIO optimization result'}


def spike_correlations(loop_rows, traffic_rows, resource_rows, period_ns, warning_margin=.8, clock_alignment=None):
    """Bounded descriptive overlap, not causation or a CPU attribution model."""
    period=number(period_ns)
    if not period or period<=0:
        return {'status':'NOT MEASURED','reason':'No verified loop period'}
    if not 0 < warning_margin <= 1:
        raise ValueError('warning_margin must be in (0,1]')
    spikes=[]
    for row in loop_rows:
        value=first_number(row,'execution_ms')
        if value is None:
            value=first_number(row,'execution_ns')
            value=value/1e6 if value is not None else None
        if value is not None and (value*1e6>=warning_margin*period or str(row.get('deadline_miss')).lower()=='true'):
            spikes.append((value,row))
    result=[]
    for value,row in sorted(spikes,key=lambda item:-item[0])[:20]:
        start,end=first_number(row,'start_ns'),first_number(row,'end_ns')
        start_us,end_us=first_number(row,'start_us'),first_number(row,'end_us')
        overlap=[]
        aligned = clock_alignment is not None and clock_alignment.get('status') == 'VERIFIED'
        for request in traffic_rows:
            if not aligned or request.get('kind','').upper()!='HTTP':continue
            a,b=first_number(request,'start_ns'),first_number(request,'end_ns')
            if start is not None and end is not None and a is not None and b is not None and a<=end and b>=start:
                overlap.append(request)
        logger=[]
        if start_us is not None and end_us is not None:
            logger=[r for r in resource_rows if r.get('kind')=='robot_logger' and number(r.get('timestamp_us')) is not None and start_us-period/1000<=float(r['timestamp_us'])<=end_us]
        gc=[v for r in logger if (v:=number(r.get('gc_time_ms_delta'))) is not None]
        queues=[v for r in logger if (v:=number(r.get('logger_queued_cycles'))) is not None]
        result.append({'cycle':row.get('cycle'),'phase':row.get('phase'),'execution_ms':value,
            'overlapping_http_requests':len(overlap) if aligned else None,
            'overlapping_http_body_bytes':sum(number(r.get('bytes')) or 0 for r in overlap) if aligned else None,
            'nearby_gc_time_ms':sum(gc) if gc else None,'nearby_logger_queue_max':max(queues) if queues else None})
    return {'status':'MEASURED','http_clock_alignment':clock_alignment or {'status':'UNVERIFIED'},'warning_margin':warning_margin,'maximum_reported_spikes':20,'spikes':result,
            'interpretation':'HTTP request overlap is available only with verified shared monotonic clock origin; unavailable overlap is null. GC/queue samples use the same recorded FPGA timeline within one period. Association does not prove causation; startup and warm-up are labelled.'}


def resource_capture_coverage(loop_rows, resource_rows, observation_enabled=True, expected_host_count=None):
    """Reconstruct the actual non-catch-up resource scheduler from loop starts.

    ObservedRobot samples on the first eligible loop, then sets nextResource to
    that loop's startNanos + 1 second. Quantization/slippage is measured, not
    misclassified as a lost record against a hypothetical fixed 1 Hz grid.
    """
    if not observation_enabled:
        times = [v for row in resource_rows if (v := number(row.get('monotonic_ns'))) is not None]
        valid_cpu = [row for row in resource_rows if (v := number(row.get('process_cpu_ns'))) is not None and v >= 0]
        duration = (times[-1]-times[0])/1e9 if len(times)>1 else None
        invalid_times = len(resource_rows)-len(times)
        unordered = sum(b<=a for a,b in zip(times,times[1:]))
        expected = number(expected_host_count)
        missing = max(0,int(expected)-len(resource_rows)) if expected is not None else None
        extra = max(0,len(resource_rows)-int(expected)) if expected is not None else None
        passed = (expected is not None and expected>=2 and len(resource_rows)==expected
                  and not invalid_times and not unordered and len(valid_cpu)>=2)
        return {'status': 'PASS' if passed else 'FAIL',
                'scheduler': 'independent host polling; count bound to manifest successful row-write/flush counter',
                'expected_samples': expected, 'observed_samples': len(resource_rows),
                'missing_samples': missing, 'unexpected_samples': extra,
                'invalid_timestamp_samples': invalid_times, 'nonincreasing_timestamps':unordered,
                'numeric_cpu_observations':len(valid_cpu),
                'achieved_hz': (len(times)-1)/duration if duration and duration>0 else None,
                'nominal_slippage_ms': None}
    expected = []
    due = None
    invalid_loops = 0
    slippage = []
    for row in loop_rows:
        start, end = first_number(row, 'start_ns'), first_number(row, 'end_ns')
        if start is None or end is None or end < start:
            invalid_loops += 1
            continue
        if due is None or start >= due:
            if due is not None:
                slippage.append((start-due)/1e6)
            expected.append((start,end))
            due = start + 1_000_000_000
    from bisect import bisect_left, bisect_right
    stamps = sorted(v for row in resource_rows if (v := first_number(row, 'monotonic_ns')) is not None)
    missing = matched = duplicates = 0
    for start,end in expected:
        count = bisect_right(stamps,end)-bisect_left(stamps,start)
        if count == 0:
            missing += 1
        else:
            matched += count
            duplicates += count-1
    unexpected = len(stamps)-matched+duplicates
    invalid_stamps = len(resource_rows)-len(stamps)
    duration = (stamps[-1]-stamps[0])/1e9 if len(stamps)>1 else None
    return {'status': 'PASS' if expected and not (missing or unexpected or invalid_stamps or invalid_loops) else 'FAIL',
            'scheduler': 'first eligible loop, next due = sampled loop start + 1 second; no catch-up',
            'expected_samples': len(expected), 'observed_samples': len(resource_rows),
            'missing_samples': missing, 'unexpected_samples': unexpected,
            'invalid_timestamp_samples': invalid_stamps, 'invalid_loop_intervals': invalid_loops,
            'achieved_hz': (len(stamps)-1)/duration if duration and duration>0 else None,
            'nominal_slippage_ms': stats(slippage),
            'cumulative_nominal_slippage_ms': sum(slippage)}


def clock_domain_guard(manifest):
    """Fail closed outside the exact source-verified local runtime combination."""
    import re
    declared=manifest.get('cross_process_clock_alignment',{})
    reasons=[]
    if declared.get('verified') is not True or declared.get('basis')!='source_verified_same_host_mach_absolute_time':
        reasons.append('no explicit source-verified shared-clock declaration')
    if manifest.get('execution_mode')!='DESKTOP_MATCH_SIM' or manifest.get('load_config',manifest.get('load_configuration',{})).get('offhost') is not False:
        reasons.append('traffic and robot are not established as local desktop processes')
    if not manifest.get('host_id'):
        reasons.append('missing host fingerprint')
    system=str(manifest.get('os',''))
    if not (system.startswith('macOS-26.5-') or (system=='Mac OS X' and manifest.get('os_version')=='26.5')):
        reasons.append('OS is outside verified macOS26.5 platform')
    if manifest.get('architecture') not in ('arm64','aarch64'):
        reasons.append('architecture is outside verified arm64 platform')
    if not re.search(r'(?<![0-9])17\.0\.18\+8(?![0-9])',str(manifest.get('java_runtime',''))):
        reasons.append('Java runtime is outside verified17.0.18+8 build')
    if not str(manifest.get('python','')).startswith('3.11.9 ') or manifest.get('python_implementation')!='CPython':
        reasons.append('Python runtime is outside verified CPython3.11.9 build')
    return {'status':'UNVERIFIED' if reasons else 'VERIFIED','reasons':reasons,
            'scope':'same-host HTTP/Python monotonic_ns and Java nanoTime overlap only; no cross-host or wall-clock alignment claim'}


def driver_station_phase(log_values):
    """Decode pinned AKit26.0.2 LoggedDriverStation input keys, not dashboard topics."""
    values={key.lstrip('/'):value for key,value in log_values.items()}
    names=('Enabled','Autonomous','Test','EmergencyStop','DSAttached')
    flags={key:values.get('DriverStation/'+key) for key in names}
    if not all(isinstance(value,bool) for value in flags.values()):
        return dict(flags,phase='UNVERIFIED_DRIVER_STATION',verified=False)
    if flags['EmergencyStop']:
        phase='emergency_stop'
    elif not flags['DSAttached']:
        phase='ds_disconnected'
    elif not flags['Enabled']:
        phase='disabled'
    elif flags['Autonomous']:
        phase='autonomous'
    elif flags['Test']:
        phase='test'
    else:
        phase='teleop'
    return dict(flags,phase=phase,verified=True)


DEFAULT_OUTPUT_BUDGET = 128 * 1024 * 1024


class OutputBudgetExceeded(RuntimeError):
    """Host output bound reached; original files and partial evidence are retained."""
    def __init__(self, partial_file, limit):
        self.partial_file = str(partial_file)
        self.limit = limit
        super().__init__(f'BLOCKED: CSV output budget of {limit} bytes exceeded; partial evidence: {partial_file}')


def csv_storage_bytes(roots):
    paths=set()
    for root in roots:
        root=Path(root)
        paths.update(path.resolve() for path in root.glob('*.csv') if path.is_file())
        paths.update(path.resolve() for path in root.glob('*.csv.partial') if path.is_file())
    return sum(path.stat().st_size for path in paths)


def bounded_write_csv(path, columns, rows, max_output_bytes=DEFAULT_OUTPUT_BUDGET, budget_roots=None):
    """Atomic replacement within a combined existing + temporary CSV byte budget."""
    import io
    import os
    import tempfile
    path=Path(path)
    path.parent.mkdir(parents=True,exist_ok=True)
    if not isinstance(max_output_bytes,int) or max_output_bytes<=0:
        raise ValueError('max_output_bytes must be a positive integer')
    roots=tuple({Path(root).resolve() for root in (budget_roots or ())} | {path.parent.resolve()})
    occupied=csv_storage_bytes(roots)
    temporary=tempfile.NamedTemporaryFile(mode='wb',prefix=path.stem+'-',suffix='.csv.partial',dir=path.parent,delete=False)
    partial=Path(temporary.name)
    written=0
    try:
        with temporary:
            for row in csv_header_and_rows(columns,rows):
                buffer=io.StringIO(newline='')
                writer=csv.DictWriter(buffer,columns)
                writer.writerow(row)
                encoded=buffer.getvalue().encode('utf-8')
                if occupied+written+len(encoded)>max_output_bytes:
                    state={'status':'BLOCKED','max_output_bytes':max_output_bytes,
                           'existing_csv_bytes':occupied,'partial_csv_bytes':written,'partial_file':str(partial)}
                    (path.parent/'output-budget-status.json').write_text(json.dumps(state,indent=2)+'\n')
                    raise OutputBudgetExceeded(partial,max_output_bytes)
                temporary.write(encoded)
                written+=len(encoded)
            temporary.flush()
        os.replace(partial,path)
    except BaseException:
        # Never destroy the old target or remove the owned partial capture on failure.
        raise
    return written


def csv_header_and_rows(columns, rows):
    yield dict(zip(columns,columns))
    yield from rows


from contextlib import contextmanager


@contextmanager
def preserving_extraction_directory():
    import tempfile
    import shutil
    directory=Path(tempfile.mkdtemp(prefix='season2027-log-extract-'))
    try:
        yield directory
    except BaseException:
        # Budget/parse failures keep the finite partial artifact for diagnosis.
        raise
    else:
        shutil.rmtree(directory)


def import_roborio(source, metadata_path, directory, max_output_bytes=DEFAULT_OUTPUT_BUDGET, *, budget_roots=None):
    directory=Path(directory)
    roots=tuple(budget_roots or (directory,))
    try:
        return _import_roborio(source,metadata_path,directory,max_output_bytes,roots)
    except OutputBudgetExceeded as failure:
        directory.mkdir(parents=True,exist_ok=True)
        manifest={'schema_version':1,'run_id':'blocked-import','completion':'BLOCKED','aborted':True,
                  'capture_complete':False,'source_log':str(Path(source).resolve()),
                  'source_log_sha256':None,'source_hash_status':'NOT COMPUTED: output budget blocked import',
                  'partial_file':failure.partial_file,'max_output_bytes':max_output_bytes}
        (directory/'manifest.json').write_text(json.dumps(manifest,indent=2)+'\n')
        raise


def enrich_from_wpilog(directory, max_output_bytes=DEFAULT_OUTPUT_BUDGET):
    """Persist every extraction outcome, including missing/ambiguous sources and errors."""
    directory=Path(directory)
    directory.mkdir(parents=True,exist_ok=True)
    (directory/'log-extraction.json').write_text(json.dumps({'status':'RUNNING','max_output_bytes':max_output_bytes})+'\n')
    try:
        result=_enrich_from_wpilog(directory,max_output_bytes)
    except BaseException as failure:
        status = 'BLOCKED' if isinstance(failure,OutputBudgetExceeded) else 'ABORTED' if isinstance(failure,(KeyboardInterrupt,SystemExit)) else 'INVALID'
        result={'status':status,
                'reason':str(failure),'max_output_bytes':max_output_bytes}
        if isinstance(failure,OutputBudgetExceeded):
            result['partial_file']=failure.partial_file
        (directory/'log-extraction.json').write_text(json.dumps(result,indent=2)+'\n')
        raise
    (directory/'log-extraction.json').write_text(json.dumps(result,indent=2)+'\n')
    return result


def verify_import_source(manifest):
    """Verify the original local log for imports that intentionally do not copy it."""
    if 'source_log' not in manifest and 'import_framing' not in manifest:
        return {'status':'NOT RUN','reason':'Live dual capture, not a source-log import'}
    source_name=manifest.get('source_log')
    expected=manifest.get('source_log_sha256')
    framing=manifest.get('import_framing',{})
    if not isinstance(source_name,str) or not isinstance(expected,str) or len(expected)!=64:
        return {'status':'FAIL','reason':'Missing original source path or SHA-256'}
    source=Path(source_name)
    if not source.is_file():
        return {'status':'FAIL','reason':'Original source log is missing'}
    if source.stat().st_size>2*1024**3:
        return {'status':'FAIL','reason':'Original source exceeds the 2 GiB import bound'}
    if not isinstance(framing,dict) or framing.get('framing_complete') is not True or framing.get('truncated'):
        return {'status':'FAIL','reason':'Source framing was not completely verified'}
    digest=hashlib.sha256()
    try:
        with source.open('rb') as raw:
            for block in iter(lambda:raw.read(65536),b''):
                digest.update(block)
    except OSError as failure:
        return {'status':'FAIL','reason':'Cannot read original source: '+str(failure)}
    if digest.hexdigest()!=expected:
        return {'status':'FAIL','reason':'Original source SHA-256 changed'}
    return {'status':'PASS','source_sha256':expected,
            'scope':'Original file exists and matches the fully parsed source; capture marker, sequence and count checks remain separate'}
