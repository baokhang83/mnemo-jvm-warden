#!/usr/bin/env python3
"""Exercise Boost with the packaged controller, real sidecar, and ZGC index workload.

Run only on a disposable cluster. The script creates cluster-scoped admission policies and
CRDs. It refuses to reuse its namespaces or overwrite its evidence file.
"""
import argparse
import datetime
import json
import os
from pathlib import Path
import socket
import subprocess
import sys
import tempfile
import time
import urllib.request
import urllib.error

parser = argparse.ArgumentParser(description=__doc__)
parser.add_argument('--context', required=True)
parser.add_argument('--controller-image', required=True, help='image repository:tag available to the cluster')
parser.add_argument('--agent-image', required=True, help='image repository:tag containing BoostIndexDemo')
parser.add_argument('--output', required=True, help='new JSON evidence file; never overwritten')
args = parser.parse_args()
root = Path(__file__).resolve().parent.parent
output = Path(args.output)
try:
    with output.open('x') as evidence: evidence.write('{}\n')
except FileExistsError:
    raise SystemExit('Refusing to overwrite evidence file: ' + str(output))
ns, control, release = 'warden-b7-acceptance', 'warden-b7-control', 'b7probe'
fault_ns = 'warden-b7-fault'
created_fault = False
base = ['kubectl', '--context', args.context]
report = {'startedAt': datetime.datetime.now(datetime.timezone.utc).isoformat(), 'context': args.context,
          'controllerImage': args.controller_image, 'agentImage': args.agent_image, 'scenarios': {}, 'result': 'running'}
forward = None
credentials_dir = None
operator_config = None
created_control = False
created_ns = False
installed = False

def persist():
    output.write_text(json.dumps(report, indent=2, sort_keys=True) + '\n')

def run(command, *, obj=None, allow_failure=False):
    result = subprocess.run(command, input=json.dumps(obj) if obj is not None else None,
                            capture_output=True, text=True)
    if result.returncode and not allow_failure:
        raise RuntimeError('command failed: ' + ' '.join(command[:5]) + '\n' + result.stderr)
    return result.stdout

def k(*args_, obj=None, allow_failure=False):
    return run(base + list(args_), obj=obj, allow_failure=allow_failure)

def create(obj): return k('create', '-f', '-', obj=obj)
def op(*args_, obj=None):
    if operator_config is None: raise RuntimeError('operator credentials not initialized')
    return run(['kubectl', '--kubeconfig', str(operator_config)] + list(args_), obj=obj)
def get(kind, name, namespace=ns): return json.loads(k('get', kind, name, '-n', namespace, '-o', 'json'))
def patch(kind, name, value, namespace=ns):
    return k('patch', kind, name, '-n', namespace, '--type=merge', '-p', json.dumps(value))

def wait(label, predicate, timeout=180):
    deadline = time.monotonic() + timeout
    last = None
    while time.monotonic() < deadline:
        try:
            last = predicate()
        except (urllib.error.URLError, ConnectionError, TimeoutError) as error:
            last = str(error)
            time.sleep(2)
            continue
        if last: return last
        time.sleep(2)
    raise TimeoutError('timed out waiting for ' + label + ': ' + str(last))

def phase(name, wanted):
    status = get('wardenboost', name).get('status', {})
    return status if status.get('phase') in wanted else None

def http(path, method='GET'):
    request = urllib.request.Request('http://127.0.0.1:' + str(local_port) + path, method=method)
    with urllib.request.urlopen(request, timeout=5) as response:
        return json.loads(response.read())

def app_status(): return http('/status')
def ensure_endpoint():
    global forward
    if forward is not None and forward.poll() is not None:
        forward = subprocess.Popen(base + ['port-forward', '-n', ns, 'pod/worker', str(local_port) + ':8089'],
                                   stdout=subprocess.DEVNULL, stderr=subprocess.DEVNULL)
    return app_status()
def memory():
    pod = get('pod', 'worker')
    status = next(c for c in pod['status']['containerStatuses'] if c['name'] == 'app')
    return {'podUid': pod['metadata']['uid'], 'containerId': status['containerID'],
            'resources': status.get('resources', {}), 'restartCount': status.get('restartCount', 0)}

def sample_queries():
    samples = []
    for _ in range(10):
        started = time.monotonic(); http('/query'); samples.append(round((time.monotonic() - started) * 1000, 3))
    return samples

