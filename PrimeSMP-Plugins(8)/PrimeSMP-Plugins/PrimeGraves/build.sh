#!/usr/bin/env bash
set -e
cd "$(dirname "$0")"
if ! command -v mvn >/dev/null 2>&1; then
    echo "Maven isn't installed. macOS: brew install maven | Ubuntu: sudo apt install maven"
    echo "Or push this folder to GitHub and use the included Actions workflow instead."
    exit 1
fi
mvn -q clean package
echo ""
echo "Build complete! Jar is at: target/$(basename "$(pwd)").jar"
