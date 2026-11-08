# MLflow server for the Helm chart (deploy/helm/costguard, mlflow.enabled) and the local stack (profile ml).
# The official image has no PostgreSQL driver and no boto3, so this one adds them. Built with the other images:
#   docker build -t <registry>/mlflow:<tag> deploy/mlflow
FROM python:3.12-slim
RUN pip install --no-cache-dir "mlflow==3.18.0" "psycopg2-binary==2.9.10" "boto3>=1.35,<2" \
    && useradd --system --uid 10001 --no-create-home mlflow
USER 10001
ENV HOME=/tmp
EXPOSE 5000
CMD ["mlflow", "server", "--host", "0.0.0.0", "--port", "5000"]
