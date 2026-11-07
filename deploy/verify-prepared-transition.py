#!/usr/bin/env python3
"""Verify Boost status CAS on a caller-owned disposable Kubernetes cluster."""
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
namespace = 'warden-atomic-probe'

def kubectl(*arguments, data=None):
    result = subprocess.run(['kubectl', '--context', args.context, *arguments],
                            input=json.dumps(data) if data else None, text=True, capture_output=True)
    if result.returncode:
        raise RuntimeError(result.stderr)
    return result.stdout

crd = root / 'charts/warden/crds/wardenboost-crd.yaml'
kubectl('apply', '-f', str(crd))
kubectl('wait', '--for=condition=Established', 'crd/wardenboosts.warden.mnemo.io', '--timeout=30s')
kubectl('create', 'namespace', namespace)
try:
    kubectl('create', '-f', '-', data={
        'apiVersion': 'warden.mnemo.io/v1alpha1', 'kind': 'WardenBoost',
        'metadata': {'name': 'request', 'namespace': namespace},
        'spec': {'policyRef': 'policy', 'podRef': {'name': 'pod', 'uid': 'pod-uid'},
                 'container': 'app', 'boost': 'maintenance'}})
    with tempfile.TemporaryDirectory(prefix='warden-atomic-') as directory:
        config = Path(directory) / 'kubeconfig'
        config.write_text(kubectl('config', 'view', '--raw', '--flatten', '--minify'))
        config.chmod(0o600)
        jar = root / 'warden-controller/target/warden-controller-0.1.0.jar'
        subprocess.run(['javac', '--release', '21', '-cp', str(jar), '-d', directory,
                        str(root / 'deploy/harness/PreparedTransitionProbe.java')], check=True)
        env = os.environ.copy()
        env['KUBECONFIG'] = str(config)
        subprocess.run(['java', '-cp', f'{directory}:{jar}', 'PreparedTransitionProbe',
                        namespace, 'request'], env=env, check=True)
finally:
    kubectl('delete', 'namespace', namespace, '--wait=false')
