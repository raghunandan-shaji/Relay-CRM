#!/usr/bin/env python3
"""Generate tables and figures directly from retained, sanitized benchmark data."""
import collections
import csv
import gzip
import json
from pathlib import Path
import re
import statistics
import sys

from run import percentile

ROOT=Path(__file__).resolve().parents[1]
DATA=ROOT/'benchmarks/results'

def memory_mb(value):
    match=re.fullmatch(r'([0-9.]+)([A-Za-z]+)',value)
    if not match:raise ValueError('Unknown Docker memory unit: '+value)
    factors={'B':1/1048576,'kB':1000/1048576,'KB':1000/1048576,'KiB':1/1024,'MB':1000000/1048576,'MiB':1,'GB':1000000000/1048576,'GiB':1024}
    return float(match[1])*factors[match[2]]

def raw_rows(result):
    with gzip.open(DATA/(result['name']+'.jtl.gz'),'rt',newline='') as source:
        rows=list(csv.DictReader(source))
    cutoff=min(int(row['timeStamp']) for row in rows)+7000
    measured=[row for row in rows if int(row['timeStamp'])>=cutoff]
    assert len(measured)==result['requests'], 'Raw samples disagree with stored summary'
    return measured

def resources(results):
    per_app=collections.defaultdict(list)
    for result in results:
        for sample in result['resources']:
            if '-app-' not in sample['name']:continue
            if not result['measurement_start_epoch']<=sample['time']<=result['measurement_end_epoch']:continue
            per_app[sample['name']].append(sample)
    if not per_app:raise ValueError('No application resource samples in measurement window')
    return {'mean_app_cpu_pct':sum(statistics.mean(s['cpu_pct'] for s in samples) for samples in per_app.values()),
            'sum_peak_app_memory_mib':sum(max(memory_mb(s['memory']) for s in samples) for samples in per_app.values()),
            'by_app':{name:{'cpu_mean_pct':round(statistics.mean(s['cpu_pct'] for s in samples),2),
                            'memory_peak_mib':round(max(memory_mb(s['memory']) for s in samples),2),'samples':len(samples)} for name,samples in per_app.items()}}

def aggregate(results):
    rows=[row for result in results for row in raw_rows(result)]
    times=[int(row['elapsed']) for row in rows]
    return {'trials':len(results),'requests':len(rows),'mean_ms':statistics.mean(times),'p95_ms':percentile(times,.95),
            'throughput_rps':len(rows)/sum(result['measured_seconds'] for result in results),
            'success_pct':100*sum(row['success']=='true' for row in rows)/len(rows),
            'error_pct':100*sum(row['success']!='true' for row in rows)/len(rows),**resources(results)}

