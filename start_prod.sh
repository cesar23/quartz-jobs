#!/usr/bin/env bash
# scripts/start_pre.sh
#
# QUARTZ_INIT_SCHEMA se controla con el parámetro --init:
#   ./start_prod.sh --init=true     -> QUARTZ_INIT_SCHEMA=always (BD nueva, bootstrap)
#   ./start_prod.sh --init=false    -> QUARTZ_INIT_SCHEMA=never  (normal, el 99% de las veces)
#
# Usa --init=true SOLO la primera vez con una BD nueva. Dejarlo en "true"
# de forma permanente dropea y recrea las tablas QRTZ_* en cada reinicio,
# perdiendo todos los jobs/triggers persistidos.
set -euo pipefail

# =============================================================================
# 🎨 SECTION: Colores para su uso
# =============================================================================
Color_Off='\033[0m'       # Reset de color.
NC='\033[0m'
Black='\033[0;30m'
Red='\033[0;31m'
Green='\033[0;32m'
Yellow='\033[0;33m'
Blue='\033[0;34m'
Purple='\033[0;35m'
Cyan='\033[0;36m'
White='\033[0;37m'
Gray='\033[0;90m'

BBlack='\033[1;30m'
BRed='\033[1;31m'
BGreen='\033[1;32m'
BYellow='\033[1;33m'
BBlue='\033[1;34m'
BPurple='\033[1;35m'
BCyan='\033[1;36m'
BWhite='\033[1;37m'
BGray='\033[1;90m'

# ── Si no hay terminal interactiva (ej: Ansible), desactivar colores ──────────
if [ ! -t 1 ]; then
    Color_Off=''; Black='';   Red='';    Green=''
    Yellow='';    Blue='';    Purple=''; Cyan='';   White=''; Gray=''
    BBlack='';    BRed='';    BGreen=''
    BYellow='';   BBlue='';   BPurple=''
    BCyan='';     BWhite='';  BGray=''
fi

run_cmd() {
  echo -e "  ${BGray}›${Color_Off} ${BYellow}$*${Color_Off}"
  "$@"
}

info()  { echo -e "${Cyan}$*${Color_Off}"; }
ok()    { echo -e "${BGreen}$*${Color_Off}"; }
warn()  { echo -e "${BYellow}$*${Color_Off}"; }
error() { echo -e "${BRed}$*${Color_Off}"; }

usage() {
    echo ""
    echo -e "${BWhite}Uso:${Color_Off}"
    echo -e "  ${Yellow}./start_prod.sh --init=true${Color_Off}    # primera vez / BD nueva (bootstrap del esquema de Quartz)"
    echo -e "  ${Yellow}./start_prod.sh --init=false${Color_Off}   # arranque normal (el 99% de las veces)"
    echo ""
    echo -e "${BWhite}Ejemplo:${Color_Off}"
    echo -e "  ${Yellow}./scripts/start_prod.sh --init=false${Color_Off}"
}


# =============================================================================
# 🔥 SECTION: Main Code
# =============================================================================


# --- Validar parámetro obligatorio --init ------------------------------------
if [ "$#" -eq 0 ]; then
    error "❌ Falta el parámetro obligatorio --init."
    usage
    exit 1
fi

INIT=""
for arg in "$@"; do
    case "$arg" in
        --init=true)  INIT=true ;;
        --init=false) INIT=false ;;
        *)
            error "❌ Argumento desconocido: $arg"
            usage
            exit 1
            ;;
    esac
done

if [ -z "$INIT" ]; then
    error "❌ Falta el parámetro obligatorio --init=true|false."
    usage
    exit 1
fi

# --- Preparar entorno ---------------------------------------------------------
export PUID="$(id -u)"
export PGID="$(id -g)"

info "→ Usuario del contenedor: PUID=${PUID} PGID=${PGID}"

# Crear logs/ si no existe, y en cualquier caso asegurar que quede con
# el dueño del usuario actual (PUID:PGID) -- si Docker la creo antes como
# root, el chown la corrige aca; si el chown falla (no hay permiso porque
# es root y no corres con sudo), avisa en vez de fallar en silencio.
run_cmd mkdir -p logs
if ! run_cmd chown "$PUID:$PGID" logs 2>/dev/null; then
    error "⚠️  No se pudo ajustar el dueño de logs/ (¿pertenece a otro usuario/root?)."
    warn  "    Corre una vez: sudo chown -R \$(id -u):\$(id -g) logs/"
    exit 1
fi

# --- Levantar el stack ---------------------------------------------------------
if [ "$INIT" = true ]; then
    ok "🆕 --init=true -- aplicando bootstrap del esquema de Quartz (QUARTZ_INIT_SCHEMA=always)."
    export QUARTZ_INIT_SCHEMA=always
else
    export QUARTZ_INIT_SCHEMA=never
fi

run_cmd docker compose -f docker-compose.yml --env-file .env.prod up -d --remove-orphans

ok "✅ Listo."
