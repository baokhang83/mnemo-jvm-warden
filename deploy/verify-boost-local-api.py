#!/usr/bin/env python3
"""Verify B8's application-facing local Boost API on a disposable Kubernetes cluster.

Exercises the real loopback HTTP surface (`BoostApiServer`) from inside a real Pod, against
the real Kubernetes API, the real opt-in admission/RBAC package, and a real shared `emptyDir`
token volume — not the in-memory fake `BoostApiServerTest` unit-tests against. Cross-pod
admission denial for a *different* pod's bound identity is already covered by
`verify-boost-security.py`'s agent/intruder scenarios; this script focuses on what that one
doesn't: the local HTTP wire format, bearer-token auth, idempotent `requestId` retries, the
one-outstanding-request limit, and real cross-container token sharing (the agent writes the
token in the `warden` container; the `app` container reads it back).

Requires an already-loaded agent image: build with `docker build -t <repo:tag> .` and
`kind load docker-image <repo:tag> --name <cluster>` first.
"""
import argparse
import json
import subprocess
import time
from pathlib import Path

parser = argparse.ArgumentParser(description=__doc__)
parser.add_argument('--context', required=True)
parser.add_argument('--agent-image', required=True, help='agent image repository:tag, already loaded into the cluster')
args = parser.parse_args()
root = Path(__file__).resolve().parent.parent
ns = 'warden-b8-local-api-probe'
release = 'b8probe'
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


def k(*arguments, data=None, deny=None):
    return run(base + list(arguments), data=data, deny=deny)


def create(obj):
    return k('create', '-f', '-', data=obj)


def curl(*curl_args, status_only=False):
    output = k('exec', 'probe', '-n', ns, '-c', 'app', '--',
               'curl', '-s', '-w', '\\nHTTP %{http_code}', *curl_args)
    body, _, status_line = output.rpartition('\n')
    status = int(status_line.removeprefix('HTTP ').strip())
    if status_only:
        return status
    return status, (json.loads(body) if body else None)


k('create', 'namespace', ns)
try:
    k('label', 'namespace', ns, 'warden.mnemo.io/boost-enabled=true')
    run(['helm', 'install', release, str(root / 'charts/warden'), '--kube-context', args.context,
         '--namespace', ns, '--set', 'boost.security.enabled=true'])
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

    # Mirrors deploy/example-sidecar-boost.yaml: a projected SA token mounted only in the
    # warden init container (boost-agent-token's own requirement), plus the new shared
    # warden-boost-token emptyDir mounted read-write there and read-only in `app`.
    pod = {
        'apiVersion': 'v1', 'kind': 'Pod',
        'metadata': {'name': 'probe', 'namespace': ns},
        'spec': {
            'serviceAccountName': 'warden-boost-agent',
            'automountServiceAccountToken': False,
            'volumes': [
                # boost-agent-token requires automountServiceAccountToken: false plus a custom
                # projected volume for the "dedicated-agent" identity — but unlike the
                # admission-only fixtures in verify-boost-security.py (whose pause container
                # never reads it), this probe runs a REAL agent that calls
                # InClusterApiServer.discover(), which also needs the namespace/ca.crt files
                # the default auto-mounted kube-api-access-* volume normally carries alongside
                # the token. Reproduce that same three-source shape explicitly.
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
                'name': 'app', 'image': 'eclipse-temurin:21-jdk', 'command': ['sleep', 'infinity'],
                'volumeMounts': [{'name': 'warden-boost-token', 'mountPath': '/var/run/warden/boost', 'readOnly': True}],
            }],
        },
    }
    create(pod)
    k('wait', '--for=condition=Ready', 'pod/probe', '-n', ns, '--timeout=120s')

    # Pod Ready only reflects the native sidecar having started, not that WardenAgent.main has
    # reached the point of writing the token file and starting BoostApiServer yet.
    token = None
    for _ in range(30):
        result = subprocess.run(
            base + ['exec', 'probe', '-n', ns, '-c', 'app', '--', 'cat', '/var/run/warden/boost/token'],
            capture_output=True, text=True)
        if result.returncode == 0 and result.stdout.strip():
            token = result.stdout.strip()
            break
        time.sleep(1)
    if not token:
        raise AssertionError('app container could not read the agent-written bearer token in time')
    auth = ['-H', 'Authorization: Bearer ' + token]
    url = 'http://127.0.0.1:8090/v1/boost'

    status = curl('-X', 'POST', url + '/acquire', '-d', '{"boost":"job"}', status_only=True)
    if status != 401:
        raise AssertionError(f'expected 401 without a bearer token, got {status}')
    status = curl('-X', 'POST', url + '/acquire', '-H', 'Authorization: Bearer wrong', '-d', '{"boost":"job"}', status_only=True)
    if status != 401:
        raise AssertionError(f'expected 401 with a wrong bearer token, got {status}')

    status, body = curl('-X', 'POST', url + '/acquire', *auth, '-d', '{"boost":"job"}')
    if status != 202 or body['phase'] != 'Pending':
        raise AssertionError(f'unexpected first acquire response: {status} {body}')
    request_id = body['requestId']

    status, body = curl(url + '/status?requestId=' + request_id, *auth)
    if status != 200 or body['requestId'] != request_id:
        raise AssertionError(f'unexpected status response: {status} {body}')

    status, body = curl('-X', 'POST', url + '/acquire', *auth, '-d', '{"boost":"job","requestId":"other-one"}')
    if status != 409 or body['requestId'] != request_id:
        raise AssertionError(f'expected 409 naming the outstanding request, got {status} {body}')

    status, body = curl('-X', 'POST', url + '/acquire', *auth,
                         '-d', json.dumps({'boost': 'job', 'requestId': request_id}))
    if status != 202 or body['requestId'] != request_id:
        raise AssertionError(f'retrying the same requestId should be idempotent, got {status} {body}')
    boosts = json.loads(k('get', 'wardenboosts', '-n', ns, '-o', 'json'))['items']
    if len(boosts) != 1:
        raise AssertionError(f'idempotent retry must not create a second WardenBoost, found {len(boosts)}')

    status = curl(url + '/status?requestId=does-not-exist', *auth, status_only=True)
    if status != 404:
        raise AssertionError(f'expected 404 for an unknown requestId, got {status}')

    status, body = curl('-X', 'POST', url + '/release', *auth, '-d', json.dumps({'requestId': request_id}))
    if status != 200:
        raise AssertionError(f'unexpected release response: {status} {body}')
    completion_requested = json.loads(
        k('get', 'wardenboost', request_id, '-n', ns, '-o', 'jsonpath={.spec.completionRequested}'))
    if completion_requested is not True:
        raise AssertionError(f'release did not set completionRequested: {completion_requested!r}')

    status, body = curl('-X', 'POST', url + '/release', *auth, '-d', json.dumps({'requestId': request_id}))
    if status != 200:
        raise AssertionError(f'releasing an already-releasing request should be idempotent, got {status} {body}')

    print('PASS: local Boost API auth, idempotent acquire, request limit, status, and release '
          '(real cluster, real admission/RBAC, real cross-container token sharing)')
finally:
    run(['helm', 'uninstall', release, '--kube-context', args.context, '--namespace', ns])
    k('delete', 'namespace', ns, '--wait=false')
