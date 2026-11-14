#!/usr/bin/env bash
# Starts one NEXUS server node in the foreground (exit code = server exit code).
# Build first:  mvn -DskipTests package        (produces banking-server/target/banking-server-*-all.jar)
#
# Required environment:  NEXUS_REPLICATION_SECRET   (>= 16 chars, identical on both nodes)
# Strongly recommended:  NEXUS_ADMIN_TOKEN          (>= 12 chars; without it NEXUS CONTROL is refused)
# Per node:              NEXUS_SERVER_ID, NEXUS_PEER_ID, NEXUS_SERVER_HOST (THIS machine's LAN address),
#                        NEXUS_PEER_HOST, and for a database: NEXUS_DB_ENABLED=true DB_USERNAME DB_PASSWORD
# Any setting can also be passed as --key=value after the script name (see README "Configuration").
set -euo pipefail
cd "$(dirname "$0")/.."
JAR=$(ls banking-server/target/banking-server-*-all.jar 2>/dev/null | head -n1 || true)
[ -n "$JAR" ] || { echo "ERROR: banking-server fat jar not found. Run: mvn -DskipTests package" >&2; exit 1; }
command -v java >/dev/null || { echo "ERROR: java 21 not found in PATH" >&2; exit 1; }
: "${NEXUS_REPLICATION_SECRET:?ERROR: set NEXUS_REPLICATION_SECRET (same value on both nodes)}"
: "${NEXUS_DB_ENABLED:?ERROR: set NEXUS_DB_ENABLED=true (MySQL/MariaDB) or false (RAM-only demo, NOT durable)}"
exec java -Dsun.rmi.transport.tcp.handshakeTimeout=5000 -jar "$JAR" "$@"
