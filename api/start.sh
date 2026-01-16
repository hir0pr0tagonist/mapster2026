#!/bin/sh

# Download Maven Wrapper if not present
if [ ! -f ./mvnw ]; then
  mvn -N io.takari:maven:wrapper
fi

./mvnw spring-boot:run
