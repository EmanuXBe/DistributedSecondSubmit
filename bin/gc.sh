#!/usr/bin/env bash
set -euo pipefail
source "$(dirname "$0")/env.sh"

ACTOR_HOST="${1:-127.0.0.1}" # IP del front (C) donde corren actores

GC_BIND_HOST=0.0.0.0 GC_PS_PORT=6055 GC_PUB_PORT=6060 \
ACTOR_HOST="$ACTOR_HOST" ACTOR_PORT=6056 \
java -cp "$CP" org.example.front.LoadBalancer
