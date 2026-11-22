#!/bin/bash

# Containerized e2e test runner, the default for `bb run-e2e-tests-ws`.
# Runs launch.js on a virtual display, passing on the arguments.
# The tests run against out/, which the host's build keeps current. The container doesn't compile.

set -e

if ! command -v docker > /dev/null; then
    echo "Docker not found. To run the e2e tests on this machine instead: bb run-e2e-tests-ws --local" >&2
    exit 1
fi

cd "$(dirname "$0")"
BD_REPO="$(cd .. && pwd)"
export BD_REPO

docker compose run --rm e2e-test \
    xvfb-run --auto-servernum --server-args="-screen 0 1280x1024x24" \
    node e2e-test-ws/launch.js "$@"
