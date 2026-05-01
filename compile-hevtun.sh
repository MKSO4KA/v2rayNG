#!/bin/bash
set -o errexit
set -o pipefail
set -o nounset

__dir="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"

if [[ -z "${NDK_HOME:-}" ]]; then
  echo "Android NDK: NDK_HOME not found. please set env \$NDK_HOME"
  exit 1
fi

# --- Windows Compatibility Fix for NDK Executable ---
NDK_EXEC="$NDK_HOME/ndk-build"
if [[ "$OSTYPE" == "msys" || "$OSTYPE" == "cygwin" ]]; then
    if [[ -f "$NDK_HOME/ndk-build.cmd" ]]; then
        NDK_EXEC="$NDK_HOME/ndk-build.cmd"
    fi
fi

# ВАЖНО: Мы больше не используем TMPDIR.
# Собираем напрямую в папке hev-socks5-tunnel, чтобы сохранить контекст .git
# и избежать проблем с копированием симлинков под Windows.
mkdir -p "$__dir/libs"
mkdir -p "$__dir/obj"

"$NDK_EXEC" \
    NDK_PROJECT_PATH="$__dir/hev-socks5-tunnel" \
    APP_BUILD_SCRIPT="$__dir/hev-socks5-tunnel/Android.mk" \
    "APP_ABI=armeabi-v7a arm64-v8a x86 x86_64" \
    APP_PLATFORM=android-24 \
    NDK_LIBS_OUT="$__dir/libs" \
    NDK_OUT="$__dir/obj" \
    "APP_CFLAGS=-O3 -DPKGNAME=com/v2ray/ang/service" \
    "APP_LDFLAGS=-Wl,--build-id=none -Wl,--hash-style=gnu"

