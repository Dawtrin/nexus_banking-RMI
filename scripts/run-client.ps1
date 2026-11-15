# NEXUS BANKING (customers: Internet Banking / ATM): own entry point com.nexus.banking.client.Launcher
$ErrorActionPreference = 'Stop'
Set-Location (Join-Path $PSScriptRoot '..')
& mvn -pl banking-client javafx:run @args
exit $LASTEXITCODE
