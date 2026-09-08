"""Portable offline reports; no report processing runs in the robot loop."""
from __future__ import annotations
import html
import json
from pathlib import Path
from match_analysis import analyze_run, read_csv, compare_runs


def display(value):
    if value is None:
        return 'UNAVAILABLE'
    if isinstance(value, float):
        return f'{value:.4f}'
    if isinstance(value, (dict, list)):
        return json.dumps(value, sort_keys=True)
    return str(value)


def table(headers, rows):
    return '<table><thead><tr>' + ''.join(f'<th>{html.escape(str(x))}</th>' for x in headers) + '</tr></thead><tbody>' + ''.join('<tr>' + ''.join(f'<td>{html.escape(display(x))}</td>' for x in row) + '</tr>' for row in rows) + '</tbody></table>'


def report(directory, comparison=None):
    directory = Path(directory)
    summary = analyze_run(directory)
    (directory / 'run-summary.json').write_text(json.dumps(summary, indent=2, allow_nan=False) + '\n')
    metrics = summary['loop']['execution_ms']
    statuses = [(('Steady-state performance' if key=='performance' else key.replace('_', ' ').title()), value.get('status') if isinstance(value, dict) else value)
                for key, value in summary.items() if key in ('workload_validity', 'measurement_completeness', 'functional_correctness', 'performance', 'hardware_validation')]
    phase_rows = [(name, item['execution_ms']['sample_count'], item['execution_ms']['median'], item['execution_ms']['p95'], item['execution_ms']['p99'], item['execution_ms']['maximum'], item['deadline_misses'], item['headroom_ms']['minimum']) for name, item in summary['phases'].items()]
    scope_rows = [(name, item['sample_count'], item['median'], item['p95'], item['p99'], item['maximum']) for name, item in sorted(summary['subsystems_ms'].items(), key=lambda x: -(x[1]['p99'] or 0))]
    traffic_rows = [(name, item.get('scheduled'), item.get('completed'), item.get('achieved_per_s'), item.get('bytes'), item.get('failed'), item.get('dropped'), item.get('status')) for name, item in summary['traffic'].items()]
    failure_rows = [('completeness', x) for x in summary['measurement_completeness']['reasons']] + [('validity', x) for x in summary['workload_validity']['reasons']]
    failure_rows += [(r.get('kind', r.get('event', 'event')), r.get('message', json.dumps(r))) for r in read_csv(directory / 'failures-and-events.csv')]
    comparison = comparison or {'status': 'NOT RUN', 'reason': 'No compatible baseline supplied'}
    title = f"{summary['manifest'].get('run_kind', 'IMPORTED')} / {summary.get('profile')} — {summary.get('platform')} — {summary.get('run_id')}"
    phase_headers = ['Phase', 'Samples', 'Median (ms)', 'p95 (ms)', 'p99 (ms)', 'Maximum (ms)', 'Deadline misses', 'Minimum headroom (ms)']
    scope_headers = ['Scope (inclusive unless declared)', 'Samples', 'Median (ms)', 'p95 (ms)', 'p99 (ms)', 'Maximum (ms)']
    traffic_headers = ['Kind', 'Offered', 'Completed', 'Achieved / s', 'Body/payload bytes', 'Failures', 'Local drops', 'Status']
    # A bounded SVG plots percentile summaries, not an invented timing trace.
    widths = sorted([(name, item['p99']) for name, item in summary['subsystems_ms'].items() if item['p99'] is not None],key=lambda pair:-pair[1])[:12]
    maximum = max((v for _, v in widths), default=1) or 1
    bars = ''.join(f'<text x="0" y="{i*32+20}">{html.escape(name.removeprefix("/RealOutputs/")[:44])}</text><rect x="310" y="{i*32+4}" width="{v/maximum*350:.2f}" height="20" fill="#26768a"/><text x="670" y="{i*32+20}">{v:.3f} ms</text>' for i, (name,v) in enumerate(widths))
    overview = [('Steady-state execution p99 (ms)',summary['steady_state_loop']['execution_ms']['p99']),
                ('Minimum steady-state headroom (ms)',summary['steady_state_loop']['headroom_ms']['minimum']),
                ('Slowest measured p99 scope',widths[0][0] if widths else None),
                ('HTTP achieved requests/s',summary['traffic'].get('HTTP',{}).get('achieved_per_s')),
                ('NT achieved publish calls/s',summary['traffic'].get('NT',{}).get('achieved_per_s')),
                ('Recovery verification',summary['recovery'].get('status'))]
    document = f'''<!doctype html><html lang="en"><meta charset="utf-8"><meta name="viewport" content="width=device-width"><title>{html.escape(title)}</title><style>body{{font:15px system-ui;margin:32px auto;max-width:1200px;padding:0 24px;color:#193442}}h1{{font-size:26px}}h2{{margin-top:32px}}table{{border-collapse:collapse;width:100%;overflow-wrap:anywhere}}td,th{{padding:9px;text-align:left;border-bottom:1px solid #dbe4e8}}th{{background:#193442;color:white}}tr:nth-child(even){{background:#f2f6f8}}.note{{background:#eef4f7;padding:16px}}svg{{max-width:100%;font-size:12px}}a{{color:#176479}}</style><h1>{html.escape(title)}</h1><p class="note">Execution platform is recorded above. Desktop results do not establish roboRIO performance. Quantiles use nearest rank. Inclusive subsystem timings must not be summed with their scheduler parent. Unknown metrics are unavailable, never zero.</p>{table(['Check','Result'],statuses)}{table(['Measured answer','Value'],overview)}<h2>Loop deadline and margin</h2>{table(phase_headers,phase_rows)}<p>Longest miss streak: {summary['loop']['longest_miss_streak']}; skipped releases: {summary['loop']['skipped_releases']}. Warm-up and startup remain separate phases. Executed-cycle misses: {summary['loop']['executed_cycle_deadline_misses']}; total missed release slots including additional expired slots at scheduler rebase: {summary['loop']['total_missed_release_slots']} / {summary['loop']['observed_release_slots']}. This inference removes double counting of the original due slot; it does not assume a fixed global schedule.</p><h2>Slowest measured sections</h2><svg viewBox="0 0 800 {max(40,len(widths)*32)}" role="img" aria-label="Subsystem p99 elapsed milliseconds">{bars}</svg>{table(scope_headers,scope_rows)}<h2>Actual traffic</h2>{table(traffic_headers,traffic_rows)}<p>Burst correlation and backlog recovery require aligned event and resource evidence; no causal attribution is inferred from these summaries alone. Recovery: {html.escape(display(summary['manifest'].get('recovery_verified')))}.</p><h2>Observed spike overlap</h2><p>HTTP clock alignment: {html.escape(display(summary['spike_correlations'].get('http_clock_alignment')))}</p>{table(['Cycle','Phase','Execution (ms)','Overlapping HTTP requests','HTTP body bytes','Nearby GC time (ms)','Logger queue maximum'],[(r['cycle'],r['phase'],r['execution_ms'],r['overlapping_http_requests'],r['overlapping_http_body_bytes'],r['nearby_gc_time_ms'],r['nearby_logger_queue_max']) for r in summary['spike_correlations'].get('spikes',[])])}<p>Temporal overlap is observational evidence, not proof of causation. The largest 20 execution spikes above the configured engineering margin (default 80%) or with a deadline miss are shown.</p><h2>CPU and memory observations</h2>{table(['Producer','Metric (unit in name)','Samples','Mean','Median','p95','p99','Sampled maximum','First','Last'],resource_rows(summary))}<p>Memory values are sampled, not proven peaks. CPU percentages use CPU-time delta divided by wall-time delta; machine fraction additionally divides by measured CPU capacity.</p><h2>Selected thread CPU</h2>{table(['Thread ID','Group','Mean one-core CPU %','p99 one-core CPU %'],[(r['thread_id'],r['group'],r['one_core_cpu_percent']['mean'],r['one_core_cpu_percent']['p99']) for r in summary['selected_threads']])}<h2>Failures and missing evidence</h2>{table(['Category','Detail'],failure_rows)}<h2>Baseline comparison</h2><pre>{html.escape(json.dumps(comparison,indent=2))}</pre><h2>Raw evidence</h2><p>{' · '.join(f'<a href="{name}">{name}</a>' for name in ['manifest.json','run-summary.json','loop-samples.csv','subsystem-timings.csv','resource-samples.csv','http-nt-results.csv','failures-and-events.csv','match-performance.xlsx'])}</p></html>'''
    (directory / 'match-performance.html').write_text(document)
    write_workbook(directory, summary, statuses, phase_headers, phase_rows, scope_headers, scope_rows, traffic_headers, traffic_rows, failure_rows, comparison)
    print(f"{title}: validity={summary['workload_validity']['status']} completeness={summary['measurement_completeness']['status']} correctness={summary['functional_correctness']} performance={summary['performance']} median={display(metrics['median'])} ms p99={display(metrics['p99'])} ms maximum={display(metrics['maximum'])} ms misses={summary['loop']['deadline_misses']}")
    return summary


