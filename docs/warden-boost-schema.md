# Warden Boost schemas (B2)

This change installs schemas only. The current controller does not process WardenBoost
requests, issue grants, or implement boost floors. Do not start application work based on
resource creation. B3–B6 implement the confirmed grant protocol.

## Policy configuration

Existing policies can omit `boosts`. A policy can define up to 32 named boosts:

```yaml
apiVersion: warden.mnemo.io/v1alpha1
kind: WardenPolicy
metadata:
  name: reporting
spec:
  timezone: Europe/Vienna
  profiles:
    maintenance:
      request: 3Gi
      limit: 4Gi
  boosts:
    index-rebuild:
      profile: maintenance
      heapTarget: 2Gi
      acquisitionTimeout: 2m
      maxDuration: 20m
```

The API server checks profile references, positive integral-byte memory quantities,
request <= limit, heapTarget < limit, and positive durations. Quantities are compared
numerically, not as strings. Profile request/limit and heapTarget strings have a 64-character
limit to bound CEL evaluation cost; durations have a 32-character limit. This length bound
also applies to profiles in policies without boosts. Ordinary quantity syntax is unchanged.

`heapTarget` reserves a distinction between heap and total container memory. The operator
must choose enough non-heap headroom. Checking runtime capability, actual JVM maximum,
target authorization, and live pod identity requires the later admission/reconcile path;
the CRD alone cannot resolve those external objects.

## Request

```yaml
apiVersion: warden.mnemo.io/v1alpha1
kind: WardenBoost
metadata:
  name: index-rebuild-123
spec:
  policyRef: reporting
  podRef:
    name: reporting-abc
    uid: actual-pod-uid
  container: app
  boost: index-rebuild
```

The request is namespaced. All identity fields are required and immutable after creation.
The API server defaults completionRequested to false. It accepts a change to true but
rejects a reversal. Status is a separate subresource and never defaults to Granted.

For a Helm upgrade, apply the updated CRDs explicitly; Helm's `crds/` installation does not
upgrade existing CRDs automatically:

```bash
kubectl apply -f charts/warden/crds/wardenpolicy-crd.yaml
kubectl apply -f charts/warden/crds/wardenboost-crd.yaml
```

No new controller/agent permissions are granted by B2. RBAC, request authorization, and
acknowledgement admission controls are implemented in B6 with M8 follow-ups.

## Verification

Build checks validate schema generation and compare both generated CRDs with their Helm
copies. For API-server enforcement, run the following against a disposable local cluster:

```bash
mvn -B --no-transfer-progress -pl warden-crd-model -am verify
kind create cluster --name warden-boost-schema-check --wait 90s
python3 deploy/verify-wardenboost-schema.py --context kind-warden-boost-schema-check
kind delete cluster --name warden-boost-schema-check
```

The Python harness creates its own namespace and deletes it afterward. It refuses to reuse
an existing namespace. It applies CRDs cluster-wide, so use a disposable cluster. It checks
valid/legacy policies, invalid references and quantities, required fields, immutable identity,
completion defaulting, and completion reversal. These schema checks do not prove runtime
resize or lifecycle behavior.

B2 verification used kind with Kubernetes v1.36.1. The existing Warden supported-cluster
requirements still apply. CEL quantity rules rely on Kubernetes quantity-library support.
See the [Kubernetes CEL reference](https://kubernetes.io/docs/reference/using-api/cel/).
