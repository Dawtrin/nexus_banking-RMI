#!/usr/bin/env bash
# ==============================================================================
# Generate Self-Signed TLS/SSL Certificates for NEXUS Distributed Chat (Bash)
# ==============================================================================
set -euo pipefail
cd "$(dirname "$0")/.."

OUTPUT_DIR="certs"
PASSWORD="nexus-changeit"
DOMAIN_OR_IP="localhost"

echo "=========================================================="
echo " NEXUS DISTRIBUTED CHAT - TLS/SSL CERTIFICATE GENERATOR"
echo "=========================================================="

if ! command -v keytool >/dev/null 2>&1; then
    echo "ERROR: Java 'keytool' not found in PATH." >&2
    exit 1
fi

mkdir -p "$OUTPUT_DIR"
KEYSTORE="$OUTPUT_DIR/nexus-server.p12"
TRUSTSTORE="$OUTPUT_DIR/nexus-truststore.p12"
CERT="$OUTPUT_DIR/nexus-cert.cer"

rm -f "$KEYSTORE" "$TRUSTSTORE" "$CERT"

SAN="dns:localhost,dns:$DOMAIN_OR_IP,ip:127.0.0.1"

echo "[1/3] Generating Server Keystore (RSA 2048, PKCS12)..."
keytool -genkeypair \
    -alias nexus \
    -keyalg RSA \
    -keysize 2048 \
    -storetype PKCS12 \
    -keystore "$KEYSTORE" \
    -storepass "$PASSWORD" \
    -keypass "$PASSWORD" \
    -dname "CN=$DOMAIN_OR_IP, OU=Nexus, O=NexusChat, C=VN" \
    -validity 3650 \
    -ext "SAN=$SAN"

echo "[2/3] Exporting Public Certificate..."
keytool -exportcert \
    -alias nexus \
    -keystore "$KEYSTORE" \
    -storepass "$PASSWORD" \
    -file "$CERT"

echo "[3/3] Creating Client/Peer Truststore..."
keytool -importcert \
    -noprompt \
    -alias nexus \
    -file "$CERT" \
    -keystore "$TRUSTSTORE" \
    -storepass "$PASSWORD"

echo "=========================================================="
echo " CERTIFICATES GENERATED SUCCESSFULLY in $OUTPUT_DIR/"
echo "=========================================================="
