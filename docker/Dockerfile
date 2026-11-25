# The agent container image, `qits/agent`: the released workspace toolchain with this daemon
# compiled in as its entrypoint. Shaped like qits-workspace-daemon's docker/Dockerfile, stage for
# stage: `build` (native compile + CycloneDX), `sbom` (the document, exported), `runnable`/`binary`
# (a runnable jar, for a pin test that wants one), and the runtime image last.
#
# --- the base pin ---------------------------------------------------------------------------------
# ONE LINE, ONE VERSION TOKEN, AND A MACHINE EDITS IT: qits-maintenance reads literal `ARG
# <NAME>=<image>:<tag>` defaults as docker pins (`arg:WORKSPACE_BASE`) and bumps the tag in place.
# Keep the value LITERAL — the same pin qits-workspace-daemon runs on.
ARG QITS_DOMAIN=wohlben.eu
ARG WORKSPACE_BASE=registry.qits.${QITS_DOMAIN}/qits/workspace-base:2026.1125.92124

# --- stage `build`: native-compile ----------------------------------------------------------------
# The builder JDK MUST be 25 (this project targets release 25), pulled through qits-mirror's quay
# cache.
FROM mirror.qits.${QITS_DOMAIN}/quay/quarkus/ubi9-quarkus-mandrel-builder-image:jdk-25 AS build
USER root
WORKDIR /src

COPY mvnw .
COPY .mvn/ .mvn/
RUN ./mvnw --version

# The whole reactor; `.dockerignore` keeps it cheap.
COPY . .

ARG QITS_DOMAIN=wohlben.eu
ENV QITS_DOMAIN=$QITS_DOMAIN

# The two secret mounts put the CI run's commissioned client into THIS `RUN`'s environment and
# nowhere else. Tests are skipped here: the git suites fork real git and the CI pipeline's verify
# step runs them before this build. cyclonedx.skipNotDeployed=false is load-bearing: the daemon
# module is <packaging>quarkus</packaging> and ships as this image, not as a deployed jar.
RUN --mount=type=secret,id=qits-client-id \
    --mount=type=secret,id=qits-client-secret \
    QITS_MAVEN_AUTH_USR="$(cat /run/secrets/qits-client-id 2>/dev/null || true)" \
    QITS_MAVEN_AUTH_PSW="$(cat /run/secrets/qits-client-secret 2>/dev/null || true)" \
    QITS_MAVEN_CENTRAL_URL="https://mirror.qits.$QITS_DOMAIN/mirror/maven/central" \
    QITS_MAVEN_REPOSITORY_URL="https://registry.qits.$QITS_DOMAIN/artifacts/maven/maven" \
    ./mvnw -B -ntp -s .qits-maven-settings.xml -pl agents-daemon -am package -Dnative -DskipTests \
      -Dqits.maven.repository.url="https://registry.qits.$QITS_DOMAIN/artifacts/maven/maven" \
      -Dquarkus.native.native-image-xmx=4g \
      org.cyclonedx:cyclonedx-maven-plugin:2.9.1:makeBom \
      -DoutputFormat=json -DoutputName=sbom -DschemaVersion=1.6 \
      -Dcyclonedx.skipNotDeployed=false

# --- sbom -----------------------------------------------------------------------------------------
# Before the runtime stage on purpose: the last stage is what a plain `docker build` builds.
FROM scratch AS sbom
COPY --from=build /src/agents-daemon/target/sbom.json /sbom.json

# --- runnable ------------------------------------------------------------------------------------
# The daemon as a runnable uber-jar: a JVM package of the reactor already resolved above, seconds
# against the native compile's minutes. `--target binary` exports it alone.
FROM build AS runnable
RUN --mount=type=secret,id=qits-client-id \
    --mount=type=secret,id=qits-client-secret \
    QITS_MAVEN_AUTH_USR="$(cat /run/secrets/qits-client-id 2>/dev/null || true)" \
    QITS_MAVEN_AUTH_PSW="$(cat /run/secrets/qits-client-secret 2>/dev/null || true)" \
    QITS_MAVEN_CENTRAL_URL="https://mirror.qits.$QITS_DOMAIN/mirror/maven/central" \
    QITS_MAVEN_REPOSITORY_URL="https://registry.qits.$QITS_DOMAIN/artifacts/maven/maven" \
    ./mvnw -B -ntp -s .qits-maven-settings.xml -pl agents-daemon -am package -DskipTests \
      -Dqits.maven.repository.url="https://registry.qits.$QITS_DOMAIN/artifacts/maven/maven" \
      -Dquarkus.package.jar.enabled=true \
      -Dquarkus.package.jar.type=uber-jar

FROM scratch AS binary
COPY --from=runnable /src/agents-daemon/target/qits-agents-daemon.jar /qits-agents-daemon.jar

# --- runtime: the toolchain, with the daemon as its entrypoint ------------------------------------
# git, a JDK, node, claude, the qits CLI, qits-browser-mcp and /usr/local/bin/qits-git-credential
# all come with the base.
FROM ${WORKSPACE_BASE}

COPY --from=build /src/agents-daemon/target/qits-agents-daemon /usr/local/bin/qits-agents-daemon
RUN chmod +x /usr/local/bin/qits-agents-daemon

# NO `USER`: the runner starts the container as the same non-root user as the desk's (the AGENT
# lease policy), with --init, /workspace as the kept work volume and /claude-home as the shared
# credential volume. No EXPOSE: the only listener is the hook webhook on 127.0.0.1.
# PLATFORM TRUST ANCHORS (epic qits-2524): start through qits-trust-init, which writes the anchors
# the creator put in QITS_TRUST_ANCHORS into every trust store under /etc/ssl/qits and then execs the
# daemon. It never stops the start. The base must carry it (workspace-base from 2026.1125.92124 on),
# or this entrypoint would name a missing file: the RUN fails the build instead of every container.
RUN test -x /usr/local/bin/qits-trust-init \
    || { echo "the base image has no /usr/local/bin/qits-trust-init: its workspace-base predates qits-2524" >&2; exit 1; }
ENTRYPOINT ["/usr/local/bin/qits-trust-init", "/usr/local/bin/qits-agents-daemon"]
