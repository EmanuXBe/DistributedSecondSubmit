#!/usr/bin/env bash
set -euo pipefail

: "${CP:?Define CP (jar + dependencias) antes de ejecutar este script}"

BOOK_ID="${1:-1}"
export GC_HOST="${GC_HOST:-localhost}"
export GC_PORT="${GC_PORT:-5555}"

echo "Ejecutando flujo PRESTAMO -> RENOVAR -> DEVOLVER para libro ${BOOK_ID} contra ${GC_HOST}:${GC_PORT}"

printf "2\nPRESTAMO %s\nRENOVAR %s\nDEVOLVER %s\nSALIR\n" \
  "$BOOK_ID" "$BOOK_ID" "$BOOK_ID" \
  | java -cp "$CP" org.example.front.RequestProducer
