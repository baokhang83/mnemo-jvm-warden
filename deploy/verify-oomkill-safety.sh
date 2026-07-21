#!/usr/bin/env bash
# Verifies W-206's acceptance criterion for real: under adversarial load, a shrink attempt is a
# safe no-op (cgroup untouched, no OOMKill); under idle load, a shrink attempt actually completes
# (cgroup lowered, no OOMKill). Drives a real ShrinkSequence against a real attached target and a
# real Kubernetes API via harness.ShrinkTrialDriver (kubectl exec'd into the running sidecar) —
# see docs/fluencyloop/features/w-206-*/design.md for why a test-only driver exists instead of
# waiting on M3's intent handoff, and deploy/README.md for how to run this manually.
#
# Manual-run only for now, matching W-202's verify-lifecycle-ordering.sh precedent — CI wiring
# (building the image + a kind cluster inside GitHub Actions) is a deliberate fast-follow, not
# part of this slice.
#
# Usage:
#   deploy/verify-oomkill-safety.sh              # spins up + tears down its own kind cluster
#   deploy/verify-oomkill-safety.sh --keep        # leaves the cluster + pods up for inspection
#   deploy/verify-oomkill-safety.sh --cluster N   # reuse an existing kind cluster named N
set -euo pipefail

CLUSTER_NAME="warden-oomkill-check"
KEEP_CLUSTER=false
OWN_CLUSTER=true
SCRIPT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
REPO_ROOT="$(cd "$SCRIPT_DIR/.." && pwd)"
IMAGE="warden-oomkill-check:local"

# Byte-exact memory sizes (Mi), matching K8sQuantity's plain-decimal-bytes acceptance.
MI=$((1024 * 1024))
SHRINK_REQUEST_BYTES=$((100 * MI))
SHRINK_LIMIT_BYTES=$((150 * MI))
GC_TIMEOUT_SECONDS=30
RESIZE_TIMEOUT_SECONDS=30

while [[ $# -gt 0 ]]; do
  case "$1" in
    --keep) KEEP_CLUSTER=true; shift ;;
    --cluster) CLUSTER_NAME="$2"; OWN_CLUSTER=false; shift 2 ;;
    *) echo "unknown argument: $1" >&2; exit 2 ;;
  esac
done

overall_fail=false
cleanup() {
  if [[ "$KEEP_CLUSTER" == false ]]; then
    kubectl --context "kind-$CLUSTER_NAME" delete pod oomkill-check-abort oomkill-check-shrink \
      --ignore-not-found --wait=false >/dev/null 2>&1 || true
    if [[ "$OWN_CLUSTER" == true ]]; then
      kind delete cluster --name "$CLUSTER_NAME" >/dev/null 2>&1 || true
    fi
  fi
}
trap cleanup EXIT

if [[ "$OWN_CLUSTER" == true ]]; then
  echo "==> creating throwaway kind cluster ($CLUSTER_NAME)"
  kind create cluster --name "$CLUSTER_NAME" >/dev/null
  kubectl --context "kind-$CLUSTER_NAME" wait --for=condition=Ready node --all --timeout=90s >/dev/null
fi

echo "==> building agent image ($IMAGE)"
docker build -q -t "$IMAGE" "$REPO_ROOT" >/dev/null
kind load docker-image "$IMAGE" --name "$CLUSTER_NAME" >/dev/null

render_manifest() {
  local pod_name="$1" retain_mb="$2" app_request_mi="$3" app_limit_mi="$4" out_file="$5"
  POD_NAME="$pod_name" WARDEN_IMAGE="$IMAGE" RETAIN_MB="$retain_mb" \
    APP_REQUEST="${app_request_mi}Mi" APP_LIMIT="${app_limit_mi}Mi" \
    envsubst '${POD_NAME} ${WARDEN_IMAGE} ${RETAIN_MB} ${APP_REQUEST} ${APP_LIMIT}' \
    < "$SCRIPT_DIR/oomkill-safety-check.yaml.tmpl" > "$out_file"
}

