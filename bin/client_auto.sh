#!/usr/bin/env bash
set -euo pipefail
source "$(dirname "$0")/env.sh"

GC_HOST="${1:-127.0.0.1}"
BOOK_ID="${2:-1}"

GC_HOST="$GC_HOST" GC_PORT=6055 ./bin/test_workflow.sh "$BOOK_ID"
