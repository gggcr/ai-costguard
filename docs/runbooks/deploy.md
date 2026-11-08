# Выкладка и откат

Кластер ведёт Argo CD (`deploy/argocd`). Staging идёт за веткой `main` и синхронизируется сам. Продакшен идёт за
тегом релиза и синхронизируется человеком.

## Установить Argo CD-приложения
```
kubectl apply -f deploy/argocd/project.yaml -f deploy/argocd/staging.yaml -f deploy/argocd/prod.yaml
```

## Релиз
1. CI собрал образы с тегом коммита и прогнал тесты.
2. Тег `vX.Y.Z` на коммите, в `deploy/helm/costguard/values-prod.yaml` — `image.tag`, в `deploy/argocd/prod.yaml` —
   `targetRevision`.
3. В Argo CD: `costguard-prod` → Diff → Sync. Перед синком сам выполнится Job миграций ClickHouse; Flyway ядра
   мигрирует PostgreSQL при старте.

## Откат
- Argo CD → History and rollback → предыдущая ревизия.
- Миграции PostgreSQL и ClickHouse вперёд-только. Откат кода на версию до миграции безопасен, пока миграция
  только добавляла столбцы и таблицы; так их и пишут. Удаляющую миграцию выкатывают отдельным релизом, когда
  старый код уже нигде не работает.