def write_workbook(directory, summary, statuses, phase_headers, phase_rows, scope_headers, scope_rows, traffic_headers, traffic_rows, failures, comparison):
    try:
        import xlsxwriter
    except ImportError as exc:
        raise RuntimeError('Install the approved host requirements-match.txt in a reporting virtual environment') from exc
    with xlsxwriter.Workbook(directory / 'match-performance.xlsx', {'constant_memory': False, 'strings_to_formulas': False, 'strings_to_urls': False}) as book:
        header = book.add_format({'bold': True, 'bg_color': '#193442', 'font_color': '#FFFFFF', 'text_wrap': True})
        number = book.add_format({'num_format': '0.0000'})
        def sheet(name, columns, rows):
            ws = book.add_worksheet(name)
            ws.hide_gridlines(2)
            ws.freeze_panes(1, 1)
            ws.set_column(0, 0, 38)
            ws.set_column(1, max(1,len(columns)-1), 22)
            ws.set_row(0, 34)
            ws.write_row(0, 0, columns, header)
            count = 0
            for count, row in enumerate(rows, 1):
                for col, value in enumerate(row):
                    if isinstance(value, (int,float)) and not isinstance(value,bool):
                        ws.write_number(count, col, value, number)
                    else:
                        ws.write_string(count, col, display(value))
            if count:
                ws.autofilter(0, 0, count, len(columns)-1)
            return ws
        sheet('Summary', ['Measurement / status', 'Value'], statuses + [('Platform',summary['platform']),('Profile',summary['profile']),('Quantile convention',summary['quantile_convention']),('Raw samples','Canonical CSV files adjacent to workbook'),('Hardware limitation','Desktop measurements are not roboRIO measurements'),('Steady-state p99 execution (ms)',summary['steady_state_loop']['execution_ms']['p99']),('Minimum steady-state headroom (ms)',summary['steady_state_loop']['headroom_ms']['minimum']),('HTTP achieved requests/s',summary['traffic'].get('HTTP',{}).get('achieved_per_s')),('NT achieved publish calls/s',summary['traffic'].get('NT',{}).get('achieved_per_s'))])
        sheet('Run Metadata', ['Field', 'Value'], sorted(summary['manifest'].items()))
        ws = sheet('Match Phases', phase_headers, phase_rows)
        if phase_rows:
            chart = book.add_chart({'type':'column'})
            chart.add_series({'name':'p99 execution (ms)', 'categories':['Match Phases',1,0,len(phase_rows),0], 'values':['Match Phases',1,4,len(phase_rows),4]})
            chart.set_title({'name':'Phase p99 execution'})
            chart.set_y_axis({'name':'Elapsed milliseconds'})
            chart.set_legend({'none':True})
            ws.insert_chart(len(phase_rows)+3,0,chart)
        sheet('Subsystem Timings',scope_headers,scope_rows)
        resource = read_csv(directory / 'resource-samples.csv')
        columns = list(resource[0]) if resource else ['Status']
        sheet('CPU and Memory', ['Producer','Metric (unit in name)','Samples','Mean','Median','p95','p99','Sampled maximum','First','Last'], resource_rows(summary))
        sheet('API and NetworkTables',traffic_headers,traffic_rows)
        sheet('Overruns and Failures',['Category','Detail'],failures + [('Deadline misses',summary['loop']['deadline_misses']),('Longest miss streak',summary['loop']['longest_miss_streak'])])
        sheet('Baseline Comparison',['Field','Value'],sorted(comparison.items()))


