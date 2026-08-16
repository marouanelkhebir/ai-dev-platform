# syntax=docker/dockerfile:1
#
# Sandbox image: the throwaway environment a ticket is developed in.
#
# Build once, then reference it through `sandbox.image`:
#     docker build -t ai-dev-sandbox:21 docker/sandbox
#
# What is deliberately NOT here:
#  * no shell tooling beyond what a build needs — the platform never spawns a shell anyway;
#  * no curl, wget, ssh or netcat — the executable allowlist would reject them, and not shipping
#    them removes the question entirely;
#  * no production credentials, no kubeconfig, no cloud CLI. The container has network access for
#    git and Maven Central only, and nothing in it can reach a production system.

FROM eclipse-temurin:21-jdk-jammy

ARG MAVEN_VERSION=3.9.9

RUN apt-get update \
    && apt-get install -y --no-install-recommends git ca-certificates \
    && rm -rf /var/lib/apt/lists/*

# Maven, for repositories that do not ship a wrapper.
ADD https://archive.apache.org/dist/maven/maven-3/${MAVEN_VERSION}/binaries/apache-maven-${MAVEN_VERSION}-bin.tar.gz /tmp/maven.tar.gz
RUN tar -xzf /tmp/maven.tar.gz -C /opt \
    && ln -s /opt/apache-maven-${MAVEN_VERSION}/bin/mvn /usr/local/bin/mvn \
    && rm /tmp/maven.tar.gz

# Uncomment when your repositories need a front-end build.
# RUN apt-get update && apt-get install -y --no-install-recommends nodejs npm && rm -rf /var/lib/apt/lists/*

RUN groupadd --gid 1000 builder \
    && useradd --uid 1000 --gid builder --create-home --shell /bin/bash builder \
    && mkdir -p /workspaces \
    && chown -R builder:builder /workspaces

# Point Maven at the internal mirror; a build that reaches the public internet is a finding, not a
# feature.
COPY settings.xml /home/builder/.m2/settings.xml
RUN chown -R builder:builder /home/builder/.m2

USER builder
WORKDIR /workspaces

ENV MAVEN_OPTS="-Xmx2g -Djava.awt.headless=true" \
    GIT_TERMINAL_PROMPT=0

# The platform keeps the container alive and execs into it; this is only the default.
CMD ["sleep", "infinity"]
