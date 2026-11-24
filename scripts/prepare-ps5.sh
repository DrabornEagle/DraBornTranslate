#!/usr/bin/env bash
set -euo pipefail
cd "$(dirname "$0")/.."
revision=f30da0aaeb96fc0026cc03683153f682352e7d7c
checkout=third-party/chiaki-ng
if ! git -C "$checkout" rev-parse --verify HEAD >/dev/null 2>&1; then
    git clone --depth 1 --branch v1.10.1 https://github.com/chiaki-ng/chiaki-ng.git "$checkout"
fi
if [[ "$(git -C "$checkout" rev-parse HEAD)" != "$revision" ]]; then
    echo 'Chiaki checkout does not match the documented revision.' >&2
    exit 1
fi
git -C "$checkout" submodule update --init --recursive --depth 1
