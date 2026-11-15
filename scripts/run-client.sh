#!/usr/bin/env bash
# NEXUS BANKING (customers: Internet Banking / ATM): own entry point com.nexus.banking.client.Launcher
set -e
cd "$(dirname "$0")/.."
exec mvn -pl banking-client javafx:run "$@"
