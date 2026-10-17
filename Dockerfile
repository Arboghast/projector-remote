# ── Phone app ──
FROM node:22-slim AS web
WORKDIR /web
COPY remote-web/package.json remote-web/package-lock.json ./
RUN npm ci
COPY remote-web/ ./
RUN npm run build

# ── Relay ──
FROM eclipse-temurin:21-jdk AS relay
WORKDIR /src
COPY gradlew settings.gradle.kts build.gradle.kts ./
COPY gradle gradle
# A Windows checkout can give gradlew CRLF endings, which sh can't run.
RUN sed -i 's/\r$//' gradlew && chmod +x gradlew && ./gradlew --no-daemon --quiet dependencies > /dev/null
COPY src src
RUN ./gradlew --no-daemon --quiet installDist

# ── Runtime ──
FROM eclipse-temurin:21-jre
RUN apt-get update && apt-get install -y --no-install-recommends adb && rm -rf /var/lib/apt/lists/*
WORKDIR /app
COPY --from=relay /src/build/install/projector-remote ./
COPY --from=web /web/dist ./web
ADD --checksum=sha256:764eb6f79811d5211fe9df341120882ba9994c7a61b897d7bf3fb662e53bc536 \
    https://github.com/Genymobile/scrcpy/releases/download/v5.0.1/scrcpy-server-v5.0.1 tools/scrcpy-server-v5.0.1
COPY tools/ink-overlay.apk tools/
ENV HOST=0.0.0.0 \
    WEB_DIR=/app/web \
    SHELF_FILE=/data/shelf.json
CMD ["bin/projector-remote"]
