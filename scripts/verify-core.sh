#!/usr/bin/env bash
# Compiles :core with kotlinc and runs the self-test (needs kotlinc + JDK 17+). Usage: scripts/verify-core.sh
set -euo pipefail
cd "$(dirname "$0")/.."
out=$(mktemp -d); trap 'rm -rf "$out"' EXIT
kotlinc core/src/main/kotlin -d "$out/core.jar"
keytool -genkeypair -alias t -keyalg RSA -keysize 2048 -storetype PKCS12 -keystore "$out/t.p12" -storepass changeit -keypass changeit -validity 3 -dname CN=test -ext "SAN=dns:www.gstatic.com,dns:cp.cloudflare.com,dns:api.ipify.org,dns:speed.cloudflare.com" >/dev/null
kotlinc core/src/test/kotlin -cp "$out/core.jar" -d "$out/test.jar"
KT=$(dirname "$(dirname "$(readlink -f "$(command -v kotlinc)")")")/lib/kotlin-stdlib.jar
java -cp "$out/core.jar:$out/test.jar:$KT" ir.irangold.core.SelfTestKt "$out/t.p12" "$out/cfg"
