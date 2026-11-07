#!/usr/bin/env python3
"""Live transport, actual allocation, ZGC soft-max, and admission checks on a disposable cluster.

Caller creates/deletes the kind cluster. This installs cluster-wide test admission policies.
The probe is a single Java container exercising the production protocol and resize classes;
it does not verify the complete controller/sidecar/Boost lifecycle (B7).
"""
import argparse
import base64
import json
from pathlib import Path
import subprocess
import tempfile
import time


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument('--context', required=True)
    args = parser.parse_args()
    root = Path(__file__).resolve().parent.parent
    ns = 'warden-boost-protocol-check'
    prefix = ['kubectl', '--context', args.context]

    def k(*arguments, obj=None, config=None, denied=None):
        cmd = prefix if config is None else ['kubectl', '--kubeconfig', str(config)]
        result = subprocess.run(cmd + list(arguments), input=json.dumps(obj) if obj else None,
                                capture_output=True, text=True)
        if denied:
            if result.returncode == 0 or denied not in result.stderr:
                raise AssertionError(f'Expected denial {denied!r}: {result.stderr}')
        elif result.returncode:
            raise AssertionError(result.stderr)
        return result.stdout

    def wire(*fields):
        return 'v1.' + '.'.join(base64.urlsafe_b64encode(str(f).encode()).decode().rstrip('=') for f in fields)

    def pod(name):
        return json.loads(k('get', 'pod', name, '-n', ns, '-o', 'json'))

    def patch(name, value, **kwargs):
        return k('patch', 'pod', name, '-n', ns, '--type=merge', '-p', json.dumps(value), **kwargs)

    k('create', 'namespace', ns)  # Refuse an existing namespace; cleanup is owned by this run.
    try:
        k('apply', '-f', str(root / 'deploy/warden-boost-ack-admission-policy.yaml'))
        # Admission compilation errors are visible before any positive/negative assertions.
        for name in ['warden-boost-ack-only', 'warden-boost-ack-writer']:
            for _ in range(30):
                policy = json.loads(k('get', 'validatingadmissionpolicy', name, '-o', 'json'))
                status = policy.get('status', {})
                if status.get('observedGeneration') == policy['metadata']['generation']:
                    warnings = status.get('typeChecking', {}).get('expressionWarnings', [])
                    assert not warnings, warnings
                    break
                time.sleep(1)
            else:
                raise AssertionError('Admission policy compilation was not observed')
        k('create', 'serviceaccount', 'warden-boost-agent', '-n', ns)
        for kind, name, spec in [
            ('Role', 'probe', {'rules': [{'apiGroups': [''], 'resources': ['pods', 'pods/resize'], 'verbs': ['get', 'patch']}]}),
            ('RoleBinding', 'probe', {'roleRef': {'apiGroup': 'rbac.authorization.k8s.io', 'kind': 'Role', 'name': 'probe'},
                                     'subjects': [{'kind': 'ServiceAccount', 'name': 'warden-boost-agent', 'namespace': ns}]})]:
            k('create', '-f', '-', obj={'apiVersion': 'rbac.authorization.k8s.io/v1', 'kind': kind,
                                        'metadata': {'name': name, 'namespace': ns}, **spec})
        with tempfile.TemporaryDirectory(prefix='boost-probe-') as tmp:
            tmp = Path(tmp)
            jar = root / 'warden-agent/target/warden-agent-0.1.0.jar'
            subprocess.run(['javac', '--release', '21', '-cp', str(jar), '-d', str(tmp),
                            str(root / 'deploy/harness/BoostProtocolProbe.java')], check=True)
            k('create', 'configmap', 'probe', '-n', ns, '--from-file=agent.jar=' + str(jar),
              '--from-file=BoostProtocolProbe.class=' + str(tmp / 'BoostProtocolProbe.class'))
            for name in ['probe-a', 'probe-b']:
                k('create', '-f', '-', obj={'apiVersion': 'v1', 'kind': 'Pod',
                    'metadata': {'name': name, 'namespace': ns, 'annotations': {'probe': 'initial'}},
                    'spec': {'serviceAccountName': 'warden-boost-agent', 'containers': [{
                        'name': 'app', 'image': 'eclipse-temurin:21-jdk', 'imagePullPolicy': 'IfNotPresent',
                        'command': ['java', '-XX:+UseZGC', '-Xmx128m', '-Xms16m', '-XX:SoftMaxHeapSize=32m',
                                    '-cp', '/probe/agent.jar:/probe', 'BoostProtocolProbe'],
                        'env': [{'name': 'POD_NAME', 'value': name}],
                        'resources': {'requests': {'memory': '64Mi'}, 'limits': {'memory': '256Mi'}},
                        'volumeMounts': [{'name': 'probe', 'mountPath': '/probe', 'readOnly': True}]}],
                        'volumes': [{'name': 'probe', 'configMap': {'name': 'probe'}}]}})
            k('wait', '--for=condition=Ready', 'pod', '--all', '-n', ns, '--timeout=180s')
            a = pod('probe-a')
            uid = a['metadata']['uid']
            cid = a['status']['containerStatuses'][0]['containerID']
            req, limit, heap = 96 * 1024**2, 320 * 1024**2, 64 * 1024**2
            for transition in ['first', 'same-numbers-new-transition']:
                intent = wire(transition, 'request-uid', uid, 'app', cid, req, limit, heap)
                patch('probe-a', {'metadata': {'annotations': {'warden.mnemo.io/resize-intent': intent,
                    'warden.mnemo.io/target-request-bytes': str(req), 'warden.mnemo.io/target-limit-bytes': str(limit)}}})
                for _ in range(60):
                    a = pod('probe-a')
                    ack = a['metadata']['annotations'].get('warden.mnemo.io/resize-ack', '')
                    if ack.startswith('v1.'):
                        fields = [base64.urlsafe_b64decode(f + '=' * (-len(f) % 4)).decode() for f in ack.split('.')[1:]]
                        if fields[0] == intent and fields[1] == 'true':
                            assert fields[3:6] == [str(req), str(limit), str(heap)]
                            assert a['status']['containerStatuses'][0]['containerID'] == cid
                            break
                    time.sleep(1)
                else:
                    raise AssertionError('No current applied acknowledgement: ' + k('logs', 'probe-a', '-n', ns))
                print('PASS: confirmed allocation, real ZGC heap, and acknowledgement for ' + transition, flush=True)
            token = k('create', 'token', 'warden-boost-agent', '-n', ns, '--bound-object-kind=Pod',
                      '--bound-object-name=probe-a', '--bound-object-uid=' + uid).strip()
            config = json.loads(k('config', 'view', '--raw', '--minify', '-o', 'json'))
            config['users'] = [{'name': 'probe', 'user': {'token': token}}]
            config['contexts'][0]['context']['user'] = 'probe'
            credentials = tmp / 'token-config.json'
            credentials.write_text(json.dumps(config)); credentials.chmod(0o600)
            patch('probe-b', {'metadata': {'annotations': {'warden.mnemo.io/resize-ack': 'forged'}}},
                  config=credentials, denied='bound pod identity')
            patch('probe-a', {'metadata': {'annotations': {'unrelated': 'forged'}}},
                  config=credentials, denied='only the bounded resize-ack')
            patch('probe-a', {'metadata': {'annotations': {'warden.mnemo.io/resize-intent': 'forged'}}},
                  config=credentials, denied='only the bounded resize-ack')
            patch('probe-a', {'metadata': {'annotations': {'warden.mnemo.io/resize-ack': 'forged'}}},
                  denied='Only the dedicated Boost agent')
            # A correct test succeeds first, so a stale test denial is not a malformed request.
            for subresource in ['', '/resize']:
                suffix = ['--subresource=resize'] if subresource else []
                good = [{'op': 'test', 'path': '/metadata/uid', 'value': uid}]
                k('patch', 'pod', 'probe-a', '-n', ns, '--type=json', '-p', json.dumps(good), *suffix)
                bad = [{'op': 'test', 'path': '/metadata/resourceVersion', 'value': 'stale'}]
                result = subprocess.run(prefix + ['patch', 'pod', 'probe-a', '-n', ns,
                    '--type=json', '-p', json.dumps(bad)] + suffix, capture_output=True, text=True)
                assert result.returncode != 0 and 'invalid' in result.stderr.lower(), result.stderr
            print('PASS: cross-pod, unrelated annotation, intent forgery, acknowledgement forgery, and stale-version writes denied')
    finally:
        k('delete', 'namespace', ns, '--wait=false')
        k('delete', '-f', str(root / 'deploy/warden-boost-ack-admission-policy.yaml'), '--ignore-not-found')


if __name__ == '__main__':
    main()
