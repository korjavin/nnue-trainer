# Multi-stage Dockerfile for NNUE Trainer Java bot

# Stage 1: Build the Java application
FROM eclipse-temurin:21-jdk-alpine AS builder

WORKDIR /build

# Copy maven wrapper and pom
COPY .mvn ./.mvn
COPY mvnw pom.xml ./

# Ensure maven wrapper is executable
RUN chmod +x mvnw

# Pre-fetch dependencies to speed up subsequent builds
RUN ./mvnw dependency:go-offline -B

# Copy source code
COPY src ./src

# Build the jar skipping test runs (CI handles testing)
RUN ./mvnw clean package -DskipTests

# Stage 2: Create final JRE image
FROM eclipse-temurin:21-jre-alpine

# Add ca-certificates for HTTPS and tzdata
RUN apk --no-cache add ca-certificates tzdata

WORKDIR /app

# Copy the built jar from builder
COPY --from=builder /build/target/nnue-trainer-0.0.1-SNAPSHOT.jar ./nnue-trainer.jar

# Create non-root user and switch to it for secure execution
RUN addgroup -g 1000 appuser && \
    adduser -D -u 1000 -G appuser appuser && \
    chown -R appuser:appuser /app

USER appuser

# Run the Spring Boot application jar
CMD ["java", "-jar", "nnue-trainer.jar"]
