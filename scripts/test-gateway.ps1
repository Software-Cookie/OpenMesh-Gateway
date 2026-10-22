# End-to-end smoke test against a running gateway started with the "dev" profile.
#   .\scripts\test-gateway.ps1 [-GatewayUrl http://localhost:8080]
param([string]$GatewayUrl = "http://localhost:8080")

$ErrorActionPreference = "Stop"
$script:pass = 0; $script:fail = 0

function Check($name, $expected, $actual) {
    if ("$expected" -eq "$actual") { Write-Host "  PASS  $name"; $script:pass++ }
    else { Write-Host "  FAIL  $name (expected $expected, got $actual)"; $script:fail++ }
}

function Status($uri, $headers = @{}, $method = "GET", $body = $null) {
    try {
        $params = @{ Uri = $uri; Headers = $headers; Method = $method; UseBasicParsing = $true }
        if ($body) { $params.Body = $body; $params.ContentType = "application/json" }
        return (Invoke-WebRequest @params).StatusCode
    } catch {
        if ($_.Exception.Response) { return [int]$_.Exception.Response.StatusCode }
        return 0
    }
}

function Token($body) {
    (Invoke-RestMethod -Uri "$GatewayUrl/auth/token" -Method Post -ContentType "application/json" -Body ($body | ConvertTo-Json)).token
}

Write-Host "OpenMesh Gateway smoke test: $GatewayUrl"

Write-Host "[1] Health"
Check "gateway is UP" "UP" (Invoke-RestMethod "$GatewayUrl/actuator/health").status

Write-Host "[2] Tokens"
$admin = Token @{ tenantId = "tenant-acme"; userId = "alice"; roles = @("ROLE_ADMIN"); scopes = @("read", "write"); tier = "PRO" }
$free = Token @{ tenantId = "free-$(Get-Random)"; userId = "bob"; scopes = @("read"); tier = "FREE" }
$A = @{ Authorization = "Bearer $admin" }
$F = @{ Authorization = "Bearer $free" }

Write-Host "[3] Security"
Check "no token -> 401" 401 (Status "$GatewayUrl/service-alpha/orders")
Check "admin API without token -> 401" 401 (Status "$GatewayUrl/admin/v1/routes")
Check "admin API as non-admin -> 403" 403 (Status "$GatewayUrl/admin/v1/routes" $F)

Write-Host "[4] Zero-trust identity"
$spoof = $A.Clone(); $spoof["X-User-Id"] = "attacker"; $spoof["X-Tenant-Id"] = "victim"
$echo = Invoke-RestMethod "$GatewayUrl/service-alpha/orders" -Headers $spoof
Check "spoofed X-User-Id replaced" "alice" $echo.identity.userId
Check "spoofed X-Tenant-Id replaced" "tenant-acme" $echo.identity.tenantId
Check "downstream verified HMAC signature" "True" $echo.signature.valid

Write-Host "[5] Rate limiting (FREE tier: burst 10)"
$limited = 0
1..25 | ForEach-Object { if ((Status "$GatewayUrl/service-beta/items" $F) -eq 429) { $limited++ } }
Check "requests rejected with 429" $true ($limited -ge 5)

Write-Host "[6] Resilience"
Check "upstream 500 -> 502 fallback" 502 (Status "$GatewayUrl/service-beta/chaos/error?status=500" $A)
Check "slow upstream -> 504 timeout" 504 (Status "$GatewayUrl/service-beta/chaos/delay?ms=2500" $A)

Write-Host "[7] Dynamic routes"
Check "invalid route rejected" 400 (Status "$GatewayUrl/admin/v1/routes" $A "POST" '{"id":"bad","uri":"http://169.254.169.254","predicates":["Path=/x/**"]}')
Check "route created" 201 (Status "$GatewayUrl/admin/v1/routes" $A "POST" '{"id":"smoke-route","uri":"http://downstream-service-alpha:8081","predicates":["Path=/smoke/**"],"filters":["StripPrefix=1"]}')
Start-Sleep -Seconds 1
Check "route deleted" 204 (Status "$GatewayUrl/admin/v1/routes/smoke-route" $A "DELETE")

Write-Host "[8] API keys"
$key = Invoke-RestMethod "$GatewayUrl/admin/v1/api-keys" -Method Post -Headers $A -ContentType "application/json" -Body '{"name":"smoke","tenantId":"tenant-smoke","tier":"PRO","scopes":["read"]}'
Check "API key authenticates" 200 (Status "$GatewayUrl/service-alpha/orders" @{ "X-API-Key" = $key.apiKey })
Status "$GatewayUrl/admin/v1/api-keys/$($key.record.id)" $A "DELETE" | Out-Null
Check "revoked key -> 401" 401 (Status "$GatewayUrl/service-alpha/orders" @{ "X-API-Key" = $key.apiKey })

Write-Host ""
Write-Host "Passed: $script:pass  Failed: $script:fail"
if ($script:fail -gt 0) { exit 1 }
