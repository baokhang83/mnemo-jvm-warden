{{/*
warden.sidecar - the native-sidecar initContainer entry, for an operator's own app chart to
include under its Pod spec's `initContainers:` list. Reproduces deploy/example-sidecar.yaml's
already-verified shape, parameterized.

Call with: {{ include "warden.sidecar" (dict "cfg" .Values.warden) }}
  cfg - a dict shaped like charts/warden's own `sidecar:` values block (values.yaml): image,
        targetContainerName, healthPort, gc.timeoutSeconds, resize.timeoutSeconds,
        intentPollIntervalSeconds, resources. targetContainerName and resources are required,
        with no default here either (see charts/warden/values.yaml's comment on
        targetContainerName).

        cfg.boost (optional; B8) - enabled, port, policyRef, acquireWaitSeconds, tokenPath. When
        cfg.boost.enabled is unset/false, none of the Boost-API env vars, the container port, or
        the token volume mount render &mdash; every existing caller that doesn't pass this key is
        unaffected. When enabled, cfg.boost.policyRef is required (same fail-fast posture as
        targetContainerName): a Pod can't discover its own WardenPolicy's name any other way.

This renders only the initContainer entry - it deliberately does NOT set
`shareProcessNamespace: true` on the Pod spec, or mount the host-cgroup volume (see
warden.sidecar.volumes below). Both are Pod-spec-level fields a single `include` can't inject
into an arbitrary location in someone else's template, so the calling chart sets
`shareProcessNamespace: true` itself and includes warden.sidecar.volumes under `spec.volumes`.
This split is the real substance of "a Helm-level include, not an admission webhook" (design.md):
the calling chart stays in full control of its own Pod spec end to end; this chart never mutates
a pod it doesn't own - and, being a library chart, never deploys anything of its own either.
*/}}
{{- define "warden.sidecar" -}}
{{- $cfg := .cfg -}}
- name: warden
  image: "{{ $cfg.image.repository }}:{{ $cfg.image.tag }}"
  imagePullPolicy: {{ $cfg.image.pullPolicy | default "IfNotPresent" }}
  restartPolicy: Always # native sidecar (K8s 1.29+): starts before, stops after, the app container
  ports:
    - name: health
      containerPort: {{ $cfg.healthPort | default 8080 }}
    {{- if ($cfg.boost).enabled }}
    - name: boost-api
      containerPort: {{ $cfg.boost.port | default 8090 }}
    {{- end }}
  env:
    - name: WARDEN_POD_NAME
      valueFrom:
        fieldRef:
          fieldPath: metadata.name
    - name: WARDEN_TARGET_CONTAINER_NAME
      value: {{ required "sidecar.targetContainerName is required - guessing wrong silently resizes nothing, or the wrong container" $cfg.targetContainerName | quote }}
    - name: WARDEN_HEALTH_PORT
      value: {{ $cfg.healthPort | default 8080 | quote }}
    - name: WARDEN_GC_TIMEOUT_SECONDS
      value: {{ $cfg.gc.timeoutSeconds | default 30 | quote }}
    - name: WARDEN_RESIZE_TIMEOUT_SECONDS
      value: {{ $cfg.resize.timeoutSeconds | default 30 | quote }}
    - name: WARDEN_INTENT_POLL_INTERVAL_SECONDS
      value: {{ $cfg.intentPollIntervalSeconds | default 5 | quote }}
    {{- if ($cfg.boost).enabled }}
    - name: WARDEN_POD_UID
      valueFrom:
        fieldRef:
          fieldPath: metadata.uid
    - name: WARDEN_BOOST_PORT
      value: {{ $cfg.boost.port | default 8090 | quote }}
    - name: WARDEN_BOOST_POLICY_REF
      value: {{ required "sidecar.boost.policyRef is required when sidecar.boost.enabled - a Pod cannot discover its own WardenPolicy's name any other way" $cfg.boost.policyRef | quote }}
    - name: WARDEN_BOOST_ACQUIRE_WAIT_SECONDS
      value: {{ $cfg.boost.acquireWaitSeconds | default 25 | quote }}
    - name: WARDEN_BOOST_TOKEN_PATH
      value: {{ $cfg.boost.tokenPath | default "/var/run/warden/boost/token" | quote }}
    {{- end }}
  volumeMounts:
    - name: warden-host-cgroup
      mountPath: /host-cgroup # must match RssReader.HOST_CGROUP_ROOT
      readOnly: true
    {{- if ($cfg.boost).enabled }}
    - name: warden-boost-token
      mountPath: {{ ($cfg.boost.tokenPath | default "/var/run/warden/boost/token" | dir) }}
    {{- end }}
  livenessProbe:
    httpGet:
      path: /healthz
      port: health
    periodSeconds: 10
  readinessProbe:
    httpGet:
      path: /readyz
      port: health
    periodSeconds: 5
  resources:
    {{- toYaml (required "sidecar.resources is required - no safe default for a sidecar's own request/limit" $cfg.resources) | nindent 4 }}
{{- end -}}

{{/*
warden.sidecar.volumes - the host-cgroup hostPath volume warden.sidecar's mount needs, for the
calling chart to include under its Pod spec's `spec.volumes:` list. See RssReader's javadoc
(bug #57) for why this hostPath is required, and deploy/README.md for the real, explicit
privilege cost it carries (full-node cgroup visibility for the sidecar, not just this pod's).

Call with: {{ include "warden.sidecar.volumes" . }}
*/}}
{{- define "warden.sidecar.volumes" -}}
- name: warden-host-cgroup
  hostPath:
    path: /sys/fs/cgroup
    type: Directory
{{- end -}}

{{/*
warden.sidecar.boostVolumes - the B8 app<->sidecar token emptyDir warden.sidecar's own mount
needs, for the calling chart to include under its Pod spec's `spec.volumes:` list, alongside
warden.sidecar.volumes. Empty (renders nothing) when cfg.boost.enabled is unset/false.

This volume is deliberately NOT mounted into the app container by this library chart: like the
split documented on warden.sidecar above, a single `include` can't reach into a container this
chart doesn't own. The calling chart's own app container must mount the same volume name
read-only at the same path (cfg.boost.tokenPath's directory) to read the bearer token the agent
writes there at startup - see docs/warden-boost.md and deploy/example-sidecar-boost.yaml for the
exact snippet.

Call with: {{ include "warden.sidecar.boostVolumes" (dict "cfg" .Values.warden) }}
*/}}
{{- define "warden.sidecar.boostVolumes" -}}
{{- $cfg := .cfg -}}
{{- if ($cfg.boost).enabled }}
- name: warden-boost-token
  emptyDir: {}
{{- end -}}
{{- end -}}
