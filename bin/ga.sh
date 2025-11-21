#!/usr/bin/env bash
set -euo pipefail
source "$(dirname "$0")/env.sh"

GA2_HOST="${1:-127.0.0.1}" # IP del primary (A)

GA_BIND_HOST=0.0.0.0 GA_PORT=6057 \
GA2_HOST="$GA2_HOST" GA2_ROUTER_PORT=6070 GA2_REP_PORT=6080 \
R_BOOK_DB="$ROOT/data/replica/books.csv" \
R_LOANS_PATH="$ROOT/data/replica/loans.csv" \
R_PENDING_LOG="$ROOT/data/replica/pending.log" \
java -cp "$CP" org.example.storage.StorageReplica
