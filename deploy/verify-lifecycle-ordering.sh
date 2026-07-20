#!/usr/bin/env bash
# Verifies W-202's acceptance criterion for real: a native sidecar (initContainer +
# restartPolicy: Always) starts before the app container and stops after it. See
# deploy/lifecycle-check.yaml for how the proof itself works (causality via shared marker
# files, not wall-clock timestamps).
#
# Usage:
#   deploy/verify-lifecycle-ordering.sh              # spins up + tears down its own kind cluster
#   deploy/verify-lifecycle-ordering.sh --keep        # leaves the cluster up for inspection
#   deploy/verify-lifecycle-ordering.sh --cluster N   # reuse an existing kind cluster named N
set -euo pipefail

CLUSTER_NAME="warden-lifecycle-check"
KEEP_CLUSTER=false
OWN_CLUSTER=true
SCRIPT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"

while [[ $# -gt 0 ]]; do
  case "$1" in
    --keep) KEEP_CLUSTER=true; shift ;;
    --cluster) CLUSTER_NAME="$2"; OWN_CLUSTER=false; shift 2 ;;
    *) echo "unknown argument: $1" >&2; exit 2 ;;
  esac
done

cleanup() {
  if [[ "$OWN_CLUSTER" == true && "$KEEP_CLUSTER" == false ]]; then
    kind delete cluster --name "$CLUSTER_NAME" >/dev/null 2>&1 || true
  fi
}
trap cleanup EXIT

if [[ "$OWN_CLUSTER" == true ]]; then
  echo "==> creating throwaway kind cluster ($CLUSTER_NAME)"
  kind create cluster --name "$CLUSTER_NAME" >/dev/null
  kubectl --context "kind-$CLUSTER_NAME" wait --for=condition=Ready node --all --timeout=90s >/dev/null
fi

echo "==> applying deploy/lifecycle-check.yaml"
# A freshly created kind cluster's node reaches Ready before the `default` ServiceAccount in the
# `default` namespace exists — that controller runs asynchronously. Retry past that race rather
# than failing on it (observed directly while building this check).
for attempt in 1 2 3 4 5; do
  if kubectl --context "kind-$CLUSTER_NAME" apply -f "$SCRIPT_DIR/lifecycle-check.yaml" >/dev/null 2>&1; then
    break
  fi
  if [[ "$attempt" == 5 ]]; then
    echo "FAIL: could not apply lifecycle-check.yaml after $attempt attempts" >&2
    kubectl --context "kind-$CLUSTER_NAME" apply -f "$SCRIPT_DIR/lifecycle-check.yaml"
    exit 1
  fi
  sleep 2
done
kubectl --context "kind-$CLUSTER_NAME" wait --for=condition=Ready pod/lifecycle-check --timeout=60s >/dev/null

echo "==> checking start ordering"
sidecar_start_log="$(kubectl --context "kind-$CLUSTER_NAME" logs lifecycle-check -c sidecar)"
app_start_log="$(kubectl --context "kind-$CLUSTER_NAME" logs lifecycle-check -c app)"

fail=false
if ! grep -q "START-ORDER: app-marker-present=no" <<<"$sidecar_start_log"; then
  echo "FAIL: sidecar saw the app's marker already present at its own start (app started first)"
  fail=true
fi
if ! grep -q "START-ORDER: sidecar-marker-present=yes" <<<"$app_start_log"; then
  echo "FAIL: app did not see the sidecar's marker at its own start (sidecar had not started yet)"
  fail=true
fi
if [[ "$fail" == false ]]; then
  echo "PASS: sidecar started before the app container"
fi

echo "==> checking stop ordering"
# Start following the sidecar's log BEFORE triggering deletion — `kubectl logs` after the pod is
# gone can race pod garbage collection and return "not found" instead of the trap's last line,
# even with a multi-second termination grace period (observed directly while building this check).
log_file="$(mktemp)"
kubectl --context "kind-$CLUSTER_NAME" logs -f lifecycle-check -c sidecar >"$log_file" 2>&1 &
follow_pid=$!
sleep 1
kubectl --context "kind-$CLUSTER_NAME" delete pod lifecycle-check --wait=false >/dev/null
sleep 5
kill "$follow_pid" 2>/dev/null || true
wait "$follow_pid" 2>/dev/null || true

if ! grep -q "STOP-ORDER: app-marker-present=no" "$log_file"; then
  echo "FAIL: sidecar saw the app's marker still present when it received SIGTERM (app had not stopped yet)"
  echo "--- sidecar log ---"
  cat "$log_file"
  fail=true
else
  echo "PASS: sidecar stopped after the app container"
fi
rm -f "$log_file"

if [[ "$fail" == true ]]; then
  echo "==> RESULT: FAIL"
  exit 1
fi
echo "==> RESULT: PASS — native-sidecar start-before/stop-after ordering confirmed"
