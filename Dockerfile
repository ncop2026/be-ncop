# ─── Backend Dockerfile ───────────────────────────────────────────────────────
# Deployment target: Railway / Render (Docker-based PaaS)
# NOTE: Vercel does NOT support Java/JVM long-running processes.
#       This Dockerfile is designed for Railway or Render — platforms that accept
#       Docker containers and are a drop-in alternative for Spring Boot backends.
#
# Google Cloud Run deployment has been commented out.
# See: .github/workflows/deploy-cloud-run-dev.yml (commented out)
# ──────────────────────────────────────────────────────────────────────────────

# Stage 1: Build the application
FROM maven:3.9.6-eclipse-temurin-17 AS builder
WORKDIR /app
COPY pom.xml .
RUN mvn -B dependency:go-offline -DskipTests
COPY src ./src
# Build and skip tests (tests require a live DB connection)
RUN mvn clean package -DskipTests

# Stage 2: Run the application
FROM eclipse-temurin:17-jre-jammy
WORKDIR /app

# Create a non-root user for security
RUN groupadd -r spring && useradd -r -g spring spring
USER spring

COPY --from=builder /app/target/be-ncop-0.0.1-SNAPSHOT.jar app.jar

# PORT is injected by Railway / Render at runtime (defaults to 8080 locally)
EXPOSE 8080

# -Dserver.port=${PORT} ensures Spring Boot listens on the platform-assigned port
ENTRYPOINT ["sh", "-c", "java -Xmx400m -Xss512k -XX:+UseContainerSupport -Dserver.port=${PORT:-8080} -jar app.jar"]