def generate_reports(directory, summary=None):
    """CLI adapter: regenerate from canonical CSVs rather than trusting stale summaries."""
    return report(directory)


def generate_suite_reports(directory, runpaths):
    """Preserve each repetition and pool raw samples, never average percentiles."""
    from match_analysis import loop_metrics
    directory = Path(directory)
    directory.mkdir(parents=True, exist_ok=True)
    runpaths = [Path(path).resolve() for path in runpaths]
    summaries = [analyze_run(path) for path in runpaths]
    write_suite_index(directory, runpaths, summaries)
    groups = {}
    for path, summary in zip(runpaths, summaries):
        manifest = summary['manifest']
        # Distinct workload/pacing/logging/instrumentation must never silently pool.
        group_metadata={k:manifest.get(k) for k in ('platform','profile','pacing','logging_mode','instrumentation','scenario_hash','period_ns','seed','load_config','artifact_sha256','artifact_hash','performance_class_hash','java_runtime','host_id','cpu_model','cpu_capacity','os','architecture','run_kind')}
        group_metadata['capture_status']=[summary['workload_validity']['status'],summary['measurement_completeness']['status'],summary['functional_correctness'],manifest.get('completion')]
        key=json.dumps(group_metadata,sort_keys=True)
        groups.setdefault(key, []).append((path, summary))
    pooled = []
    for key, runs in groups.items():
        rows = []
        for path, _ in runs:
            # No warm-up deletion: export separate phase groups, plus all captured rows.
            rows.extend(dict(row, run_id=str(path)) for row in read_csv(Path(path)/'loop-samples.csv'))
        metadata = json.loads(key)
        phases = {phase:loop_metrics([r for r in rows if r.get('phase')==phase],metadata.get('period_ns'))
                  for phase in sorted(set(r.get('phase','unknown') for r in rows))}
        pooled.append({'metadata':metadata,'repetitions':len(runs),'phases':phases,
                       'run_ids':[s['run_id'] for _,s in runs]})
    result = {'schema_version':1,'kind':'suite','runs':summaries,'pooled_groups':pooled,
              'hardware_validation':'NOT MEASURED' if all(s['hardware_validation']=='NOT MEASURED' for s in summaries) else 'SEE INDIVIDUAL RUNS'}
    (directory/'run-summary.json').write_text(json.dumps(result,indent=2,allow_nan=False)+'\n')
    rows = [(s['run_id'],s['platform'],s['profile'],s['workload_validity']['status'],s['measurement_completeness']['status'],s['functional_correctness'],s['performance'],s['steady_state_loop']['execution_ms']['median'],s['steady_state_loop']['execution_ms']['p99'],s['steady_state_loop']['execution_ms']['maximum'],s['steady_state_loop']['deadline_misses'],s['steady_state_loop']['headroom_ms']['minimum']) for s in summaries]
    headers=['Run','Platform','Profile','Workload','Completeness','Correctness','Steady qualification','Steady median (ms)','Steady p99 (ms)','Steady maximum (ms)','Steady misses','Steady minimum headroom (ms)']
    links=''.join(f'<li><a href="{html.escape(str(Path(path).relative_to(directory)) if Path(path).is_relative_to(directory) else Path(path).as_uri())}/match-performance.html">{html.escape(s["run_id"])}</a></li>' for path,s in zip(runpaths,summaries))
    (directory/'match-performance.html').write_text('<!doctype html><html lang="en"><meta charset="utf-8"><title>Match performance suite</title><style>body{font:14px system-ui;margin:32px;color:#193442}table{border-collapse:collapse}td,th{padding:8px;border-bottom:1px solid #ddd}th{background:#193442;color:white}</style><h1>Match performance suite</h1><p>Each repetition is retained. This overview shows warmed steady state; individual reports retain startup, warm-up and all-cycle results. Pooled phase quantiles use raw samples, not averaged p99 values. INVALID workloads are not qualification evidence. Desktop results do not establish roboRIO timing.</p>'+table(headers,rows)+'<p><a href="manifest.json">Suite manifest</a> · <a href="raw-file-index.json">Raw-file index</a></p><h2>Individual evidence</h2><ul>'+links+'</ul></html>')
    import xlsxwriter
    with xlsxwriter.Workbook(directory/'match-performance.xlsx',{'constant_memory':False,'strings_to_formulas':False,'strings_to_urls':False}) as book:
        header=book.add_format({'bold':True,'bg_color':'#193442','font_color':'white','text_wrap':True})
        fmt=book.add_format({'num_format':'0.0000'})
        def add(name,columns,values):
            ws=book.add_worksheet(name);ws.hide_gridlines(2);ws.freeze_panes(1,1);ws.set_column(0,len(columns)-1,23);ws.set_row(0,34);ws.write_row(0,0,columns,header)
            count=0
            for count,row in enumerate(values,1):
                for col,value in enumerate(row):
                    if isinstance(value,(int,float)) and not isinstance(value,bool):ws.write_number(count,col,value,fmt)
                    else:ws.write_string(count,col,display(value))
            if count:ws.autofilter(0,0,count,len(columns)-1)
            return ws
        summary_sheet=add('Summary',headers,rows)
        if rows:
            chart=book.add_chart({'type':'column'});chart.add_series({'name':'Run p99 execution (ms)','categories':['Summary',1,0,len(rows),0],'values':['Summary',1,8,len(rows),8]});chart.set_y_axis({'name':'Milliseconds'});chart.set_legend({'none':True});summary_sheet.insert_chart(len(rows)+3,0,chart)
        add('Run Metadata',['Run','Field','Value'],((s['run_id'],k,v) for s in summaries for k,v in sorted(s['manifest'].items())))
        add('Match Phases',['Platform','Profile','Repetitions','Phase','Samples','Median (ms)','p99 (ms)','Maximum (ms)'],((g['metadata']['platform'],g['metadata']['profile'],g['repetitions'],p,m['execution_ms']['sample_count'],m['execution_ms']['median'],m['execution_ms']['p99'],m['execution_ms']['maximum']) for g in pooled for p,m in g['phases'].items()))
        add('Subsystem Timings',['Run','Scope','Samples','Median (ms)','p99 (ms)','Maximum (ms)'],((s['run_id'],name,m['sample_count'],m['median'],m['p99'],m['maximum']) for s in summaries for name,m in s['subsystems_ms'].items()))
        add('CPU and Memory',['Run','Producer','Metric (unit in name)','Samples','Mean','Median','p95','p99','Sampled maximum','First','Last'],((s['run_id'],*row) for s in summaries for row in resource_rows(s)))
        add('API and NetworkTables',['Run','Kind','Summary'],((s['run_id'],k,v) for s in summaries for k,v in s['traffic'].items()))
        add('Overruns and Failures',['Run','Misses','Miss streak','Reasons'],((s['run_id'],s['loop']['deadline_misses'],s['loop']['longest_miss_streak'],s['measurement_completeness']['reasons']+s['workload_validity']['reasons']) for s in summaries))
        add('Baseline Comparison',['Status','Reason'],[['NOT RUN','Use compare subcommand with equivalent platform/scenario metadata']])
    return result