def sidecar_usage():
    # These are cgroup snapshots, not a claim about incremental overhead vs no agent.
    memory_current = k('exec', 'worker', '-n', ns, '-c', 'warden', '--', 'cat', '/sys/fs/cgroup/memory.current').strip()
    cpu = k('exec', 'worker', '-n', ns, '-c', 'warden', '--', 'cat', '/sys/fs/cgroup/cpu.stat')
    cgroup = k('exec', 'worker', '-n', ns, '-c', 'warden', '--', 'cat', '/proc/self/cgroup')
    return {'memoryCurrentBytes': int(memory_current), 'cpuStat': cpu, 'cgroup': cgroup}

def create_request(name, boost='rebuild'):
    pod = get('pod', 'worker')
    request = {'apiVersion': 'warden.mnemo.io/v1alpha1', 'kind': 'WardenBoost',
               'metadata': {'name': name, 'namespace': ns},
               'spec': {'policyRef': 'policy', 'podRef': {'name': 'worker', 'uid': pod['metadata']['uid']},
                        'container': 'app', 'boost': boost, 'completionRequested': False}}
    op('create', '-f', '-', obj=request)

def complete(name):
    op('patch', 'wardenboost', name, '-n', ns, '--type=merge', '-p', '{"spec":{"completionRequested":true}}')
def finish_work():
    http('/finish', 'POST')
    wait('index operation to stop', lambda: not app_status()['running'], timeout=60)

def run_scenario(name, action):
    print('==> ' + name, flush=True)
    started = time.monotonic()
    try:
        evidence = action()
        report['scenarios'][name] = {'result': 'PASS', 'durationSeconds': round(time.monotonic() - started, 3), **evidence}
        persist()
        print('PASS: ' + name, flush=True)
    except Exception as exc:
        report['scenarios'][name] = {'result': 'FAIL', 'error': str(exc), 'durationSeconds': round(time.monotonic() - started, 3)}
        persist()
        raise

