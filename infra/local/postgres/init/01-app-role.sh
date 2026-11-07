#!/bin/sh
# First start of an empty volume only. The image makes POSTGRES_USER a superuser, and a superuser
# bypasses row-level security even with FORCE, so the application gets its own ordinary role.
set -eu

psql -v ON_ERROR_STOP=1 --username "$POSTGRES_USER" --dbname postgres \
    -v app_user="$DB_USER" -v app_password="$DB_PASSWORD" -v app_db=costguard \
    -f /opt/costguard/fix-app-role.sql
