# === V2rayNG Core Builder Makefile ===
# Optimized for MSYS2 UCRT64 Environment with Precise Path Detection

# --- PROPERTY LOADER ---
# Load dynamic environment configurations safely from V2rayNG/local.properties
LOCAL_PROPS := $(CURDIR)/V2rayNG/local.properties
get_prop = $(shell grep -E "^$(1)=" $(LOCAL_PROPS) 2>/dev/null | cut -d'=' -f2- | tr -d '\r')

# --- ENVIRONMENT CONFIGURATION ---
RAW_ANDROID_HOME := $(or $(call get_prop,msys.android.home),/d/Android/Sdk)
RAW_NDK_HOME     := $(or $(call get_prop,msys.ndk.home),$(RAW_ANDROID_HOME)/ndk/28.2.13676358)
RAW_JAVA_BIN     := $(or $(call get_prop,msys.java.bin),/d/Android Studio/jbr/bin)

# Fallback GOPATH if 'go env' fails; cut handles multiple paths separated by ';'
RAW_GOPATH := $(shell go env GOPATH 2>/dev/null | cut -d';' -f1)
ifeq ($(RAW_GOPATH),)
    RAW_GOPATH := $(HOME)/go
endif

# Convert to MSYS format for internal Makefile use
export ANDROID_HOME  := $(shell cygpath -u "$(RAW_ANDROID_HOME)")
export NDK_HOME      := $(shell cygpath -u "$(RAW_NDK_HOME)")
export JAVA_HOME_BIN := $(shell cygpath -u "$(RAW_JAVA_BIN)")
export JAVA_HOME     := $(shell dirname "$(JAVA_HOME_BIN)")
export GOPATH_MSYS   := $(shell cygpath -u "$(RAW_GOPATH)")

# --- RF CONNECTIVITY & AUTO TOOLCHAIN ---
export GOTOOLCHAIN := auto
export GOPROXY     := https://goproxy.cn,https://goproxy.io,direct
export GOSUMDB     := sum.golang.google.cn

# --- PATH INJECTION ---
# Ensure GOPATH/bin is in the MSYS PATH so native commands find installed binaries
export PATH := $(GOPATH_MSYS)/bin:/c/Program Files/Go/bin:/c/Go/bin:$(JAVA_HOME_BIN):$(PATH)

# --- NDK SETUP ---
ifneq (,$(wildcard $(NDK_HOME)/ndk-build.cmd))
    NDK_EXEC := $(NDK_HOME)/ndk-build.cmd
else
    NDK_EXEC := $(NDK_HOME)/ndk-build
endif

# --- PATHS ---
PROJECT_ROOT := $(CURDIR)
APP_LIBS_DIR := $(PROJECT_ROOT)/V2rayNG/app/libs
CORE_SRC_DIR := $(PROJECT_ROOT)/AndroidLibXrayLite
TUNNEL_DIR   := $(PROJECT_ROOT)/hev-socks5-tunnel

# --- TUNNEL BUILD COMMAND ---
# Abstracted to allow try/catch symlink hotfix fallback
TUNNEL_BUILD_CMD := $(NDK_EXEC) \
	NDK_PROJECT_PATH=$(TUNNEL_DIR) \
	APP_BUILD_SCRIPT=$(TUNNEL_DIR)/Android.mk \
	APP_ABI="armeabi-v7a arm64-v8a x86 x86_64" \
	APP_PLATFORM=android-24 \
	NDK_LIBS_OUT=$(PROJECT_ROOT)/libs \
	NDK_OUT=$(PROJECT_ROOT)/obj \
	APP_CFLAGS="-O3 -DPKGNAME=com/v2ray/ang/service" \
	APP_LDFLAGS="-Wl,--build-id=none -Wl,--hash-style=gnu"

