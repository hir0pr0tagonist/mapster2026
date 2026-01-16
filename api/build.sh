#!/bin/sh

# Download Maven Wrapper if not present
if [ ! -f ./mvnw ]; then
  mvn -N io.takari:maven:wrapper
fi

chmod +x ./mvnw
./mvnw package -DskipTests
