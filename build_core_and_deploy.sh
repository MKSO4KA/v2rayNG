#!/bin/bash

# --- MSYS2 EDITION ---
set -e
set -o pipefail

echo "=== V2rayNG Core Builder [MSYS2 Native Mode] ==="

# --- ПУТИ К ANDROID SDK И NDK ---
export ANDROID_HOME="/d/Android/Sdk"
export NDK_HOME="/d/Android/Sdk/ndk/28.2.13676358"
export GOTOOLCHAIN=auto
export GOPROXY=direct

# --- 1. ДОБАВЛЯЕМ GO В PATH ---
if ! command -v go &> /dev/null; then
    export PATH="$PATH:/c/Program Files/Go/bin:/c/Go/bin"
fi
export PATH="$PATH:$(go env GOPATH)/bin"

# --- 2. ДОБАВЛЯЕМ JAVA (javac) В PATH ---
# Твой путь к Java в формате MSYS2
export PATH="/d/Android Studio/jbr/bin:$PATH"

if ! command -v javac &> /dev/null; then
    echo "ОШИБКА: javac всё еще не найден по пути /d/Android Studio/jbr/bin"
    exit 1
else
    echo "Java найдена: $(command -v javac)"
fi

PROJECT_ROOT=$(pwd)
APP_LIBS_DIR="$PROJECT_ROOT/V2rayNG/app/libs"
CORE_SRC_DIR="$PROJECT_ROOT/AndroidLibXrayLite"

# --- ШАГ 1: TUNNEL ---
echo "[1/3] Building Tunnel (C++)..."
bash compile-hevtun.sh

# --- ШАГ 2: PREPARE ASSETS ---
echo "[2/3] Preparing Go Core assets..."
pushd "$CORE_SRC_DIR" > /dev/null

mkdir -p data assets
if [ ! -f "data/geoip.dat" ]; then
    bash gen_assets.sh download
fi
cp -vf data/*.dat assets/ 2>/dev/null || true

# --- ШАГ 3: BUILD CORE (Go -> AAR) ---
echo "[3/3] Compiling Go Core (Xray Lite) -> AAR..."

# Чистим мусор от старых попыток
rm -f run_gomobile_safe.go

go install github.com/sagernet/gomobile/cmd/gomobile@latest
go install github.com/sagernet/gomobile/cmd/gobind@latest

# Удаляем конфликтующие правила в go.mod
go mod edit -dropreplace=golang.org/x/mobile 2>/dev/null || true
go mod edit -dropreplace=github.com/sagernet/gomobile 2>/dev/null || true

# Чтобы gomobile не удалял bind, создаем фиктивный файл инструментов
mkdir -p buildtools
cat << 'EOF' > buildtools/tools.go
//go:build tools
package buildtools
import _ "github.com/sagernet/gomobile/bind"
EOF

go get github.com/sagernet/gomobile/bind@latest
go mod tidy

# Создаем обертку для исправления бага Windows-переменных (на уровень выше)
cat << 'EOF' > ../run_gomobile_safe.go
package main
import (
	"os"
	"os/exec"
	"strings"
)
func main() {
	cmd := exec.Command(os.Args[1], os.Args[2:]...)
	cmd.Stdout = os.Stdout
	cmd.Stderr = os.Stderr
	for _, env := range os.Environ() {
		if !strings.HasPrefix(env, "=") {
			cmd.Env = append(cmd.Env, env)
		}
	}
	if err := cmd.Run(); err != nil {
		os.Exit(1)
	}
}
EOF

# Сборка AAR
go run ../run_gomobile_safe.go gomobile init
go run ../run_gomobile_safe.go gomobile bind -v -androidapi 24 -trimpath -ldflags='-s -w -buildid=' -o "libv2ray.aar" ./

# Удаляем временные файлы
rm -f ../run_gomobile_safe.go
rm -rf buildtools

echo "    Deploying to Android Project..."
mkdir -p "$APP_LIBS_DIR"
cp -f "libv2ray.aar" "$APP_LIBS_DIR/"
cp -rf "$PROJECT_ROOT/libs/"* "$APP_LIBS_DIR/" || true

popd > /dev/null

echo ""
echo "================================================"
echo "SUCCESS! libv2ray.aar собран и скопирован."
echo "================================================"