try:
    # Reuse the packaged admission/RBAC negative matrix before installing this test's
    # cluster-scoped policies; running two releases with overlapping selectors is unsafe.
    security = subprocess.run([sys.executable, str(root / 'deploy/verify-boost-security.py'),
        '--context', args.context, '--controller-image', args.controller_image], capture_output=True, text=True)
    report['scenarios']['security-boundaries'] = {'result': 'PASS' if security.returncode == 0 else 'FAIL',
                                                   'output': security.stdout, 'error': security.stderr}
    persist()
    if security.returncode: raise RuntimeError('security acceptance failed: ' + security.stderr)
    # Creating a namespace rather than applying one prevents reusing someone else's resources.
    k('create', 'namespace', control); created_control = True
    repository, tag = args.controller_image.rsplit(':', 1)
    installed = True  # Namespace is ours; also clean up a partially failed Helm release.
    run(['helm', 'install', release, str(root / 'charts/warden'), '--kube-context', args.context,
         '--namespace', control, '--set', 'boost.security.enabled=true', '--set', 'boost.observability.enabled=true',
         '--set', 'controller.image.repository=' + repository, '--set', 'controller.image.tag=' + tag,
         '--set', 'controller.image.pullPolicy=IfNotPresent',
         '--set', 'controller.prometheusUrl=http://worker.' + ns + '.svc:8089'])
    k('rollout', 'status', 'deployment/' + release + '-warden-controller', '-n', control, '--timeout=120s')
    k('create', 'namespace', ns); created_ns = True
    k('label', 'namespace', ns, 'warden.mnemo.io/boost-enabled=true')
    for account in ('warden-boost-agent', 'warden-boost-operator'): k('create', 'serviceaccount', account, '-n', ns)
    for role in ('agent', 'operator'):
        k('create', 'rolebinding', 'boost-' + role, '-n', ns, '--clusterrole=' + release + '-warden-boost-' + role,
          '--serviceaccount=' + ns + ':warden-boost-' + role)
    credentials_dir = tempfile.TemporaryDirectory(prefix='warden-b7-credentials-')
    token = k('create', 'token', 'warden-boost-operator', '-n', ns).strip()
    kubeconfig = json.loads(k('config', 'view', '--raw', '--flatten', '--minify', '-o', 'json'))
    kubeconfig['users'] = [{'name': 'operator', 'user': {'token': token}}]
    kubeconfig['contexts'][0]['context']['user'] = 'operator'
    operator_config = Path(credentials_dir.name) / 'config.json'
    operator_config.write_text(json.dumps(kubeconfig)); operator_config.chmod(0o600)
    policy = {'apiVersion': 'warden.mnemo.io/v1alpha1', 'kind': 'WardenPolicy', 'metadata': {'name': 'policy', 'namespace': ns},
        'spec': {'timezone': 'UTC', 'targetRef': {'kind': 'Pod', 'name': 'worker'},
                 'profiles': {'normal': {'request': '128Mi', 'limit': '320Mi', 'heapTarget': '64Mi'},
                              'boosted': {'request': '256Mi', 'limit': '640Mi', 'heapTarget': '384Mi'},
                              'crossed': {'request': '300Mi', 'limit': '448Mi', 'heapTarget': '128Mi'},
                              'emergency': {'request': '192Mi', 'limit': '704Mi', 'heapTarget': '128Mi'}},
                 'schedule': [{'cron': '* * * * *', 'profile': 'normal'}],
                 'boosts': {'rebuild': {'profile': 'boosted', 'heapTarget': '384Mi',
                                        'acquisitionTimeout': '2m', 'maxDuration': '45s'}}}}
    create(policy)
    policy_uid = get('wardenpolicy', 'policy')['metadata']['uid']
    credentials = {'name': 'warden-api', 'projected': {'sources': [
        {'serviceAccountToken': {'path': 'token', 'expirationSeconds': 3600}},
        {'configMap': {'name': 'kube-root-ca.crt', 'items': [{'key': 'ca.crt', 'path': 'ca.crt'}]}},
        {'downwardAPI': {'items': [{'path': 'namespace', 'fieldRef': {'fieldPath': 'metadata.namespace'}}]}}]}}
    agent_mount = {'name': 'warden-api', 'mountPath': '/var/run/secrets/kubernetes.io/serviceaccount', 'readOnly': True}
    pod = {'apiVersion': 'v1', 'kind': 'Pod', 'metadata': {'name': 'worker', 'namespace': ns,
             'labels': {'warden.mnemo.io/policy-uid': policy_uid, 'app': 'worker'}}, 'spec': {
        'serviceAccountName': 'warden-boost-agent', 'automountServiceAccountToken': False, 'shareProcessNamespace': True,
        'volumes': [credentials, {'name': 'host-cgroup', 'hostPath': {'path': '/sys/fs/cgroup', 'type': 'Directory'}}],
        'initContainers': [{'name': 'warden', 'image': args.agent_image, 'imagePullPolicy': 'IfNotPresent', 'restartPolicy': 'Always',
            'env': [{'name': 'WARDEN_POD_NAME', 'valueFrom': {'fieldRef': {'fieldPath': 'metadata.name'}}},
                    {'name': 'WARDEN_TARGET_CONTAINER_NAME', 'value': 'app'}, {'name': 'WARDEN_INTENT_POLL_INTERVAL_SECONDS', 'value': '2'},
                    {'name': 'WARDEN_GC_TIMEOUT_SECONDS', 'value': '15'}, {'name': 'WARDEN_RESIZE_TIMEOUT_SECONDS', 'value': '25'}],
            'volumeMounts': [agent_mount, {'name': 'host-cgroup', 'mountPath': '/host-cgroup', 'readOnly': True}],
            'readinessProbe': {'httpGet': {'path': '/readyz', 'port': 8080}, 'periodSeconds': 3},
            'resources': {'requests': {'memory': '64Mi', 'cpu': '25m'}, 'limits': {'memory': '256Mi'}}}],
        'containers': [{'name': 'app', 'image': args.agent_image, 'imagePullPolicy': 'IfNotPresent',
            'securityContext': {'runAsUser': 10001, 'runAsGroup': 10001, 'runAsNonRoot': True},
            'command': ['java', '-XX:+UseZGC', '-XX:ZUncommitDelay=1', '-Xmx768m', '-Xms16m', '-XX:SoftMaxHeapSize=64m',
                '-Dcom.sun.management.jmxremote.port=9999', '-Dcom.sun.management.jmxremote.rmi.port=9999',
                '-Dcom.sun.management.jmxremote.host=127.0.0.1', '-Dcom.sun.management.jmxremote.authenticate=false',
                '-Dcom.sun.management.jmxremote.ssl=false', '-Djava.rmi.server.hostname=127.0.0.1',
                '-cp', '/app/warden-agent.jar', 'io.github.baokhang83.mnemo.warden.agent.harness.BoostIndexDemo'],
            'ports': [{'containerPort': 8089}], 'resources': {'requests': {'memory': '128Mi', 'cpu': '100m'}, 'limits': {'memory': '320Mi'}},
            'resizePolicy': [{'resourceName': 'memory', 'restartPolicy': 'NotRequired'}]}]}}
    create(pod)
    create({'apiVersion': 'v1', 'kind': 'Service', 'metadata': {'name': 'worker', 'namespace': ns},
        'spec': {'selector': {'app': 'worker'}, 'ports': [{'name': 'http', 'port': 8089, 'targetPort': 8089}]}})
    k('wait', '--for=condition=Ready', 'pod/worker', '-n', ns, '--timeout=180s')
    with socket.socket() as sock:
        sock.bind(('127.0.0.1', 0)); local_port = sock.getsockname()[1]
    forward = subprocess.Popen(base + ['port-forward', '-n', ns, 'pod/worker', str(local_port) + ':8089'],
                               stdout=subprocess.DEVNULL, stderr=subprocess.DEVNULL)
    wait('demo HTTP endpoint', lambda: app_status(), timeout=60)
    report['cluster'] = json.loads(k('version', '-o', 'json'))['serverVersion']
    report['node'] = json.loads(k('get', 'nodes', '-o', 'json'))['items'][0]['status']['nodeInfo']
    baseline_before = app_status()
    http('/rebuild', 'POST')
    wait('unboosted baseline rebuild', lambda: app_status()['preparedRuns'] > baseline_before['preparedRuns'], timeout=60)
    wait('unboosted scan work', lambda: app_status()['scanPasses'] > baseline_before['scanPasses'], timeout=60)
    baseline_queries = sample_queries()
    finish_work()
    report['baseline'] = {'allocation': memory(), 'workload': app_status(), 'queryLatencyMillis': baseline_queries,
                          'sidecarCgroup': sidecar_usage(),
                          'description': 'unboosted control rebuild'}
    persist()

    def success_expiry():
        before = memory(); started = time.monotonic()
        create_request('expiry')
        grant = wait('grant', lambda: phase('expiry', {'Granted'}), timeout=180)
        acquisition = round(time.monotonic() - started, 3)
        granted = memory(); granted_workload = app_status(); granted_sidecar = sidecar_usage()
        if granted_workload['softMaxBytes'] != 384 * 1024**2 or granted_workload['rssBytes'] <= 0:
            raise AssertionError('actual JVM heap target or RSS could not be verified')
        if granted['podUid'] != before['podUid'] or granted['containerId'] != before['containerId']:
            raise AssertionError('target identity changed before grant')
        previous_status = app_status()
        http('/rebuild', 'POST')
        wait('index rebuilt', lambda: app_status()['preparedRuns'] > previous_status['preparedRuns'], timeout=60)
        wait('active index scanning', lambda: app_status()['scanPasses'] > previous_status['scanPasses'], timeout=60)
        # A competing request must be rejected while this Pod remains claimed.
        create_request('competitor')
        competitor = wait('competing request rejection', lambda: phase('competitor', {'Rejected'}), timeout=90)
        # Crossed resource profiles and an emergency signal must never erase the active floor.
        patch('wardenpolicy', 'policy', {'spec': {'schedule': [{'cron': '* * * * *', 'profile': 'crossed'}]}})
        crossed = wait('crossed scheduled allocation', lambda: (m if (m := memory())['resources'].get('requests', {}).get('memory') == '300Mi' else None), timeout=180)
        if crossed['resources'].get('limits', {}).get('memory') != '640Mi':
            raise AssertionError('crossed schedule dropped the admitted limit floor')
        http('/metric/high', 'POST')
        patch('wardenpolicy', 'policy', {'spec': {'guardrail': {'metric': 'vector(99)', 'emergencyGrowAbove': '50'}}})
        emergency = wait('emergency allocation', lambda: (m if (m := memory())['resources'].get('limits', {}).get('memory') == '704Mi' else None), timeout=180)
        if emergency['resources'].get('requests', {}).get('memory') != '300Mi':
            raise AssertionError('emergency dropped the crossed scheduled request')
        http('/metric/low', 'POST')
        patch('wardenpolicy', 'policy', {'spec': {'guardrail': None, 'schedule': [{'cron': '* * * * *', 'profile': 'normal'}]}})
        wait('active floor after schedule recovery', lambda: (m if (m := memory())['resources'].get('requests', {}).get('memory') == '256Mi'
            and m['resources'].get('limits', {}).get('memory') == '640Mi' else None), timeout=180)
        query_samples = sample_queries()
        # Restart both control components while the operation is active. Neither may create
        # a second grant or change the bound application instance.
        controller_before = get('deployment', release + '-warden-controller', control)['metadata']['generation']
        k('rollout', 'restart', 'deployment/' + release + '-warden-controller', '-n', control)
        k('rollout', 'status', 'deployment/' + release + '-warden-controller', '-n', control, '--timeout=120s')
        prior_agent_restarts = next(c for c in get('pod', 'worker')['status']['initContainerStatuses'] if c['name'] == 'warden')['restartCount']
        k('exec', 'worker', '-n', ns, '-c', 'warden', '--', 'pkill', '-TERM', '-f', '[j]ava -jar /app/warden-agent.jar', allow_failure=True)
        wait('agent restart', lambda: next(c for c in get('pod', 'worker')['status']['initContainerStatuses'] if c['name'] == 'warden')['restartCount'] > prior_agent_restarts, timeout=90)
        k('wait', '--for=condition=Ready', 'pod/worker', '-n', ns, '--timeout=120s')
        if memory()['containerId'] != before['containerId'] or not app_status()['running']:
            raise AssertionError('restarting controller/agent changed the app or stopped the work')
        expired = wait('expiry with work running', lambda: phase('expiry', {'Expired'}), timeout=120)
        if not app_status()['running']: raise AssertionError('work did not remain active through expiry')
        held = memory()
        if held['resources'] != granted['resources']: raise AssertionError('expiry changed allocation while work was active')
        finish_work(); work = app_status(); complete('expiry')
        released = wait('release', lambda: phase('expiry', {'Completed'}), timeout=240)
        wait('claim cleanup', lambda: 'warden.mnemo.io/boost-claim' not in get('pod', 'worker')['metadata'].get('annotations', {}))
        after = memory()
        if after['podUid'] != before['podUid'] or after['containerId'] != before['containerId']:
            raise AssertionError('pod or app restarted during successful boost')
        if app_status()['softMaxBytes'] != 64 * 1024**2:
            raise AssertionError('JVM soft maximum did not return to the normal target')
        return {'acquisitionSeconds': acquisition, 'controllerGenerationBeforeRestart': controller_before,
                'agentRestartsBefore': prior_agent_restarts, 'before': before, 'grant': grant,
                'grantedAllocation': granted, 'grantedWorkload': granted_workload, 'grantedSidecarCgroup': granted_sidecar, 'competitor': competitor,
                'crossedAllocation': crossed, 'emergencyAllocation': emergency, 'expired': expired, 'heldAllocation': held, 'workload': work,
                'queryLatencyMillis': query_samples, 'completed': released, 'after': after, 'afterWorkload': app_status(),
                'afterSidecarCgroup': sidecar_usage()}
    run_scenario('success-and-expiry', success_expiry)

    def restart_application():
        before = memory()
        create_request('restarted')
        granted = wait('restart-scenario grant', lambda: phase('restarted', {'Granted'}), timeout=180)
        previous = app_status()['preparedRuns']
        http('/rebuild', 'POST')
        wait('restart-scenario work', lambda: app_status()['preparedRuns'] > previous, timeout=60)
        k('exec', 'worker', '-n', ns, '-c', 'app', '--', 'pkill', '-TERM', '-f', '[j]ava.*BoostIndexDemo', allow_failure=True)
        def replacement():
            pod = get('pod', 'worker')
            for item in pod.get('status', {}).get('containerStatuses', []):
                if item['name'] == 'app' and item.get('containerID') and item['containerID'] != before['containerId']:
                    return item['containerID']
            return None
        replacement_id = wait('replacement app container', replacement, timeout=120)
        orphaned = wait('old grant orphaned', lambda: phase('restarted', {'Orphaned'}), timeout=120)
        wait('orphaned claim cleanup', lambda: 'warden.mnemo.io/boost-claim' not in get('pod', 'worker')['metadata'].get('annotations', {}), timeout=120)
        k('wait', '--for=condition=Ready', 'pod/worker', '-n', ns, '--timeout=120s')
        wait('restarted demo endpoint', ensure_endpoint, timeout=60)
        if get('pod', 'worker')['metadata']['uid'] != before['podUid']:
            raise AssertionError('Pod was replaced instead of the application container')
        if replacement_id == granted['containerId']: raise AssertionError('old grant matches replacement container')
        return {'before': before, 'oldGrant': granted, 'replacementContainerId': replacement_id,
                'orphaned': orphaned, 'after': memory()}
    run_scenario('application-restart-invalidates-grant', restart_application)

    def blocked_release():
        before = memory()
        create_request('blocked')
        grant = wait('blocked-scenario grant', lambda: phase('blocked', {'Granted'}), timeout=180)
        allocated = memory()
        http('/retain', 'POST')
        load = app_status()
        if load['retainedMiB'] != 320: raise AssertionError('test live set was not retained')
        complete('blocked')
        def blocked():
            status = get('wardenboost', 'blocked').get('status', {})
            if status.get('phase') == 'Completed': raise AssertionError('release completed despite retained live set')
            return status if any(c.get('reason') == 'ReleaseBlocked' for c in status.get('conditions', [])) else None
        state = wait('failed shrink acknowledgement', blocked, timeout=240)
        held = memory()
        if held['resources'] != allocated['resources']: raise AssertionError('failed shrink reduced applied allocation')
        http('/clear', 'POST')
        completed = wait('recovery after release block', lambda: phase('blocked', {'Completed'}), timeout=240)
        wait('blocked claim cleanup', lambda: 'warden.mnemo.io/boost-claim' not in get('pod', 'worker')['metadata'].get('annotations', {}))
        after = memory()
        if after['podUid'] != before['podUid'] or after['containerId'] != before['containerId']:
            raise AssertionError('target restarted during release verification')
        return {'before': before, 'grant': grant, 'allocated': allocated, 'retainedWorkload': load,
                'blocked': state, 'held': held, 'completed': completed, 'after': after, 'afterWorkload': app_status()}
    run_scenario('failed-shrink-and-recovery', blocked_release)

    def denied_growth():
        # A real admission quota rejects the in-place resource increase, without exhausting
        # the node or depending on a particular scheduler's deferral timing.
        create({'apiVersion': 'v1', 'kind': 'ResourceQuota', 'metadata': {'name': 'deny-growth', 'namespace': ns},
                'spec': {'hard': {'requests.memory': '200Mi', 'limits.memory': '1Gi'}}})
        wait('quota accounting', lambda: get('resourcequota', 'deny-growth').get('status', {}).get('hard'), timeout=60)
        patch('wardenpolicy', 'policy', {'spec': {'boosts': {'rebuild': {'acquisitionTimeout': '25s'}}}})
        before = memory(); work_before = app_status()
        create_request('denied', 'rebuild')
        observed = []
        def failed():
            status = get('wardenboost', 'denied').get('status', {})
            phase_name = status.get('phase')
            if not observed or observed[-1] != phase_name: observed.append(phase_name)
            if phase_name == 'Granted': raise AssertionError('denied growth granted permission')
            return status if phase_name in ('Failed', 'Releasing', 'Completed') else None
        state = wait('denied acquisition', failed, timeout=120)
        def quota_log():
            log = k('logs', 'worker', '-n', ns, '-c', 'warden', '--tail=150')
            return log if 'exceeded quota' in log.lower() else None
        log = wait('resource-quota denial in agent log', quota_log, timeout=90)
        after = memory()
        if after['resources'] != before['resources']: raise AssertionError('quota-denied growth changed applied allocation')
        if app_status()['runs'] != work_before['runs'] or app_status()['running']:
            raise AssertionError('work started during denied acquisition')
        cleaned = wait('cleanup after denied acquisition', lambda: phase('denied', {'Completed'}), timeout=240)
        wait('denied claim cleanup', lambda: 'warden.mnemo.io/boost-claim' not in get('pod', 'worker')['metadata'].get('annotations', {}))
        return {'before': before, 'after': after, 'observedPhases': observed, 'terminalObservation': state,
                'cleaned': cleaned, 'quota': get('resourcequota', 'deny-growth')['status'],
                'agentLogTail': log, 'workload': app_status()}
    run_scenario('denied-growth', denied_growth)

    def failed_jvm_preparation():
        global created_fault
        # This separate namespace intentionally uses an isolated test agent running in its own
        # ZGC JVM, with the production CorrelatedResize/PodResizeClient and a faulting heap adapter.
        # B6's security matrix and the real sidecar are exercised separately above.
        k('create', 'namespace', fault_ns); created_fault = True
        k('create', 'serviceaccount', 'fault-agent', '-n', fault_ns)
        create({'apiVersion': 'rbac.authorization.k8s.io/v1', 'kind': 'Role',
            'metadata': {'name': 'fault-agent', 'namespace': fault_ns}, 'rules': [
                {'apiGroups': [''], 'resources': ['pods'], 'resourceNames': ['fault'], 'verbs': ['get', 'patch']},
                {'apiGroups': [''], 'resources': ['pods/resize'], 'resourceNames': ['fault'], 'verbs': ['patch']}]})
        create({'apiVersion': 'rbac.authorization.k8s.io/v1', 'kind': 'RoleBinding',
            'metadata': {'name': 'fault-agent', 'namespace': fault_ns},
            'subjects': [{'kind': 'ServiceAccount', 'name': 'fault-agent', 'namespace': fault_ns}],
            'roleRef': {'apiGroup': 'rbac.authorization.k8s.io', 'kind': 'Role', 'name': 'fault-agent'}})
        fault_policy = {'apiVersion': 'warden.mnemo.io/v1alpha1', 'kind': 'WardenPolicy',
            'metadata': {'name': 'fault-policy', 'namespace': fault_ns}, 'spec': {'timezone': 'UTC',
            'targetRef': {'kind': 'Pod', 'name': 'fault'},
            'profiles': {'normal': {'request': '128Mi', 'limit': '320Mi', 'heapTarget': '64Mi'},
                         'boosted': {'request': '256Mi', 'limit': '512Mi', 'heapTarget': '256Mi'}},
            'schedule': [{'cron': '* * * * *', 'profile': 'normal'}],
            'boosts': {'rebuild': {'profile': 'boosted', 'heapTarget': '256Mi',
                                   'acquisitionTimeout': '60s', 'maxDuration': '2m'}}}}
        create(fault_policy)
        policy_id = get('wardenpolicy', 'fault-policy', fault_ns)['metadata']['uid']
        create({'apiVersion': 'v1', 'kind': 'Pod', 'metadata': {'name': 'fault', 'namespace': fault_ns,
                 'labels': {'warden.mnemo.io/policy-uid': policy_id}}, 'spec': {'serviceAccountName': 'fault-agent',
            'containers': [{'name': 'app', 'image': args.agent_image, 'imagePullPolicy': 'IfNotPresent',
                'env': [{'name': 'WARDEN_POD_NAME', 'valueFrom': {'fieldRef': {'fieldPath': 'metadata.name'}}}],
                'command': ['java', '-XX:+UseZGC', '-Xmx384m', '-Xms16m', '-XX:SoftMaxHeapSize=64m',
                    '-cp', '/app/warden-agent.jar', 'io.github.baokhang83.mnemo.warden.agent.harness.BoostFaultDemo'],
                'resources': {'requests': {'memory': '128Mi'}, 'limits': {'memory': '320Mi'}},
                'resizePolicy': [{'resourceName': 'memory', 'restartPolicy': 'NotRequired'}]}]}})
        k('wait', '--for=condition=Ready', 'pod/fault', '-n', fault_ns, '--timeout=120s')
        fault_uid = get('pod', 'fault', fault_ns)['metadata']['uid']
        create({'apiVersion': 'warden.mnemo.io/v1alpha1', 'kind': 'WardenBoost',
            'metadata': {'name': 'fault', 'namespace': fault_ns}, 'spec': {'policyRef': 'fault-policy',
            'podRef': {'name': 'fault', 'uid': fault_uid}, 'container': 'app', 'boost': 'rebuild', 'completionRequested': False}})
        observed = []
        def failure_after_growth():
            status = get('wardenboost', 'fault', fault_ns).get('status', {})
            if status.get('phase') == 'Granted' or status.get('grantedAt'):
                raise AssertionError('JVM preparation failure granted permission')
            if not observed or observed[-1] != status.get('phase'): observed.append(status.get('phase'))
            logs = k('logs', 'fault', '-n', fault_ns, '--tail=100')
            return {'status': status, 'log': logs, 'pod': get('pod', 'fault', fault_ns)} if 'INJECTED_AFTER_GROWTH' in logs else None
        evidence = wait('injected JVM failure after applied growth', failure_after_growth, timeout=150)
        resources = evidence['pod']['status']['containerStatuses'][0].get('resources', {})
        if resources.get('limits', {}).get('memory') != '512Mi':
            raise AssertionError('fault did not follow applied Kubernetes growth')
        def cleaned():
            status = get('wardenboost', 'fault', fault_ns).get('status', {})
            if status.get('phase') == 'Granted' or status.get('grantedAt'):
                raise AssertionError('faulted acquisition was granted')
            return status if status.get('phase') == 'Completed' else None
        complete_status = wait('faulted acquisition cleanup', cleaned, timeout=240)
        final_pod = get('pod', 'fault', fault_ns)
        if final_pod['status']['containerStatuses'][0]['resources']['limits']['memory'] != '320Mi':
            raise AssertionError('faulted acquisition did not reclaim allocation')
        return {'injectedAfterRealGrowth': evidence, 'observedPhases': observed,
                'completed': complete_status, 'finalPod': final_pod,
                'scope': 'production correlated sequence with test-only heap fault, separate from B6 security test'}
    run_scenario('jvm-preparation-fails-after-growth', failed_jvm_preparation)

    def policy_and_target_deletion():
        k('delete', 'resourcequota', 'deny-growth', '-n', ns, '--wait=true')
        patch('wardenpolicy', 'policy', {'spec': {'boosts': {'rebuild': {'acquisitionTimeout': '2m'}}}})
        before = memory()
        create_request('policy-deleted')
        grant = wait('policy-deletion grant', lambda: phase('policy-deleted', {'Granted'}), timeout=180)
        previous = app_status()['preparedRuns']
        http('/rebuild', 'POST')
        wait('work before policy deletion', lambda: app_status()['preparedRuns'] > previous, timeout=60)
        k('delete', 'wardenpolicy', 'policy', '-n', ns, '--wait=true')
        # Allow several Boost reconciliation intervals; no policy can authorize a release.
        time.sleep(25)
        held = memory()
        if held['resources'].get('requests', {}).get('memory') != '256Mi' or held['resources'].get('limits', {}).get('memory') != '640Mi':
            raise AssertionError('policy deletion lost the admitted floor')
        if not app_status()['running']: raise AssertionError('work stopped during policy deletion')
        finish_work(); complete('policy-deleted')
        releasing = wait('release held without policy', lambda: phase('policy-deleted', {'Releasing'}), timeout=90)
        time.sleep(15)
        if not phase('policy-deleted', {'Releasing'}): raise AssertionError('release completed without the bound policy')
        before_delete = get('pod', 'worker')
        k('delete', 'pod', 'worker', '-n', ns, '--wait=false')
        orphaned = wait('target deletion orphaning', lambda: phase('policy-deleted', {'Orphaned'}), timeout=120)
        return {'before': before, 'grant': grant, 'held': held, 'releasing': releasing, 'orphaned': orphaned,
                'podBeforeDeletion': before_delete}
    run_scenario('policy-and-target-deletion', policy_and_target_deletion)

    final_pod = report['scenarios']['policy-and-target-deletion']['podBeforeDeletion']
    containers = final_pod.get('status', {}).get('containerStatuses', []) + final_pod.get('status', {}).get('initContainerStatuses', [])
    report['finalObservation'] = {'podUid': final_pod['metadata']['uid'], 'containers': [
        {'name': c['name'], 'restartCount': c.get('restartCount', 0), 'lastState': c.get('lastState', {})} for c in containers],
        'events': json.loads(k('get', 'events', '-n', ns, '-o', 'json'))['items']}
    if any(c.get('lastState', {}).get('terminated', {}).get('reason') == 'OOMKilled' for c in containers):
        raise AssertionError('observed an OOMKilled container')
    report['result'] = 'PASS'
