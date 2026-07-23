{{- define "transactiq.labels" -}}
app.kubernetes.io/part-of: transactiq
app.kubernetes.io/managed-by: {{ .Release.Service }}
helm.sh/chart: {{ .Chart.Name }}-{{ .Chart.Version }}
{{- end -}}
