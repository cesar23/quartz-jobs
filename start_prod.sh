#!/usr/bin/env bash
# scripts/start_pre.sh
#
# QUARTZ_INIT_SCHEMA=always SOLO se aplica si el volumen de MariaDB de
# "pre" todavia no existe (BD nueva) -- se detecta solo, no hay que
# acordarse de nada. Si ya existe, arranca normal (never, el default).
set -euo pipefail

export PUID="$(id -u)"
export PGID="$(id -g)"

if docker volume inspect jobs-prod-mariadb-data >/dev/null 2>&1; then
    QUARTZ_INIT_SCHEMA=never
else
    echo "🆕 BD 'prod' nueva -- aplicando bootstrap del esquema de Quartz."
    QUARTZ_INIT_SCHEMA=always
fi

QUARTZ_INIT_SCHEMA=$QUARTZ_INIT_SCHEMA \
  docker compose -f docker-compose.yml --env-file .env.prod up -d --remove-orphans
