# Dockerfile for Proxy-Client (Gradle)

# --- Build Stage ---
FROM eclipse-temurin:17-jdk-focal AS builder

WORKDIR /app

# Copy Gradle wrapper and build files
COPY gradlew .
COPY gradle gradle
COPY build.gradle .
COPY settings.gradle .

# Copy source code
COPY src ./src

# Make gradlew executable
RUN chmod +x gradlew

# Build the Spring Boot application using Gradle
# The 'bootJar' task creates an executable JAR
RUN ./gradlew bootJar -x test

# --- Run Stage ---
FROM eclipse-temurin:17-jre-focal

WORKDIR /app

# Copy the built JAR from the build stage
# Gradle's bootJar typically puts it in build/libs/
COPY --from=builder /app/build/libs/*.jar app.jar

# Expose the port where the client proxy listens for browser connections (e.g., 8080)
EXPOSE 8080

# Run the Spring Boot application
# Use environment variables or bind-mount application.properties for external configuration
ENTRYPOINT ["java", "-jar", "app.jar"]

# Example of how you might pass properties at runtime (e.g., in docker-compose.yml)
# -Dproxy.server.host=offshore-proxy -Dproxy.server.port=9000
# -Dreconnect.initial-delay-ms=1000 -Dreconnect.max-delay-ms=32000
# -Dheartbeat.interval-ms=10000 -Dheartbeat.timeout-ms=5000
