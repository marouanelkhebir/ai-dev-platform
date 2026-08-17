#!/usr/bin/env sh
# Prepares a local development environment without starting the application.
set -eu

project_dir="$(CDPATH= cd "$(dirname "$0")" && pwd)"
cd "$project_dir"

if ! command -v docker >/dev/null 2>&1; then
    echo "Docker is required but was not found in PATH." >&2
    exit 1
fi

if ! docker info >/dev/null 2>&1; then
    echo "Docker is installed but its daemon is unavailable. Start Docker and retry." >&2
    exit 1
fi

if [ ! -f .env ]; then
    cp .env.example .env
    echo "Created .env from .env.example. Fill in its credentials before starting the platform."
fi

echo "Building the Maven sandbox image..."
docker build -t ai-dev-sandbox:21 docker/sandbox

echo "Building the Angular sandbox image..."
docker build -t ai-dev-sandbox-angular:22 docker/sandbox-angular

echo "Building the Python 3.11 sandbox image..."
docker build -t ai-dev-sandbox-python:3.11 docker/sandbox-python

echo "Initialization complete. Configure .env, then run: docker compose up --build"
