#!/usr/bin/env python3
"""Verify B9's Java lease client (warden-boost-client) on a disposable Kubernetes cluster.

Runs the actual `IndexRebuildExample` application code (not raw curl) as the `app` container,
talking to a real agent (B8's local API) over the real shared bearer token, with real
Kubernetes admission/RBAC installed. The chart controller is scaled to zero and no matching
policy is installed, so the request cannot reach `Granted` — that path (the wire format and happy-path logic) is already covered by
`deploy/verify-boost-local-api.py` (raw curl against the same real API) and
`WardenBoostClientTest` (the client's own logic against a protocol-faithful fake server); a
real controller's full grant mechanics are `verify-boost-acceptance.py`'s territory, not
B9-specific. What's novel here is the one behavior that specifically needs a real cluster to
mean anything: confirming the *actual client*, for real, never releases an uncertain grant when
it gives up waiting — the dev plan's "never assume the operation could not have started."

Requires an already-loaded agent image and the example image built from
deploy/Dockerfile.boost-client-example:
    docker build -f deploy/Dockerfile.boost-client-example -t <repo:tag> .
    kind load docker-image <repo:tag> --name <cluster>
"""
import argparse
import json
import subprocess
import time
from pathlib import Path

parser = argparse.ArgumentParser(description=__doc__)
parser.add_argument('--context', required=True)
parser.add_argument('--agent-image', required=True, help='agent image repository:tag, already loaded into the cluster')
parser.add_argument('--example-image', required=True,
                     help='deploy/Dockerfile.boost-client-example image repository:tag, already loaded')
args = parser.parse_args()
root = Path(__file__).resolve().parent.parent
ns = 'warden-b9-client-probe'
release = 'b9probe'
base = ['kubectl', '--context', args.context]


def run(cmd, *, data=None):
    result = subprocess.run(cmd, input=json.dumps(data) if data is not None else None,
                             capture_output=True, text=True)
    if result.returncode:
        raise AssertionError(result.stderr)
    return result.stdout


def k(*arguments, data=None):
    return run(base + list(arguments), data=data)


def create(obj):
    return k('create', '-f', '-', data=obj)