# --- DYNAMIC CODE GENERATION ---
define GO_SAFE_WRAPPER
package main
import (
	"fmt"
	"os"
	"os/exec"
	"strings"
)
func main() {
	exePath := os.Args[1]
	if _, err := os.Stat(exePath); os.IsNotExist(err) {
		fmt.Fprintf(os.Stderr, "[GO-WRAPPER] FATAL ERROR: Binary not found at %s\\n", exePath)
		os.Exit(1)
	}
	cmd := exec.Command(exePath, os.Args[2:]...)
	cmd.Stdout = os.Stdout
	cmd.Stderr = os.Stderr

	// Extract WIN_GOBIN to inject into child PATH
	winGobin := os.Getenv("WIN_GOBIN")
	var newEnv []string
	for _, env := range os.Environ() {
		// Filter out invalid Windows internal variables
		if !strings.HasPrefix(env, "=") {
			// Forcefully prepend the correct Go binary path to PATH
			if strings.HasPrefix(strings.ToUpper(env), "PATH=") && winGobin != "" {
				env = "PATH=" + winGobin + string(os.PathListSeparator) + env[5:]
			}
			newEnv = append(newEnv, env)
		}
	}
	cmd.Env = newEnv

	if err := cmd.Run(); err != nil {
		fmt.Fprintf(os.Stderr, "[GO-WRAPPER] EXECUTION ERROR: %v\\n", err)
		os.Exit(1)
	}
}
endef
export GO_SAFE_WRAPPER

define GO_TOOLS_FILE
//go:build tools
package buildtools
import _ "github.com/sagernet/gomobile/bind"
endef
export GO_TOOLS_FILE

.PHONY: all help check tunnel assets core deploy clean

all: check tunnel core deploy

help:
	@echo "=== V2rayNG MSYS2 Build System ==="
	@echo "Targets:"
	@echo "  make all       - Build Tunnel, Go Core, and Deploy to Android project "
	@echo "  make check     - Verify environment paths and toolchain configuration"
	@echo "  make tunnel    - Compile hev-socks5-tunnel (C++) using NDK"
	@echo "  make assets    - Download and prepare GeoIP/GeoSite assets"
	@echo "  make core      - Compile Go Core (Xray Lite) into AAR"
	@echo "  make deploy    - Copy build artifacts to app/libs"
	@echo "  make clean     - Remove build artifacts"
	@echo "  make debug     - Interactive pipeline + build Android Debug APK [FLAVOR=fdroid|playstore]"
	@echo "  make release   - Interactive pipeline + build Android Release APK [FLAVOR=fdroid|playstore]"
	@echo "  make build_all - Fully automated pipeline (alias for 'make all' + 'make release')"
	@echo "  make diff      - Generate .diff file with your changes [UPSTREAM=upstream] [BRANCH=master]"

check:
	@echo "=== Environment Check ==="
	@echo "LOCAL_PROPS:    $(LOCAL_PROPS)"
	@if [ -f "$(LOCAL_PROPS)" ]; then echo "  [OK] Found local.properties"; else echo "  [!!] Missing local.properties"; fi
	@echo "ANDROID_HOME:   $(ANDROID_HOME)"
	@if [ -d "$(ANDROID_HOME)" ]; then echo "  [OK] Directory exists"; else echo "  [!!] Directory NOT found"; fi
	@echo "NDK_HOME:       $(NDK_HOME)"
	@if [ -d "$(NDK_HOME)" ]; then echo "  [OK] Directory exists"; else echo "  [!!] Directory NOT found"; fi
	@echo "JAVA_HOME_BIN:  $(JAVA_HOME_BIN)"
	@if [ -d "$(JAVA_HOME_BIN)" ]; then echo "  [OK] Directory exists"; else echo "  [!!] Directory NOT found"; fi
	@echo "--- Toolchain Check ---"
	@command -v go >/dev/null 2>&1 && echo "Go:             [OK] `go version`" || echo "Go:             [!!] NOT FOUND in PATH"
	@command -v javac >/dev/null 2>&1 && echo "Java Compiler:  [OK] `javac -version 2>&1`" || echo "Java Compiler:  [!!] NOT FOUND in PATH"
	@command -v jq >/dev/null 2>&1 && echo "jq:             [OK] `jq --version`" || echo "jq:             [!!] NOT FOUND in PATH"
	@command -v curl >/dev/null 2>&1 && echo "curl:           [OK] `curl --version | head -n 1`" || echo "curl:           [!!] NOT FOUND in PATH"
	@echo "--- Paths ---"
	@echo "Go GOPATH:      `go env GOPATH`"
	@echo "Go GOBIN:       `go env GOBIN`"

assets:
	@echo "[1/4] Preparing Go Core assets..."
	@echo "$$GO_SAFE_WRAPPER" > "$(PROJECT_ROOT)/run_gomobile_safe.go"
	@mkdir -p "$(CORE_SRC_DIR)/data" "$(CORE_SRC_DIR)/assets"
	@if [ -d "$(CORE_SRC_DIR)" ]; then \
		(cd "$(CORE_SRC_DIR)" && if [ ! -f "data/geoip.dat" ]; then bash gen_assets.sh download; fi); \
	fi
	@cp -vf "$(CORE_SRC_DIR)/data/"*.dat "$(CORE_SRC_DIR)/assets/" 2>/dev/null || true

