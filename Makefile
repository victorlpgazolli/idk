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

.PHONY: install_dependencies compile prepare_release release run run_docker

install_dependencies:
ifeq ($(OS),Darwin)
	python3 -m venv $(VENV)
	$(PIP) install --upgrade pip
	$(PIP) install pyinstaller frida
endif
	cd bridge && npm ci

compile:
	cd bridge && npx frida-compile agent.js -o agent.bundle.js -c
	cd bridge && $(PYTHON) -m PyInstaller bridge.spec
	./gradlew $(GRADLE_TARGET)

release: compile prepare_release

run_docker:
	./start_docker.sh

run:
	./dist/idk

prepare_release:
	mkdir -p dist
	cp $(TUI_BIN) dist/idk
	cp $(BRIDGE_BIN) dist/idk-bridge
	chmod +x dist/idk dist/idk-bridge
	@echo "Binaries ready in dist/"
