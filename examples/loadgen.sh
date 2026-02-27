#!/usr/bin/env bash
# loadgen.sh - continuously calls both example endpoints and prints span IDs.
# Usage: ./loadgen.sh [n]
#   n  Fibonacci input (default: 40)

set -euo pipefail

N="${1:-40}"
EXT_URL="http://localhost:8080/fibonacci?n=${N}"
LIB_URL="http://localhost:8081/fibonacci?n=${N}"

echo "Sending requests to both examples (Ctrl-C to stop)..."
echo ""

while true; do
    ext=$(curl -sf "${EXT_URL}" 2>/dev/null || echo "error: otel-extension-example not reachable")
    lib=$(curl -sf "${LIB_URL}" 2>/dev/null || echo "error: otel-library-example not reachable")

    ext_span=$(echo "${ext}" | grep -oP 'spanId=\K[0-9a-f]+' || echo "n/a")
    lib_span=$(echo "${lib}" | grep -oP 'spanId=\K[0-9a-f]+' || echo "n/a")

    printf "[otel-extension] spanId=%s\n" "${ext_span}"
    printf "[otel-library  ] spanId=%s\n" "${lib_span}"
    echo ""

    sleep 1
done
