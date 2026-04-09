#!/bin/bash
set -e

echo "Creating virtual environment for build..."
python3 -m venv venv
source venv/bin/activate

echo "Installing build dependencies..."
# Bundle the Frida agent
npm install
npx frida-compile agent.js -c -B iife -o agent.bundle.js

pip install --upgrade pip
pip install pyinstaller frida

echo "Running PyInstaller..."
pyinstaller bridge.spec

echo "Build complete. Binary available at dist/idk-bridge"
deactivate
