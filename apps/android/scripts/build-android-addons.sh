#!/usr/bin/env bash
# Cross-compile the native Node addons the full DeepSeek Harness needs for Android (bionic),
# which the published packages ship only for glibc, musl, macOS, and Windows.
# Output, per ABI under android/app/src/main/assets/native-addons/<abi>/:
#   node-addon-require-builtin-android-<arch>/  a package that addon's loader finds by name;
#   node-addon-system-android-<arch>/           this repository's flock addon (native/system), which
#                                               LinuxEngine installs and enables for Android.
# Requires ANDROID_NDK_HOME (NDK 28), Node 24 headers, git, npm.
set -euo pipefail
here="$(cd "$(dirname "$0")" && pwd)"
out="$here/../android/app/src/main/assets/native-addons"
ndk="${ANDROID_NDK_HOME:?set ANDROID_NDK_HOME to an NDK 28 install}/toolchains/llvm/prebuilt/linux-x86_64/bin"
node_headers="${NODE_HEADERS:-$(dirname "$(dirname "$(command -v node)")")/include/node}"
# dsh-node-addon-require-builtin at the commit these builds were verified against.
source_commit=a58d3599a2e551d7d706d1ff796c9fd4bbaff63c
work="$(mktemp -d)"
trap 'rm -rf "$work"' EXIT

git clone -q https://github.com/deepseek-ai/dsh-node-addon-require-builtin "$work/src"
git -C "$work/src" checkout -q "$source_commit"
(cd "$work" && npm pack --silent node-addon-api@8 > /dev/null && tar -xzf node-addon-api-*.tgz)
native="$work/src/packages/native"
sources=$(node -e '
  const gyp = JSON.parse(require("fs").readFileSync(process.argv[1], "utf8").replace(/#.*/g, ""));
  const target = gyp.targets[0];
  const napi = target.conditions.find(c => c[0].startsWith("narb_backend"))[2].sources;
  console.log([...target.sources, ...napi].join(" "));
' "$native/binding.gyp")

for pair in aarch64:arm64:arm64-v8a x86_64:x64:x86_64; do
  IFS=: read -r triple arch abi <<< "$pair"
  package="$out/$abi/node-addon-require-builtin-android-$arch"
  mkdir -p "$package"
  # shellcheck disable=SC2086
  "$ndk/${triple}-linux-android24-clang++" -shared -fPIC -O2 -std=c++17 -fno-exceptions -fvisibility=hidden \
    -Wno-unused-parameter -DNAPI_VERSION=9 -DNODE_ADDON_API_DISABLE_CPP_EXCEPTIONS -DNARB_BACKEND=1 -DNARB_PRODUCT=1 \
    -DBUILDING_NODE_EXTENSION -I"$node_headers" -I"$work/package" -I"$native/src" \
    $(for f in $sources; do printf '%s ' "$native/$f"; done) -static-libstdc++ -s -o "$package/require_builtin.node"
  printf "module.exports = require('./require_builtin.node')\n" > "$package/index.js"
  printf '{"name":"node-addon-require-builtin-android-%s","version":"0.1.10","main":"index.js","license":"MIT"}\n' "$arch" > "$package/package.json"
  echo "built $package"

  system="$out/$abi/node-addon-system-android-$arch"
  mkdir -p "$system/bin"
  "$ndk/${triple}-linux-android24-clang" -shared -fPIC -O2 -fvisibility=hidden -DNAPI_VERSION=8 \
    -I"$node_headers" "$here/../../../native/system/packages/entry/src/flock.c" -s -o "$system/bin/system.node"
  printf '{"name":"@deepseek-ai/node-addon-system-android-%s","version":"0.1.2","license":"MIT"}\n' "$arch" > "$system/package.json"
  echo "built $system"
done
