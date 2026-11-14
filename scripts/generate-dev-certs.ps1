# ==============================================================================
# Generate Self-Signed TLS/SSL Certificates for NEXUS Distributed Chat
# Uses JDK's built-in 'keytool' to create PKCS12 keystore and truststore
# ==============================================================================
[CmdletBinding()]
param(
    [string]$OutputDir = "certs",
    [string]$Password = "nexus-changeit",
    [string]$DomainOrIp = "localhost"
)

$ErrorActionPreference = 'Stop'
Set-Location (Join-Path $PSScriptRoot '..')

Write-Host "==========================================================" -ForegroundColor Cyan
Write-Host " NEXUS DISTRIBUTED CHAT - TLS/SSL CERTIFICATE GENERATOR" -ForegroundColor Cyan
Write-Host "==========================================================" -ForegroundColor Cyan

if (-not (Get-Command keytool -ErrorAction SilentlyContinue)) {
    Write-Error "Java 'keytool' utility not found in PATH. Please install Java 21 JDK."
    exit 1
}

if (-not (Test-Path $OutputDir)) {
    New-Item -ItemType Directory -Path $OutputDir -Force | Out-Null
}

$keystorePath = Join-Path $OutputDir "nexus-server.p12"
$truststorePath = Join-Path $OutputDir "nexus-truststore.p12"
$certPath = Join-Path $OutputDir "nexus-cert.cer"

# Gather local IP addresses for Subject Alternative Names (SAN)
$localIps = @("127.0.0.1")
try {
    $found = [System.Net.Dns]::GetHostAddresses([System.Net.Dns]::GetHostName()) | 
        Where-Object { $_.AddressFamily -eq 'InterNetwork' -and $_.IPAddressToString -ne '127.0.0.1' } |
        ForEach-Object { $_.IPAddressToString }
    if ($found) { $localIps += $found }
} catch {}

$sanParts = @("dns:localhost", "dns:$DomainOrIp")
foreach ($ip in $localIps) {
    $sanParts += "ip:$ip"
}
$sanString = ($sanParts | Select-Object -Unique) -join ","

Write-Host "[1/3] Generating Server Keystore (RSA 2048, PKCS12)..." -ForegroundColor Yellow
Write-Host "      SAN: $sanString" -ForegroundColor DarkGray

if (Test-Path $keystorePath) { Remove-Item $keystorePath -Force }
if (Test-Path $truststorePath) { Remove-Item $truststorePath -Force }
if (Test-Path $certPath) { Remove-Item $certPath -Force }

& keytool -genkeypair `
    -alias nexus `
    -keyalg RSA `
    -keysize 2048 `
    -storetype PKCS12 `
    -keystore $keystorePath `
    -storepass $Password `
    -keypass $Password `
    -dname "CN=$DomainOrIp, OU=Nexus Distributed Chat, O=Nexus, C=VN" `
    -validity 3650 `
    -ext "SAN=$sanString"

if ($LASTEXITCODE -ne 0) {
    Write-Error "Failed to generate keystore."
    exit $LASTEXITCODE
}

Write-Host "[2/3] Exporting Public Certificate..." -ForegroundColor Yellow
& keytool -exportcert `
    -alias nexus `
    -keystore $keystorePath `
    -storepass $Password `
    -file $certPath

Write-Host "[3/3] Creating Client/Peer Truststore..." -ForegroundColor Yellow
& keytool -importcert `
    -noprompt `
    -alias nexus `
    -file $certPath `
    -keystore $truststorePath `
    -storepass $Password

Write-Host ""
Write-Host "==========================================================" -ForegroundColor Green
Write-Host " CERTIFICATES GENERATED SUCCESSFULLY!" -ForegroundColor Green
Write-Host " - Keystore:   $keystorePath" -ForegroundColor White
Write-Host " - Truststore: $truststorePath" -ForegroundColor White
Write-Host " - Password:   $Password" -ForegroundColor White
Write-Host "==========================================================" -ForegroundColor Green
Write-Host ""
Write-Host "HOW TO RUN WITH TLS/SSL:" -ForegroundColor Cyan
Write-Host ""
Write-Host "1. Run Server Node A (TLS/SSL enabled):" -ForegroundColor White
Write-Host "   powershell -ExecutionPolicy Bypass -File .\scripts\run-server-a.ps1 -Ssl" -ForegroundColor Gray
Write-Host ""
Write-Host "2. Run NEXUS CHAT (Desktop Client):" -ForegroundColor White
Write-Host "   Check the 'Use TLS / SSL encryption' box on the login screen," -ForegroundColor Gray
Write-Host "   or run: mvn -pl banking-client -am javafx:run" -ForegroundColor Gray
Write-Host ""
Write-Host "3. Run NEXUS CONTROL (Admin Dashboard):" -ForegroundColor White
Write-Host "   Check the 'Use TLS / SSL encryption' box on the login screen," -ForegroundColor Gray
Write-Host "   or run: mvn -pl banking-control -am javafx:run" -ForegroundColor Gray
