#!/usr/bin/env python3
"""Reproducible local JMeter benchmark. Credentials stay in ignored .local files."""
import argparse
import base64
import collections
import csv
import gzip
import hashlib
import hmac
import json
import math
import os
from pathlib import Path
import random
import statistics
import subprocess
import threading
import time
from urllib.error import HTTPError, URLError
from urllib.request import Request, urlopen
import uuid
import xml.etree.ElementTree as ET

ROOT = Path(__file__).resolve().parents[1]
COMPOSE = ['docker', 'compose', '-f', 'compose.yaml', '-f', 'compose.scale.yaml']
LOCAL = ROOT / '.local'
RESULTS = ROOT / 'benchmarks' / 'results'

def command(args, **kwargs):
    return subprocess.run(args, cwd=ROOT, check=True, **kwargs)

def compose(*args):
    return command(COMPOSE + list(args), stdout=subprocess.PIPE, stderr=subprocess.STDOUT, text=True).stdout

def seed(count=200, cleanup=False):
    env = dict(os.environ, RUN_BENCHMARK_SEED='true')
    args = ['./mvnw', '-B', '-ntp', 'test', '-Dtest=BenchmarkFixtureToolTests',
            '-Dlogging.level.org.mongodb.driver=ERROR', '-Dlogging.level.root=WARN',
            '-Dcrm.benchmark.customers=' + str(count)]
    if cleanup:
        args.append('-Dcrm.benchmark.cleanup=true')
    result = command(args, env=env, stdout=subprocess.PIPE, stderr=subprocess.STDOUT, text=True)
    for line in result.stdout.splitlines():
        if 'Benchmark customers=' in line or 'Removed only benchmark' in line:
            print(line, flush=True)

def signing_key():
    value = os.environ.get('JWT_SECRET')
    if not value:
        for line in (LOCAL / 'jwt.properties').read_text().splitlines():
            key, separator, content = line.partition('=')
            if separator and key.strip() in ('JWT_SECRET', 'crm.auth.secret-base64'):
                value = content.strip()
    key = base64.b64decode(value or '', validate=True)
    if len(key) < 32:
        raise ValueError('Set a valid local signing key before benchmarking')
    return key

def token(customer_id, key, staff=False):
    def encoded(value):
        return base64.urlsafe_b64encode(json.dumps(value, separators=(',', ':')).encode()).rstrip(b'=')
    now = int(time.time())
    payload = {'iss':'crm-lab','aud':['crm-api'],'sub':customer_id,'scope':'crm:staff' if staff else '',
               'iat':now,'nbf':now,'exp':now+900,'jti':str(uuid.uuid4())}
    data = encoded({'alg':'HS256','typ':'JWT'}) + b'.' + encoded(payload)
    return (data+b'.'+base64.urlsafe_b64encode(hmac.digest(key, data, 'sha256')).rstrip(b'=')).decode()

def users(manifest):
    key = signing_key()
    staff_token = token(manifest['staffId'], key, True)
    ids = list(manifest['customerIds'])
    random.Random(3513).shuffle(ids)
    path = LOCAL / 'benchmark-users.csv'
    with path.open('w', newline='') as output:
        writer = csv.writer(output)
        for customer_id in ids:
            writer.writerow([customer_id, token(customer_id, key), staff_token])
    path.chmod(0o600)
    return path, key

def request(port, path, bearer=None):
    headers = {'Authorization':'Bearer '+bearer} if bearer else {}
    try:
        with urlopen(Request(f'http://127.0.0.1:{port}'+path, headers=headers), timeout=12) as result:
            return result.status, json.loads(result.read()), dict(result.headers)
    except HTTPError as error:
        return error.code, None, {}

def ready(port):
    end = time.monotonic()+90
    while time.monotonic() < end:
        try:
            status, body, _ = request(port, '/actuator/health/liveness')
            if status == 200 and body.get('status') == 'UP':
                return
        except (URLError, TimeoutError, OSError):
            pass
        time.sleep(1)
    raise RuntimeError('Application did not become ready on port '+str(port))

