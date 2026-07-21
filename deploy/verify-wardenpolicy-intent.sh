#!/usr/bin/env bash
# Verifies W-306's acceptance criteria for real, end-to-end: a WardenPolicy's scheduled window
# actually resizes a real demo workload. Runs the REAL agent image (the actual init-container
# sidecar, not a kubectl-exec'd test driver like W-206's) alongside the REAL controller process,
# both against a real kind cluster (constitution §8) — the capstone check tying M2's shrink/grow
# sequences to M3's schedule evaluation via the pod-annotation intent handoff.
#
# Manual-run only, matching every other check in this directory.
#
# Usage:
#   deploy/verify-wardenpolicy-intent.sh              # spins up + tears down its own kind cluster
#   deploy/verify-wardenpolicy-intent.sh --keep        # leaves the cluster up for inspection
#   deploy/verify-wardenpolicy-intent.sh --cluster N   # reuse an existing kind cluster named N
set -euo pipefail

CLUSTER_NAME="warden-intent-check"
KEEP_CLUSTER=false
OWN_CLUSTER=true
SCRIPT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
REPO_ROOT="$(cd "$SCRIPT_DIR/.." && pwd)"
IMAGE="warden-intent-check:local"
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
    kubectl --context "kind-$CLUSTER_NAME" delete pod warden-demo-e2e --ignore-not-found --wait=false >/dev/null 2>&1 || true
    kubectl --context "kind-$CLUSTER_NAME" delete wardenpolicy warden-demo-e2e-policy --ignore-not-found --wait=false >/dev/null 2>&1 || true
    if [[ "$OWN_CLUSTER" == true ]]; then
      kind delete cluster --name "$CLUSTER_NAME" >/dev/null 2>&1 || true
    fi
  fi
}
trap cleanup EXIT

echo "==> building the agent image and warden-crd-model/warden-controller"
docker build -q -t "$IMAGE" "$REPO_ROOT" >/dev/null
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
kind load docker-image "$IMAGE" --name "$CLUSTER_NAME" >/dev/null

echo "==> applying the WardenPolicy CRD, the demo pod, and its policy"
kubectl --context "kind-$CLUSTER_NAME" apply -f "$GENERATED_CRD" >/dev/null
kubectl --context "kind-$CLUSTER_NAME" wait --for=condition=Established \
  crd/wardenpolicies.warden.mnemo.io --timeout=30s >/dev/null

manifest="$(mktemp)"
WARDEN_IMAGE="$IMAGE" envsubst '${WARDEN_IMAGE}' < "$SCRIPT_DIR/wardenpolicy-demo-pod.yaml.tmpl" > "$manifest"
kubectl --context "kind-$CLUSTER_NAME" apply -f "$manifest" >/dev/null
rm -f "$manifest"
kubectl --context "kind-$CLUSTER_NAME" wait --for=condition=Ready pod/warden-demo-e2e --timeout=120s >/dev/null

echo "==> confirming the demo app is genuinely idle before the resize (retain 10Mi, matching W-206)"
for _ in $(seq 1 30); do
  if kubectl --context "kind-$CLUSTER_NAME" logs warden-demo-e2e -c app 2>/dev/null | grep -q "load-target ready"; then
    break
  fi
  sleep 1
done

before_limit="$(kubectl --context "kind-$CLUSTER_NAME" get pod warden-demo-e2e \
  -o jsonpath='{.status.containerStatuses[?(@.name=="app")].resources.limits.memory}')"
echo "    initial app container limit: $before_limit"

kubectl --context "kind-$CLUSTER_NAME" apply -f "$SCRIPT_DIR/wardenpolicy-demo-policy.yaml" >/dev/null

echo "==> starting the real WardenController (out-of-cluster, kind's own kubeconfig context)"
CLASSPATH="$REPO_ROOT/warden-controller/target/classes:$(cat "$CLASSPATH_FILE")"
java -cp "$CLASSPATH" io.github.baokhang83.mnemo.warden.controller.WardenController \
  >"$CONTROLLER_LOG" 2>&1 &
CONTROLLER_PID=$!

echo "==> waiting for the schedule to reach status.currentProfile, the agent to read the intent,"
echo "    and a real resize to land on the demo pod's app container"
fail=false
after_limit=""
for _ in $(seq 1 60); do
  after_limit="$(kubectl --context "kind-$CLUSTER_NAME" get pod warden-demo-e2e \
    -o jsonpath='{.status.containerStatuses[?(@.name=="app")].resources.limits.memory}' 2>/dev/null || true)"
  [[ "$after_limit" != "$before_limit" ]] && [[ -n "$after_limit" ]] && break
  sleep 2
done

current_profile="$(kubectl --context "kind-$CLUSTER_NAME" get wardenpolicy warden-demo-e2e-policy \
  -o jsonpath='{.status.currentProfile}' 2>/dev/null || true)"
restarts="$(kubectl --context "kind-$CLUSTER_NAME" get pod warden-demo-e2e \
  -o jsonpath='{.status.containerStatuses[?(@.name=="app")].restartCount}' 2>/dev/null || true)"

if [[ "$current_profile" != "demo-target" ]]; then
  echo "FAIL: expected WardenPolicy status.currentProfile=\"demo-target\", got \"$current_profile\""
  fail=true
fi
if [[ "$after_limit" == "$before_limit" ]]; then
  echo "FAIL: app container's memory limit never changed from $before_limit"
  fail=true
elif [[ "$after_limit" != "150Mi" ]]; then
  # The API server normalizes an exact-Mi byte count back to "150Mi" in status, not the raw byte
  # string PATCHed — the same normalization PodResizeClient's own javadoc already documents.
  echo "FAIL: expected app container limit \"150Mi\", got \"$after_limit\""
  fail=true
else
  echo "PASS: app container resized $before_limit -> $after_limit, driven end-to-end by the schedule"
fi
if [[ "$restarts" != "0" ]]; then
  echo "FAIL: app container restarted ($restarts times) — possible OOMKill during the real resize"
  fail=true
fi

if [[ "$fail" == true ]]; then
  echo "--- controller log ---"
  cat "$CONTROLLER_LOG"
  echo "--- agent (warden container) log ---"
  kubectl --context "kind-$CLUSTER_NAME" logs warden-demo-e2e -c warden 2>&1 || true
  echo "==> RESULT: FAIL"
  exit 1
fi
echo "==> RESULT: PASS — a scheduled window drove a real, end-to-end resize of a demo workload"
