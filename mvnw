#!/bin/sh
# Maven wrapper script
MAVEN_PROJECTBASEDIR=$(cd "$(dirname "$0")" && pwd)
. "$MAVEN_PROJECTBASEDIR/.mvn/wrapper/maven-wrapper.properties" 2>/dev/null

# Download maven wrapper jar if needed
if [ ! -f "$MAVEN_PROJECTBASEDIR/.mvn/wrapper/maven-wrapper.jar" ]; then
  mkdir -p "$MAVEN_PROJECTBASEDIR/.mvn/wrapper"
  if command -v curl >/dev/null 2>&1; then
    curl -sL -o "$MAVEN_PROJECTBASEDIR/.mvn/wrapper/maven-wrapper.jar" "$wrapperUrl"
  elif command -v wget >/dev/null 2>&1; then
    wget -q -O "$MAVEN_PROJECTBASEDIR/.mvn/wrapper/maven-wrapper.jar" "$wrapperUrl"
  fi
fi

# Use JAVA_HOME if set
if [ -n "$JAVA_HOME" ]; then
  JAVA_EXE="$JAVA_HOME/bin/java"
else
  JAVA_EXE="java"
fi

exec "$JAVA_EXE" \
  -classpath "$MAVEN_PROJECTBASEDIR/.mvn/wrapper/maven-wrapper.jar" \
  "-Dmaven.multiModuleProjectDirectory=$MAVEN_PROJECTBASEDIR" \
  org.apache.maven.wrapper.MavenWrapperMain \
  "$@"
