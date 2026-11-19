#!/usr/bin/env bash
# Runs the tournament-rules tests with plain Java (no Android SDK needed).
set -euo pipefail
cd "$(dirname "$0")/android/app/src"
out=$(mktemp -d)
javac -d "$out" main/java/org/pickleballpools/core/*.java test/java/org/pickleballpools/core/CoreTest.java
java -cp "$out" org.pickleballpools.core.CoreTest
