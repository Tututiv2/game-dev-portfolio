#!/usr/bin/env bash
# Cutaway previews of the mines module's underground islands (see MinesCutaway.java).
#   bash tools/gen/mines/cutaway.sh com.skyclone.mod.mines.gen.DeepCavernsGenerator 140:170:l1 110:139:l2 ...
set -u
ROOT="$(cd "$(dirname "$0")/../../.." && pwd)"
SRC="$ROOT/plugin/src/main/java"
RES="$ROOT/plugin/src/main/resources"
CP="$(cat "$ROOT/plugin/build/classpath.txt")"
GEN="$1"; shift
OUT="${TMPDIR:-/tmp}/skyclone/mines/cut-$$"
mkdir -p "$OUT/classes"
W() { cygpath -w "$1"; }
GEN_FILE="$SRC/$(echo "$GEN" | tr '.' '/').java"
javac -encoding UTF-8 --release 25 -nowarn -implicit:class -Xmaxerrs 40 -sourcepath "$(W "$SRC")" -cp "$CP" \
  -d "$(W "$OUT/classes")" "$(W "$GEN_FILE")" "$(W "$ROOT/tools/gen/mines/MinesCutaway.java")" \
  "$(W "$SRC/com/skyclone/gen/PreviewRenderer.java")" "$(W "$SRC/com/skyclone/gen/IslandReport.java")" 2>&1 | grep -v '^Note:'
if [ "${PIPESTATUS[0]}" -ne 0 ]; then echo "COMPILE FAILED"; exit 2; fi
java -Xmx6g -Djava.awt.headless=true -cp "$(W "$OUT/classes");$(W "$RES");$CP" MinesCutaway "$GEN" "$(W "$ROOT/previews")" "$@"
RC=$?
rm -rf "$OUT"
exit $RC
