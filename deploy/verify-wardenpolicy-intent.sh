#!/usr/bin/env bash
# Verifies W-306/#69's acceptance criteria for real, end-to-end: a WardenPolicy's scheduled
# window actually resizes a real demo workload — both a directly-named Pod (W-306) and a
# Deployment resolved to its live replicas via its own label selector (#69). Runs the REAL agent
# image (the actual init-container sidecar, not a kubectl-exec'd test driver like W-206's)
# alongside the REAL controller process, both against a real kind cluster (constitution §8) —
# the capstone check tying M2's shrink/grow sequences to M3's schedule evaluation via the
# pod-annotation intent handoff.
#
# Manual-run only, matching every other check in this directory.
#
# Usage:
#   deploy/verify-wardenpolicy-intent.sh              # spins up + tears down its own kind cluster
#   deploy/verify-wardenpolicy-intent.sh --keep        # leaves the cluster + pods up for inspection
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
DEPLOY_SELECTOR="app.kubernetes.io/name=warden-demo-e2e-deploy"

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
    kubectl --context "kind-$CLUSTER_NAME" delete deployment warden-demo-e2e-deploy --ignore-not-found --wait=false >/dev/null 2>&1 || true
    kubectl --context "kind-$CLUSTER_NAME" delete wardenpolicy warden-demo-e2e-deploy-policy --ignore-not-found --wait=false >/dev/null 2>&1 || true
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

echo "==> applying the WardenPolicy CRD"
kubectl --context "kind-$CLUSTER_NAME" apply -f "$GENERATED_CRD" >/dev/null
kubectl --context "kind-$CLUSTER_NAME" wait --for=condition=Established \
  crd/wardenpolicies.warden.mnemo.io --timeout=30s >/dev/null

echo "==> applying the single-pod demo (W-306) and the 2-replica Deployment demo (#69)"
pod_manifest="$(mktemp)"
WARDEN_IMAGE="$IMAGE" envsubst '${WARDEN_IMAGE}' < "$SCRIPT_DIR/wardenpolicy-demo-pod.yaml.tmpl" > "$pod_manifest"
kubectl --context "kind-$CLUSTER_NAME" apply -f "$pod_manifest" >/dev/null
rm -f "$pod_manifest"

deploy_manifest="$(mktemp)"
WARDEN_IMAGE="$IMAGE" envsubst '${WARDEN_IMAGE}' < "$SCRIPT_DIR/wardenpolicy-demo-deployment.yaml.tmpl" > "$deploy_manifest"
kubectl --context "kind-$CLUSTER_NAME" apply -f "$deploy_manifest" >/dev/null
rm -f "$deploy_manifest"

kubectl --context "kind-$CLUSTER_NAME" wait --for=condition=Ready pod/warden-demo-e2e --timeout=120s >/dev/null
kubectl --context "kind-$CLUSTER_NAME" wait --for=condition=Ready pod -l "$DEPLOY_SELECTOR" --timeout=120s >/dev/null

deploy_pods="$(kubectl --context "kind-$CLUSTER_NAME" get pods -l "$DEPLOY_SELECTOR" -o jsonpath='{.items[*].metadata.name}')"
echo "    deployment replicas: $deploy_pods"

echo "==> confirming both demos' apps are genuinely idle before the resize (retain 10Mi, matching W-206)"
for pod in warden-demo-e2e $deploy_pods; do
  for _ in $(seq 1 30); do
    if kubectl --context "kind-$CLUSTER_NAME" logs "$pod" -c app 2>/dev/null | grep -q "load-target ready"; then
      break
    fi
    sleep 1
  done
done

pod_before="$(kubectl --context "kind-$CLUSTER_NAME" get pod warden-demo-e2e \
  -o jsonpath='{.status.containerStatuses[?(@.name=="app")].resources.limits.memory}')"
echo "    single-pod initial app container limit: $pod_before"

# Every replica comes from the identical PodTemplateSpec, so all start at the same limit — one
# shared value, not a per-pod map (bash 3.2, macOS's default, has no associative arrays).
deploy_before=""
for pod in $deploy_pods; do
  limit="$(kubectl --context "kind-$CLUSTER_NAME" get pod "$pod" \
    -o jsonpath='{.status.containerStatuses[?(@.name=="app")].resources.limits.memory}')"
  echo "    deployment replica $pod initial app container limit: $limit"
  deploy_before="$limit"
done

echo "==> applying both WardenPolicies (targetRef kind: Pod, and kind: Deployment)"
kubectl --context "kind-$CLUSTER_NAME" apply -f "$SCRIPT_DIR/wardenpolicy-demo-policy.yaml" >/dev/null
kubectl --context "kind-$CLUSTER_NAME" apply -f "$SCRIPT_DIR/wardenpolicy-demo-deployment-policy.yaml" >/dev/null