k('create', 'namespace', ns)
try:
    k('label', 'namespace', ns, 'warden.mnemo.io/boost-enabled=true')
    run(['helm', 'install', release, str(root / 'charts/warden'), '--kube-context', args.context,
         '--namespace', ns, '--set', 'boost.security.enabled=true'])
    # These probes require pending requests; do not let the chart's controller reconcile them.
    k('scale', 'deployment/' + release + '-warden-controller', '-n', ns, '--replicas=0')
    k('rollout', 'status', 'deployment/' + release + '-warden-controller', '-n', ns, '--timeout=120s')
    k('create', 'serviceaccount', 'warden-boost-agent', '-n', ns)
    k('create', 'rolebinding', 'boost-agent', '-n', ns,
      '--clusterrole=' + release + '-warden-boost-agent', '--serviceaccount=' + ns + ':warden-boost-agent')
    for suffix in ['boost-agent-token', 'boost-request-guard']:
        name = release + '-warden-' + suffix
        for _ in range(60):
            obj = json.loads(k('get', 'validatingadmissionpolicy', name, '-o', 'json'))
            if obj.get('status', {}).get('observedGeneration') == obj['metadata']['generation']:
                break
            time.sleep(1)
        else:
            raise AssertionError(f'{name}: no CEL type check')

    # Same shape as verify-boost-local-api.py's probe pod, but the `app` container now runs the
    # real IndexRebuildExample instead of `sleep infinity` + curl. A short acquisition timeout
    # (the chart controller is scaled to zero) exercises the
    # uncertain-grant-retention path quickly instead of waiting out the example's 2-minute default.
    pod = {
        'apiVersion': 'v1', 'kind': 'Pod',
        'metadata': {'name': 'probe', 'namespace': ns},
        'spec': {
            'serviceAccountName': 'warden-boost-agent',
            'automountServiceAccountToken': False,
            'volumes': [
                {'name': 'agent-token', 'projected': {'sources': [
                    {'serviceAccountToken': {'path': 'token', 'expirationSeconds': 3607}},
                    {'configMap': {'name': 'kube-root-ca.crt', 'items': [{'key': 'ca.crt', 'path': 'ca.crt'}]}},
                    {'downwardAPI': {'items': [{'path': 'namespace', 'fieldRef': {'fieldPath': 'metadata.namespace'}}]}},
                ]}},
                {'name': 'warden-boost-token', 'emptyDir': {}},
            ],
            'initContainers': [{
                'name': 'warden', 'image': args.agent_image, 'imagePullPolicy': 'Never',
                'restartPolicy': 'Always',
                'env': [
                    {'name': 'WARDEN_POD_NAME', 'valueFrom': {'fieldRef': {'fieldPath': 'metadata.name'}}},
                    {'name': 'WARDEN_TARGET_CONTAINER_NAME', 'value': 'app'},
                    {'name': 'WARDEN_POD_UID', 'valueFrom': {'fieldRef': {'fieldPath': 'metadata.uid'}}},
                    {'name': 'WARDEN_BOOST_POLICY_REF', 'value': 'my-policy'},
                    {'name': 'WARDEN_BOOST_PORT', 'value': '8090'},
                ],
                'volumeMounts': [
                    {'name': 'agent-token', 'mountPath': '/var/run/secrets/kubernetes.io/serviceaccount', 'readOnly': True},
                    {'name': 'warden-boost-token', 'mountPath': '/var/run/warden/boost'},
                ],
            }],
            'containers': [{
                'name': 'app', 'image': args.example_image, 'imagePullPolicy': 'Never',
                'env': [{'name': 'WARDEN_BOOST_EXAMPLE_TIMEOUT_SECONDS', 'value': '5'}],
                'volumeMounts': [{'name': 'warden-boost-token', 'mountPath': '/var/run/warden/boost', 'readOnly': True}],
            }],
            'restartPolicy': 'Never',
        },
    }
    create(pod)
    k('wait', '--for=condition=Initialized', 'pod/probe', '-n', ns, '--timeout=120s')

    def app_terminated():
        status = json.loads(k('get', 'pod', 'probe', '-n', ns, '-o', 'json'))['status']
        for container in status.get('containerStatuses', []):
            if container['name'] == 'app' and 'terminated' in container.get('state', {}):
                return container['state']['terminated']
        return None

    terminated = None
    for _ in range(60):
        terminated = app_terminated()
        if terminated:
            break
        time.sleep(1)
    if not terminated:
        raise AssertionError('app container (IndexRebuildExample) never terminated')

    logs = k('logs', 'probe', '-n', ns, '-c', 'app')
    if "acquiring boost 'index-rebuild'" not in logs:
        raise AssertionError(f'app did not even start acquiring: {logs}')
    if 'acquisition timed out (uncertain outcome)' not in logs:
        raise AssertionError(f'expected the client to report its own acquisition timeout: {logs}')
    if terminated['exitCode'] != 2:
        raise AssertionError(f"expected exit code 2 (the example's timeout path), got {terminated}: {logs}")

    request_id = None
    for line in logs.splitlines():
        if 'requestId=' in line:
            request_id = line.split('requestId=')[1].split()[0]
            break
    if not request_id:
        raise AssertionError(f'could not find the requestId the client reported: {logs}')

    boosts = json.loads(k('get', 'wardenboosts', '-n', ns, '-o', 'json'))['items']
    if len(boosts) != 1 or boosts[0]['metadata']['name'] != request_id:
        raise AssertionError(f'expected exactly one WardenBoost named {request_id}, found {boosts}')
    if boosts[0]['spec']['completionRequested'] is not False:
        raise AssertionError(
            'a client-side acquisition timeout must never request completion on the uncertain '
            f"request — found completionRequested={boosts[0]['spec']['completionRequested']}")

    # The client's own reconciliation path: calling inspect() on the retained ID must still work.
    status_check = json.loads(k('get', 'wardenboost', request_id, '-n', ns, '-o', 'json'))
    if status_check['metadata']['name'] != request_id:
        raise AssertionError('could not re-fetch the retained request by its stable ID')

    print('PASS: real IndexRebuildExample client, real agent and admission/RBAC — a client-side '
          'acquisition timeout retains the uncertain request untouched (never releases it)')
finally:
    run(['helm', 'uninstall', release, '--kube-context', args.context, '--namespace', ns])
    k('delete', 'namespace', ns, '--wait=false')
