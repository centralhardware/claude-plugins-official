#!/bin/sh
# Builds the server jar on first run (or after a plugin update changed the
# sources), then execs it. Gradle output goes to stderr: stdout is the MCP stream.
set -e
cd "$(dirname "$0")"
JAR=build/telegram-channel.jar
if [ ! -f "$JAR" ] || [ -n "$(find src build.gradle.kts settings.gradle.kts -newer "$JAR" -print -quit)" ]; then
  ./gradlew -q --console=plain fatJar >&2
fi
# FORK, not the default POSIX_SPAWN: posix_spawn goes through lib/jspawnhelper, and
# an apt upgrade of the JDK under the running JVM leaves a version mismatch that
# fails every ProcessBuilder.start() (tmux, /ns) until the server restarts.
exec java -Djdk.lang.Process.launchMechanism=FORK -XX:+UseSerialGC -Xss512k -XX:TieredStopAtLevel=1 -jar "$JAR"
