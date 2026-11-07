#!/usr/bin/env python3
"""Verify opt-in Boost admission and RBAC on a disposable Kubernetes cluster."""
import argparse
import base64
import datetime
import json
import subprocess
import tempfile
import time
from pathlib import Path

parser = argparse.ArgumentParser(description=__doc__)
parser.add_argument('--context', required=True)
parser.add_argument('--controller-image', help='loaded controller image repository:tag for full lifecycle and metrics')
args = parser.parse_args()
root = Path(__file__).resolve().parent.parent
ns = 'warden-b6-security-probe'
other_ns = 'warden-b6-security-other'
control = 'warden-b6-security-control'
release = 'b6probe'
base = ['kubectl', '--context', args.context]

def run(cmd, *, data=None, deny=None):
    result = subprocess.run(cmd, input=json.dumps(data) if data is not None else None,
                            capture_output=True, text=True)
    if deny is not None:
        if result.returncode == 0 or deny not in result.stderr:
            raise AssertionError(f'Expected denial {deny}: {result.stderr}')
    elif result.returncode:
        raise AssertionError(result.stderr)
    return result.stdout

def k(*arguments, data=None, config=None, deny=None):
    return run((base if config is None else ['kubectl', '--kubeconfig', str(config)]) + list(arguments), data=data, deny=deny)

def create(obj):
    return k('create', '-f', '-', data=obj)

def patch(kind, name, body, config=None, deny=None, suffix=()):
    return k('patch', kind, name, '-n', ns, '--type=merge', '-p', json.dumps(body), *suffix, config=config, deny=deny)

legacy = run(['helm', 'template', 'legacy', str(root / 'charts/warden'), '--set', 'boost=null'])
if 'ValidatingAdmissionPolicy' in legacy or 'boost-metrics' in legacy or 'WARDEN_BOOST_' in legacy:
    raise AssertionError('default/legacy chart unexpectedly enables Boost security or observability')
run(['helm', 'template', 'invalid', str(root / 'charts/warden'), '--set', 'boost.security.enabled=true',
     '--set', 'boost.security.agentServiceAccountName=BadName'], deny='DNS labels')

