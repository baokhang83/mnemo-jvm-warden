#!/usr/bin/env bash
# W-804: proves, against a real API server, that the RBAC this project ships grants exactly the
# operations docs/rbac-mapping.md documents — and nothing else. `kubectl auth can-i` answers this
# authoritatively (constitution §8: a manifest that looks right in YAML still needs a real API
# server to prove it's enforced), independent of whether any workload actually exercises the
# permission.
#
# Two service accounts are checked:
#   - warden-controller  (the Helm chart's ClusterRole, cluster-wide)
#   - warden-example      (the sidecar's per-pod Role, deploy/example-sidecar.yaml)
#
# For each, every verb/resource pair in docs/rbac-mapping.md's tables must be ALLOWED, and a
# representative set of verbs/resources NOT in those tables (secrets, pods/exec, workload delete,
# any nodes verb) must be DENIED. The sidecar check also proves its Role's `resourceNames` scoping
# actually restricts pods/resize and pods/get to its own pod, not a different one.
#
# Doesn't touch WardenPolicy/the controller's actual reconcile behavior at all — this is a pure
# RBAC check, complementary to deploy/verify-cross-pod-resize-denied.sh (which covers the
# Deployment/StatefulSet ClusterRole shape's admission-policy backstop, not this project's own
# baseline grants).
#
# Manual-run only, matching every other check in this directory.
#
# Usage:
#   deploy/verify-rbac-boundaries.sh              # spins up + tears down its own kind cluster
#   deploy/verify-rbac-boundaries.sh --keep        # leaves the cluster + resources up for inspection
#   deploy/verify-rbac-boundaries.sh --cluster N   # reuse an existing kind cluster named N
set -euo pipefail

CLUSTER_NAME="warden-rbac-boundaries-check"
KEEP_CLUSTER=false
OWN_CLUSTER=true
SCRIPT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
REPO_ROOT="$(cd "$SCRIPT_DIR/.." && pwd)"
CONTROLLER_SA="system:serviceaccount:default:warden-controller"
SIDECAR_SA="system:serviceaccount:default:warden-example"
OTHER_NS="warden-rbac-check-other-ns"

while [[ $# -gt 0 ]]; do
  case "$1" in
    --keep) KEEP_CLUSTER=true; shift ;;
    --cluster) CLUSTER_NAME="$2"; OWN_CLUSTER=false; shift 2 ;;
    *) echo "unknown argument: $1" >&2; exit 2 ;;
  esac
done

