{{- define "costguard.labels" -}}
app.kubernetes.io/part-of: costguard
app.kubernetes.io/managed-by: {{ .Release.Service }}
helm.sh/chart: {{ .Chart.Name }}-{{ .Chart.Version }}
{{- end -}}

{{- define "costguard.image" -}}
{{ .root.Values.image.registry }}/{{ .image }}:{{ .root.Values.image.tag }}
{{- end -}}

{{- define "costguard.env" -}}
- name: KAFKA_BOOTSTRAP_SERVERS
  value: {{ .Values.external.kafkaBootstrapServers | quote }}
- name: SCHEMA_REGISTRY_URL
  value: {{ .Values.external.schemaRegistryUrl | quote }}
- name: DB_URL
  value: {{ .Values.external.postgresUrl | quote }}
- name: REDIS_HOST
  value: {{ .Values.external.redisHost | quote }}
- name: CLICKHOUSE_URL
  value: {{ .Values.external.clickhouseUrl | quote }}
- name: S3_ENDPOINT
  value: {{ .Values.external.s3Endpoint | quote }}
- name: MANAGEMENT_ENDPOINT_HEALTH_PROBES_ENABLED
  value: "true"
- name: KEYCLOAK_ISSUER_URI
  value: {{ .Values.external.keycloakIssuerUri | quote }}
{{- if .Values.mlflow.enabled }}
- name: MLFLOW_TRACKING_URI
  value: "http://costguard-mlflow"
{{- end }}
{{- end -}}
