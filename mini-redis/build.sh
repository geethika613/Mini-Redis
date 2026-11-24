#!/usr/bin/env bash
# Compiles with plain javac (no dependencies needed).
set -euo pipefail
cd "$(dirname "$0")"
rm -rf out && mkdir -p out
javac --release 21 -d out $(find src/main/java -name '*.java')
cp -R src/main/resources/. out/        # dashboard HTML
echo "Built into ./out"
