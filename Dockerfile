# syntax=docker/dockerfile:1

# --- Stage 1: build the agent jar (this whole stage is discarded) ---
FROM maven:3.9-eclipse-temurin-21 AS builder
WORKDIR /build

# Copy every module POM first so the reactor can load, then only the agent's sources.
# Building with -pl warden-agent -am compiles just the agent and its upstream (the parent),
# not the other modules, so their sources are not needed here.
COPY pom.xml ./
COPY warden-crd-model/pom.xml warden-crd-model/pom.xml
COPY warden-controller/pom.xml warden-controller/pom.xml
COPY warden-agent/pom.xml warden-agent/pom.xml
COPY examples/pom.xml examples/pom.xml
COPY coverage/pom.xml coverage/pom.xml
COPY warden-agent/src warden-agent/src

RUN mvn -q -B -pl warden-agent -am -DskipTests package

# --- Stage 2: runtime (this is what ships) ---
FROM eclipse-temurin:21-jdk AS runtime

# Run unprivileged.
RUN groupadd --system warden && useradd --system --gid warden --home-dir /app warden
WORKDIR /app

# Only the jar crosses from the builder. The agent has no runtime deps beyond the JDK, so a
# plain jar with a Main-Class manifest is directly runnable.
COPY --from=builder /build/warden-agent/target/warden-agent-*.jar /app/warden-agent.jar

USER warden
EXPOSE 8080
ENTRYPOINT ["java", "-jar", "/app/warden-agent.jar"]
