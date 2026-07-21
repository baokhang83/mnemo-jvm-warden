#!/usr/bin/env bash
# Verifies W-401's acceptance criterion for real: PrometheusMetricSource evaluates a real PromQL
# query against a real Prometheus instance on the reconciler's own cadence, and the latest value
# lands in status.currentMetricValue — not just a unit test of the JSON parsing (constitution §8).
#
# The controller runs out-of-cluster (same as every other check in this directory), so it can't
# resolve in-cluster DNS or reach a ClusterIP service directly — this port-forwards Prometheus's
# Service to localhost instead, the same way any out-of-cluster tool would reach it.
#
# Manual-run only, matching every other check in this directory.
#
# Usage:
#   deploy/verify-prometheus-metric-source.sh              # spins up + tears down its own kind cluster
#   deploy/verify-prometheus-metric-source.sh --keep        # leaves the cluster up for inspection
#   deploy/verify-prometheus-metric-source.sh --cluster N   # reuse an existing kind cluster named N
set -euo pipefail

CLUSTER_NAME="warden-metric-check"
KEEP_CLUSTER=false
OWN_CLUSTER=true
SCRIPT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
REPO_ROOT="$(cd "$SCRIPT_DIR/.." && pwd)"
GENERATED_CRD="$REPO_ROOT/warden-crd-model/target/classes/META-INF/fabric8/wardenpolicies.warden.mnemo.io-v1.yml"
CLASSPATH_FILE="$(mktemp)"
CONTROLLER_LOG="$(mktemp)"
PORT_FORWARD_LOG="$(mktemp)"
CONTROLLER_PID=""
PORT_FORWARD_PID=""
PROMETHEUS_LOCAL_PORT=39090

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
  if [[ -n "$PORT_FORWARD_PID" ]]; then
    kill "$PORT_FORWARD_PID" >/dev/null 2>&1 || true
    wait "$PORT_FORWARD_PID" 2>/dev/null || true
  fi
  rm -f "$CLASSPATH_FILE" "$CONTROLLER_LOG" "$PORT_FORWARD_LOG"
  if [[ "$KEEP_CLUSTER" == false ]]; then
    kubectl --context "kind-$CLUSTER_NAME" delete -f "$SCRIPT_DIR/prometheus-demo.yaml" --ignore-not-found --wait=false >/dev/null 2>&1 || true
    kubectl --context "kind-$CLUSTER_NAME" delete wardenpolicy warden-demo-metric-policy --ignore-not-found --wait=false >/dev/null 2>&1 || true
    if [[ "$OWN_CLUSTER" == true ]]; then
      kind delete cluster --name "$CLUSTER_NAME" >/dev/null 2>&1 || true
    fi
  fi
}
trap cleanup EXIT

echo "==> building warden-crd-model and warden-controller"
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

echo "==> applying the WardenPolicy CRD and a real Prometheus"
kubectl --context "kind-$CLUSTER_NAME" apply -f "$GENERATED_CRD" >/dev/null
kubectl --context "kind-$CLUSTER_NAME" wait --for=condition=Established \
  crd/wardenpolicies.warden.mnemo.io --timeout=30s >/dev/null
kubectl --context "kind-$CLUSTER_NAME" apply -f "$SCRIPT_DIR/prometheus-demo.yaml" >/dev/null
kubectl --context "kind-$CLUSTER_NAME" wait --for=condition=Available deployment/warden-demo-prometheus --timeout=120s >/dev/null

echo "==> port-forwarding Prometheus to localhost:$PROMETHEUS_LOCAL_PORT (the out-of-cluster controller can't reach the ClusterIP directly)"
kubectl --context "kind-$CLUSTER_NAME" port-forward svc/warden-demo-prometheus "$PROMETHEUS_LOCAL_PORT:9090" \
  >"$PORT_FORWARD_LOG" 2>&1 &
PORT_FORWARD_PID=$!
for _ in $(seq 1 30); do
  if grep -q "Forwarding from" "$PORT_FORWARD_LOG" 2>/dev/null; then
    break
  fi
  sleep 1
done

echo "==> applying the guardrail-only WardenPolicy"
kubectl --context "kind-$CLUSTER_NAME" apply -f "$SCRIPT_DIR/wardenpolicy-demo-metric-policy.yaml" >/dev/null

echo "==> starting the real WardenController (out-of-cluster, kind's own kubeconfig context)"
CLASSPATH="$REPO_ROOT/warden-controller/target/classes:$(cat "$CLASSPATH_FILE")"
WARDEN_PROMETHEUS_URL="http://127.0.0.1:$PROMETHEUS_LOCAL_PORT" \
  java -cp "$CLASSPATH" io.github.baokhang83.mnemo.warden.controller.WardenController \
  >"$CONTROLLER_LOG" 2>&1 &
CONTROLLER_PID=$!

echo "==> waiting for status.currentMetricValue to be patched"
fail=false
metric_value=""
for _ in $(seq 1 30); do
  metric_value="$(kubectl --context "kind-$CLUSTER_NAME" get wardenpolicy warden-demo-metric-policy \
    -o jsonpath='{.status.currentMetricValue}' 2>/dev/null || true)"
  [[ -n "$metric_value" ]] && break
  sleep 2
done

if [[ "$metric_value" == "1.0" || "$metric_value" == "1" ]]; then
  echo "PASS: status.currentMetricValue=\"$metric_value\" — vector(1) evaluated against a real Prometheus"
else
  echo "FAIL: expected status.currentMetricValue=\"1.0\", got \"$metric_value\""
  echo "--- controller log ---"
  cat "$CONTROLLER_LOG"
  fail=true
fi

if [[ "$fail" == true ]]; then
  echo "==> RESULT: FAIL"
  exit 1
fi
echo "==> RESULT: PASS — a real PromQL query, evaluated against a real Prometheus, reaches status.currentMetricValue"
