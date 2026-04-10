# IDK: Interactive Debug Kit

## Project Overview
IDK (Interactive Debug Kit) is a high-performance, low-latency TUI debugger engineered specifically for Android runtime environments. Developed using Kotlin Native, IDK provides a specialized terminal-based interface for real-time process instrumentation, state inspection, and runtime manipulation. It is designed for security researchers and Android engineers who require a robust, command-line driven environment for deep system analysis without the overhead of heavy graphical IDEs.

IMAGEM_AQUI_1

## Core Capabilities

### Class Discovery
IDK facilitates real-time enumeration of loaded classes within the target JVM/ART runtime. The system includes an advanced filtering engine that allows users to isolate specific package hierarchies, identify synthetic classes, and monitor class-loading events as they occur.

### Deep Inspection
The toolkit provides a recursive traversal engine for object hierarchies. Users can inspect both static and instance fields with high precision. IDK supports live memory tracking, enabling the identification and inspection of active object instances. The inspection module also allows for the dynamic modification of field values at runtime to facilitate state-based testing and vulnerability research.

### Method Hooking
IDK implements a sophisticated method interception framework. It allows users to hook arbitrary methods to intercept execution flow, inspect input parameters, and capture return values. The hooking mechanism is designed to be highly performant, ensuring minimal overhead during high-frequency execution cycles.

## Dynamic Runtime Integration
The system is designed for zero-impact integration. It requires no modifications to the target application’s source code and does not necessitate the inclusion of external SDKs or libraries. IDK is capable of attaching to any debuggable process, including release builds that have been marked as debuggable, providing a non-invasive solution for production-grade application analysis.

## The Technological Pipeline
The IDK architecture is built upon a multi-stage pipeline that ensures reliable communication between the host and the target Android device:

1.  **JDWP Bootstrap**: Initial communication is established via the Java Debug Wire Protocol (JDWP). IDK utilizes ADB to prepare the environment and deploy the Frida gadget into the target process.
2.  **JSON-RPC Bridge**: A Python-based mediator (`bridge.py`) exposes a JSON-RPC HTTP server. This component manages the lifecycle of the Frida session and provides a standardized interface for the TUI.
3.  **Frida Injection**: A dynamic JavaScript agent (`agent.js`) is injected into the Android process. This agent performs the actual instrumentation, leveraging the Frida Java bridge to interact with the ART runtime.
4.  **Native TUI**: The user interface is a standalone Kotlin Native binary that communicates with the bridge via Ktor. It provides a deterministic, flicker-free terminal experience using ANSI escape codes and POSIX terminal control.

IMAGEM_AQUI_2

## Installation

To install IDK on **macOS (ARM64)**, **Linux (x64/ARM64)**, or **WSL**, run the following command in your terminal:

```bash
curl -sSL https://idk.victorlpgazolli.dev/install.sh -o install.sh && bash install.sh
```

The installer automatically detects your architecture, sets up the environment, and handles macOS quarantine flags.

### Manual Setup
If you prefer manual installation, ensure the following variables are in your shell configuration (`.zshrc` or `.bashrc`):

```bash
export PATH="$PATH:$HOME/.idk/bin"
export IDK_BRIDGE_PATH="$HOME/.idk/bin/idk-bridge"
```


IMAGEM_AQUI_3
