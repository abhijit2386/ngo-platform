#!/usr/bin/env bash
# Shared helpers for the Yogdaan install steps (Oracle Linux 8/9, x86_64). Sourced by install.sh.
# shellcheck disable=SC2034  # settings below are used by the step scripts that source this file
set -Eeuo pipefail

KIT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
ENV_FILE=/etc/yogdaan/yogdaan.env          # all settings and secrets (0600, root:yogdaan)
SECRETS_DIR=/etc/yogdaan/secrets           # generated passwords for admin tools (0700 root)
APP_DIR=/opt/yogdaan                       # service jars
LOG_DIR=/var/log/yogdaan                   # service log files (ECS JSON, read by Filebeat)
APP_USER=yogdaan
KEYCLOAK_VERSION="${KEYCLOAK_VERSION:-26.7.2}"
PG_MAJOR="${PG_MAJOR:-17}"
ELASTIC_MAJOR="${ELASTIC_MAJOR:-9}"

# Services in start order (same set and order as scripts/start-local.ps1) with port and Java heap.
SERVICES=(
  "discovery-server 8761 192m"
  "identity-service 8082 384m"
  "ngo-registry-service 8083 384m"
  "subscription-service 8084 384m"
  "notification-service 8091 384m"
  "campaign-project-service 8088 512m"
  "donation-payment-service 8086 384m"
  "receipt-service 8087 512m"
  "document-service 8093 512m"
  "reporting-service 8092 384m"
  "api-gateway 8080 384m"
)
# PostgreSQL roles/databases (infrastructure/database/init-databases.sql); keycloak gets its own password.
DATABASES=(identity ngo_registry subscription donor donation receipt campaign operations helpdesk notification reporting document)

log()  { printf '\n== %s\n' "$*"; }
ok()   { printf '   ok: %s\n' "$*"; }
warn() { printf '   WARNING: %s\n' "$*" >&2; }
die()  { printf '\nFAILED: %s\n' "$*" >&2; exit 1; }
trap 'die "line $LINENO: $BASH_COMMAND"' ERR

need_root() { [ "$(id -u)" -eq 0 ] || die "run with sudo"; }

os_major() { . /etc/os-release; echo "${VERSION_ID%%.*}"; }

check_os() {
  . /etc/os-release
  case "${ID}:${VERSION_ID%%.*}" in
    ol:8|ol:9|rhel:8|rhel:9|almalinux:8|almalinux:9|rocky:8|rocky:9) ok "$PRETTY_NAME" ;;
    *) die "expected Oracle Linux 8 or 9, found $PRETTY_NAME" ;;
  esac
  [ "$(uname -m)" = "x86_64" ] || die "expected an x86_64 (AMD/Intel) machine, found $(uname -m)"
}

# Loads /etc/yogdaan/yogdaan.env into the environment.
load_env() {
  [ -f "$ENV_FILE" ] || die "$ENV_FILE missing: run './install.sh config' first"
  # shellcheck disable=SC1090
  set -a; . "$ENV_FILE"; set +a
}

gen_secret() { openssl rand -base64 48 | tr -d '/+=\n' | cut -c1-40; }

# Sets KEY=value in the env file (adds the line when missing).
set_env() {
  local key="$1" value="$2"
  if grep -q "^${key}=" "$ENV_FILE"; then
    sed -i "s|^${key}=.*|${key}=${value//|/\\|}|" "$ENV_FILE"
  else
    printf '%s=%s\n' "$key" "$value" >> "$ENV_FILE"
  fi
}

# Fails when a required setting is empty.
require_env() {
  local missing=()
  for k in "$@"; do [ -n "${!k:-}" ] || missing+=("$k"); done
  [ ${#missing[@]} -eq 0 ] || die "set these in config/yogdaan.env.prod (then './install.sh config'): ${missing[*]}"
}

wait_http() { # url seconds [curl options...]
  local url="$1" secs="$2"; shift 2
  local end=$((SECONDS + secs))
  until curl -fsS -o /dev/null "$@" "$url"; do
    [ $SECONDS -lt $end ] || return 1
    sleep 3
  done
}

dnf_install() { dnf -y -q install "$@" >/dev/null; }
