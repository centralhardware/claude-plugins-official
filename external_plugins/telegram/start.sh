#!/bin/sh
# Builds the server jar on first run (or after a plugin update changed the
# sources), then execs it. Gradle output goes to stderr: stdout is the MCP stream.
set -e
cd "$(dirname "$0")"
JAR=build/telegram-channel.jar
if [ ! -f "$JAR" ] || [ -n "$(find src build.gradle.kts settings.gradle.kts -newer "$JAR" -print -quit)" ]; then
  ./gradlew -q --console=plain fatJar >&2
fi
exec java -XX:+UseSerialGC -Xss512k -XX:TieredStopAtLevel=1 -jar "$JAR"