def recovered(port, path, bearer):
    start=time.monotonic();attempts=0
    while time.monotonic()-start<45:
        attempts+=1
        try:
            status,_,_=request(port,path,bearer)
            if status==200:return {'status':status,'seconds':round(time.monotonic()-start,3),'attempts':attempts}
        except (URLError,OSError,TimeoutError):
            pass
        time.sleep(.5)
    raise RuntimeError('Dependency did not recover within 45 seconds')

def generate_plan(path):
    root = ET.Element('jmeterTestPlan', version='1.2', properties='5.0', jmeter='5.6.3')
    tree = ET.SubElement(root, 'hashTree')
    def prop(parent, name, value, kind='stringProp'):
        ET.SubElement(parent, kind, name=name).text = str(value)
    plan = ET.SubElement(tree, 'TestPlan', guiclass='TestPlanGui', testclass='TestPlan', testname='Relay CRM mixed workload', enabled='true')
    prop(plan, 'TestPlan.functional_mode', 'false', 'boolProp')
    prop(plan, 'TestPlan.serialize_threadgroups', 'false', 'boolProp')
    plan_tree = ET.SubElement(tree, 'hashTree')
    group = ET.SubElement(plan_tree, 'ThreadGroup', guiclass='ThreadGroupGui', testclass='ThreadGroup', testname='Concurrent users', enabled='true')
    prop(group, 'ThreadGroup.on_sample_error', 'continue')
    loop = ET.SubElement(group, 'elementProp', name='ThreadGroup.main_controller', elementType='LoopController', guiclass='LoopControlPanel', testclass='LoopController', testname='Loop', enabled='true')
    prop(loop, 'LoopController.continue_forever', 'false', 'boolProp')
    prop(loop, 'LoopController.loops', '-1')
    for name, value in {'num_threads':'${__P(threads,5)}','ramp_time':'2','duration':'${__P(duration,27)}','delay':'0'}.items():
        prop(group, 'ThreadGroup.'+name, value)
    prop(group, 'ThreadGroup.scheduler', 'true', 'boolProp')
    group_tree = ET.SubElement(plan_tree, 'hashTree')
    dataset = ET.SubElement(group_tree, 'CSVDataSet', guiclass='TestBeanGUI', testclass='CSVDataSet', testname='Synthetic customers', enabled='true')
    for name, value in {'filename':'${__P(users)}','fileEncoding':'UTF-8','variableNames':'customerId,token,staffToken','delimiter':',','shareMode':'shareMode.all'}.items():
        prop(dataset, name, value)
    for name, value in {'quotedData':'false','recycle':'true','stopThread':'false','ignoreFirstLine':'false'}.items():
        prop(dataset, name, value, 'boolProp')
    ET.SubElement(group_tree, 'hashTree')
    endpoints = [
        ('profile','GET','/api/customers/${customerId}','${token}',None),
        ('search','GET','/api/customers/search?prefix=Benchmark%20Customer%200000&limit=20','${staffToken}',None),
        ('autocomplete','GET','/api/customers/suggestions?prefix=Benchmark%20Customer%200000&limit=10','${staffToken}',None),
        ('history','GET','/api/customers/${customerId}/interactions?limit=20','${token}',None),
        ('tickets','GET','/api/customers/${customerId}/tickets?limit=20','${token}',None),
        ('interaction_write','POST','/api/customers/${customerId}/interactions','${staffToken}','{"type":"NOTE","summary":"Benchmark write","details":"Synthetic fixture"}')]
    for label, method, route, bearer, body in endpoints:
        sampler = ET.SubElement(group_tree, 'HTTPSamplerProxy', guiclass='HttpTestSampleGui', testclass='HTTPSamplerProxy', testname=label, enabled='true')
        arguments = ET.SubElement(sampler, 'elementProp', name='HTTPsampler.Arguments', elementType='Arguments')
        collection = ET.SubElement(arguments, 'collectionProp', name='Arguments.arguments')
        if body:
            argument = ET.SubElement(collection, 'elementProp', name='', elementType='HTTPArgument')
            prop(argument, 'HTTPArgument.always_encode', 'false', 'boolProp')
            prop(argument, 'Argument.value', body)
            prop(argument, 'Argument.metadata', '=')
        for name, value in {'domain':'127.0.0.1','port':'${__P(port,8090)}','protocol':'http','path':route,'method':method,
                            'connect_timeout':'3000','response_timeout':'10000'}.items():
            prop(sampler, 'HTTPSampler.'+name, value)
        for name, value in {'follow_redirects':'false','auto_redirects':'false','use_keepalive':'true','postBodyRaw':str(bool(body)).lower()}.items():
            prop(sampler, 'HTTPSampler.'+name, value, 'boolProp')
        sampler_tree = ET.SubElement(group_tree, 'hashTree')
        manager = ET.SubElement(sampler_tree, 'HeaderManager', guiclass='HeaderPanel', testclass='HeaderManager', testname='Bearer authentication', enabled='true')
        headers = ET.SubElement(manager, 'collectionProp', name='HeaderManager.headers')
        for name, value in [('Authorization','Bearer '+bearer),('Content-Type','application/json')]:
            header = ET.SubElement(headers, 'elementProp', name='', elementType='Header')
            prop(header, 'Header.name', name); prop(header, 'Header.value', value)
        ET.SubElement(sampler_tree, 'hashTree')
        assertion = ET.SubElement(sampler_tree, 'JSR223Assertion', guiclass='TestBeanGUI', testclass='JSR223Assertion', testname='Validate response and record upstream', enabled='true')
        prop(assertion,'scriptLanguage','groovy'); prop(assertion,'cacheKey','true')
        prop(assertion,'script', '''def match = (prev.getResponseHeaders() =~ /(?im)^X-CRM-Upstream: ([^\\r\\n]+)/)
vars.put('upstream', match.find() ? match.group(1).trim() : 'unknown')
if (!prev.isSuccessful()) return
try {
  def data = new groovy.json.JsonSlurper().parseText(prev.getResponseDataAsString())
  def label = prev.getSampleLabel()
  if (label == 'profile') assert data.id == vars.get('customerId')
  else if (label == 'interaction_write') { assert prev.getResponseCode() == '201'; assert data.customerId == vars.get('customerId'); assert data.id }
  else { assert data instanceof List; assert data.size() <= 20
    if (label in ['history','tickets']) assert data.every { it.customerId == vars.get('customerId') }
    if (label in ['search','autocomplete']) { assert data.size() > 0; assert data.every { it.fullName.toLowerCase().startsWith('benchmark customer 0000') } }
  }
} catch (Exception | AssertionError error) { AssertionResult.setFailure(true); AssertionResult.setFailureMessage('Response content validation failed') }
''')
        ET.SubElement(sampler_tree, 'hashTree')
    ET.indent(root)
    ET.ElementTree(root).write(path, encoding='UTF-8', xml_declaration=True)

