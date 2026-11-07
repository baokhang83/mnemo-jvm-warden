#!/usr/bin/env python3
"""Validate generated Boost schemas against a disposable kind cluster.

Run Maven process-classes first. Usage: --context kind-warden-boost-schema-check.
The caller owns cluster creation/deletion. Only the named disposable namespace is removed.
"""
import argparse
import copy
import json
from pathlib import Path
import subprocess


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument('--context', required=True)
    args = parser.parse_args()
    root = Path(__file__).resolve().parent.parent
    ns = 'warden-boost-schema-check'
    prefix = ['kubectl', '--context', args.context]

    def kubectl(*arguments, obj=None, success=True, reason=None):
        result = subprocess.run(prefix + list(arguments), input=json.dumps(obj) if obj else None,
                                capture_output=True, text=True)
        if success:
            if result.returncode:
                raise AssertionError(result.stderr)
        else:
            if result.returncode == 0:
                raise AssertionError('Invalid object was accepted')
            if reason not in result.stderr:
                raise AssertionError(f'Expected {reason!r}: {result.stderr}')
        return result.stdout

    for plural in ['wardenpolicies', 'wardenboosts']:
        crd = root / f'warden-crd-model/target/classes/META-INF/fabric8/{plural}.warden.mnemo.io-v1.yml'
        kubectl('apply', '-f', str(crd))
        kubectl('wait', '--for=condition=Established', f'crd/{plural}.warden.mnemo.io', '--timeout=30s')
    # Refuse to use an existing namespace; cleanup must never delete somebody else's resources.
    kubectl('create', 'namespace', ns)
    count = 0
    try:
        policy = {'apiVersion': 'warden.mnemo.io/v1alpha1', 'kind': 'WardenPolicy',
                  'metadata': {'name': 'reporting', 'namespace': ns},
                  'spec': {'timezone': 'Europe/Vienna',
                           'profiles': {'maintenance': {'request': '3Gi', 'limit': '4Gi'}},
                           'boosts': {'index-rebuild': {'profile': 'maintenance', 'heapTarget': '2Gi',
                                                       'acquisitionTimeout': '2m', 'maxDuration': '20m'}}}}
        kubectl('create', '--dry-run=server', '-f', '-', obj=policy)
        legacy = copy.deepcopy(policy)
        del legacy['spec']['boosts']
        kubectl('create', '--dry-run=server', '-f', '-', obj=legacy)
        count += 2
        cases = [
            ('profile', 'absent', 'existing profile'),
            ('heapTarget', '4Gi', 'heapTarget < limit'),
            ('heapTarget', '-1Gi', 'positive'),
            ('heapTarget', 'garbage', 'quantity'),
            ('heapTarget', '0.5', 'integral-byte'),
            ('acquisitionTimeout', '0s', 'positive duration'),
            ('maxDuration', '-1m', 'positive duration'),
            ('maxDuration', 'forever', 'duration'),
        ]
        for field, value, reason in cases:
            invalid = copy.deepcopy(policy)
            invalid['spec']['boosts']['index-rebuild'][field] = value
            kubectl('create', '--dry-run=server', '-f', '-', obj=invalid, success=False, reason=reason)
            count += 1
        profile_heap = copy.deepcopy(policy)
        profile_heap['spec']['profiles']['maintenance']['heapTarget'] = '2Gi'
        kubectl('create', '--dry-run=server', '-f', '-', obj=profile_heap)
        count += 1
        for value in ['4Gi', '0', '0.5', 'bad']:
            invalid = copy.deepcopy(profile_heap)
            invalid['spec']['profiles']['maintenance']['heapTarget'] = value
            kubectl('create', '--dry-run=server', '-f', '-', obj=invalid, success=False, reason='heapTarget')
            count += 1
        for request in ['5Gi', '0', 'bad', '0.5']:
            invalid = copy.deepcopy(policy)
            invalid['spec']['profiles']['maintenance']['request'] = request
            kubectl('create', '--dry-run=server', '-f', '-', obj=invalid, success=False, reason='boost profiles')
            count += 1
        for field in ['profile', 'heapTarget', 'acquisitionTimeout', 'maxDuration']:
            invalid = copy.deepcopy(policy)
            del invalid['spec']['boosts']['index-rebuild'][field]
            kubectl('create', '--dry-run=server', '-f', '-', obj=invalid, success=False, reason=field)
            count += 1
        boost = {'apiVersion': 'warden.mnemo.io/v1alpha1', 'kind': 'WardenBoost',
                 'metadata': {'name': 'rebuild', 'namespace': ns},
                 'spec': {'policyRef': 'reporting', 'podRef': {'name': 'reporting-abc', 'uid': 'pod-uid'},
                          'container': 'app', 'boost': 'index-rebuild'}}
        created = json.loads(kubectl('create', '-f', '-', '-o', 'json', obj=boost))
        assert created['spec']['completionRequested'] is False
        assert not created.get('status'), 'Schema must never default to Granted'
        count += 1
        for field in ['policyRef', 'container', 'boost']:
            modified = copy.deepcopy(created)
            modified['spec'][field] = 'another'
            kubectl('replace', '--dry-run=server', '-f', '-', obj=modified, success=False, reason='immutable')
            count += 1
        for field in ['name', 'uid']:
            modified = copy.deepcopy(created)
            modified['spec']['podRef'][field] = 'another'
            kubectl('replace', '--dry-run=server', '-f', '-', obj=modified, success=False, reason='immutable')
            count += 1
        for field in ['policyRef', 'podRef', 'container', 'boost']:
            invalid = copy.deepcopy(boost)
            invalid['metadata']['name'] = 'invalid'
            del invalid['spec'][field]
            kubectl('create', '--dry-run=server', '-f', '-', obj=invalid, success=False, reason=field)
            count += 1
        invalid = copy.deepcopy(boost)
        del invalid['spec']
        kubectl('create', '--dry-run=server', '-f', '-', obj=invalid, success=False, reason='spec')
        count += 1
        created['spec']['completionRequested'] = True
        completed = json.loads(kubectl('replace', '-f', '-', '-o', 'json', obj=created))
        completed['spec']['completionRequested'] = False
        kubectl('replace', '--dry-run=server', '-f', '-', obj=completed, success=False, reason='cannot change')
        count += 2
        print(f'PASS: {count} real API-server schema and update checks')
    finally:
        kubectl('delete', 'namespace', ns, '--wait=false')


if __name__ == '__main__':
    main()