cleanup() {
  if [[ "$KEEP_CLUSTER" == false ]]; then
    helm --kube-context "kind-$CLUSTER_NAME" uninstall warden >/dev/null 2>&1 || true
    kubectl --context "kind-$CLUSTER_NAME" delete -f "$SCRIPT_DIR/example-sidecar.yaml" --ignore-not-found >/dev/null 2>&1 || true
    kubectl --context "kind-$CLUSTER_NAME" delete namespace "$OTHER_NS" --ignore-not-found --wait=false >/dev/null 2>&1 || true
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
else
  kubectl config use-context "kind-$CLUSTER_NAME" >/dev/null
fi

echo "==> installing the CRD + Helm chart (controller RBAC only; no image needs to actually run)"
kubectl --context "kind-$CLUSTER_NAME" apply -f "$REPO_ROOT/charts/warden/crds/wardenpolicy-crd.yaml" >/dev/null
helm --kube-context "kind-$CLUSTER_NAME" install warden "$REPO_ROOT/charts/warden" >/dev/null

echo "==> applying the sidecar's ServiceAccount/Role/RoleBinding (deploy/example-sidecar.yaml; not the Pod itself)"
kubectl --context "kind-$CLUSTER_NAME" apply -f "$SCRIPT_DIR/example-sidecar.yaml" >/dev/null
# A second, unrelated pod name to prove resourceNames scoping actually excludes it.
kubectl --context "kind-$CLUSTER_NAME" run warden-example-decoy --image=pause --restart=Never >/dev/null 2>&1 || true
kubectl --context "kind-$CLUSTER_NAME" create namespace "$OTHER_NS" >/dev/null

fail=false

check() {
  local expect="$1" as="$2" ns="$3" desc="$4"
  shift 4
  local result
  if kubectl --context "kind-$CLUSTER_NAME" auth can-i "$@" --as="$as" -n "$ns" >/dev/null 2>&1; then
    result="yes"
  else
    result="no"
  fi
  if [[ "$result" == "$expect" ]]; then
    echo "PASS ($expect): $desc"
  else
    echo "FAIL: expected '$expect' but got '$result': $desc"
    fail=true
  fi
}

echo "==> controller ServiceAccount ($CONTROLLER_SA): grants documented in docs/rbac-mapping.md"
check yes "$CONTROLLER_SA" default "get wardenpolicies (own namespace)" get wardenpolicies.warden.mnemo.io
check yes "$CONTROLLER_SA" "$OTHER_NS" "list wardenpolicies (cluster-wide watch, unrelated namespace)" list wardenpolicies.warden.mnemo.io
check yes "$CONTROLLER_SA" default "patch wardenpolicies/status" patch wardenpolicies.warden.mnemo.io --subresource=status
check yes "$CONTROLLER_SA" default "get deployments (targetRef resolution)" get deployments.apps
check yes "$CONTROLLER_SA" default "get statefulsets (targetRef resolution)" get statefulsets.apps
check yes "$CONTROLLER_SA" default "list pods (selector resolution)" list pods
check yes "$CONTROLLER_SA" default "patch pods (annotation handoff)" patch pods
check yes "$CONTROLLER_SA" "$OTHER_NS" "patch pods in an unrelated namespace (ClusterRole is cluster-wide)" patch pods

echo "==> controller ServiceAccount: operations NOT documented must be denied"
check no "$CONTROLLER_SA" default "get secrets" get secrets
check no "$CONTROLLER_SA" default "create pods/exec" create pods --subresource=exec
check no "$CONTROLLER_SA" default "delete pods" delete pods
check no "$CONTROLLER_SA" default "patch pods/resize" patch pods --subresource=resize
check no "$CONTROLLER_SA" default "delete deployments" delete deployments.apps
check no "$CONTROLLER_SA" default "patch nodes" patch nodes
check no "$CONTROLLER_SA" default "update wardenpolicies (only status is patchable)" update wardenpolicies.warden.mnemo.io

echo "==> sidecar ServiceAccount ($SIDECAR_SA): grants scoped to its own pod (warden-example)"
check yes "$SIDECAR_SA" default "get its own pod" get pods/warden-example
check yes "$SIDECAR_SA" default "patch its own pod's resize subresource" patch pods/warden-example --subresource=resize

echo "==> sidecar ServiceAccount: resourceNames scoping excludes a different pod"
check no "$SIDECAR_SA" default "get a different pod" get pods/warden-example-decoy
check no "$SIDECAR_SA" default "patch a different pod's resize subresource" patch pods/warden-example-decoy --subresource=resize

echo "==> sidecar ServiceAccount: operations NOT documented must be denied"
check no "$SIDECAR_SA" default "get secrets" get secrets
check no "$SIDECAR_SA" default "create pods/exec" create pods/warden-example --subresource=exec
check no "$SIDECAR_SA" default "delete its own pod" delete pods/warden-example
check no "$SIDECAR_SA" default "list pods (only get is granted)" list pods
check no "$SIDECAR_SA" default "patch nodes" patch nodes
check no "$SIDECAR_SA" default "get wardenpolicies (sidecar has no CRD access at all)" get wardenpolicies.warden.mnemo.io

if [[ "$fail" == true ]]; then
  echo "==> RESULT: FAIL"
  exit 1
fi
echo "==> RESULT: PASS — both service accounts hold exactly the documented grants, no more"
