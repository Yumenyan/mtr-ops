#!/usr/bin/env bash
# MOD層(Minecraft/MTR/Fabric に依存する部分)の「型だけ」のコンパイル検査。
# core-test/stubs/ は、使っている外部クラスの代用品(中身は空)です:
#   mtr.*        … MTR 3.2.2-hotfix-1 のソース(3.x.x タグ)から、使っているフィールド・メソッドの定義を写したもの
#   net.minecraft.* / net.fabricmc.* ほか … Minecraft 1.19.2(Mojang名)/ Fabric API の定義に合わせて手で書いたもの
# 本物の jar でのビルドの代わりにはなりません(綴り・型・引数の数の間違いを見つけるためのもの)。
set -euo pipefail
cd "$(dirname "$0")/.."
OUT=core-test/out-modcheck
rm -rf "$OUT" && mkdir -p "$OUT"
javac -nowarn -encoding UTF-8 --release 17 -d "$OUT" -sourcepath core-test/stubs $(find mod/src/main/java -name "*.java")
echo "MOD層の型チェック: OK ($(find "$OUT/jp" -name '*.class' | wc -l) クラス)"
