OS   := $(shell uname -s)
ARCH := $(shell uname -m)

ifeq ($(OS),Darwin)
  GRADLE_TARGET := linkReleaseExecutableMacosArm64
  TUI_BIN       := build/bin/macosArm64/releaseExecutable/idk.kexe
else ifeq ($(OS),Linux)
  GRADLE_TARGET := linkReleaseExecutableLinuxArm64
  TUI_BIN       := build/bin/linuxArm64/releaseExecutable/idk.kexe
else
  $(error Unsupported OS: $(OS))
endif

BRIDGE_DIR := $(abspath bridge)
BRIDGE_BIN := bridge/dist/idk-bridge
VENV       := bridge/venv
PIP        := $(VENV)/bin/pip
PYTHON     := $(abspath $(VENV)/bin/python)

.PHONY: install_dependencies compile release

install_dependencies:
ifeq ($(OS),Darwin)
	python3 -m venv $(VENV)
	$(PIP) install --upgrade pip
	$(PIP) install pyinstaller frida
endif
	cd bridge && npm ci

compile:
	cd bridge && npx frida-compile agent.js -o agent.bundle.js -c
ifeq ($(OS),Darwin)
	cd bridge && $(PYTHON) -m PyInstaller bridge.spec
else ifeq ($(OS),Linux)
	docker run --rm --platform linux/arm64 \
	    -v "$(BRIDGE_DIR)":/bridge \
	    -w /bridge \
	    python:3.11-slim \
	    bash -c "pip install pyinstaller frida && pyinstaller bridge.spec"
endif
	./gradlew $(GRADLE_TARGET)

release:
	mkdir -p dist
	cp $(TUI_BIN) dist/idk
	cp $(BRIDGE_BIN) dist/idk-bridge
	chmod +x dist/idk dist/idk-bridge
	@echo "Binaries ready in dist/"
