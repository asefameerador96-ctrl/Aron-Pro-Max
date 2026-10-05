# syntax=docker/dockerfile:1.7
# Aron backend image: ONE image, three roles chosen by ARON_ROLE (api | worker | migrate; docs/24 s6.1, D24-27).
#
# Build context = the Gradle distribution, produced on the runner first (keeps the image build fast and the
# context small):
#   ./gradlew :backend:app:installDist
#   docker build -f infra/docker/backend.Dockerfile backend/app/build/install/aron-backend
#
# The Application Insights Java agent (version pinned in gradle/libs.versions.toml, appinsights-agent) is attached
# with -javaagent; it reads APPLICATIONINSIGHTS_CONNECTION_STRING and APPLICATIONINSIGHTS_ROLE_NAME at start.

ARG JRE_IMAGE=eclipse-temurin:21.0.12_8-jre-noble@sha256:7739f0ffce786528961eea6bf46d9610ee968ac6127c9b2e93494757bdecce9f

FROM ${JRE_IMAGE} AS agent
ARG AI_AGENT_VERSION=3.7.10
ARG AI_AGENT_SHA256=93a70c8f5d364c7e777f6c4d1b235dba91aef8448bd3fa94359f1d7f3e2eb0ec
ADD --checksum=sha256:${AI_AGENT_SHA256} \
    https://repo1.maven.org/maven2/com/microsoft/azure/applicationinsights-agent/${AI_AGENT_VERSION}/applicationinsights-agent-${AI_AGENT_VERSION}.jar \
    /agent/applicationinsights-agent.jar

FROM ${JRE_IMAGE}
LABEL org.opencontainers.image.title="aron-backend" \
      org.opencontainers.image.source="https://github.com/asefameerador96-ctrl/Aron-Pro-Max"
RUN groupadd --system --gid 10001 aron && useradd --system --uid 10001 --gid aron --no-create-home aron
COPY --from=agent --chown=root:root --chmod=0444 /agent/applicationinsights-agent.jar /opt/aron/applicationinsights-agent.jar
COPY --chown=root:root . /opt/aron/app
ENV JAVA_TOOL_OPTIONS="-javaagent:/opt/aron/applicationinsights-agent.jar -XX:MaxRAMPercentage=75 -XX:+ExitOnOutOfMemoryError" \
    PORT=8080 \
    TZ=UTC
USER 10001:10001
EXPOSE 8080
ENTRYPOINT ["/opt/aron/app/bin/aron-backend"]
