#!/bin/sh

SCRIPT_DIR=$(CDPATH= cd -- "$(dirname -- "$0")" && pwd)
cd "$SCRIPT_DIR" || exit 1

# Download Maven Wrapper if not present
if [ ! -f ./mvnw ]; then
  mvn -N io.takari:maven:wrapper
fi

chmod +x ./mvnw
./mvnw spring-boot:run
