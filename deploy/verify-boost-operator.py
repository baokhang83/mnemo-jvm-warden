#!/usr/bin/env python3
"""Check packaged operator admission and deletion protection on a disposable cluster."""
import argparse
import json
from pathlib import Path
import subprocess
import time

parser = argparse.ArgumentParser(description=__doc__)
parser.add_argument('--context', required=True)
parser.add_argument('--expect-unsupported', action='store_true')
args = parser.parse_args()
namespace = 'warden-b5-operator-probe'

def kubectl(*arguments, data=None, check=True):
    result = subprocess.run(['kubectl', '--context', args.context, *arguments],
                            input=json.dumps(data) if data else None, text=True, capture_output=True)
    if check and result.returncode:
        raise RuntimeError(result.stderr)
    return result.stdout

def get(kind, name):
    raw = kubectl('get', kind, name, '-n', namespace, '-o', 'json', check=False)
    return json.loads(raw) if raw else None

def wait_phase(phase, seconds=60):
    end = time.monotonic() + seconds
    while time.monotonic() < end:
        resource = get('wardenboost', 'request')
        if resource and resource.get('status', {}).get('phase') == phase:
            return resource
        time.sleep(1)
    raise AssertionError(f'request did not reach {phase}: {get("wardenboost", "request")}')

kubectl('create', 'namespace', namespace)
try:
    kubectl('create', '-f', '-', data={'apiVersion': 'warden.mnemo.io/v1alpha1', 'kind': 'WardenPolicy',
        'metadata': {'name': 'policy', 'namespace': namespace},
        'spec': {'timezone': 'UTC', 'targetRef': {'kind': 'Pod', 'name': 'worker'},
                 'profiles': {'normal': {'request': '64Mi', 'limit': '256Mi', 'heapTarget': '32Mi'}},
                 'schedule': [{'cron': '* * * * *', 'profile': 'normal'}],
                 'boosts': {'index': {'profile': 'normal', 'heapTarget': '32Mi',
                                      'acquisitionTimeout': '2m', 'maxDuration': '5m'}}}})
    uid = get('wardenpolicy', 'policy')['metadata']['uid']
    kubectl('create', '-f', '-', data={'apiVersion': 'v1', 'kind': 'Pod', 'metadata': {'name': 'worker',
        'namespace': namespace, 'labels': {'warden.mnemo.io/policy-uid': uid}}, 'spec': {'containers': [{'name': 'app',
        'image': 'registry.k8s.io/pause:3.10.1', 'resources': {'requests': {'memory': '64Mi'}, 'limits': {'memory': '256Mi'}}}]}})
    kubectl('wait', '--for=condition=Ready', 'pod/worker', '-n', namespace, '--timeout=120s')
    pod_uid = get('pod', 'worker')['metadata']['uid']
    kubectl('create', '-f', '-', data={'apiVersion': 'warden.mnemo.io/v1alpha1', 'kind': 'WardenBoost',
        'metadata': {'name': 'request', 'namespace': namespace}, 'spec': {'podRef': {'name': 'worker', 'uid': pod_uid},
        'container': 'app', 'policyRef': 'policy', 'boost': 'index', 'completionRequested': False}})
    if args.expect_unsupported:
        pending = wait_phase('Pending')
        if not any(c.get('reason') == 'UnsupportedControllerTopology' for c in pending['status'].get('conditions', [])):
            raise AssertionError('unsupported topology reason was not reported')
        if get('pod', 'worker').get('metadata', {}).get('annotations', {}).get('warden.mnemo.io/boost-claim'):
            raise AssertionError('unsupported topology acquired a claim')
        print('PASS: scaled controller reports unsupported topology and does not admit')
    else:
        preparing = wait_phase('Preparing')
    if not args.expect_unsupported and not preparing['metadata'].get('finalizers'):
        raise AssertionError('active request has no finalizer')
    if not args.expect_unsupported:
        kubectl('delete', 'wardenboost', 'request', '-n', namespace, '--wait=false')
        time.sleep(3)
        deleting = get('wardenboost', 'request')
        if not deleting or not deleting['metadata'].get('deletionTimestamp') or not deleting['metadata'].get('finalizers'):
            raise AssertionError('deletion removed active request without completion')
        print('PASS: packaged operator admits request and finalizer holds deletion')
finally:
    kubectl('patch', 'wardenboost', 'request', '-n', namespace, '--type=merge',
            '-p', '{"metadata":{"finalizers":[]}}', check=False)
    kubectl('delete', 'namespace', namespace, '--wait=false', check=False)
