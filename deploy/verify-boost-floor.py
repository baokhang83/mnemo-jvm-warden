#!/usr/bin/env python3
"""Run the B4 emitter probe on a caller-owned disposable Kubernetes cluster."""
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
namespace = 'warden-b4-probe'


def kubectl(*arguments, data=None):
    result = subprocess.run(['kubectl', '--context', args.context, *arguments],
                            input=json.dumps(data) if data else None,
                            text=True, capture_output=True)
    if result.returncode:
        raise RuntimeError(result.stderr)
    return result.stdout


# Install only on a disposable cluster; CRDs are cluster-scoped.
crd = root / 'charts/warden/crds/wardenboost-crd.yaml'
kubectl('apply', '-f', str(crd))
kubectl('wait', '--for=condition=Established', 'crd/wardenboosts.warden.mnemo.io', '--timeout=30s')

# Refuse to reuse an existing namespace, so cleanup only affects resources created here.
kubectl('create', 'namespace', namespace)
try:
    kubectl('create', '-f', '-', data={
        'apiVersion': 'v1', 'kind': 'Pod', 'metadata': {'name': 'worker', 'namespace': namespace},
        'spec': {'containers': [{'name': 'app', 'image': 'registry.k8s.io/pause:3.10.1',
            'resources': {'requests': {'memory': '64Mi'}, 'limits': {'memory': '256Mi'}}}]}})
    kubectl('wait', '--for=condition=Ready', 'pod/worker', '-n', namespace, '--timeout=120s')
    with tempfile.TemporaryDirectory(prefix='warden-b4-') as directory:
        config = Path(directory) / 'kubeconfig'
        config.write_text(kubectl('config', 'view', '--raw', '--flatten', '--minify'))
        config.chmod(0o600)
        jar = root / 'warden-controller/target/warden-controller-0.1.0.jar'
        subprocess.run(['javac', '--release', '21', '-cp', str(jar), '-d', directory,
                        str(root / 'deploy/harness/BoostFloorProbe.java')], check=True)
        env = os.environ.copy()
        env['KUBECONFIG'] = str(config)
        subprocess.run(['java', '-cp', f'{directory}:{jar}', 'BoostFloorProbe',
                        namespace, 'worker'], env=env, check=True)
finally:
    kubectl('delete', 'namespace', namespace, '--wait=false')
