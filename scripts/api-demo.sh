#!/usr/bin/env bash
# Demonstrates the end-to-end workflow and the concurrency guarantee against a RUNNING server.
# Usage: ./scripts/api-demo.sh [base-url]     (default http://localhost:8080)
# Creates its own uniquely named test data; the seed data is not modified.
set -euo pipefail
BASE="${1:-http://localhost:8080}"
J='Content-Type: application/json'
id() { sed -E 's/.*"id":([0-9]+).*/\1/'; }
TAG="demo-$(date +%s)"

echo ">> health";  curl -sf "$BASE/api/health"; echo
echo ">> creating 10-unit consumable resource and two organizations (claim 7 each)"
RES=$(curl -sf -X POST "$BASE/api/resources" -H "$J" -d "{\"name\":\"$TAG-water\",\"category\":\"WATER\",\"totalQuantity\":10,\"reusable\":false,\"location\":\"Demo\"}" | id)
A=$(curl -sf -X POST "$BASE/api/organizations" -H "$J" -d "{\"name\":\"$TAG-A\",\"orgType\":\"SHELTER\",\"location\":\"Demo\"}" | id)
B=$(curl -sf -X POST "$BASE/api/organizations" -H "$J" -d "{\"name\":\"$TAG-B\",\"orgType\":\"SHELTER\",\"location\":\"Demo\"}" | id)
for O in $A $B; do curl -sf -X PUT "$BASE/api/organizations/$O/claims" -H "$J" -d "{\"resourceId\":$RES,\"maxClaim\":7}" >/dev/null; done
RA=$(curl -sf -X POST "$BASE/api/requests" -H "$J" -d "{\"organizationId\":$A,\"resourceId\":$RES,\"quantity\":7,\"priority\":3}" | id)
RB=$(curl -sf -X POST "$BASE/api/requests" -H "$J" -d "{\"organizationId\":$B,\"resourceId\":$RES,\"quantity\":7,\"priority\":3}" | id)

echo ">> firing both allocations AT THE SAME TIME"
curl -s -X POST "$BASE/api/requests/$RA/allocate" > /tmp/dras_a.json &
curl -s -X POST "$BASE/api/requests/$RB/allocate" > /tmp/dras_b.json &
wait
echo "request A: $(cat /tmp/dras_a.json)"; echo "request B: $(cat /tmp/dras_b.json)"
echo ">> resulting inventory (expect allocated=7 available=3, never more than 10 units out):"
curl -sf "$BASE/api/resources/$RES"; echo
echo ">> database integrity checks:"; curl -sf "$BASE/api/system/status" | grep -o '"inventoryInvariantViolations":[0-9]*\|"allocationLedgerMismatches":[0-9]*'
