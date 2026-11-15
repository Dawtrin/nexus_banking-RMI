# NEXUS CONTROL (operations center): own entry point com.nexus.banking.control.AdminLauncher
# Optional: $env:NEXUS_NODE_A='host:port'; $env:NEXUS_NODE_B='host:port'. The admin token is typed in the UI.
$ErrorActionPreference = 'Stop'
Set-Location (Join-Path $PSScriptRoot '..')
& mvn -pl banking-control javafx:run @args
exit $LASTEXITCODE
