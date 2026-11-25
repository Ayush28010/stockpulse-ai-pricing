#!/usr/bin/env bash
# Walkthrough: order -> stock below threshold -> async suggestions -> accept price. Requires curl and jq.
set -euo pipefail
API=${API:-http://localhost:8080}
P=${1:-PRD-003}

echo "== $P before"
curl -s "$API/products/$P" | jq '{id, stockLevel, reorderThreshold, currentPrice, demandVelocity, status}'

echo "== simulate a sale (returns immediately)"
curl -s -X POST "$API/products/$P/orders" -H 'Content-Type: application/json' -d '{"quantity":1}' \
  | jq '{orderId, product: {stockLevel: .product.stockLevel, status: .product.status}}'

echo "== waiting for the agent (INVENTORY_LOW)..."
for _ in $(seq 1 30); do
  ID=$(curl -s "$API/pricing-suggestions?status=PENDING&productId=$P" \
    | jq -r '[.[] | select(.triggerReason=="INVENTORY_LOW")][0].id // empty')
  [ -n "$ID" ] && break
  sleep 1
done
[ -z "${ID:-}" ] && { echo "no suggestion yet"; exit 1; }

curl -s "$API/pricing-suggestions?status=PENDING&productId=$P" \
  | jq '.[] | select(.triggerReason=="INVENTORY_LOW") | {id, currentPrice, recommendedPrice, tactic, confidence, generatedBy, reasoning}'
curl -s "$API/reorder-suggestions?status=PENDING&productId=$P" \
  | jq '.[] | select(.triggerReason=="INVENTORY_LOW") | {id, recommendedQuantity, leadTimeDays, generatedBy, reasoning}'

echo "== accept pricing suggestion $ID"
curl -s -X PATCH "$API/pricing-suggestions/$ID" -H 'Content-Type: application/json' -d '{"status":"ACCEPTED"}' \
  | jq '{suggestion: .suggestion.status, price: .product.currentPrice, status: .product.status}'