def percentile(values, fraction):
    return sorted(values)[max(0, math.ceil(len(values)*fraction)-1)]

def summarize(path, warmup):
    with path.open(newline='') as source:
        rows = list(csv.DictReader(source))
    if not rows:
        raise RuntimeError('JMeter produced no samples')
    cutoff = min(int(row['timeStamp']) for row in rows)+warmup*1000
    rows = [row for row in rows if int(row['timeStamp']) >= cutoff]
    if not rows:
        raise RuntimeError('No samples remain after warm-up')
    elapsed = [int(row['elapsed']) for row in rows]
    span = (max(int(row['timeStamp'])+int(row['elapsed']) for row in rows)-min(int(row['timeStamp']) for row in rows))/1000
    success = sum(row['success']=='true' for row in rows)
    per_label = {}
    for label in sorted({row['label'] for row in rows}):
        selected = [row for row in rows if row['label']==label]
        times = [int(row['elapsed']) for row in selected]
        per_label[label] = {'requests':len(selected),'mean_ms':round(statistics.mean(times),3),'p95_ms':percentile(times,.95),
                            'errors':sum(row['success']!='true' for row in selected)}
    return {'requests':len(rows),'measured_seconds':round(span,3),'mean_ms':round(statistics.mean(elapsed),3),
			'measurement_start_epoch':min(int(row['timeStamp']) for row in rows)/1000,
			'measurement_end_epoch':max(int(row['timeStamp'])+int(row['elapsed']) for row in rows)/1000,
            'p95_ms':percentile(elapsed,.95),'throughput_rps':round(len(rows)/span,3),'success_pct':round(100*success/len(rows),3),
            'error_pct':round(100*(len(rows)-success)/len(rows),3),'status_counts':dict(collections.Counter(row['responseCode'] for row in rows)),
            'upstream_counts':dict(collections.Counter(row.get('upstream','unknown') for row in rows)), 'endpoints':per_label}

