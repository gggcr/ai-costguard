"""Settings from the environment, with the names used in .env.example."""

from pydantic_settings import BaseSettings, SettingsConfigDict


class Settings(BaseSettings):
    model_config = SettingsConfigDict(env_file=".env", extra="ignore")

    kafka_bootstrap_servers: str = "localhost:9092"
    schema_registry_url: str = "http://localhost:8085"
    s3_endpoint: str = "http://localhost:9000"
    # The signing region of S3 requests; Helm sets it from `region`, the one region every store is in (ADR 0024).
    s3_region: str = "ru-central1"
    s3_access_key: str = "costguard"
    s3_secret_key: str = ""
    clickhouse_url: str = "http://localhost:8123"
    clickhouse_user: str = "costguard"
    clickhouse_password: str = ""
