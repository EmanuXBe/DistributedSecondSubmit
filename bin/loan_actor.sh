#!/usr/bin/env bash
set -euo pipefail
source "$(dirname "$0")/env.sh"

GA_HOST="${1:-127.0.0.1}" # IP de la réplica (B)
GA2_HOST="${2:-127.0.0.1}" # IP del primary (A)

ACTOR_BIND_HOST=0.0.0.0 ACTOR_PORT=6056 \
GA_HOST="$GA_HOST" GA_PORT=6057 \
GA2_HOST="$GA2_HOST" GA2_PORT=6080 \
java -cp "$CP" org.example.actor.LoanActor
