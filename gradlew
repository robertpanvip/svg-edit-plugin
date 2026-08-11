#!/usr/bin/env sh
###################################################################
# Gradle start up script for UN*X
# (from Gradle 8.5 wrapper)
###################################################################
set -e
APP_NAME="Gradle"
DIRNAME="$(dirname "$0")"
APP_HOME="$(cd "$DIRNAME" && pwd)"
exec "$APP_HOME/gradle/wrapper/gradle-wrapper.jar" "$@"
# Note: This script expects the Gradle wrapper jar to be present under gradle/wrapper/
