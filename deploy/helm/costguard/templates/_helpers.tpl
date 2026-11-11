{{- define "costguard.labels" -}}
app.kubernetes.io/part-of: costguard
app.kubernetes.io/managed-by: {{ .Release.Service }}
helm.sh/chart: {{ .Chart.Name }}-{{ .Chart.Version }}
{{- end -}}

{{- define "costguard.image" -}}
{{ .root.Values.image.registry }}/{{ .image }}:{{ .root.Values.image.tag }}
{{- end -}}

{{- define "costguard.s3Endpoint" -}}
{{- $region := required "region is required (ADR 0024)" .Values.region -}}
{{- $known := index (.Values.regions | default dict) $region | default dict -}}
{{- .Values.external.s3Endpoint | default $known.s3Endpoint | required (printf "no S3 endpoint for region %s: add it to regions" $region) -}}
{{- end -}}

{{- /* Keycloak of the chart is reached at its ingress host; otherwise at external.* */}}
{{- define "costguard.keycloakUrl" -}}
{{- if .Values.keycloak.enabled -}}
https://{{ required "ingress.hosts.keycloak is required with keycloak.enabled" .Values.ingress.hosts.keycloak }}
{{- else -}}
{{ .Values.external.keycloakAdminUrl }}
{{- end -}}
{{- end -}}

{{- define "costguard.keycloakIssuer" -}}
{{- if .Values.keycloak.enabled -}}
{{ include "costguard.keycloakUrl" . }}/realms/{{ .Values.keycloak.realm }}
{{- else -}}
{{ .Values.external.keycloakIssuerUri }}
{{- end -}}
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
  value: {{ include "costguard.s3Endpoint" . | quote }}
- name: S3_REGION
  value: {{ .Values.region | quote }}
- name: DATA_REGION
  value: {{ .Values.region | quote }}
- name: KEYCLOAK_ADMIN_URL
  value: {{ include "costguard.keycloakUrl" . | quote }}
- name: MANAGEMENT_ENDPOINT_HEALTH_PROBES_ENABLED
  value: "true"
- name: KEYCLOAK_ISSUER_URI
  value: {{ include "costguard.keycloakIssuer" . | quote }}
{{- /* Uploaded files (ADR 0049): the core writes and the ingest pipeline reads the same bucket. */}}
{{- if not .Values.imports.bucket }}{{ fail "imports.bucket is required: terraform output imports_bucket" }}{{ end }}
- name: COSTGUARD_IMPORTS_S3_BUCKET
  value: {{ .Values.imports.bucket | quote }}
- name: INGEST_S3_BUCKET
  value: {{ .Values.imports.bucket | quote }}
{{- /* Traces (ADR 0048, 0050): Java and Python services to the OpenTelemetry Collector, or nowhere. */}}
{{- if .Values.tracing.enabled }}
- name: MANAGEMENT_OPENTELEMETRY_TRACING_EXPORT_OTLP_ENDPOINT
  value: {{ .Values.tracing.otlpEndpoint | quote }}
- name: OTEL_EXPORTER_OTLP_TRACES_ENDPOINT
  value: {{ .Values.tracing.otlpEndpoint | quote }}
- name: COSTGUARD_TRACING
  value: "true"
{{- else }}
- name: MANAGEMENT_TRACING_EXPORT_ENABLED
  value: "false"
{{- end }}
{{- if .Values.mlflow.enabled }}
- name: MLFLOW_TRACKING_URI
  value: "http://costguard-mlflow"
{{- end }}
{{- end -}}