echo "==> starting the real WardenController (out-of-cluster, kind's own kubeconfig context)"
CLASSPATH="$REPO_ROOT/warden-controller/target/classes:$(cat "$CLASSPATH_FILE")"
java -cp "$CLASSPATH" io.github.baokhang83.mnemo.warden.controller.WardenController \
  >"$CONTROLLER_LOG" 2>&1 &
CONTROLLER_PID=$!

fail=false

echo "==> [W-306] waiting for the single pod to resize"
pod_after=""
for _ in $(seq 1 60); do
  pod_after="$(kubectl --context "kind-$CLUSTER_NAME" get pod warden-demo-e2e \
    -o jsonpath='{.status.containerStatuses[?(@.name=="app")].resources.limits.memory}' 2>/dev/null || true)"
  [[ "$pod_after" != "$pod_before" ]] && [[ -n "$pod_after" ]] && break
  sleep 2
done

pod_profile="$(kubectl --context "kind-$CLUSTER_NAME" get wardenpolicy warden-demo-e2e-policy \
  -o jsonpath='{.status.currentProfile}' 2>/dev/null || true)"
pod_restarts="$(kubectl --context "kind-$CLUSTER_NAME" get pod warden-demo-e2e \
  -o jsonpath='{.status.containerStatuses[?(@.name=="app")].restartCount}' 2>/dev/null || true)"

if [[ "$pod_profile" != "demo-target" ]]; then
  echo "FAIL: [pod] expected status.currentProfile=\"demo-target\", got \"$pod_profile\""
  fail=true
elif [[ "$pod_after" == "$pod_before" || "$pod_after" != "150Mi" ]]; then
  # The API server normalizes an exact-Mi byte count back to "150Mi" in status, not the raw byte
  # string PATCHed — the same normalization PodResizeClient's own javadoc already documents.
  echo "FAIL: [pod] expected app container limit \"150Mi\", got \"$pod_after\""
  fail=true
elif [[ "$pod_restarts" != "0" ]]; then
  echo "FAIL: [pod] app container restarted ($pod_restarts times) — possible OOMKill"
  fail=true
else
  echo "PASS: [pod] resized $pod_before -> $pod_after, driven end-to-end by the schedule"
fi

echo "==> [#69] waiting for every Deployment replica to resize"
for pod in $deploy_pods; do
  after=""
  for _ in $(seq 1 60); do
    after="$(kubectl --context "kind-$CLUSTER_NAME" get pod "$pod" \
      -o jsonpath='{.status.containerStatuses[?(@.name=="app")].resources.limits.memory}' 2>/dev/null || true)"
    [[ "$after" != "$deploy_before" ]] && [[ -n "$after" ]] && break
    sleep 2
  done
  restarts="$(kubectl --context "kind-$CLUSTER_NAME" get pod "$pod" \
    -o jsonpath='{.status.containerStatuses[?(@.name=="app")].restartCount}' 2>/dev/null || true)"

  if [[ "$after" == "$deploy_before" || "$after" != "150Mi" ]]; then
    echo "FAIL: [deploy $pod] expected app container limit \"150Mi\", got \"$after\""
    fail=true
  elif [[ "$restarts" != "0" ]]; then
    echo "FAIL: [deploy $pod] app container restarted ($restarts times) — possible OOMKill"
    fail=true
  else
    echo "PASS: [deploy $pod] resized $deploy_before -> $after"
  fi
done

deploy_profile="$(kubectl --context "kind-$CLUSTER_NAME" get wardenpolicy warden-demo-e2e-deploy-policy \
  -o jsonpath='{.status.currentProfile}' 2>/dev/null || true)"
if [[ "$deploy_profile" != "demo-target" ]]; then
  echo "FAIL: [deploy] expected status.currentProfile=\"demo-target\", got \"$deploy_profile\""
  fail=true
else
  echo "PASS: [deploy] status.currentProfile=\"demo-target\" for the Deployment-targeted policy"
fi

if [[ "$fail" == true ]]; then
  echo "--- controller log ---"
  cat "$CONTROLLER_LOG"
  echo "--- agent (warden container) log, single pod ---"
  kubectl --context "kind-$CLUSTER_NAME" logs warden-demo-e2e -c warden 2>&1 || true
  echo "==> RESULT: FAIL"
  exit 1
fi
echo "==> RESULT: PASS — a scheduled window drove a real, end-to-end resize of a directly-named pod and every replica of a Deployment"
