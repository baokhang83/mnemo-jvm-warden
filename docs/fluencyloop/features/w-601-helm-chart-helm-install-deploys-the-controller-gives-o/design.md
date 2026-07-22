# Design: W-601: Helm chart — helm install deploys the controller + gives operators an includable sidecar template, with values for image, gc timeouts, and an injection toggle

started: 2026-07-22

`charts/warden` ships two things from one install: the controller (Deployment + RBAC + the
WardenPolicy CRD), and a reusable `warden.sidecar` named template an operator's own app chart
includes to get the native-sidecar pattern from `deploy/example-sidecar.yaml` — parameterized
instead of hand-copied. No admission webhook exists (or is being built here); "injection" means a
Helm-level `include`, not a mutation of unrelated pods.

## Class diagram

```mermaid
classDiagram
  class WardenCrdModel {
    <<Maven module>>
    crd-generator-maven-plugin
  }
  class GeneratedCrdYaml {
    <<generated>>
    target/classes/META-INF/fabric8/wardenpolicies...v1.yml
  }
  class ChartCrdCopy {
    <<checked in>>
    charts/warden/crds/wardenpolicy-crd.yaml
  }
  class CiDriftCheck {
    <<CI>>
    regenerate + diff, fail on drift
  }
  class Dockerfile {
    <<existing>>
    builds warden-agent.jar
  }
  class DockerfileController {
    <<new>>
    builds warden-controller.jar
  }
  class AgentImage {
    ghcr.io/.../mnemo-jvm-warden
  }
  class ControllerImage {
    ghcr.io/.../mnemo-jvm-warden-controller
  }
  class ValuesYaml {
    controller.image
    sidecar.image
    sidecar.gc.*
    sidecar.enabled
  }
  class ControllerDeployment {
    templates/controller-deployment.yaml
    templates/controller-rbac.yaml
  }
  class HelpersTpl {
    charts/warden/templates/_helpers.tpl
    warden.fullname, warden.labels, etc.
  }
  class SidecarLibraryChart {
    <<Helm library chart>>
    charts/warden-sidecar
    warden.sidecar named template
  }
  class OperatorAppChart {
    <<outside this repo>>
    depends on warden-sidecar (not warden)
  }

  WardenCrdModel --> GeneratedCrdYaml : generates at process-classes
  GeneratedCrdYaml ..> ChartCrdCopy : checked-in copy
  GeneratedCrdYaml ..> CiDriftCheck : diffed against
  ChartCrdCopy ..> CiDriftCheck : diffed against
  Dockerfile --> AgentImage : builds + publishes
  DockerfileController --> ControllerImage : builds + publishes
  ValuesYaml --> ControllerDeployment : configures
  ControllerImage ..> ControllerDeployment : image ref
  AgentImage ..> SidecarLibraryChart : image ref (via cfg.image)
  SidecarLibraryChart ..> OperatorAppChart : depended on by (type: library - never deploys its own resources)
```

## Sequence: two installs, two lifecycles

```mermaid
sequenceDiagram
  actor Operator
  participant Helm
  participant K8sApi as K8s API
  participant CtrlPod as Controller Pod
  participant AppPod as App Pod

  Note over Operator,CtrlPod: helm install warden charts/warden
  Operator->>Helm: helm install warden charts/warden
  Helm->>K8sApi: apply crds/wardenpolicy-crd.yaml (once)
  Helm->>K8sApi: apply ServiceAccount + ClusterRole(Binding)
  Helm->>K8sApi: apply Deployment (image: values.controller.image)
  K8sApi->>CtrlPod: schedule + pull ghcr.io/.../warden-controller
  CtrlPod->>K8sApi: watch WardenPolicy (informer starts)

  Note over Operator,AppPod: separately: operator's own app chart depends on<br/>charts/warden-sidecar (type: library - never deploys its own resources)
  Note right of Operator: templates/pod.yaml:<br/>{{ include "warden.sidecar" (dict "cfg" .Values.warden) }}
  Operator->>Helm: helm install my-app ./my-app-chart
  Helm->>K8sApi: apply Pod (app container + warden initContainer,<br/>rendered only if sidecar.enabled)
  K8sApi->>AppPod: schedule pod
  Note over AppPod: warden starts first (initContainer,<br/>restartPolicy: Always), app starts after;<br/>warden manages it for the pod's whole life
```

## Decisions

- **Sidecar "injection" is a named Helm template, not a webhook.** No admission webhook exists in
  this codebase; building one is a new controller feature (TLS certs,
  `MutatingWebhookConfiguration`, its own safety review) — not what this ticket's acceptance
  criteria describes. `warden.sidecar` in `_helpers.tpl` mirrors `deploy/example-sidecar.yaml`'s
  already-verified shape, parameterized.
- **The controller gets its own `Dockerfile.controller` + publish job.** The agent and controller
  are independently versioned, independently deployed artifacts (one runs per-pod as a sidecar,
  one runs once cluster-wide) — two small, single-purpose Dockerfiles over one conditional one
  (§3).
- **The chart's CRD copy is checked in, with a CI drift check.** The real schema is
  Maven-generated; a stale hand-copied CRD would silently ship a schema the CRD model no longer
  matches. A cheap regenerate-and-diff step in CI catches that the moment the model changes, not
  at install time on a real cluster.
- **`warden.sidecar` lives in its own library chart (`charts/warden-sidecar`), not inside
  `charts/warden`.** Verified empirically (a throwaway consumer chart depending on `charts/warden`
  directly): Helm's only way for one chart to reach another's named templates is the dependency
  graph, so depending on the application chart to reach one template also deploys a second
  controller `Deployment`/`ClusterRole`/`ClusterRoleBinding` per app. A Helm library chart is
  structurally forbidden from rendering resources of its own, closing this off by construction
  rather than a `controller.enabled: false` convention someone could forget to set.
