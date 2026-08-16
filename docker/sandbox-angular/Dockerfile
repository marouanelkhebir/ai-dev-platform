FROM node:22-bookworm-slim

# Chromium's normal sandbox cannot start after the platform has dropped every container capability
# and enabled no-new-privileges. Keep this exception local to the disposable, unprivileged Angular
# sandbox rather than asking each generated project to change Karma.
RUN apt-get update \
    && apt-get install -y --no-install-recommends git ca-certificates chromium \
    && rm -rf /var/lib/apt/lists/* \
    && printf '%s\n' '#!/bin/sh' 'exec /usr/bin/chromium --headless --no-sandbox --disable-dev-shm-usage "$@"' \
        > /usr/local/bin/chromium-for-karma \
    && chmod 0755 /usr/local/bin/chromium-for-karma \
    && mkdir -p /workspaces \
    && chown -R node:node /workspaces

USER node
WORKDIR /workspaces

ENV CI=true \
    CHROME_BIN=/usr/local/bin/chromium-for-karma \
    GIT_TERMINAL_PROMPT=0

CMD ["sleep", "infinity"]
