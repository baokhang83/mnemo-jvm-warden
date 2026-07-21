#!/usr/bin/env bash
# Verifies W-302's acceptance criterion for real: java-operator-sdk's watch -> reconcile ->
# status-patch loop actually works against a real cluster, not just a unit test of the pure
# placeholder-selection logic (constitution §8). Runs the real WardenController process
# (out-of-cluster, against kind's own kubeconfig context — production runs in-cluster and needs
# no such override) and confirms a real WardenPolicy's status.currentProfile gets patched.
#
# Manual-run only, matching W-202/W-206/W-301's precedent.
#
# Usage:
#   deploy/verify-wardenpolicy-reconciler.sh              # spins up + tears down its own kind cluster
#   deploy/verify-wardenpolicy-reconciler.sh --keep        # leaves the cluster up for inspection
#   deploy/verify-wardenpolicy-reconciler.sh --cluster N   # reuse an existing kind cluster named N
set -euo pipefail

CLUSTER_NAME="warden-reconciler-check"
KEEP_CLUSTER=false
OWN_CLUSTER=true
SCRIPT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
REPO_ROOT="$(cd "$SCRIPT_DIR/.." && pwd)"
GENERATED_CRD="$REPO_ROOT/warden-crd-model/target/classes/META-INF/fabric8/wardenpolicies.warden.mnemo.io-v1.yml"
CLASSPATH_FILE="$(mktemp)"
CONTROLLER_LOG="$(mktemp)"
CONTROLLER_PID=""

while [[ $# -gt 0 ]]; do
  case "$1" in
    --keep) KEEP_CLUSTER=true; shift ;;
    --cluster) CLUSTER_NAME="$2"; OWN_CLUSTER=false; shift 2 ;;
    *) echo "unknown argument: $1" >&2; exit 2 ;;
  esac
done

cleanup() {
  if [[ -n "$CONTROLLER_PID" ]]; then
    kill "$CONTROLLER_PID" >/dev/null 2>&1 || true
    wait "$CONTROLLER_PID" 2>/dev/null || true
  fi
  rm -f "$CLASSPATH_FILE" "$CONTROLLER_LOG"
  if [[ "$KEEP_CLUSTER" == false ]]; then
    kubectl --context "kind-$CLUSTER_NAME" delete wardenpolicy warden-policy-sample \
      --ignore-not-found --wait=false >/dev/null 2>&1 || true
    if [[ "$OWN_CLUSTER" == true ]]; then
      kind delete cluster --name "$CLUSTER_NAME" >/dev/null 2>&1 || true
    fi
  fi
}
trap cleanup EXIT

echo "==> building warden-crd-model and warden-controller"
# warden-controller's own dependency:build-classpath run (below) needs warden-crd-model
# resolvable from the local repo, not just present in this reactor — install it there first.
(cd "$REPO_ROOT" && mvn -q install -pl warden-crd-model -am -DskipTests)
(cd "$REPO_ROOT" && mvn -q -pl warden-controller -am process-classes)
(cd "$REPO_ROOT" && mvn -q -pl warden-controller dependency:build-classpath -Dmdep.outputFile="$CLASSPATH_FILE")
if [[ ! -f "$GENERATED_CRD" ]]; then
  echo "FAIL: expected generated CRD at $GENERATED_CRD, not found" >&2
  exit 1
fi

if [[ "$OWN_CLUSTER" == true ]]; then
  echo "==> creating throwaway kind cluster ($CLUSTER_NAME)"
  kind create cluster --name "$CLUSTER_NAME" >/dev/null
  kubectl --context "kind-$CLUSTER_NAME" wait --for=condition=Ready node --all --timeout=90s >/dev/null
else
  kubectl config use-context "kind-$CLUSTER_NAME" >/dev/null
fi

echo "==> applying the WardenPolicy CRD and a sample policy"
kubectl --context "kind-$CLUSTER_NAME" apply -f "$GENERATED_CRD" >/dev/null
kubectl --context "kind-$CLUSTER_NAME" wait --for=condition=Established \
  crd/wardenpolicies.warden.mnemo.io --timeout=30s >/dev/null
kubectl --context "kind-$CLUSTER_NAME" apply -f "$SCRIPT_DIR/wardenpolicy-sample-valid.yaml" >/dev/null

echo "==> starting the real WardenController (out-of-cluster, kind's own kubeconfig context)"
# The controller has no in-cluster config here — it picks up the current kubeconfig context,
# which kind just set. Production runs in-pod, where Fabric8's default auto-detection instead
# finds the in-cluster service-account config; nothing about WardenController itself changes.
CLASSPATH="$REPO_ROOT/warden-controller/target/classes:$(cat "$CLASSPATH_FILE")"
java -cp "$CLASSPATH" io.github.baokhang83.mnemo.warden.controller.WardenController \
  >"$CONTROLLER_LOG" 2>&1 &
CONTROLLER_PID=$!

echo "==> waiting for status.currentProfile to be patched"
fail=false
current_profile=""
for _ in $(seq 1 30); do
  current_profile="$(kubectl --context "kind-$CLUSTER_NAME" get wardenpolicy warden-policy-sample \
    -o jsonpath='{.status.currentProfile}' 2>/dev/null || true)"
  [[ -n "$current_profile" ]] && break
  sleep 1
done

if [[ "$current_profile" == "off-peak" ]]; then
  echo "PASS: status.currentProfile patched to \"off-peak\" (alphabetically first of off-peak/peak)"
else
  echo "FAIL: expected status.currentProfile=\"off-peak\", got \"$current_profile\""
  echo "--- controller log ---"
  cat "$CONTROLLER_LOG"
  fail=true
fi

if [[ "$fail" == true ]]; then
  echo "==> RESULT: FAIL"
  exit 1
fi
echo "==> RESULT: PASS — the reconciler watches WardenPolicy and patches status for real"
