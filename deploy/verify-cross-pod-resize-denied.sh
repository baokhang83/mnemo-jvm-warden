#!/usr/bin/env bash
# Verifies #71's acceptance criterion for real: a ValidatingAdmissionPolicy actually stops one
# Deployment replica's own bound token from resizing a *different* replica, even though the
# underlying RBAC grant (ClusterRole + RoleBinding, namespace-wide) allows it — while a replica
# resizing itself still works normally. Constitution §8: a policy that looks right in YAML still
# needs a real API server to prove it's enforced.
#
# Doesn't touch WardenPolicy/the controller at all — this is a pure RBAC/admission-control check,
# independent of the schedule/intent machinery deploy/verify-wardenpolicy-intent.sh covers.
#
# Manual-run only, matching every other check in this directory.
#
# Usage:
#   deploy/verify-cross-pod-resize-denied.sh              # spins up + tears down its own kind cluster
#   deploy/verify-cross-pod-resize-denied.sh --keep        # leaves the cluster + pods up for inspection
#   deploy/verify-cross-pod-resize-denied.sh --cluster N   # reuse an existing kind cluster named N
set -euo pipefail

CLUSTER_NAME="warden-rbac-check"
KEEP_CLUSTER=false
OWN_CLUSTER=true
SCRIPT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
REPO_ROOT="$(cd "$SCRIPT_DIR/.." && pwd)"
IMAGE="warden-rbac-check:local"
DEPLOY_SELECTOR="app.kubernetes.io/name=warden-demo-e2e-deploy"

while [[ $# -gt 0 ]]; do
  case "$1" in
    --keep) KEEP_CLUSTER=true; shift ;;
    --cluster) CLUSTER_NAME="$2"; OWN_CLUSTER=false; shift 2 ;;
    *) echo "unknown argument: $1" >&2; exit 2 ;;
  esac
done

cleanup() {
  if [[ "$KEEP_CLUSTER" == false ]]; then
    kubectl --context "kind-$CLUSTER_NAME" delete deployment warden-demo-e2e-deploy --ignore-not-found --wait=false >/dev/null 2>&1 || true
    kubectl --context "kind-$CLUSTER_NAME" delete -f "$SCRIPT_DIR/warden-resize-admission-policy.yaml" --ignore-not-found >/dev/null 2>&1 || true
    if [[ "$OWN_CLUSTER" == true ]]; then
      kind delete cluster --name "$CLUSTER_NAME" >/dev/null 2>&1 || true
    fi
  fi
}
trap cleanup EXIT

echo "==> building the agent image"
docker build -q -t "$IMAGE" "$REPO_ROOT" >/dev/null

if [[ "$OWN_CLUSTER" == true ]]; then
  echo "==> creating throwaway kind cluster ($CLUSTER_NAME)"
  kind create cluster --name "$CLUSTER_NAME" >/dev/null
  kubectl --context "kind-$CLUSTER_NAME" wait --for=condition=Ready node --all --timeout=90s >/dev/null
else
  kubectl config use-context "kind-$CLUSTER_NAME" >/dev/null
fi
kind load docker-image "$IMAGE" --name "$CLUSTER_NAME" >/dev/null

echo "==> applying the ValidatingAdmissionPolicy and the 2-replica Deployment demo"
kubectl --context "kind-$CLUSTER_NAME" apply -f "$SCRIPT_DIR/warden-resize-admission-policy.yaml" >/dev/null

deploy_manifest="$(mktemp)"
WARDEN_IMAGE="$IMAGE" envsubst '${WARDEN_IMAGE}' < "$SCRIPT_DIR/wardenpolicy-demo-deployment.yaml.tmpl" > "$deploy_manifest"
kubectl --context "kind-$CLUSTER_NAME" apply -f "$deploy_manifest" >/dev/null
rm -f "$deploy_manifest"
kubectl --context "kind-$CLUSTER_NAME" wait --for=condition=Ready pod -l "$DEPLOY_SELECTOR" --timeout=120s >/dev/null

pods="$(kubectl --context "kind-$CLUSTER_NAME" get pods -l "$DEPLOY_SELECTOR" -o jsonpath='{.items[*].metadata.name}')"
replica_a="$(echo "$pods" | awk '{print $1}')"
replica_b="$(echo "$pods" | awk '{print $2}')"
echo "    replica A: $replica_a"
echo "    replica B: $replica_b"

fail=false

echo "==> [negative control] replica A attempts to resize replica B, using its own token"
set +e
cross_result="$(kubectl --context "kind-$CLUSTER_NAME" exec "$replica_a" -c warden -- \
  java -cp /app/warden-agent.jar io.github.baokhang83.mnemo.warden.agent.harness.CrossPodResizeAttempt \
  "$replica_b" app 104857600 157286400 15 2>&1)"
cross_exit=$?
set -e
echo "$cross_result"

if [[ "$cross_exit" == 1 ]] && echo "$cross_result" | grep -qi "403\|forbidden\|denied\|own pod"; then
  echo "PASS: cross-replica resize was denied"
else
  echo "FAIL: expected the cross-replica resize to be denied (exit 1, HTTP 403), got exit $cross_exit"
  fail=true
fi

echo "==> [positive control] replica A resizes itself, using its own token"
set +e
self_result="$(kubectl --context "kind-$CLUSTER_NAME" exec "$replica_a" -c warden -- \
  java -cp /app/warden-agent.jar io.github.baokhang83.mnemo.warden.agent.harness.CrossPodResizeAttempt \
  "$replica_a" app 104857600 157286400 15 2>&1)"
self_exit=$?
set -e
echo "$self_result"

if [[ "$self_exit" == 0 ]]; then
  echo "PASS: self-resize still succeeds — the policy doesn't block legitimate use"
else
  echo "FAIL: expected replica A's self-resize to succeed (exit 0), got exit $self_exit"
  fail=true
fi

if [[ "$fail" == true ]]; then
  echo "==> RESULT: FAIL"
  exit 1
fi
echo "==> RESULT: PASS — a replica's own token can resize itself, but never a different replica"
