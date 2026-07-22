{{/*
warden.name - short chart name.
*/}}
{{- define "warden.name" -}}
{{- .Chart.Name -}}
{{- end -}}

{{/*
warden.fullname - <release>-<chart>, unless the release name already contains the chart name.
*/}}
{{- define "warden.fullname" -}}
{{- if contains .Chart.Name .Release.Name -}}
{{- .Release.Name -}}
{{- else -}}
{{- printf "%s-%s" .Release.Name .Chart.Name -}}
{{- end -}}
{{- end -}}

{{/*
warden.labels - the full label set applied to every resource this chart owns.
*/}}
{{- define "warden.labels" -}}
{{ include "warden.selectorLabels" . }}
app.kubernetes.io/managed-by: {{ .Release.Service }}
{{- end -}}

{{/*
warden.selectorLabels - the stable subset of warden.labels safe to use as a Deployment
selector (app.kubernetes.io/managed-by can vary across tooling; a selector must not, since
Kubernetes rejects changing it on an existing Deployment).
*/}}
{{- define "warden.selectorLabels" -}}
app.kubernetes.io/name: {{ include "warden.name" . }}
app.kubernetes.io/instance: {{ .Release.Name }}
{{- end -}}

{{/*
warden.controller.serviceAccountName - the controller Deployment's ServiceAccount.
*/}}
{{- define "warden.controller.serviceAccountName" -}}
{{- printf "%s-controller" (include "warden.fullname" .) -}}
{{- end -}}
