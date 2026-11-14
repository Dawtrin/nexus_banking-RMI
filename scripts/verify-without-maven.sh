#!/usr/bin/env bash
# Substitute build + test run for machines WITHOUT Maven Central access (used for the recorded results).
# Uses Ubuntu packages: openjdk-21-jdk-headless libslf4j-java libhikaricp-java liblogback-java junit5 libassertj-core-java
#                       libmariadb-java openjfx(optional, API compile check only) mariadb-server(optional, DB tests)
# NOT equivalent to `mvn clean package`: library versions differ from the poms.
set -euo pipefail
cd "$(dirname "$0")/.."
J=/usr/share/java; B=${BUILD_DIR:-/tmp/nexus-build}
RT=$J/slf4j-api.jar:$J/HikariCP.jar:$J/logback-classic.jar:$J/logback-core.jar
TT=$J/junit-jupiter-api.jar:$J/assertj-core.jar:$J/opentest4j.jar:$J/apiguardian-api.jar:$J/mariadb-java-client.jar
rm -rf "$B"; mkdir -p "$B"/{contract,server,server-test,contract-test,dcore,dcore-test,acore,acore-test}
javac --release 21 -d "$B/contract" $(find banking-contract/src/main -name '*.java')
javac --release 21 -cp "$B/contract:$RT" -d "$B/server" $(find banking-server/src/main -name '*.java'); cp -r banking-server/src/main/resources/* "$B/server/"
javac --release 21 -cp "$B/server:$B/contract:$RT:$TT" -d "$B/server-test" $(find banking-server/src/test -name '*.java')
javac --release 21 -cp "$B/contract:$TT" -d "$B/contract-test" $(find banking-contract/src/test -name '*.java')
javac --release 21 -cp "$B/contract" -d "$B/dcore" banking-client/src/main/java/com/nexus/banking/client/core/ClientCore.java banking-client/src/main/java/com/nexus/banking/client/model/ConnectionState.java
javac --release 21 -cp "$B/contract:$B/dcore:$TT" -d "$B/dcore-test" banking-client/src/test/java/com/nexus/banking/client/core/*.java
javac --release 21 -cp "$B/contract" -d "$B/acore" banking-control/src/main/java/com/nexus/banking/control/core/*.java
javac --release 21 -cp "$B/contract:$B/acore:$TT" -d "$B/acore-test" banking-control/src/test/java/com/nexus/banking/control/*.java
run() { java -Dsun.rmi.transport.tcp.handshakeTimeout=1500 -jar $J/junit-platform-console-standalone.jar execute --class-path "$1" --scan-class-path "$2" --disable-banner --details=summary 2>&1 | grep -E "tests (found|successful|failed|aborted|skipped)"; }
echo "== banking-contract"; run "$B/contract:$B/contract-test:$J/assertj-core.jar" "$B/contract-test"
echo "== banking-server (database tests run only if NEXUS_TEST_DB_HOST is set)"; run "$B/contract:$B/server:$B/server-test:$RT:$J/assertj-core.jar:$J/mariadb-java-client.jar" "$B/server-test"
echo "== banking-client core"; run "$B/contract:$B/dcore:$B/dcore-test:$J/assertj-core.jar" "$B/dcore-test"
echo "== banking-control core"; run "$B/contract:$B/acore:$B/acore-test:$J/assertj-core.jar" "$B/acore-test"
