#!/usr/bin/env bash
set -euo pipefail
source "$(dirname "$0")/env.sh"

GA_HOST="${1:-127.0.0.1}" # IP de la réplica (B)
GA2_HOST="${2:-127.0.0.1}" # IP del primary (A)
GC_HOST="${3:-127.0.0.1}" # IP del front (C)

GA_HOST="$GA_HOST" GA_PORT=6057 \
GA2_HOST="$GA2_HOST" GA2_PORT=6080 \
GC_HOST="$GC_HOST" GC_PUB_PORT=6060 \
java -cp "$CP" org.example.actor.ReturnRenewalActor
