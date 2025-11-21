#!/usr/bin/env bash
set -euo pipefail
source "$(dirname "$0")/env.sh"

GC_HOST="${1:-127.0.0.1}" # IP del front (C)

GC_HOST="$GC_HOST" GC_PORT=6055 REQUESTS_FILE="$ROOT/data/requests/requests.txt" \
java -cp "$CP" org.example.front.RequestProducer