def capture_resources(stop, output):
    while not stop.is_set():
        try:
            result = command(['docker','stats','--no-stream','--format','{{json .}}'], stdout=subprocess.PIPE,stderr=subprocess.DEVNULL,text=True)
            for line in result.stdout.splitlines():
                sample = json.loads(line)
                if sample['Name'].startswith('sdminiproj-'):
                    output.append({'time':time.time(),'name':sample['Name'],'cpu_pct':float(sample['CPUPerc'].rstrip('%')),
                                   'memory':sample['MemUsage'].split(' / ')[0]})
        except (subprocess.CalledProcessError, KeyError, ValueError):
            pass
        stop.wait(1)

def component_metrics(manifest):
    bearer = token(manifest['staffId'], signing_key(), True)
    result = {}
    for port in (8081,8082,8083):
        values = {}
        for name in ('crm.customer_cache.lookup','crm.rate_limit.lookup','crm.customer_search.lookup','crm.customer_autocomplete.lookup','crm.customer_cache.requests'):
            try:
                status, body, _ = request(port, '/actuator/metrics/'+name, bearer)
                if status == 200:
                    values[name] = {item['statistic']:item['value'] for item in body['measurements']}
            except (URLError, OSError, TimeoutError):
                break
        result[str(port)] = values
    return result

def jmeter_run(name, port, concurrency, duration, manifest, fail_app=False):
    users_path, _ = users(manifest)
    path = RESULTS/(name+'.jtl')
    warmup = 7
    args = ['jmeter','-n','-t',str(ROOT/'benchmarks/crm.jmx'),'-l',str(path),'-j',str(LOCAL/'jmeter.log'),
            '-Jthreads='+str(concurrency),'-Jduration='+str(duration+warmup),'-Jport='+str(port),'-Jusers='+str(users_path),
            '-Jsample_variables=upstream','-Jjmeter.save.saveservice.output_format=csv',
            '-Jjmeter.save.saveservice.print_field_names=true','-Jjmeter.save.saveservice.timestamp_format=ms',
            '-Jjmeter.save.saveservice.response_data=false','-Jjmeter.save.saveservice.requestHeaders=false',
            '-Jjmeter.save.saveservice.responseHeaders=false']
    print('Running '+name,flush=True)
    before = component_metrics(manifest)
    fault = {}
    def stop_replica():
        fault['requested_at_epoch']=time.time()
        compose('stop','-t','1','app-2')
        fault['stopped_at_epoch']=time.time()
    timer=threading.Timer(warmup+duration/2,stop_replica) if fail_app else None
    samples=[]; stop=threading.Event(); collector=threading.Thread(target=capture_resources,args=(stop,samples),daemon=True);collector.start()
    try:
        if timer:timer.start()
        command(args,stdout=subprocess.PIPE,stderr=subprocess.STDOUT,text=True,timeout=duration+warmup+90)
    finally:
        if timer:timer.cancel();timer.join(timeout=15)
        stop.set();collector.join(timeout=10)
    result = summarize(path,warmup)
    result.update(name=name,concurrency=concurrency,customers=len(manifest['customerIds']))
    result['component_metrics'] = {'before':before,'after':component_metrics(manifest)}
    if fault:result['fault']=fault
    result['resources'] = samples
    with gzip.open(str(path)+'.gz','wb') as output: output.write(path.read_bytes())
    path.unlink()
    (RESULTS/(name+'.json')).write_text(json.dumps(result,indent=2))
    print(json.dumps({key:value for key,value in result.items() if key not in ('resources','endpoints','upstream_counts')}),flush=True)
    return result