except Exception as exc:
    report['result'] = 'FAIL'; report['error'] = str(exc)
    if created_ns:
        report['diagnostics'] = {'pod': k('get', 'pod', 'worker', '-n', ns, '-o', 'yaml', allow_failure=True),
            'boosts': k('get', 'wardenboosts', '-n', ns, '-o', 'yaml', allow_failure=True),
            'agentLog': k('logs', 'worker', '-n', ns, '-c', 'warden', '--tail=100', allow_failure=True),
            'controllerLog': k('logs', 'deployment/' + release + '-warden-controller', '-n', control, '--tail=100', allow_failure=True)}
    if created_fault:
        report['faultDiagnostics'] = {'pod': k('get', 'pod', 'fault', '-n', fault_ns, '-o', 'yaml', allow_failure=True),
            'boost': k('get', 'wardenboost', 'fault', '-n', fault_ns, '-o', 'yaml', allow_failure=True),
            'log': k('logs', 'fault', '-n', fault_ns, '--tail=100', allow_failure=True)}
    print('FAIL: ' + str(exc), file=sys.stderr)
finally:
    report['finishedAt'] = datetime.datetime.now(datetime.timezone.utc).isoformat()
    persist()
    if forward is not None: forward.terminate(); forward.wait(timeout=10)
    if credentials_dir is not None: credentials_dir.cleanup()
    if created_fault:
        raw_fault = k('get', 'wardenboosts', '-n', fault_ns, '-o', 'json', allow_failure=True)
        if raw_fault:
            for item in json.loads(raw_fault).get('items', []):
                name = item['metadata']['name']
                k('delete', 'wardenboost', name, '-n', fault_ns, '--wait=false', '--ignore-not-found', allow_failure=True)
                k('patch', 'wardenboost', name, '-n', fault_ns, '--type=merge', '-p', '{"metadata":{"finalizers":[]}}', allow_failure=True)
        k('delete', 'namespace', fault_ns, '--wait=false', allow_failure=True)
    if created_ns:
        raw = k('get', 'wardenboosts', '-n', ns, '-o', 'json', allow_failure=True)
        if raw:
            for item in json.loads(raw).get('items', []):
                name = item['metadata']['name']
                k('delete', 'wardenboost', name, '-n', ns, '--wait=false', '--ignore-not-found', allow_failure=True)
                k('patch', 'wardenboost', name, '-n', ns, '--type=merge', '-p', '{"metadata":{"finalizers":[]}}', allow_failure=True)
        k('delete', 'namespace', ns, '--wait=false', allow_failure=True)
    if installed:
        run(['helm', 'uninstall', release, '--kube-context', args.context, '--namespace', control], allow_failure=True)
    if created_control: k('delete', 'namespace', control, '--wait=false', allow_failure=True)
if report['result'] != 'PASS': raise SystemExit(1)
