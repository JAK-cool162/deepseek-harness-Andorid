#!/bin/bash
# Built-in Linux engine installer: runs as root inside the app's Debian under proot.
# Idempotent: every step checks its own result, so a failed install can be rerun.
set -euo pipefail
export DEBIAN_FRONTEND=noninteractive
NODE_VERSION=24.22.3
DSH_PACKAGE=@deepseek-ai/dsh@alpha
DSH_SCRIPTS=@deepseek-ai/dsh-subprocess-local,koffi,node-pty,protobufjs

step() { echo "::step:: $*"; }

step "Preparing package manager"
# proot cannot switch users; apt must download as root.
echo 'APT::Sandbox::User "root";' > /etc/apt/apt.conf.d/01-dsh-android-sandbox
rm -f /etc/apt/apt.conf.d/docker-clean

if ! command -v git > /dev/null || ! command -v g++ > /dev/null || ! command -v xz > /dev/null || ! command -v gh > /dev/null; then
  step "Installing system tools"
  apt-get update
  apt-get install -y --no-install-recommends ca-certificates curl xz-utils git gh python3 make g++ procps
  apt-get clean
fi

case "$(uname -m)" in
  aarch64) NODE_ARCH=arm64 ;;
  x86_64) NODE_ARCH=x64 ;;
  *) echo "unsupported architecture $(uname -m)"; exit 1 ;;
esac
if [ "$(node --version 2> /dev/null || true)" != "v$NODE_VERSION" ]; then
  step "Installing Node.js $NODE_VERSION"
  curl -fsSL "https://nodejs.org/dist/v$NODE_VERSION/node-v$NODE_VERSION-linux-$NODE_ARCH.tar.xz" \
    | tar -xJ -C /usr/local --strip-components=1 --no-same-owner
fi
node --version

step "Installing DeepSeek Harness"
npm install -g --no-fund --no-audit --allow-scripts="$DSH_SCRIPTS" "$DSH_PACKAGE"
dsh --version || true

mkdir -p /root/work
touch /root/.dsh-android-installed
step "Done"
