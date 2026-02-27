#!/usr/bin/env bash
# loadgen.sh - continuously calls all example endpoints and prints span IDs.
# Usage: ./loadgen.sh [n]
#   n  Fibonacci input (default: 40)
#
# The otel-library example returns the span ID in the HTTP response.
# The otel-extension and otel-extension-manual-start examples do not (the OTel
# agent loads in an isolated classloader, so the app cannot access OTel API
# classes directly); span IDs for those examples are visible in their container
# logs (OTEL_TRACES_EXPORTER=logging).

set -euo pipefail

N="${1:-40}"
EXT_URL="http://localhost:8080/fibonacci?n=${N}"
LIB_URL="http://localhost:8081/fibonacci?n=${N}"
MANUAL_URL="http://localhost:8082/fibonacci?n=${N}"

echo "Sending requests to all examples (Ctrl-C to stop)..."
echo ""

while true; do
    ext=$(curl -sf "${EXT_URL}" 2>/dev/null || echo "error: otel-extension-example not reachable")
    lib=$(curl -sf "${LIB_URL}" 2>/dev/null || echo "error: otel-library-example not reachable")
    manual=$(curl -sf "${MANUAL_URL}" 2>/dev/null || echo "error: otel-extension-manual-start-example not reachable")

    lib_span=$(echo "${lib}" | grep -oP 'spanId=\K[0-9a-f]+' || echo "n/a")

    printf "[otel-extension             ] %s\n" "${ext}"
    printf "[otel-library               ] spanId=%s\n" "${lib_span}"
    printf "[otel-extension-manual-start] %s\n" "${manual}"
    echo ""

    sleep 1
done