k('create', 'namespace', control)
try:
    install = ['helm', 'install', release, str(root / 'charts/warden'), '--kube-context', args.context,
               '--namespace', control, '--set', 'boost.security.enabled=true']
    if args.controller_image:
        repository, tag = args.controller_image.rsplit(':', 1)
        install += ['--set', 'boost.observability.enabled=true', '--set', 'controller.image.repository=' + repository,
                    '--set', 'controller.image.tag=' + tag, '--set', 'controller.image.pullPolicy=IfNotPresent']
    run(install)
    if args.controller_image:
        k('rollout', 'status', 'deployment/' + release + '-warden-controller', '-n', control, '--timeout=120s')
    k('create', 'namespace', ns)
    other_created = False
    try:
        k('create', 'namespace', other_ns)
        other_created = True
        k('label', 'namespace', ns, 'warden.mnemo.io/boost-enabled=true')
        k('label', 'namespace', other_ns, 'warden.mnemo.io/boost-enabled=true')
        k('create', 'rolebinding', 'foreign-agent', '-n', other_ns,
          '--clusterrole=' + release + '-warden-boost-agent', '--serviceaccount=' + ns + ':warden-boost-agent')
        create({'apiVersion': 'rbac.authorization.k8s.io/v1', 'kind': 'Role', 'metadata': {'name': 'resize-get', 'namespace': other_ns},
                'rules': [{'apiGroups': [''], 'resources': ['pods/resize'], 'verbs': ['get']}]})
        k('create', 'rolebinding', 'foreign-resize-get', '-n', other_ns, '--role=resize-get',
          '--serviceaccount=' + ns + ':warden-boost-agent')
        create({'apiVersion': 'v1', 'kind': 'Pod', 'metadata': {'name': 'worker-a', 'namespace': other_ns},
                'spec': {'containers': [{'name': 'app', 'image': 'registry.k8s.io/pause:3.10.1',
                    'resources': {'requests': {'memory': '64Mi'}, 'limits': {'memory': '128Mi'}}}]}})
        k('wait', '--for=condition=Ready', 'pod/worker-a', '-n', other_ns, '--timeout=120s')
        for name in ['warden-boost-agent', 'warden-boost-operator', 'intruder']:
            k('create', 'serviceaccount', name, '-n', ns)
        for role in ['agent', 'operator']:
            k('create', 'rolebinding', 'boost-' + role, '-n', ns,
              '--clusterrole=' + release + '-warden-boost-' + role,
              '--serviceaccount=' + ns + ':warden-boost-' + role)
        # Deliberately over-grant request writes so denial proves admission, not RBAC.
        create({'apiVersion': 'rbac.authorization.k8s.io/v1', 'kind': 'Role', 'metadata': {'name': 'overgrant', 'namespace': ns},
                'rules': [{'apiGroups': ['warden.mnemo.io'], 'resources': ['wardenboosts', 'wardenboosts/status'], 'verbs': ['create', 'get', 'patch', 'delete']}, {'apiGroups': [''], 'resources': ['pods/resize'], 'verbs': ['get']}, {'apiGroups': [''], 'resources': ['pods'], 'verbs': ['get', 'patch']}]})
        for name in ['warden-boost-agent', 'warden-boost-operator', 'intruder']:
            k('create', 'rolebinding', 'overgrant-' + name, '-n', ns, '--role=overgrant',
              '--serviceaccount=' + ns + ':' + name)
        # Existing scheduled-only Pods in a selected namespace retain ordinary Pod behavior.
        create({'apiVersion': 'v1', 'kind': 'Pod', 'metadata': {'name': 'legacy', 'namespace': ns},
                'spec': {'containers': [{'name': 'app', 'image': 'registry.k8s.io/pause:3.10.1'}]}})
        patch('pod', 'legacy', {'metadata': {'annotations': {'legacy-schedule': 'ok'}}})
        bad = {'apiVersion': 'v1', 'kind': 'Pod', 'metadata': {'name': 'bad-token', 'namespace': ns},
               'spec': {'serviceAccountName': 'warden-boost-agent', 'containers': [{'name': 'app', 'image': 'registry.k8s.io/pause:3.10.1'}]}}
        k('create', '-f', '-', data=bad, deny='disable automatic service-account token')
        token_volume = {'name': 'agent-token', 'projected': {'sources': [{'serviceAccountToken': {'path': 'token'}}]}}
        token_mount = {'name': 'agent-token', 'mountPath': '/var/run/secrets/kubernetes.io/serviceaccount', 'readOnly': True}
        for name in ['worker-a', 'worker-b', 'worker-c']:
            agent_pod = name != 'worker-c'
            app = {'name': 'app', 'image': 'registry.k8s.io/pause:3.10.1', 'resources': {'requests': {'memory': '64Mi'}, 'limits': {'memory': '128Mi'}}}
            spec = {'serviceAccountName': 'warden-boost-agent' if agent_pod else 'intruder', 'containers': [app]}
            if agent_pod:
                spec['automountServiceAccountToken'] = False
                spec['volumes'] = [token_volume]
                spec['initContainers'] = [{'name': 'warden', 'image': 'registry.k8s.io/pause:3.10.1',
                                           'restartPolicy': 'Always', 'volumeMounts': [token_mount]}]
            create({'apiVersion': 'v1', 'kind': 'Pod', 'metadata': {'name': name, 'namespace': ns, 'annotations': {'test': 'initial'}}, 'spec': spec})
        bad['metadata']['name'] = 'shared-token'
        bad['spec']['automountServiceAccountToken'] = False
        bad['spec']['volumes'] = [token_volume]
        bad['spec']['initContainers'] = [{'name': 'warden', 'image': 'registry.k8s.io/pause:3.10.1', 'volumeMounts': [token_mount]}]
        bad['spec']['containers'][0]['volumeMounts'] = [token_mount]
        k('create', '-f', '-', data=bad, deny='mounted only in the Warden init container')
        k('wait', '--for=condition=Ready', 'pod', '--all', '-n', ns, '--timeout=120s')
        pod = json.loads(k('get', 'pod', 'worker-a', '-n', ns, '-o', 'json'))
        uid = pod['metadata']['uid']
        for suffix in ['boost-agent-token', 'boost-agent-pod', 'boost-ack-owner', 'boost-agent-resize', 'boost-request-guard']:
            name = release + '-warden-' + suffix
            for _ in range(60):
                obj = json.loads(k('get', 'validatingadmissionpolicy', name, '-o', 'json'))
                if obj.get('status', {}).get('observedGeneration') == obj['metadata']['generation']:
                    warnings = obj.get('status', {}).get('typeChecking', {}).get('expressionWarnings', [])
                    if warnings: raise AssertionError(f'{name}: {warnings}')
                    break
                time.sleep(1)
            else: raise AssertionError(f'{name}: no CEL type check')
        with tempfile.TemporaryDirectory(prefix='warden-b6-') as tmp:
            tmp = Path(tmp)
            baseconfig = json.loads(k('config', 'view', '--raw', '--flatten', '--minify', '-o', 'json'))
            def credential(name, namespace, bound=False, pod_name='worker-a', pod_uid=None):
                arguments = ['create', 'token', name, '-n', namespace]
                if bound: arguments += ['--bound-object-kind=Pod', '--bound-object-name=' + pod_name, '--bound-object-uid=' + (pod_uid or uid)]
                token = k(*arguments).strip()
                config = json.loads(json.dumps(baseconfig))
                config['users'] = [{'name': 'test', 'user': {'token': token}}]
                config['contexts'][0]['context']['user'] = 'test'
                path = tmp / (namespace + '-' + name + ('-bound' if bound else '-unbound') + '.json')
                path.write_text(json.dumps(config)); path.chmod(0o600)
                return path
            agent = credential('warden-boost-agent', ns, True)
            unbound = credential('warden-boost-agent', ns)
            operator = credential('warden-boost-operator', ns)
            other_uid = json.loads(k('get', 'pod', 'worker-c', '-n', ns, '-o', 'json'))['metadata']['uid']
            intruder = credential('intruder', ns, True, 'worker-c', other_uid)
            controller = credential(release + '-warden-controller', control)
            ack = {'metadata': {'annotations': {'warden.mnemo.io/resize-ack': 'ack'}}}
            resize = {'spec': {'containers': [{'name': 'app', 'resources': {'requests': {'memory': '64Mi'},
                                                                            'limits': {'memory': '128Mi'}}}]}}
            patch('pod', 'worker-a', resize, config=agent, suffix=('--subresource=resize',))
            patch('pod', 'worker-b', resize, config=agent, suffix=('--subresource=resize',), deny='token-bound Pod')
            k('patch', 'pod', 'worker-a', '-n', other_ns, '--type=merge', '-p', json.dumps(resize),
              '--subresource=resize', config=agent, deny='token-bound Pod')
            k('patch', 'pod', 'worker-a', '-n', other_ns, '--type=merge',
              '-p', json.dumps({'metadata': {'annotations': {'unrelated': 'forged'}}}), config=agent, deny='token-bound Pod')
            patch('pod', 'worker-a', ack, config=agent)
            patch('pod', 'worker-b', ack, config=agent, deny='token-bound Pod')
            patch('pod', 'worker-a', {'metadata': {'labels': {'forged': 'true'}}}, config=agent, deny='only the bounded acknowledgement')
            patch('pod', 'worker-a', {'metadata': {'annotations': {'warden.mnemo.io/resize-ack': 'forged-by-intruder'}}},
                  config=intruder, deny='Only the Boost agent may write acknowledgements')
            patch('pod', 'worker-a', ack, config=unbound, deny='token-bound Pod')
            patch('pod', 'worker-a', {'metadata': {'annotations': {'warden.mnemo.io/resize-ack': 'forged'}}}, config=controller,
                  deny='controller may remove')
            patch('pod', 'worker-a', {'metadata': {'annotations': {'warden.mnemo.io/resize-ack': None}}}, config=controller,
                  deny='controller may remove')
            patch('pod', 'worker-a', {'metadata': {'annotations': {'warden.mnemo.io/boost-claim': 'claim',
                  'warden.mnemo.io/resize-intent': 'intent'}}})
            patch('pod', 'worker-a', {'metadata': {'annotations': {'warden.mnemo.io/resize-ack': None,
                  'warden.mnemo.io/boost-claim': None, 'warden.mnemo.io/resize-intent': None}}}, config=controller)
            request = {'apiVersion': 'warden.mnemo.io/v1alpha1', 'kind': 'WardenBoost',
                       'metadata': {'name': 'request', 'namespace': ns},
                       'spec': {'policyRef': 'policy', 'podRef': {'name': 'worker-b', 'uid': uid},
                                'container': 'app', 'boost': 'job', 'completionRequested': False}}
            k('create', '-f', '-', data=request, config=agent, deny='cannot create or change Boost')
            k('create', '-f', '-', data=request, config=intruder, deny='may target only their own Pod identity')
            request['spec']['podRef']['name'] = 'worker-a'
            k('create', '-f', '-', data=request, config=operator)
            patch('wardenboost', 'request', {'status': {'phase': 'Granted'}}, config=operator,
                  suffix=('--subresource=status',), deny='Only the Boost controller may change status')
            patch('wardenboost', 'request', {'status': {'phase': 'Granted'}}, config=agent,
                  suffix=('--subresource=status',), deny='cannot create or change Boost')
            patch('wardenboost', 'request', {'status': {'phase': 'Granted'}}, config=intruder,
                  suffix=('--subresource=status',), deny='Only the Boost controller may change status')
            patch('wardenboost', 'request', {'metadata': {'finalizers': ['attacker.example/finalizer']}}, config=operator,
                  deny='cannot modify request ownership metadata')
            k('delete', 'wardenboost', 'request', '-n', ns, '--wait=false', config=operator,
              deny='must request completion rather than delete')
            patch('wardenboost', 'request', {'spec': {'completionRequested': True}}, config=operator)
            if args.controller_image:
                # A separate authorized request is admitted under the real controller and policies.
                policy = {'apiVersion': 'warden.mnemo.io/v1alpha1', 'kind': 'WardenPolicy',
                    'metadata': {'name': 'policy', 'namespace': ns}, 'spec': {'timezone': 'UTC',
                    'targetRef': {'kind': 'Pod', 'name': 'worker-a'},
                    'profiles': {'normal': {'request': '64Mi', 'limit': '128Mi', 'heapTarget': '32Mi'}},
                    'schedule': [{'cron': '* * * * *', 'profile': 'normal'}],
                    'boosts': {'job': {'profile': 'normal', 'heapTarget': '32Mi',
                                       'acquisitionTimeout': '3m', 'maxDuration': '5m'}}}}
                create(policy)
                policy_uid = json.loads(k('get', 'wardenpolicy', 'policy', '-n', ns, '-o', 'json'))['metadata']['uid']
                patch('pod', 'worker-a', {'metadata': {'labels': {'warden.mnemo.io/policy-uid': policy_uid}}})
                # Remove the earlier rejected/completed test request before admitting a new one.
                k('delete', 'wardenboost', 'request', '-n', ns, '--wait=true', '--timeout=30s')
                request['metadata']['name'] = 'lifecycle'
                request['spec']['completionRequested'] = False
                k('create', '-f', '-', data=request, config=operator)
                def get(kind, name):
                    return json.loads(k('get', kind, name, '-n', ns, '-o', 'json'))
                def until(predicate, label):
                    for _ in range(120):
                        value = predicate()
                        if value: return value
                        time.sleep(1)
                    raise AssertionError('Timed out waiting for ' + label + ': ' + json.dumps(get('wardenboost', 'lifecycle').get('status', {})))
                def current_intent():
                    return get('pod', 'worker-a')['metadata'].get('annotations', {}).get('warden.mnemo.io/resize-intent')
                def encode(*fields):
                    return 'v1.' + '.'.join(base64.urlsafe_b64encode(str(f).encode()).decode().rstrip('=') for f in fields)
                def acknowledge(intent):
                    fields = [base64.urlsafe_b64decode(f + '=' * (-len(f) % 4)).decode() for f in intent.split('.')[1:]]
                    stamp = datetime.datetime.now(datetime.timezone.utc).isoformat().replace('+00:00', 'Z')
                    value = encode(intent, 'true', 'Applied', fields[5], fields[6], fields[7], stamp)
                    patch('pod', 'worker-a', {'metadata': {'annotations': {'warden.mnemo.io/resize-ack': value}}}, config=agent)
                first = until(current_intent, 'initial intent')
                acknowledge(first)
                until(lambda: get('wardenboost', 'lifecycle').get('status', {}).get('phase') == 'Granted', 'grant')
                patch('wardenboost', 'lifecycle', {'spec': {'completionRequested': True}}, config=operator)
                release_intent = until(lambda: (v if (v := current_intent()) and v != first else None), 'release intent')
                acknowledge(release_intent)
                until(lambda: get('wardenboost', 'lifecycle').get('status', {}).get('phase') == 'Completed', 'completion')
                until(lambda: 'warden.mnemo.io/boost-claim' not in get('pod', 'worker-a')['metadata'].get('annotations', {}), 'claim cleanup')
                def completed_event():
                    items = json.loads(k('get', 'events', '-n', ns, '-o', 'json'))['items']
                    return next((e for e in items if e.get('involvedObject', {}).get('name') == 'lifecycle'
                                 and e.get('reason') == 'BoostCompleted'), None)
                event = until(completed_event, 'completed Boost event')
                if 'requestUid=' not in event.get('message', '') or 'transitionId=' not in event.get('message', ''):
                    raise AssertionError('Event lacks request/transition correlation')
                def scrape():
                    metrics = k('get', '--raw', '/api/v1/namespaces/' + control + '/services/' + release + '-warden-boost-metrics:8081/proxy/metrics')
                    return metrics if ('warden_boost_transitions_total{phase="Granted"} 1' in metrics
                        and 'warden_boost_acquisition_duration_seconds_count 1' in metrics
                        and 'warden_boost_release_duration_seconds_count 1' in metrics) else None
                metrics = until(scrape, 'grant and release metrics')
                if uid in metrics or 'lifecycle' in metrics:
                    raise AssertionError('Metrics leaked request or Pod identifiers')
                print('PASS: packaged controller, operator grant/release, protected cleanup, events, and metrics (synthetic agent evidence)')
            print('PASS: opt-in RBAC/admission, bound agent isolation, controller cleanup, and operator/status protection')
    finally:
        # The namespace and all requests are created by this probe; synthetic work cannot
        # still be running. Clear test finalizers before uninstalling the test controller.
        remaining = json.loads(k('get', 'wardenboosts', '-n', ns, '-o', 'json'))['items']
        for item in remaining:
            if item['metadata'].get('finalizers'):
                k('delete', 'wardenboost', item['metadata']['name'], '-n', ns, '--wait=false', '--ignore-not-found')
                try:
                    patch('wardenboost', item['metadata']['name'], {'metadata': {'finalizers': []}})
                except AssertionError as error:
                    if 'not found' not in str(error).lower(): raise
        k('delete', 'namespace', ns, '--wait=false')
        if other_created:
            k('delete', 'namespace', other_ns, '--wait=false')
finally:
    run(['helm', 'uninstall', release, '--kube-context', args.context, '--namespace', control])
    k('delete', 'namespace', control, '--wait=false')
