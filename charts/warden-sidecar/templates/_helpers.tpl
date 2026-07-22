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
  volumeMounts:
    - name: warden-host-cgroup
      mountPath: /host-cgroup # must match RssReader.HOST_CGROUP_ROOT
      readOnly: true
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
