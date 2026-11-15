#!/usr/bin/env bash
set -euo pipefail
cd "$(dirname "$0")"
jbr="${JAVA_HOME:-/Applications/Android Studio.app/Contents/jbr/Contents/Home}"
mkdir -p build/test
"$jbr/bin/javac" --release 17 -encoding UTF-8 -d build/test src/Scenario.java test/ScenarioTest.java
"$jbr/bin/java" -cp build/test kr.routerescue.demo.ScenarioTest
