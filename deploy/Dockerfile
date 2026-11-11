# syntax=docker/dockerfile:1
#
# tt-server image. Build from the repository root:
#   docker build -f deploy/Dockerfile -t tt-server .
# (deploy/docker-compose.yml does this for you.)

# ---- build: Gradle installDist (runs on the build machine's own platform; the output is plain JVM bytecode)
FROM --platform=$BUILDPLATFORM eclipse-temurin:21-jdk AS build
WORKDIR /src

# Wrapper and build scripts first so the Gradle distribution is cached between source changes.
COPY gradlew settings.gradle.kts build.gradle.kts gradle.properties ./
COPY gradle ./gradle
RUN --mount=type=cache,target=/root/.gradle ./gradlew --version --no-daemon

COPY shared ./shared
COPY server ./server
# GTFS-Realtime .proto files used by :server:live (Wire code generation).
COPY proto ./proto
RUN --mount=type=cache,target=/root/.gradle \
    ./gradlew :server:app:installDist --no-daemon --console=plain

# ---- runtime
FROM eclipse-temurin:21-jre

# curl is used by the health check (present in current temurin images; installed if missing).
RUN command -v curl >/dev/null 2>&1 || \
      (apt-get update && apt-get install -y --no-install-recommends curl && rm -rf /var/lib/apt/lists/*)

# Unprivileged user; /data holds the database, timetable packs and GTFS archive.
RUN groupadd --system --gid 10001 tt \
 && useradd --system --uid 10001 --gid tt --home-dir /data --no-create-home --shell /usr/sbin/nologin tt \
 && mkdir -p /data \
 && chown tt:tt /data

COPY --from=build /src/server/app/build/install/tt-server /opt/tt-server

# A fixed heap, not a share of RAM: inside Docker inside an LXC container the JVM can see the
# host's memory rather than the container's, and building the bus timetable fills whatever heap it
# is given. Measured on 12 Nov 2026 (both packs built from scratch): -Xmx1g peaks at about 1.33 GB
# for the whole process, which fits a 2 GB container; 75% of 2 GB (1.5 GB) peaked at 1.67 GB and
# was killed by the host. On a machine with more memory, raise it with JAVA_OPTS in deploy/.env.
ENV PATH="/opt/tt-server/bin:${PATH}" \
    TT_DATA_DIR=/data \
    TT_PORT=8080 \
    JAVA_OPTS="-Xmx1g -XX:+ExitOnOutOfMemoryError"

VOLUME ["/data"]
USER tt
WORKDIR /data
EXPOSE 8080

HEALTHCHECK --interval=30s --timeout=5s --start-period=60s --retries=3 \
    CMD curl -fsS "http://localhost:${TT_PORT}/health" >/dev/null || exit 1

ENTRYPOINT ["tt-server"]
