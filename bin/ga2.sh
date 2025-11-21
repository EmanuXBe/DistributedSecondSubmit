#!/usr/bin/env bash
set -euo pipefail
source "$(dirname "$0")/env.sh"

GA_HOST="${1:-127.0.0.1}" # IP de la réplica (B)

GA2_ROUTER_HOST=0.0.0.0 GA2_ROUTER_PORT=6070 \
GA2_REP_HOST=0.0.0.0 GA2_REP_PORT=6080 \
GA_HOST="$GA_HOST" GA_PORT=6057 \
P_BOOK_DB="$ROOT/data/primary/books.csv" \
P_LOANS_PATH="$ROOT/data/primary/loans.csv" \
P_PENDING_LOG="$ROOT/data/primary/pending.log" \
java -cp "$CP" org.example.storage.StoragePrimary
