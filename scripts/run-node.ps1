# Starts one NEXUS BANKING branch node in the foreground. Build first:  mvn -DskipTests package
# Required env: NEXUS_REPLICATION_SECRET (>=16 chars, same on both nodes), NEXUS_DB_ENABLED (true|false)
# Typical env : NEXUS_SERVER_ID, NEXUS_PEER_ID, NEXUS_SERVER_HOST, NEXUS_PEER_HOST, NEXUS_ADMIN_TOKEN, DB_USERNAME, DB_PASSWORD
# Extra --key=value arguments are passed to the server (they override environment variables).
$ErrorActionPreference = 'Stop'
Set-Location (Join-Path $PSScriptRoot '..')
$jar = Get-ChildItem 'banking-server/target/banking-server-*-all.jar' -ErrorAction SilentlyContinue | Select-Object -First 1
if (-not $jar) { Write-Error 'banking-server fat jar not found. Run: mvn -DskipTests package'; exit 1 }
if (-not (Get-Command java -ErrorAction SilentlyContinue)) { Write-Error 'java 21 not found in PATH'; exit 1 }
if (-not $env:NEXUS_REPLICATION_SECRET) { Write-Error 'Set NEXUS_REPLICATION_SECRET (same value on both nodes)'; exit 1 }
if (-not $env:NEXUS_DB_ENABLED) { Write-Error 'Set NEXUS_DB_ENABLED=true (database) or false (RAM-only demo, NOT durable)'; exit 1 }
& java '-Dsun.rmi.transport.tcp.handshakeTimeout=5000' -jar $jar.FullName @args
exit $LASTEXITCODE
