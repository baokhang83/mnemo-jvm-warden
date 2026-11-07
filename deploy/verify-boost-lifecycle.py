#!/usr/bin/env python3
"""Exercise the controller's lifecycle against a disposable Kubernetes cluster."""
import argparse
import json
import os
from pathlib import Path
import subprocess
import tempfile

parser = argparse.ArgumentParser(description=__doc__)
parser.add_argument('--context', required=True)
args = parser.parse_args()
root = Path(__file__).resolve().parent.parent
namespace = 'warden-b5-lifecycle-probe'

def kubectl(*arguments, data=None):
    result = subprocess.run(['kubectl', '--context', args.context, *arguments],
                            input=json.dumps(data) if data else None, text=True, capture_output=True)
    if result.returncode:
        raise RuntimeError(result.stderr)
    return result.stdout

for crd in ('wardenpolicy', 'wardenboost'):
    kubectl('apply', '-f', str(root / f'charts/warden/crds/{crd}-crd.yaml'))
for crd in ('wardenpolicies', 'wardenboosts'):
    kubectl('wait', '--for=condition=Established', f'crd/{crd}.warden.mnemo.io', '--timeout=30s')
kubectl('create', 'namespace', namespace)
try:
    policy = {'apiVersion': 'warden.mnemo.io/v1alpha1', 'kind': 'WardenPolicy',
        'metadata': {'name': 'policy', 'namespace': namespace},
        'spec': {'timezone': 'UTC', 'targetRef': {'kind': 'Pod', 'name': 'worker'},
                 'profiles': {'normal': {'request': '64Mi', 'limit': '256Mi', 'heapTarget': '32Mi'}},
                 'schedule': [{'cron': '* * * * *', 'profile': 'normal'}],
                 'boosts': {'index': {'profile': 'normal', 'heapTarget': '32Mi',
                                      'acquisitionTimeout': '2m', 'maxDuration': '5m'}}}}
    kubectl('create', '-f', '-', data=policy)
    policy_uid = json.loads(kubectl('get', 'wardenpolicy', 'policy', '-n', namespace, '-o', 'json'))['metadata']['uid']
    pod = {'apiVersion': 'v1', 'kind': 'Pod', 'metadata': {'name': 'worker', 'namespace': namespace,
           'labels': {'warden.mnemo.io/policy-uid': policy_uid}}, 'spec': {'containers': [{'name': 'app',
           'image': 'registry.k8s.io/pause:3.10.1', 'resources': {'requests': {'memory': '64Mi'}, 'limits': {'memory': '256Mi'}}}]}}
    kubectl('create', '-f', '-', data=pod)
    kubectl('wait', '--for=condition=Ready', 'pod/worker', '-n', namespace, '--timeout=120s')
    pod_uid = json.loads(kubectl('get', 'pod', 'worker', '-n', namespace, '-o', 'json'))['metadata']['uid']
    kubectl('create', '-f', '-', data={'apiVersion': 'warden.mnemo.io/v1alpha1', 'kind': 'WardenBoost',
        'metadata': {'name': 'request', 'namespace': namespace}, 'spec': {'podRef': {'name': 'worker', 'uid': pod_uid},
        'container': 'app', 'policyRef': 'policy', 'boost': 'index', 'completionRequested': False}})
    kubectl('create', '-f', '-', data={'apiVersion': 'warden.mnemo.io/v1alpha1', 'kind': 'WardenBoost',
        'metadata': {'name': 'competitor', 'namespace': namespace}, 'spec': {'podRef': {'name': 'worker', 'uid': pod_uid},
        'container': 'app', 'policyRef': 'policy', 'boost': 'index', 'completionRequested': False}})
    with tempfile.TemporaryDirectory(prefix='warden-b5-lifecycle-') as directory:
        config = Path(directory) / 'kubeconfig'
        config.write_text(kubectl('config', 'view', '--raw', '--flatten', '--minify'))
        config.chmod(0o600)
        jar = root / 'warden-controller/target/warden-controller-0.1.0.jar'
        subprocess.run(['javac', '--release', '21', '-cp', str(jar), '-d', directory,
                        str(root / 'deploy/harness/BoostLifecycleProbe.java')], check=True)
        env = os.environ.copy()
        env['KUBECONFIG'] = str(config)
        subprocess.run(['java', '-cp', f'{directory}:{jar}', 'BoostLifecycleProbe', namespace], env=env, check=True)
finally:
    kubectl('delete', 'namespace', namespace, '--wait=false')
