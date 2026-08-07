#!/usr/bin/env bash
# Run before pushing: catches spotless and compile failures locally.
set -euo pipefail
cd "$(dirname "$0")/.."
export JAVA_HOME="${JAVA_HOME:-/opt/homebrew/opt/openjdk@21/libexec/openjdk.jdk/Contents/Home}"
./mvnw -q spotless:check compile
echo "pre-push check OK"
