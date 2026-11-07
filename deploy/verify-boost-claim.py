#!/usr/bin/env python3
"""Exercise claim ownership using a disposable cluster and namespace."""
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
namespace = 'warden-b5-claim-probe'

def kubectl(*arguments, data=None):
    result = subprocess.run(['kubectl', '--context', args.context, *arguments],
                            input=json.dumps(data) if data else None, text=True, capture_output=True)
    if result.returncode:
        raise RuntimeError(result.stderr)
    return result.stdout

kubectl('apply', '-f', str(root / 'charts/warden/crds/wardenboost-crd.yaml'))
kubectl('wait', '--for=condition=Established', 'crd/wardenboosts.warden.mnemo.io', '--timeout=30s')
kubectl('create', 'namespace', namespace)
try:
    kubectl('create', '-f', '-', data={'apiVersion': 'v1', 'kind': 'Pod',
        'metadata': {'name': 'worker', 'namespace': namespace},
        'spec': {'containers': [{'name': 'app', 'image': 'registry.k8s.io/pause:3.10.1'}]}})
    kubectl('wait', '--for=condition=Ready', 'pod/worker', '-n', namespace, '--timeout=120s')
    pod = json.loads(kubectl('get', 'pod', 'worker', '-n', namespace, '-o', 'json'))
    kubectl('create', '-f', '-', data={'apiVersion': 'warden.mnemo.io/v1alpha1', 'kind': 'WardenBoost',
        'metadata': {'name': 'request', 'namespace': namespace},
        'spec': {'podRef': {'name': 'worker', 'uid': pod['metadata']['uid']}, 'container': 'app',
                 'policyRef': 'policy', 'boost': 'index', 'completionRequested': False}})
    with tempfile.TemporaryDirectory(prefix='warden-b5-') as directory:
        config = Path(directory) / 'kubeconfig'
        config.write_text(kubectl('config', 'view', '--raw', '--flatten', '--minify'))
        config.chmod(0o600)
        jar = root / 'warden-controller/target/warden-controller-0.1.0.jar'
        subprocess.run(['javac', '--release', '21', '-cp', str(jar), '-d', directory,
                        str(root / 'deploy/harness/BoostClaimProbe.java')], check=True)
        env = os.environ.copy()
        env['KUBECONFIG'] = str(config)
        subprocess.run(['java', '-cp', f'{directory}:{jar}', 'BoostClaimProbe', namespace], env=env, check=True)
finally:
    kubectl('delete', 'namespace', namespace, '--wait=false')