def main():
    parser=argparse.ArgumentParser();parser.add_argument('--duration',type=int,default=20);parser.add_argument('--repeats',type=int,default=2)
    parser.add_argument('--cleanup',action='store_true');parser.add_argument('--plan-only',action='store_true')
    args=parser.parse_args();generate_plan(ROOT/'benchmarks/crm.jmx')
    if args.plan_only:return
    if args.cleanup:seed(cleanup=True);return
    if args.duration<5 or args.repeats<1:raise ValueError('Use at least 5 seconds and 1 repeat')
    LOCAL.mkdir(exist_ok=True);RESULTS.mkdir(exist_ok=True)
    seed(200);manifest=json.loads((LOCAL/'benchmark-fixtures.json').read_text())
    os.environ['CRM_STAFF_EMAILS']='raghu.demo@crm.local,'+manifest['staffEmail']
    os.environ['RATE_LIMIT_API']='1000000'
    os.environ['RATE_LIMIT_LOGIN']='1000'
    print(compose('up','-d','app-1','baseline-gateway'),flush=True);ready(8081);ready(8091)
    compose('stop','app-2','app-3','gateway')
    all_results=[]
    try:
        for repeat in range(1,args.repeats+1):
            for concurrency in (5,20,60):
                all_results.append(jmeter_run(f'baseline-c{concurrency}-r{repeat}',8091,concurrency,args.duration,manifest))
        print(compose('up','-d','app-2','app-3','gateway'),flush=True)
        for port in (8082,8083,8090):ready(port)
        for repeat in range(1,args.repeats+1):
            for concurrency in (5,20,60):
                all_results.append(jmeter_run(f'scaled-c{concurrency}-r{repeat}',8090,concurrency,args.duration,manifest))
        seed(3000);manifest=json.loads((LOCAL/'benchmark-fixtures.json').read_text())
        all_results.append(jmeter_run('growth-3000-c20',8090,20,args.duration,manifest))
        try:all_results.append(jmeter_run('app-outage-c20',8090,20,args.duration,manifest,fail_app=True))
        finally:compose('start','app-2');ready(8082)
        key=signing_key();employee=token(manifest['staffId'],key,True)
        failure=[]
        compose('stop','-t','1','mongodb-shard-2')
        try:
            for customer_id in manifest['customerIds'][:60]:
                start=time.perf_counter();status,_,_=request(8090,f'/api/customers/{customer_id}/interactions?limit=2',token(customer_id,key))
                failure.append({'status':status,'latency_ms':round((time.perf_counter()-start)*1000,3)})
            status,_,_=request(8090,'/api/customers/search?prefix=Benchmark&limit=20',employee)
            search_status=status
        finally:compose('start','mongodb-shard-2')
        recovery=recovered(8090,'/api/customers/search?prefix=Benchmark&limit=20',employee)
        (RESULTS/'shard-failure.json').write_text(json.dumps({'samples':failure,'search_status':search_status,'recovery':recovery},indent=2))
        compose('stop','-t','1','redis')
        try:
            start=time.perf_counter();status,_,_=request(8090,f'/api/customers/{manifest["customerIds"][0]}',token(manifest['customerIds'][0],key))
            redis_failure={'api_status':status,'latency_ms':round((time.perf_counter()-start)*1000,3),'liveness_status':request(8090,'/actuator/health/liveness')[0]}
        finally:compose('start','redis')
        redis_failure['recovery']=recovered(8090,f'/api/customers/{manifest["customerIds"][0]}',token(manifest['customerIds'][0],key))
        (RESULTS/'redis-failure.json').write_text(json.dumps(redis_failure,indent=2))
        (RESULTS/'summary.json').write_text(json.dumps({'runs':[{k:v for k,v in result.items() if k!='resources'} for result in all_results],
            'distribution':manifest['distribution'],'baseline_customers':200,'growth_customers':3000,
            'warmup_seconds':7,'requested_measurement_seconds':args.duration,'repeats':args.repeats,'request_mix':'equal profile/search/autocomplete/history/tickets/interaction_write',
            'environment':{'machine':os.uname().machine,'docker':compose('version').strip(),'jmeter':'5.6.3','jvm_heap_per_app_mb':384},
            'rate_limit_api':1000000},indent=2))
    finally:
        # Always restore dependencies and replicas, including after a failed experiment.
        compose('start','mongodb-shard-2','redis','app-2','app-3')
        (LOCAL/'benchmark-users.csv').unlink(missing_ok=True)
    print('Results saved in benchmarks/results. Use --cleanup after reviewing the fixture data.',flush=True)

if __name__=='__main__':main()