apply_and_wait() {
  local pod_name="$1" manifest="$2"
  kubectl --context "kind-$CLUSTER_NAME" apply -f "$manifest" >/dev/null
  # Same asynchronous-ServiceAccount race as verify-lifecycle-ordering.sh's retry loop.
  kubectl --context "kind-$CLUSTER_NAME" wait --for=condition=Ready "pod/$pod_name" --timeout=120s >/dev/null
  wait_for_load_target_ready "$pod_name"
}

# Pod Ready only reflects the WARDEN sidecar's own /readyz (a target is attached), not whether
# LoadTarget has *finished* retaining its live set — for the 220Mi scenario that retain() loop is
# real, non-instant work. Running the trial before it finishes would attach mid-allocation, which
# at minimum makes the RSS reading meaningless and at worst races a JMX call against a JVM still
# GC-churning through startup. Poll the app container's own log line instead of guessing a sleep.
wait_for_load_target_ready() {
  local pod_name="$1"
  for _ in $(seq 1 30); do
    if kubectl --context "kind-$CLUSTER_NAME" logs "$pod_name" -c app 2>/dev/null | grep -q "load-target ready"; then
      return 0
    fi
    sleep 1
  done
  echo "FAIL: LoadTarget in $pod_name never logged ready within 30s" >&2
  return 1
}

container_limit_bytes() {
  local pod_name="$1"
  kubectl --context "kind-$CLUSTER_NAME" get pod "$pod_name" \
    -o jsonpath='{.status.containerStatuses[?(@.name=="app")].resources.limits.memory}'
}

restart_count() {
  local pod_name="$1"
  kubectl --context "kind-$CLUSTER_NAME" get pod "$pod_name" \
    -o jsonpath='{.status.containerStatuses[?(@.name=="app")].restartCount}'
}

# TargetLocator.findTarget() expects exactly ONE other "java" process besides the caller. That
# holds for the real WardenAgent (the only java process in the container), but this harness execs
# a SECOND java process (the driver itself) into the same shareProcessNamespace container, which
# already has WardenAgent's own long-running main loop — three java processes total, ambiguous.
# So the trial explicitly overrides WARDEN_TARGET_PID to the LoadTarget process found by cmdline,
# rather than relying on locator auto-discovery the way production code will.
target_pid() {
  local pod_name="$1"
  kubectl --context "kind-$CLUSTER_NAME" exec "$pod_name" -c warden -- sh -c '
    for p in /proc/[0-9]*; do
      pid="${p#/proc/}"
      if [ -r "$p/cmdline" ] && tr "\0" " " < "$p/cmdline" | grep -q harness.LoadTarget; then
        echo "$pid"
        exit 0
      fi
    done
    exit 1
  '
}

# Deliberately does NOT toggle `set -e`/`set +e` internally: a `return` of this function's
# (expected, non-zero) exit code would itself trip errexit immediately, before the caller ever
# sees it, if errexit were active at that point — bash applies -e to a bare `return N` inside a
# function exactly like any other failing command, regardless of how the *caller* invoked the
# function. Callers must invoke this as `run_trial ... || exit_code=$?` (the exemption bash
# grants a command that is the left side of `||`) rather than capturing `$?` after a bare call.
run_trial() {
  local pod_name="$1"
  local pid
  pid="$(target_pid "$pod_name")" || { echo "FAIL: could not find the LoadTarget process in $pod_name" >&2; return 3; }
  kubectl --context "kind-$CLUSTER_NAME" exec "$pod_name" -c warden -- \
    env "WARDEN_TARGET_PID=$pid" \
    java -cp /app/warden-agent.jar io.github.baokhang83.mnemo.warden.agent.harness.ShrinkTrialDriver \
    "$pod_name" app "$SHRINK_REQUEST_BYTES" "$SHRINK_LIMIT_BYTES" "$GC_TIMEOUT_SECONDS" "$RESIZE_TIMEOUT_SECONDS"
}

