# NEXUS CHAT (end users): own entry point com.nexus.banking.client.Launcher
$ErrorActionPreference = 'Stop'
Set-Location (Join-Path $PSScriptRoot '..')
& mvn -pl banking-client javafx:run @args
exit $LASTEXITCODE
