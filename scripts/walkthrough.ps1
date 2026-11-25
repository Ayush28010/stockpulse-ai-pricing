# Walkthrough: order -> stock below threshold -> async suggestions -> accept price.
param([string]$ProductId = 'PRD-003', [string]$Api = 'http://localhost:8080')
$json = 'application/json'

Write-Host "== $ProductId before"
Invoke-RestMethod "$Api/products/$ProductId" | Select-Object id, stockLevel, reorderThreshold, currentPrice, demandVelocity, status | Format-List

Write-Host "== simulate a sale (returns immediately)"
$order = Invoke-RestMethod -Method Post "$Api/products/$ProductId/orders" -ContentType $json -Body '{"quantity":1}'
"stock now $($order.product.stockLevel), status $($order.product.status)"

Write-Host "== waiting for the agent (INVENTORY_LOW)..."
$suggestion = $null
for ($i = 0; $i -lt 30 -and -not $suggestion; $i++) {
    $suggestion = @(Invoke-RestMethod "$Api/pricing-suggestions?status=PENDING&productId=$ProductId") |
        Where-Object triggerReason -eq 'INVENTORY_LOW' | Select-Object -First 1
    if (-not $suggestion) { Start-Sleep -Seconds 1 }
}
if (-not $suggestion) { throw 'No suggestion yet' }
$suggestion | Select-Object id, currentPrice, recommendedPrice, tactic, confidence, generatedBy, reasoning | Format-List
@(Invoke-RestMethod "$Api/reorder-suggestions?status=PENDING&productId=$ProductId") |
    Where-Object triggerReason -eq 'INVENTORY_LOW' | Select-Object id, recommendedQuantity, leadTimeDays, generatedBy, reasoning | Format-List

Write-Host "== accept pricing suggestion $($suggestion.id)"
$decision = Invoke-RestMethod -Method Patch "$Api/pricing-suggestions/$($suggestion.id)" -ContentType $json -Body '{"status":"ACCEPTED"}'
"suggestion $($decision.suggestion.status); price now $($decision.product.currentPrice); product $($decision.product.status)"