# --- Scenario A: adversarial load — must abort safely, cgroup untouched, no OOMKill ---
run_scenario_abort() {
  echo "==> scenario: adversarial load (retain 220Mi, target 150Mi) — expect a safe abort"
  local manifest; manifest="$(mktemp)"
  render_manifest "oomkill-check-abort" 220 400 450 "$manifest"
  apply_and_wait "oomkill-check-abort" "$manifest"
  rm -f "$manifest"

  local before after
  before="$(container_limit_bytes oomkill-check-abort)"

  local exit_code=0
  run_trial oomkill-check-abort || exit_code=$?

  after="$(container_limit_bytes oomkill-check-abort)"
  local restarts; restarts="$(restart_count oomkill-check-abort)"

  local fail=false
  if [[ "$exit_code" != 3 ]]; then
    echo "FAIL: expected the trial to abort (exit 3), got exit $exit_code"
    fail=true
  fi
  if [[ "$after" != "$before" ]]; then
    echo "FAIL: cgroup limit changed on an aborted shrink (before=$before after=$after)"
    fail=true
  fi
  if [[ "$restarts" != "0" ]]; then
    echo "FAIL: app container restarted ($restarts times) — possible OOMKill"
    fail=true
  fi
  if [[ "$fail" == true ]]; then
    echo "==> RESULT (abort scenario): FAIL"
    overall_fail=true
  else
    echo "PASS: aborted correctly, cgroup untouched ($before), no OOMKill"
  fi
}

# --- Scenario B: idle load — shrink must actually complete, no OOMKill ---
run_scenario_shrink() {
  echo "==> scenario: idle load (retain 10Mi, target 150Mi) — expect a completed shrink"
  local manifest; manifest="$(mktemp)"
  render_manifest "oomkill-check-shrink" 10 400 450 "$manifest"
  apply_and_wait "oomkill-check-shrink" "$manifest"
  rm -f "$manifest"

  local before after
  before="$(container_limit_bytes oomkill-check-shrink)"

  local exit_code=0
  run_trial oomkill-check-shrink || exit_code=$?

  after="$(container_limit_bytes oomkill-check-shrink)"
  # Give the kubelet a moment past the confirmed resize to see if the smaller cgroup ever OOMs
  # the now-idle target — the driver only confirms the PATCH, not post-resize stability.
  sleep 5
  local restarts; restarts="$(restart_count oomkill-check-shrink)"
  local phase; phase="$(kubectl --context "kind-$CLUSTER_NAME" get pod oomkill-check-shrink -o jsonpath='{.status.phase}')"

  local fail=false
  if [[ "$exit_code" != 0 ]]; then
    echo "FAIL: expected the trial to complete (exit 0), got exit $exit_code"
    fail=true
  fi
  if [[ "$after" == "$before" ]]; then
    echo "FAIL: cgroup limit unchanged on a completed shrink (still $before)"
    fail=true
  fi
  if [[ "$restarts" != "0" ]]; then
    echo "FAIL: app container restarted ($restarts times) — possible OOMKill after shrinking"
    fail=true
  fi
  if [[ "$phase" != "Running" ]]; then
    echo "FAIL: pod phase is $phase after shrinking, expected Running"
    fail=true
  fi
  if [[ "$fail" == true ]]; then
    echo "==> RESULT (shrink scenario): FAIL"
    overall_fail=true
  else
    echo "PASS: shrank correctly ($before -> $after), no OOMKill, pod still Running"
  fi
}

run_scenario_abort
run_scenario_shrink

if [[ "$overall_fail" == true ]]; then
  echo "==> OVERALL RESULT: FAIL"
  exit 1
fi
echo "==> OVERALL RESULT: PASS — ordering holds under adversarial load, and a safe shrink still completes"