tunnel:
	@echo "[2/4] Building Tunnel (C++)..."
	@mkdir -p $(PROJECT_ROOT)/libs $(PROJECT_ROOT)/obj
	@if $(TUNNEL_BUILD_CMD); then \
		echo "  -> [OK] Tunnel built successfully on first try."; \
	else \
		echo "  -> [WARN] Build failed! Attempting Windows symlink hotfix..."; \
		find "$(TUNNEL_DIR)" -type f -size -256c -not -name "*.symbak" -not -path "*/.git/*" | while read -r file; do \
			content=$$(cat "$$file" 2>/dev/null); \
			if [ -n "$$content" ]; then \
				target_path="$$(dirname "$$file")/$$content"; \
				if [ -f "$$target_path" ] && [ "$$file" != "$$target_path" ]; then \
					cp "$$file" "$$file.symbak"; \
					cp -f "$$target_path" "$$file"; \
				fi; \
			fi; \
			done; \
		echo "  -> Retrying build with hotfix applied..."; \
		$(TUNNEL_BUILD_CMD); \
		BUILD_EXIT=$$?; \
		echo "  -> Restoring original symlink files..."; \
		find "$(TUNNEL_DIR)" -type f -name "*.symbak" | while read -r bak_file; do \
			orig_file="$${bak_file%.symbak}"; \
			mv -f "$$bak_file" "$$orig_file"; \
		done; \
		if [ $$BUILD_EXIT -ne 0 ]; then \
			echo "  -> [FATAL] Tunnel build failed even with hotfix."; \
			exit $$BUILD_EXIT; \
		else \
			echo "  -> [OK] Tunnel built successfully with hotfix."; \
		fi; \
	fi

core: assets
	@echo "[3/4] Compiling Go Core (Xray Lite) -> AAR..."
	@( \
		cd $(CORE_SRC_DIR); \
		go install github.com/sagernet/gomobile/cmd/gomobile@latest; \
		go install github.com/sagernet/gomobile/cmd/gobind@latest; \
		ACTUAL_GOBIN=$$(go env GOBIN); \
		if [ -z "$$ACTUAL_GOBIN" ]; then ACTUAL_GOBIN=$$(go env GOPATH | cut -d';' -f1)/bin; fi; \
		GOMOBILE_EXE_WIN=$$(cygpath -w "$$ACTUAL_GOBIN/gomobile.exe"); \
		export WIN_GOBIN=$$(cygpath -w "$$ACTUAL_GOBIN"); \
		go mod edit -dropreplace=golang.org/x/mobile 2>/dev/null || true; \
		go mod edit -dropreplace=github.com/sagernet/gomobile 2>/dev/null || true; \
		mkdir -p buildtools; \
		echo "$$GO_TOOLS_FILE" > buildtools/tools.go; \
		go get github.com/sagernet/gomobile/bind@latest; \
		go mod tidy; \
		go run ../run_gomobile_safe.go "$$GOMOBILE_EXE_WIN" init; \
		go run ../run_gomobile_safe.go "$$GOMOBILE_EXE_WIN" bind -v -androidapi 24 -trimpath -ldflags='-s -w -buildid=' -o "libv2ray.aar" ./; \
		rm -rf buildtools; \
	)



deploy:
	@echo "[4/4] Deploying artifacts to V2rayNG project..."
	@if [ -z "$(APP_LIBS_DIR)" ]; then echo "ERROR: APP_LIBS_DIR is empty!"; exit 1; fi
	@mkdir -p "$(APP_LIBS_DIR)"
	@if [ -f "$(CORE_SRC_DIR)/libv2ray.aar" ]; then \
		cp -v "$(CORE_SRC_DIR)/libv2ray.aar" "$(APP_LIBS_DIR)/"; \
	fi
	@if [ -d "$(PROJECT_ROOT)/libs" ]; then \
		cp -rvf "$(PROJECT_ROOT)/libs/." "$(APP_LIBS_DIR)/" 2>/dev/null || true; \
	fi
	@echo "DONE! Artifacts deployed to $(APP_LIBS_DIR)"

