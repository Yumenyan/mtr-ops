#!/usr/bin/env bash
# Minecraft なしで Webアプリを確認する開発用サーバー(シミュレーションデータ)
#   使い方: core-test/dev-server.sh [ポート]    →  http://127.0.0.1:8765/
set -euo pipefail
cd "$(dirname "$0")/.."
OUT=core-test/out-dev
rm -rf "$OUT" && mkdir -p "$OUT"
javac --release 17 -encoding UTF-8 -d "$OUT" mod/src/main/java/jp/mtrops/core/*.java core-test/DevServer.java
exec java -Dstdout.encoding=UTF-8 -Dfile.encoding=UTF-8 -cp "$OUT" DevServer "${1:-8765}" web
