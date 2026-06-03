# ─────────────────────────────────────────────────────────────────────────────
# Stage 1 — Build the React/Vite frontend
# ─────────────────────────────────────────────────────────────────────────────
FROM node:22-alpine AS frontend-build

WORKDIR /app/frontend

# Install dependencies first (cache layer)
COPY frontend/package*.json ./
RUN npm ci --prefer-offline

# Copy source and build
COPY frontend/ ./
RUN npm run build
# Output lands in ../backend/src/main/resources/static (vite.config.ts outDir)
# That path resolves to /app/backend/src/main/resources/static


# ─────────────────────────────────────────────────────────────────────────────
# Stage 2 — Build the Spring Boot backend (fat jar)
# ─────────────────────────────────────────────────────────────────────────────
FROM eclipse-temurin:21-jdk-alpine AS backend-build

WORKDIR /app/backend

# Copy Maven wrapper + pom first for dependency caching
COPY backend/mvnw           ./
COPY backend/.mvn           ./.mvn
COPY backend/pom.xml        ./
RUN ./mvnw dependency:go-offline -q

# Copy source
COPY backend/src ./src

# Copy the built frontend assets into static resources
COPY --from=frontend-build /app/backend/src/main/resources/static \
     ./src/main/resources/static

# Package (skip tests — they need MockWebServer; run separately in CI)
RUN ./mvnw package -DskipTests -q

# The fat jar lands at target/deploymate-*.jar
RUN mv target/deploymate-*.jar target/app.jar


# ─────────────────────────────────────────────────────────────────────────────
# Stage 3 — Minimal JRE runtime image
# ─────────────────────────────────────────────────────────────────────────────
FROM eclipse-temurin:21-jre-alpine AS runtime

# Non-root user for security
RUN addgroup -S deploy && adduser -S deploy -G deploy

WORKDIR /app

# Copy the fat jar
COPY --from=backend-build /app/backend/target/app.jar ./app.jar

# Log directory writable by app user
RUN mkdir -p logs && chown deploy:deploy logs

USER deploy

EXPOSE 8080 5005

# Use exec form so signals reach the JVM
ENTRYPOINT ["java", \
  "-agentlib:jdwp=transport=dt_socket,server=y,suspend=n,address=*:5005", \
  "-XX:+UseZGC", \
  "-XX:+ZGenerational", \
  "-Xmx512m", \
  "-jar", "app.jar"]
