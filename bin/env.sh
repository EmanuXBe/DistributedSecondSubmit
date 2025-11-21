#!/usr/bin/env bash
set -euo pipefail

# Determina la raíz del proyecto
ROOT="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)"

# Calcula CP si no viene definido
if [[ -z "${CP:-}" ]]; then
  JEROMQ_JAR=$(find "$HOME/.gradle/caches/modules-2/files-2.1/org.zeromq/jeromq/0.5.3" -name 'jeromq-0.5.3.jar' -print -quit)
  CP="$ROOT/build/libs/BibliotecaDistribuida-1.0-SNAPSHOT.jar:$ROOT/build/classes/java/main:${JEROMQ_JAR}"
fi

export ROOT CP
