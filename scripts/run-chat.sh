#!/usr/bin/env bash
# NEXUS CHAT (end users): own entry point com.nexus.banking.client.Launcher
set -e
cd "$(dirname "$0")/.."
exec mvn -pl banking-client javafx:run "$@"
