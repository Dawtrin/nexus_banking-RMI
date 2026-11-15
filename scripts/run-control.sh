#!/usr/bin/env bash
# NEXUS CONTROL (operations center): own entry point com.nexus.banking.control.AdminLauncher
set -e
cd "$(dirname "$0")/.."
exec mvn -pl banking-control javafx:run "$@"
