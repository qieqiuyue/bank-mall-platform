{{/*
Expand the name of the chart.
*/}}
{{- define "bank-mall.name" -}}
{{- default .Chart.Name .Values.nameOverride | trunc 63 | trimSuffix "-" }}
{{- end }}

{{/*
Common labels
*/}}
{{- define "bank-mall.labels" -}}
helm.sh/chart: {{ include "bank-mall.chart" . }}
{{ include "bank-mall.selectorLabels" . }}
{{- if .Chart.AppVersion }}
app.kubernetes.io/version: {{ .Chart.AppVersion | quote }}
{{- end }}
app.kubernetes.io/managed-by: {{ .Release.Service }}
{{- end }}

{{/*
Selector labels
*/}}
{{- define "bank-mall.selectorLabels" -}}
app.kubernetes.io/name: {{ include "bank-mall.name" . }}
app.kubernetes.io/instance: {{ .Release.Name }}
{{- end }}

{{/*
Create chart name and version as used by the chart label.
*/}}
{{- define "bank-mall.chart" -}}
{{- printf "%s-%s" .Chart.Name .Chart.Version | replace "+" "_" | trunc 63 | trimSuffix "-" }}
{{- end }}

{{/*
Full image path
*/}}
{{- define "bank-mall.image" -}}
{{ .Values.image.registry }}/{{ .Values.image.repository }}/{{ .imageName }}:{{ .tag }}
{{- end }}