def main():
    summary=json.loads((DATA/'summary.json').read_text())
    results=[json.loads((DATA/(result['name']+'.json')).read_text()) for result in summary['runs']]
    groups=collections.defaultdict(list)
    for result in results:
        if result['name'].startswith(('baseline-','scaled-')):groups[(result['name'].split('-')[0],result['concurrency'])].append(result)
    table=[]
    for (system,concurrency),runs in sorted(groups.items()):
        table.append({'system':system,'concurrency':concurrency,**aggregate(runs)})
    with (DATA/'comparison.csv').open('w',newline='') as output:
        writer=csv.DictWriter(output,fieldnames=[key for key in table[0] if key!='by_app'],lineterminator='\n');writer.writeheader()
        writer.writerows({key:value for key,value in row.items() if key!='by_app'} for row in table)
    (DATA/'comparison.json').write_text(json.dumps(table,indent=2))
    try:
        import matplotlib
        matplotlib.use('Agg')
        import matplotlib.pyplot as plt
        plt.rcParams.update({'font.size':10,'axes.spines.top':False,'axes.spines.right':False})
        figure,axes=plt.subplots(1,2,figsize=(11,4.2),layout='constrained')
        for system,color,label in [('baseline','#7a8782','1 server'),('scaled','#24604b','3 servers')]:
            entries=[row for row in table if row['system']==system]
            for axis,metric in [(axes[0],'throughput_rps'),(axes[1],'p95_ms')]:
                values=[row[metric] for row in entries]
                lower=[row[metric]-min(run[metric] for run in groups[(system,row['concurrency'])]) for row in entries]
                upper=[max(run[metric] for run in groups[(system,row['concurrency'])])-row[metric] for row in entries]
                axis.errorbar([row['concurrency'] for row in entries],values,yerr=[lower,upper],marker='o',color=color,label=label,capsize=4)
        axes[0].set_ylabel('Completed requests / second');axes[0].set_title('Mixed-workload throughput')
        axes[1].set_ylabel('P95 response time (ms)');axes[1].set_title('Tail latency')
        for axis in axes:axis.set_xlabel('Concurrent JMeter users');axis.set_xticks([5,20,60]);axis.grid(alpha=.2);axis.legend(frameon=False)
        figure.supxlabel('Whiskers show the range across two trials; points use pooled samples.',fontsize=9)
        figure.savefig(DATA/'scalability.png',dpi=180);figure.savefig(DATA/'scalability.svg');plt.close(figure)
        svg=DATA/'scalability.svg'
        svg.write_text('\n'.join(line.rstrip() for line in svg.read_text().splitlines())+'\n')
    except ImportError:
        print('Matplotlib unavailable; numeric tables generated, plots skipped.',file=sys.stderr)
    failures=json.loads((DATA/'shard-failure.json').read_text());redis=json.loads((DATA/'redis-failure.json').read_text())
    outage=next(result for result in results if result['name'].startswith('app-outage'))
    growth=next(result for result in results if result['name'].startswith('growth'))
    lines=['# Measured performance and failure evaluation','',
      'Measured on 25 October 2026 on one Apple Silicon laptop. These results establish behavior for this local configuration; they are not a forecast for production hardware or millions of users.','',
      '## Method','',
      '- JMeter 5.6.3 in CLI mode; 5, 20, and 60 concurrent threads; two trials per one-server/three-server configuration.',
      '- Each trial has a 2-second ramp and a 7-second excluded warm-up, with about 20 seconds of retained samples. The report uses actual sample spans rather than the requested duration as its throughput denominator.',
      '- Each loop sends equal numbers of profile reads, indexed staff searches, autocomplete requests, history reads, ticket-list reads, and interaction writes: about 83% reads and 17% writes. Assertions validate response content, ownership IDs, search prefixes, and expected creation status.',
      '- Both configurations use the same backend, MongoDB shards, Redis, and an NGINX gateway. Baseline has one active JVM; scaled has three independent JVM/container processes with worker IDs 20/21/22. This isolates application process count, not a comparison to an entirely unsharded CRM.',
      '- Each app has a 384 MiB maximum Java heap, 640 MiB container memory limit, and 2-CPU quota. Docker has 8 CPUs and about 8 GB memory. JMeter and containers share the same laptop.',
      '- Capacity trials raise the authenticated API limit to 1,000,000 requests/customer/window, preventing throttling from dominating capacity. Default runtime limits are 120. Limiter correctness/rejection is evaluated separately.',
      '- The fixture tool initially seeds 200 synthetic customers and 10 historical interactions each; a few existing demo accounts remain. Interaction-writing workloads add records during trials. The growth case expands to 3,000 synthetic customers and their initial histories. This is a growth scenario, not an isolated causal experiment on customer count alone.',
      '- Fixed execution order is baseline before scaled; JVM warm-up, cache state, accumulating history, and shared-machine contention can affect comparisons. Two short trials do not establish statistical significance. Tests are closed-loop without think time and do not correct for coordinated omission.',
      '- Profile cache is enabled in both server-count configurations. Prior indexed-search versus forced-collection-scan tests provide a separate algorithm baseline.',
      '- Raw result files contain synthetic IDs/URLs and timing/status data. Authorization headers, bodies, signing keys, and tokens are not retained in tracked results. Authentication setup/token issuance is outside the timed request mix.','',
      '## Overall results','',
      'Means/P95 and success rates below are calculated from pooled retained raw samples. Throughput is total requests divided by summed measured trial spans.','',
      '| Active app servers | Users | Trials | Requests | Mean ms | P95 ms | Requests/s | Success % | Error % | Mean app CPU % | Sum of app peak memory MiB |',
      '| --- | --- | --- | --- | --- | --- | --- | --- | --- | --- | --- |']
    for row in table:
        lines.append(f'| {1 if row["system"]=="baseline" else 3} | {row["concurrency"]} | {row["trials"]} | {row["requests"]} | {row["mean_ms"]:.2f} | {row["p95_ms"]} | {row["throughput_rps"]:.2f} | {row["success_pct"]:.3f} | {row["error_pct"]:.3f} | {row["mean_app_cpu_pct"]:.1f} | {row["sum_peak_app_memory_mib"]:.1f} |')
    lines+=['','Trial-to-trial variation is substantial. The graph whiskers show the two-trial ranges; the data does not support a statistical claim of scaling efficiency.','']
    lines+=['','Docker CPU 100% represents roughly one occupied CPU core. CPU is the sum of per-app sample means in the measurement window. Memory is the sum of each app’s observed peak container memory; these peaks need not be simultaneous. Resource samples are coarse Docker snapshots, not continuous hardware profiling. Database/Redis/gateway resource snapshots are retained in each run JSON.','',
            '![Measured throughput and P95](../benchmarks/results/scalability.png)','',
            '## Endpoint and component measurements','',
            'The following response times use the pooled three-server, 60-user samples. They include gateway, authentication, quota checking, serialization, and database/cache work.','',
            '| Endpoint | Requests | Mean ms | P95 ms | Errors |','| --- | --- | --- | --- | --- |']
    high=[row for result in groups[('scaled',60)] for row in raw_rows(result)]
    for label in sorted({row['label'] for row in high}):
        rows=[row for row in high if row['label']==label];times=[int(row['elapsed']) for row in rows]
        lines.append(f'| {label} | {len(rows)} | {statistics.mean(times):.2f} | {percentile(times,.95)} | {sum(row["success"]!="true" for row in rows)} |')
    lines+=['','Internal component timer means use before/after Micrometer counter differences across all active app processes. Unlike the HTTP table, these timer intervals include the warm-up. Cache lookup includes Redis access and JSON decoding; it is not a measurement of Redis server execution time alone.','',
            '| Component timer, scaled 60 users | Calls | Mean ms |','| --- | --- | --- |']
    for name in ['crm.customer_cache.lookup','crm.rate_limit.lookup','crm.customer_search.lookup','crm.customer_autocomplete.lookup']:
        count=total=0
        for result in groups[('scaled',60)]:
            for port,values in result['component_metrics']['after'].items():
                after=values.get(name,{});before=result['component_metrics']['before'].get(port,{}).get(name,{})
                count+=after.get('COUNT',0)-before.get('COUNT',0);total+=after.get('TOTAL_TIME',0)-before.get('TOTAL_TIME',0)
        if count:lines.append(f'| {name} | {int(count)} | {1000*total/count:.3f} |')
        else:lines.append(f'| {name} | unavailable | unavailable |')
    lines+=['','Additional component checks from the regression suite:','',
      '- Snowflake: 400,000 IDs, 0 collisions; last recorded in-process generation rate 2,501,532 IDs/s. This isolated loop is not API registration throughput.',
      '- Shared Redis limiter: 100 requests, allowance 25, exactly 25 admitted and 75 rejected (75% rejection), no overshoot across 20 threads and two limiter objects.',
      '- Hash-ring routing: 100,000 keys distributed 33.09%, 33.81%, 33.10%; adding a fourth node remapped 27.50%, versus 75.01% for the tested modulo baseline. Removing one ring node remapped 33.10%. No stored data was migrated.',
      '- Indexed name search: 12 documents examined versus 803 for the forced collection-scan baseline in the latest full suite (600 temporary search fixtures plus existing local records). Autocomplete top-five accuracy was 5/5 expected results for its controlled fixture test.',
      '- Concurrent qualification, activity completion, and support reply tests each used 32 competing updates at one version: one succeeded, 31 conflicted.','',
      '## Data and request distribution','',
      'Synthetic-customer distribution after expansion: '+', '.join(f'{key}: {value}' for key,value in summary['distribution'].items())+'.','',
      'Gateway upstream counts for each measured scaled trial are retained below. Container addresses identify the actual responding JVM; they are local experiment identifiers, not public endpoints.','',
      '| Trial | Responses by upstream address |','| --- | --- |']
    for result in results:
        if result['name'].startswith('scaled-'):lines.append('| '+result['name']+' | '+', '.join(f'{key}: {value}' for key,value in result['upstream_counts'].items())+' |')
    statuses=collections.Counter(str(row['status']) for row in failures['samples'])
    lines+=['','## Growth and failures','',
      '| Scenario | Requests / observation | Mean ms | P95 ms | Requests/s | Success % |','| --- | --- | --- | --- | --- |',
      f'| 3,000-customer growth, 3 servers, 20 users | {growth["requests"]} | {growth["mean_ms"]:.2f} | {growth["p95_ms"]} | {growth["throughput_rps"]:.2f} | {growth["success_pct"]:.3f} |',
      f'| Stop app-2 during a 20-user workload | {outage["requests"]} | {outage["mean_ms"]:.2f} | {outage["p95_ms"]} | {outage["throughput_rps"]:.2f} | {outage["success_pct"]:.3f} |','',
      'App-outage HTTP statuses: '+json.dumps(outage['status_counts'])+'. Upstream distribution: '+json.dumps(outage['upstream_counts'])+'. The stop was triggered during the run; exact fault timestamps are in its JSON. NGINX can retry safe connection failures; a write already sent upstream can still have an ambiguous outcome and must not be blindly replayed.','',
      'With MongoDB shard 2 stopped, 60 uncached customer-history probes returned '+json.dumps(dict(statuses))+'. Global staff search returned '+str(failures['search_status'])+'. Recovery search returned '+str(failures['recovery']['status'])+' after '+str(failures['recovery']['seconds'])+' seconds from starting recovery probes. Other-shard successes demonstrate isolation; affected-shard 503 responses demonstrate the absence of replica failover.','',
      'With Redis stopped, an authenticated API request returned '+str(redis['api_status'])+' in '+str(redis['latency_ms'])+' ms; liveness returned '+str(redis['liveness_status'])+'. Recovery API status was '+str(redis['recovery']['status'])+' after '+str(redis['recovery']['seconds'])+' seconds of probing. The quota path intentionally fails closed.','',
      'Worker-recovery tests separately verified durable outbox delivery after interruption and duplicate replay without resetting read acknowledgements. All stopped services are restored by the runner; inspect Compose health before demonstrating the system.','',
      '## Interpretation and limits','',
      'The measurements show no consistent throughput gain from three processes in this setup. At 60 users, pooled throughput is about 667 requests/s on one app and 652 on three, while P95 improves from 314 ms to 265 ms. Aggregate app CPU and memory consumption increase. This is evidence of load distribution and application redundancy, not linear throughput scaling.','',
      'Baseline app CPU samples are close to its two-CPU quota, and search/autocomplete are the slowest measured endpoints. CPU pressure and three-shard search fan-out are plausible contributors. More app servers also share Redis, data shards, CPU budget, and the client machine. These are evidence-informed hypotheses, not profiler-confirmed root causes.','',
      'The experiment demonstrates application process scaling, load distribution, controlled data growth, dependency failure behavior, and recovery. It does not implement database replication or shard migration, measure independent-machine/cloud capacity, establish a maximum sustainable production throughput, or provide a security penetration test. There is no basis here to claim fully fault-tolerant or million-user operation.','',
      '## Reproduction and evidence','',
      'See [benchmark instructions](../benchmarks/README.md). Numeric comparison: [CSV](../benchmarks/results/comparison.csv), [JSON](../benchmarks/results/comparison.json). Full run summaries and compressed JMeter samples are in [benchmarks/results](../benchmarks/results/). The generated JMeter plan is [crm.jmx](../benchmarks/crm.jmx).','']
    (ROOT/'docs/performance.md').write_text('\n'.join(lines))
    print(json.dumps([{key:value for key,value in row.items() if key!='by_app'} for row in table],indent=2))

if __name__=='__main__':main()
