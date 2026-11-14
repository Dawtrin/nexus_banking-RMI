#!/usr/bin/env bash
# Local end-to-end check with REAL processes: two server JVMs, two databases, real RMI on localhost.
# This is NOT a two-physical-machine test. Requires: java 21, a MySQL/MariaDB server, and a built classpath.
#
# Required environment:
#   NEXUS_CP        classpath with banking-contract, banking-server (main+test classes) and dependencies (slf4j, HikariCP, JDBC driver)
#   E2E_DB_HOST / E2E_DB_PORT / E2E_DB_USER / E2E_DB_PASSWORD   account allowed to CREATE/DROP databases
#   E2E_DB_CLIENT   command-line client (default: mysql; use mariadb for MariaDB)
set -u
: "${NEXUS_CP:?set NEXUS_CP}"; : "${E2E_DB_USER:?set E2E_DB_USER}"; : "${E2E_DB_PASSWORD:?set E2E_DB_PASSWORD}"
HOST=${E2E_DB_HOST:-127.0.0.1}; PORT=${E2E_DB_PORT:-3306}; CLI=${E2E_DB_CLIENT:-mysql}
export NEXUS_REPLICATION_SECRET=e2e-shared-secret-0123456789 NEXUS_ADMIN_TOKEN=e2e-admin-token-12345
SERVER=com.nexus.banking.server.ServerApplication
CLIENT=com.nexus.banking.server.e2e.E2eClient
LOGDIR=${E2E_LOGDIR:-/tmp/nexus-e2e}; mkdir -p "$LOGDIR"
pass=0; fail=0
ok()   { echo "PASS: $*"; pass=$((pass+1)); }
bad()  { echo "FAIL: $*"; fail=$((fail+1)); }
sql()  { "$CLI" -h "$HOST" -P "$PORT" -u "$E2E_DB_USER" -p"$E2E_DB_PASSWORD" -N -e "$1"; }

start_node() { # id peer regPort svcPort peerRegPort db
  java -Dorg.slf4j.simpleLogger.defaultLogLevel=warn -cp "$NEXUS_CP" $SERVER \
    --server.id=$1 --server.peer.id=$2 --server.rmi.registry.port=$3 --server.rmi.service.port=$4 \
    --server.peer.registry.port=$5 --database.enabled=true --database.host=$HOST --database.port=$PORT \
    --database.name=$6 --database.username="$E2E_DB_USER" --database.password="$E2E_DB_PASSWORD" \
    > "$LOGDIR/$1.log" 2>&1 &
  echo $! > "$LOGDIR/$1.pid"
}
stop_node() { kill -9 "$(cat "$LOGDIR/$1.pid")" 2>/dev/null; wait "$(cat "$LOGDIR/$1.pid")" 2>/dev/null; }
wait_port() { for _ in $(seq 1 40); do (exec 3<>/dev/tcp/127.0.0.1/$1) 2>/dev/null && return 0; sleep 0.5; done; return 1; }
client() { java -cp "$NEXUS_CP" $CLIENT "$@" 2>&1; }
history_has() { client history 127.0.0.1 "$1" "$2" | grep -c "$3"; }

for db in nexus_e2e_a nexus_e2e_b; do sql "DROP DATABASE IF EXISTS $db; CREATE DATABASE $db CHARACTER SET utf8mb4;"; done
start_node server-a server-b 41099 41100 42099 nexus_e2e_a
start_node server-b server-a 42099 42100 41099 nexus_e2e_b
wait_port 41099 && wait_port 42099 && ok "both servers listening (registry 41099 / 42099)" || { bad "servers did not start"; cat "$LOGDIR"/*.log; exit 1; }
sleep 3

client send 127.0.0.1 41099 alice 5 fromA >/dev/null; sleep 3
[ "$(history_has 42099 reader1 'fromA')" = 5 ] && ok "A -> B: 5 messages replicated over RMI" || bad "A -> B replication"
client send 127.0.0.1 42099 bob 3 fromB >/dev/null; sleep 3
[ "$(history_has 41099 reader2 'fromB')" = 3 ] && ok "B -> A: 3 messages replicated" || bad "B -> A replication"

stop_node server-b; ok "server B killed (kill -9)"
client send 127.0.0.1 41099 alice 4 whileBdown >/dev/null
client status 127.0.0.1 41099 "$NEXUS_ADMIN_TOKEN" | tee "$LOGDIR/status-while-down.txt" | grep -q "pending=4" && ok "A keeps 4 messages pending while B is down" || { bad "pending count while B down"; cat "$LOGDIR/status-while-down.txt"; }
start_node server-b server-a 42099 42100 41099 nexus_e2e_b
wait_port 42099 && ok "server B restarted" || bad "B restart"
sleep 8
[ "$(history_has 42099 reader3 'whileBdown')" = 4 ] && ok "B recovered all 4 messages after restart (outbox + catch-up)" || bad "recovery after B restart"
client status 127.0.0.1 41099 "$NEXUS_ADMIN_TOKEN" | tee "$LOGDIR/status-after.txt" | grep -q "pending=0" && ok "A backlog reached 0" || { bad "backlog not 0"; cat "$LOGDIR/status-after.txt"; }

stop_node server-a; start_node server-a server-b 41099 41100 42099 nexus_e2e_a; wait_port 41099 && sleep 3
client send 127.0.0.1 41099 alice 2 afterArestart >/dev/null; sleep 3
[ "$(sql "SELECT COUNT(*) FROM nexus_e2e_a.chat_messages")" = 14 ] && ok "A restart: all 14 messages in A's database, none lost" || bad "A message count: $(sql 'SELECT COUNT(*) FROM nexus_e2e_a.chat_messages')"
[ "$(sql "SELECT MAX(origin_sequence) FROM nexus_e2e_a.chat_messages WHERE origin_server_id='server-a'")" = 11 ] && ok "A origin sequence continued (11), no reuse" || bad "A origin sequence"
[ "$(history_has 42099 reader4 'afterArestart')" = 2 ] && ok "B received the post-restart messages from A" || bad "B missing post-restart messages"
[ "$(client history 127.0.0.1 42099 reader5 | grep -c '^MSG')" = 14 ] && ok "B holds all 14 messages" || bad "B total"

stop_node server-a; stop_node server-b
echo "RESULT: $pass passed, $fail failed (logs in $LOGDIR)"
[ "$fail" = 0 ]
