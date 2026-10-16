#!/usr/bin/env bash
# Minecraft/MTR なしで core パッケージだけをコンパイル・テストする
set -euo pipefail
cd "$(dirname "$0")/.."
OUT=core-test/out
rm -rf "$OUT" && mkdir -p "$OUT"
javac --release 17 -encoding UTF-8 -Xlint:all -d "$OUT" mod/src/main/java/jp/mtrops/core/*.java core-test/CoreSelfTest.java
java -Dstdout.encoding=UTF-8 -Dfile.encoding=UTF-8 -cp "$OUT" CoreSelfTest