def resource_rows(summary):
    for producer, counters in summary.get('resources', {}).items():
        for metric, values in counters.items():
            if isinstance(values, dict) and 'sample_count' in values:
                yield (producer,metric,values.get('sample_count'),values.get('mean'),values.get('median'),
                       values.get('p95'),values.get('p99'),values.get('maximum'),
                       counters.get(metric+'_first'),counters.get(metric+'_last'))


def write_suite_index(directory, runpaths, summaries):
    """Keep suite-level provenance and references without copying large raw CSVs."""
    import datetime
    import os
    from match_analysis import CSV_FILES
    directory=Path(directory).resolve()
    manifest_path=directory/'manifest.json'
    manifest=json.loads(manifest_path.read_text()) if manifest_path.exists() else {}
    if manifest and (manifest.get('kind') not in (None,'suite','SUITE') or manifest.get('run_id')):
        raise ValueError('Suite report output contains a non-suite manifest; choose a suite directory')
    records=[]
    status_counts={key:{} for key in ('workload_validity','measurement_completeness','functional_correctness','performance','hardware_validation')}
    for path,summary in zip(runpaths,summaries):
        path=Path(path).resolve()
        statuses={key:summary[key].get('status') if isinstance(summary[key],dict) else summary[key] for key in status_counts}
        for key,value in statuses.items():
            status_counts[key][value]=status_counts[key].get(value,0)+1
        raw=[]
        for name in (*CSV_FILES,'host-resource-samples.csv','thread-resource-samples.csv'):
            source=path/name
            raw.append({'name':name,'path':os.path.relpath(source,directory),
                        'status':'PRESENT' if source.is_file() else 'NOT AVAILABLE',
                        'bytes':source.stat().st_size if source.is_file() else None,
                        'sha256':summary.get('source_hashes',{}).get(name)})
        records.append({'run_id':summary['run_id'],'path':os.path.relpath(path,directory),
                        'platform':summary.get('platform'),'profile':summary.get('profile'),
                        'statuses':statuses,'capture_completion':summary['manifest'].get('completion'),
                        'manifest':os.path.relpath(path/'manifest.json',directory),
                        'run_summary':os.path.relpath(path/'run-summary.json',directory),
                        'raw_files':raw,'source_logs':summary['manifest'].get('source_logs',[])})
    index={'schema_version':1,'kind':'raw file reference index','path_base':'directory containing this index',
           'raw_files_copied':False,'runs':records}
    (directory/'raw-file-index.json').write_text(json.dumps(index,indent=2,allow_nan=False)+'\n')
    manifest.update(schema_version=1,kind='suite',report_generated_utc=datetime.datetime.now(datetime.timezone.utc).isoformat(),
                    run_count=len(records),run_ids=[record['run_id'] for record in records],
                    run_paths=[record['path'] for record in records],status_counts=status_counts,
                    raw_file_index='raw-file-index.json',
                    reports=['run-summary.json','match-performance.html','match-performance.xlsx'])
    manifest_path.write_text(json.dumps(manifest,indent=2,allow_nan=False)+'\n')