clean:
	@echo "Cleaning build artifacts..."
	@if [ -n "$(PROJECT_ROOT)" ] && [ "$(PROJECT_ROOT)" != "/" ]; then \
		rm -rf "$(PROJECT_ROOT)/libs" "$(PROJECT_ROOT)/obj" "$(PROJECT_ROOT)/run_gomobile_safe.go"; \
	fi
	@if [ -n "$(CORE_SRC_DIR)" ] && [ "$(CORE_SRC_DIR)" != "/" ]; then \
		rm -f "$(CORE_SRC_DIR)/libv2ray.aar"; \
	fi
	@if [ -n "$(APP_LIBS_DIR)" ] && [ "$(APP_LIBS_DIR)" != "/" ]; then \
		rm -rf "$(APP_LIBS_DIR)"/*; \
	fi
	@echo "Clean completed."

# --- ANDROID APP BUILD TASKS ---
# Usage: make debug FLAVOR=playstore
#        make release FLAVOR=fdroid

FLAVOR ?= playstore
ifeq ($(FLAVOR),playstore)
    GRADLE_FLAVOR := Playstore
else
    GRADLE_FLAVOR := Fdroid
endif

.PHONY: interactive_prep debug release build_all

interactive_prep: check
	@printf "\\nRebuild Tunnel? [y/N]: "; read ans_t; \
	if [ "$$ans_t" = "y" ] || [ "$$ans_t" = "Y" ]; then $(MAKE) tunnel; fi
	@printf "Rebuild Core? [y/N]: "; read ans_c; \
	if [ "$$ans_c" = "y" ] || [ "$$ans_c" = "Y" ]; then $(MAKE) core; fi
	@$(MAKE) deploy

debug: interactive_prep
	@echo "[5/5] Building Android App (Debug, Flavor: $(GRADLE_FLAVOR))..."
	# Добавляем clean перед сборкой
	@cd "$(PROJECT_ROOT)/V2rayNG" && chmod +x gradlew && ./gradlew clean assemble$(GRADLE_FLAVOR)Debug
	@echo "=== DEBUG BUILD COMPLETE ==="
	@APK_DIR="$(PROJECT_ROOT)/V2rayNG/app/build/outputs/apk/$(FLAVOR)/debug"; \
	echo "APK can be found in $$APK_DIR"; \
	explorer.exe $$(cygpath -w "$$APK_DIR") 2>/dev/null || true

release: interactive_prep
	@echo "[5/5] Building Android App (Release, Flavor: $(GRADLE_FLAVOR))..."
	# Добавляем clean перед сборкой
	@cd "$(PROJECT_ROOT)/V2rayNG" && chmod +x gradlew && ./gradlew clean assemble$(GRADLE_FLAVOR)Release
	@echo "=== RELEASE BUILD COMPLETE ==="
	@APK_DIR="$(PROJECT_ROOT)/V2rayNG/app/build/outputs/apk/$(FLAVOR)/release"; \
	echo "APK can be found in $$APK_DIR"; \
	explorer.exe $$(cygpath -w "$$APK_DIR") 2>/dev/null || true

build_all: all
	@echo "[5/5] Building Android App (Release, Flavor: $(GRADLE_FLAVOR))..."
	@cd "$(PROJECT_ROOT)/V2rayNG" && chmod +x gradlew && ./gradlew assemble$(GRADLE_FLAVOR)Release
	@echo "=== FULL BUILD COMPLETE ==="
	@APK_DIR="$(PROJECT_ROOT)/V2rayNG/app/build/outputs/apk/$(FLAVOR)/release"; \
	echo "APK can be found in $$APK_DIR"; \
	explorer.exe $$(cygpath -w "$$APK_DIR") 2>/dev/null || true

# --- FORK DIFF GENERATOR ---
# Usage: make diff UPSTREAM=upstream BRANCH=master
# EXT_FILTER specifies which file types to include, ignoring binary/garbage files.

UPSTREAM   ?= upstream
BRANCH     ?= master
EXT_FILTER ?= "*.kt" "*.kts" "*.java" "*.go" "*.c" "*.h" "*.mk" "*.sh" "*.xml" "*.gradle" "*.properties" "Makefile" "*.md" "*.yml" "*.yaml" "*.gitignore" "*.gitmodules"

.PHONY: diff
diff:
	@echo "Updating $(UPSTREAM) remote..."
	@git fetch $(UPSTREAM) $(BRANCH)
	
	@echo "Staging new files in parent and submodules..."
	@git add -N .
	@git submodule foreach --recursive 'git add -N .'
	
	@echo "Calculating merge-base..."
	@BASE=$$(git merge-base $(UPSTREAM)/$(BRANCH) HEAD); \
	echo "Base commit: $$BASE"; \
	echo "Generating diff (Parent)..." >&2; \
	git diff $$BASE HEAD -- $(EXT_FILTER) > my_v2rayng_changes.diff; \
	\
	echo "Generating diff (Submodules)..." >&2; \
	git submodule foreach --recursive " \
		# Получаем хеш коммита подмодуля, который был в базовой версии проекта \
		SM_BASE=\$$(git -C $(CURDIR) rev-parse \$$BASE:\$$displaypath 2>/dev/null); \
		if [ -z \"\$$SM_BASE\" ]; then \
			# Если подмодуля не было, сравниваем с пустым деревом \
			SM_BASE=\$$(git hash-object -t tree /dev/null); \
			echo \"Submodule \$$displaypath is new.\"; \
		fi; \
		echo \"\n--- Submodule: \$$displaypath ---\" >> $(CURDIR)/my_v2rayng_changes.diff; \
		git diff \$$SM_BASE HEAD -- $(EXT_FILTER) >> $(CURDIR)/my_v2rayng_changes.diff \
	"
	@echo "DONE! File created: my_v2rayng_changes.diff"
	
# --- ADB INSTALLATION TASK ---
.PHONY: install

install:
	@echo "=== Поиск последнего APK для установки (Flavor: $(FLAVOR)) ==="
	@LATEST_RELEASE=$$(ls -t $(PROJECT_ROOT)/V2rayNG/app/build/outputs/apk/$(FLAVOR)/release/*.apk 2>/dev/null | head -n 1); \
	LATEST_DEBUG=$$(ls -t $(PROJECT_ROOT)/V2rayNG/app/build/outputs/apk/$(FLAVOR)/debug/*.apk 2>/dev/null | head -n 1); \
	SELECTED_APK=""; \
	if [ -n "$$LATEST_RELEASE" ] && [ -n "$$LATEST_DEBUG" ]; then \
		if [ "$$LATEST_RELEASE" -nt "$$LATEST_DEBUG" ]; then SELECTED_APK="$$LATEST_RELEASE"; else SELECTED_APK="$$LATEST_DEBUG"; fi; \
	elif [ -n "$$LATEST_RELEASE" ]; then SELECTED_APK="$$LATEST_RELEASE"; \
	elif [ -n "$$LATEST_DEBUG" ]; then SELECTED_APK="$$LATEST_DEBUG"; \
	fi; \
	if [ -z "$$SELECTED_APK" ]; then \
		echo "Ошибка: APK не найден."; \
		exit 1; \
	fi; \
	ADB_BIN="$(ANDROID_HOME)/platform-tools/adb.exe"; \
	WIN_APK_PATH=$$(cygpath -w "$$SELECTED_APK"); \
	APK_NAME=$$(basename "$$SELECTED_APK"); \
	echo "Найден файл: $$SELECTED_APK"; \
	echo "Попытка прямой установки через ADB..."; \
	if "$$ADB_BIN" install -r "$$WIN_APK_PATH"; then \
		echo "=== Установка завершена успешно ==="; \
	else \
		echo "------------------------------------------------------------"; \
		echo "ОШИБКА: Прямая установка заблокирована телефоном."; \
		echo "СОВЕТ: Включите 'Установка через USB' в настройках разработчика."; \
		echo "------------------------------------------------------------"; \
		echo "Копирую APK в память телефона (Download/)..."; \
		if "$$ADB_BIN" push "$$WIN_APK_PATH" "//sdcard/Download/$$APK_NAME"; then \
			echo "Файл успешно скопирован в: /sdcard/Download/$$APK_NAME"; \
			echo "------------------------------------------------------------"; \
			echo "Попытка открыть папку загрузок на телефоне..."; \
			"$$ADB_BIN" shell am start -a android.intent.action.VIEW \
				-d "content://com.android.externalstorage.documents/root/primary:Download" \
				-t "vnd.android.document/directory" >/dev/null 2>&1 || \
			"$$ADB_BIN" shell am start -n com.android.documentsui/.files.FilesActivity >/dev/null 2>&1 || \
			echo "Не удалось открыть папку автоматически. Откройте её вручную."; \
			echo "------------------------------------------------------------"; \
		fi; \
	fi