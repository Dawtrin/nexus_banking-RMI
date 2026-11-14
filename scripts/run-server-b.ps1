# Start NEXUS Server Node B (Port 2099)
[CmdletBinding()]
param(
    [switch]$Ssl,
    [switch]$Db,
    [string]$AdvertisedHost,
    [string]$PeerHost,
    [Parameter(ValueFromRemainingArguments = $true)]
    [string[]]$RemainingArgs
)

$ErrorActionPreference = 'Stop'
Set-Location (Join-Path $PSScriptRoot '..')

if (-not $env:NEXUS_REPLICATION_SECRET) {
    $env:NEXUS_REPLICATION_SECRET = 'nexus-cluster-shared-secret-16chars'
}
if ($Db) {
    $env:NEXUS_DB_ENABLED = 'true'
}
if (-not $env:NEXUS_DB_ENABLED) {
    $env:NEXUS_DB_ENABLED = 'false'
}
if ($env:NEXUS_DB_ENABLED -eq 'true' -and -not $env:DB_USERNAME) {
    $env:DB_USERNAME = 'root'
}
if (-not $env:NEXUS_ADMIN_TOKEN) {
    $env:NEXUS_ADMIN_TOKEN = 'nexus-admin-secret'
}
if (-not $env:NEXUS_SINGLE_SESSION) {
    $env:NEXUS_SINGLE_SESSION = 'true'
}

$sslArgs = @()
if ($Ssl -or ($env:NEXUS_SSL_ENABLED -eq 'true')) {
    $env:NEXUS_SSL_ENABLED = 'true'
    if (Test-Path 'certs/nexus-server.p12') {
        $env:NEXUS_SSL_KEYSTORE_PATH = 'certs/nexus-server.p12'
        $env:NEXUS_SSL_KEYSTORE_PASSWORD = 'nexus-changeit'
        $env:NEXUS_SSL_TRUSTSTORE_PATH = 'certs/nexus-truststore.p12'
        $env:NEXUS_SSL_TRUSTSTORE_PASSWORD = 'nexus-changeit'
    }
    $sslArgs += "--server.ssl.enabled=true"
    Write-Host "[SECURITY] TLS/SSL Encryption is ENABLED" -ForegroundColor Green
}

$hostArgs = @()
if ($AdvertisedHost) {
    $hostArgs += "--server.advertised.host=$AdvertisedHost"
}
if ($PeerHost) {
    $hostArgs += "--server.peer.host=$PeerHost"
}

Write-Host "=========================================" -ForegroundColor Cyan
Write-Host " Starting NEXUS Server Node B (Port 2099)" -ForegroundColor Cyan
Write-Host " DB Mode:   $(if ($env:NEXUS_DB_ENABLED -eq 'true') {'MySQL/MariaDB'} else {'RAM-Only Demo'})" -ForegroundColor Cyan
Write-Host " Transport: $(if ($env:NEXUS_SSL_ENABLED -eq 'true') {'TLS/SSL Encrypted'} else {'Plain TCP'})" -ForegroundColor Cyan
if ($AdvertisedHost) {
    Write-Host " Advertised: $AdvertisedHost" -ForegroundColor Cyan
}
Write-Host "=========================================" -ForegroundColor Cyan

& .\scripts\run-server.ps1 `
    --server.id=server-b `
    --server.rmi.registry.port=2099 `
    --server.rmi.service.port=2100 `
    --server.peer.id=server-a `
    --server.peer.registry.port=1099 `
    --server.peer.service.port=1100 `
    @sslArgs `
    @hostArgs `
    @RemainingArgs
