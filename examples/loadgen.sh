#!/usr/bin/env bash
# loadgen.sh - continuously calls both example endpoints and prints span IDs.
# Usage: ./loadgen.sh [n]
#   n  Fibonacci input (default: 40)
#
# The otel-library example returns the span ID in the HTTP response.
# The otel-extension example does not (the OTel agent loads in an isolated
# classloader, so the app cannot access OTel API classes directly); span IDs
# for that example are visible in its container logs (OTEL_TRACES_EXPORTER=logging).

set -euo pipefail

N="${1:-40}"
EXT_URL="http://localhost:8080/fibonacci?n=${N}"
LIB_URL="http://localhost:8081/fibonacci?n=${N}"

echo "Sending requests to both examples (Ctrl-C to stop)..."
echo ""

while true; do
    ext=$(curl -sf "${EXT_URL}" 2>/dev/null || echo "error: otel-extension-example not reachable")
    lib=$(curl -sf "${LIB_URL}" 2>/dev/null || echo "error: otel-library-example not reachable")

    lib_span=$(echo "${lib}" | grep -oP 'spanId=\K[0-9a-f]+' || echo "n/a")

    printf "[otel-extension] %s\n" "${ext}"
    printf "[otel-library  ] spanId=%s\n" "${lib_span}"
    echo ""

    sleep 1
done
