FROM python:3.11-slim-bookworm

# This profile deliberately pins the supported minimum runtime: repositories run with Python 3.11,
# while later Python profiles can be introduced without changing its behaviour.
ARG PYTEST_VERSION=8.3.5

# pytest is part of the sandbox toolchain, not a dependency that every repository must declare.
RUN apt-get update \
    && apt-get install -y --no-install-recommends git ca-certificates \
    && rm -rf /var/lib/apt/lists/* \
    && python -m pip install --no-cache-dir "pytest==${PYTEST_VERSION}" \
    && groupadd --gid 1000 builder \
    && useradd --uid 1000 --gid builder --create-home --shell /bin/bash builder \
    && mkdir -p /workspaces \
    && chown -R builder:builder /workspaces

USER builder
WORKDIR /workspaces

ENV GIT_TERMINAL_PROMPT=0 \
    PIP_DISABLE_PIP_VERSION_CHECK=1 \
    PYTHONDONTWRITEBYTECODE=1

CMD ["sleep", "infinity"]
