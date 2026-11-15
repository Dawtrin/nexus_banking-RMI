#!/usr/bin/env bash
# Start NEXUS BANKING branch Node A - Ha Noi (registry 1099)
set -euo pipefail
cd "$(dirname "$0")/.."

export NEXUS_REPLICATION_SECRET="${NEXUS_REPLICATION_SECRET:-nexus-cluster-shared-secret-16chars}"
export NEXUS_DB_ENABLED="${NEXUS_DB_ENABLED:-false}"
export NEXUS_DB_NAME="${NEXUS_DB_NAME:-nexus_banking_a}"
export NEXUS_ADMIN_TOKEN="${NEXUS_ADMIN_TOKEN:-nexus-admin-secret}"

PEER_HOST="127.0.0.1"
ADVERTISED_HOST=""

EXTRA_ARGS=()
while [[ $# -gt 0 ]]; do
  case "$1" in
    -PeerHost|--server.peer.host)
      PEER_HOST="$2"
      shift 2
      ;;
    --server.peer.host=*)
      PEER_HOST="${1#*=}"
      shift
      ;;
    -AdvertisedHost|--server.advertised.host)
      ADVERTISED_HOST="$2"
      shift 2
      ;;
    --server.advertised.host=*)
      ADVERTISED_HOST="${1#*=}"
      shift
      ;;
    -Db)
      export NEXUS_DB_ENABLED="true"
      shift
      ;;
    *)
      EXTRA_ARGS+=("$1")
      shift
      ;;
  esac
done

HOST_ARGS=("--server.peer.host=$PEER_HOST")
if [ -n "$ADVERTISED_HOST" ]; then
  HOST_ARGS+=("--server.advertised.host=$ADVERTISED_HOST")
fi

echo "========================================="
echo " NEXUS BANKING - Chi nhanh A (Ha Noi) - Node A, registry 1099"
echo " Peer Host:  $PEER_HOST:2099"
echo " DB Mode:    $NEXUS_DB_ENABLED"
echo "========================================="

exec ./scripts/run-node.sh \
  --server.id=server-a \
  --server.rmi.registry.port=1099 \
  --server.rmi.service.port=1100 \
  --server.peer.id=server-b \
  --server.peer.registry.port=2099 \
  --server.peer.service.port=2100 \
  "${HOST_ARGS[@]}" \
  "${EXTRA_ARGS[@]+"${EXTRA_ARGS[@]}"}"
