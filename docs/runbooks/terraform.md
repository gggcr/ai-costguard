# Окружение в Yandex Cloud

`deploy/terraform/yandex` создаёт сеть, Kubernetes, PostgreSQL, Kafka со Schema Registry, ClickHouse, Redis,
бакет для загруженных счетов и реестр образов.

## Первый раз
1. Бакет для состояния и ключ к нему создать руками (Terraform не может хранить состояние в том, что создаёт):
   `yc storage bucket create --name costguard-terraform-state`, сервисный аккаунт с `storage.editor`, статический ключ.
2. `cp backend.tf.example backend.tf`, `cp staging.tfvars.example staging.tfvars`, заполнить.
3. ```
   export AWS_ACCESS_KEY_ID=... AWS_SECRET_ACCESS_KEY=...   # ключ к бакету состояния
   export YC_TOKEN=$(yc iam create-token)
   terraform init
   terraform plan -var-file=staging.tfvars -out plan
   terraform apply plan
   ```
4. Выходы перенести в values окружения (`external.*`) и в секрет, см. [secrets.md](secrets.md).
5. `yc managed-kubernetes cluster get-credentials <kubernetes_cluster_id> --external`, затем Argo CD, см.
   [deploy.md](deploy.md).

## Изменить
Всегда `plan` перед `apply` и читать план: пересоздание кластера базы (`-/+` у `yandex_mdb_*`) уничтожит данные.
Размеры (`*_preset`, `*_disk_gb`) меняются без пересоздания.
