#!/usr/bin/env bash
# Cave views for the hollows islands (the normal preview's top.png only shows the ceiling).
#   bash tools/gen/hollows/view.sh <generator class> <view>...
#   e.g. bash tools/gen/hollows/view.sh com.skyclone.mod.hollows.gen.HollowsGenerator cut:cut100:100 iso:jungle:-175,70,-175,-73,110,-73
# PNGs land in $TMPDIR/skyclone/hollows/views/.
set -u
ROOT="$(cd "$(dirname "$0")/../../.." && pwd)"
SRC="$ROOT/plugin/src/main/java"
RES="$ROOT/plugin/src/main/resources"
CP="$(cat "$ROOT/plugin/build/classpath.txt")"
GEN="$1"; shift
OUT="${TMPDIR:-/tmp}/skyclone/hollows/viewbuild"
VIEWS="${TMPDIR:-/tmp}/skyclone/hollows/views"
rm -rf "$OUT"; mkdir -p "$OUT/classes" "$VIEWS"
GEN_FILE="$SRC/$(echo "$GEN" | tr '.' '/').java"
W() { cygpath -w "$1"; }
javac -encoding UTF-8 --release 25 -nowarn -implicit:class -sourcepath "$(W "$SRC")" -cp "$CP" -d "$(W "$OUT/classes")" \
  "$(W "$GEN_FILE")" "$(W "$ROOT/tools/gen/hollows/HollowsView.java")" 2>&1 | grep -v '^Note:'
java -Xmx${PREVIEW_XMX:-6g} -Djava.awt.headless=true -cp "$(W "$OUT/classes");$(W "$RES");$CP" HollowsView "$GEN" "$(W "$VIEWS")" "$@"
