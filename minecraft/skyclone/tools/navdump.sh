#!/usr/bin/env bash
# Cut a box out of a generated island into a TestPilot nav fixture (no server, no client):
#
#   bash tools/navdump.sh <GeneratorClass> <name> x1 y1 z1 x2 y2 z2 [seed]
#   bash tools/navdump.sh com.skyclone.mod.mines.gen.DeepCavernsGenerator deep_lift3 -12 94 -12 12 110 12
#
# Writes tools/testpilot/src/test/resources/nav/<name>.txt.gz, which NavWorldTest loads with real
# Minecraft block states, so a place the walker got stuck in becomes a unit test of the planner.
# Heap: PREVIEW_XMX (default 6g) - the hub needs a few GB.
set -u
ROOT="$(cd "$(dirname "$0")/.." && pwd)"
SRC="$ROOT/plugin/src/main/java"
RES="$ROOT/plugin/src/main/resources"
CPFILE="$ROOT/plugin/build/classpath.txt"
GEN="${1:-}"; NAME="${2:-}"
if [ -z "$GEN" ] || [ -z "$NAME" ] || [ $# -lt 8 ]; then
  echo "usage: navdump.sh <GeneratorClass> <name> x1 y1 z1 x2 y2 z2 [seed]"; exit 2
fi
shift 2
GEN_FILE="$SRC/$(echo "$GEN" | tr '.' '/').java"
[ -f "$GEN_FILE" ] || { echo "no source file for $GEN"; exit 2; }
if [ ! -f "$CPFILE" ]; then
  (cd "$ROOT/plugin" && ./gradlew.bat writeClasspath -q --console=plain >/dev/null 2>&1)
fi
CP="$(cat "$CPFILE")"
OUT="${TMPDIR:-/tmp}/skyclone-navdump-$$"
mkdir -p "$OUT/classes"
LIST="$OUT/sources.txt"
printf '%s\n%s\n' "$GEN_FILE" "$ROOT/tools/navdump/NavDump.java" > "$LIST"
WIN=0; command -v cygpath >/dev/null 2>&1 && WIN=1
winpath() { if [ "$WIN" -eq 1 ]; then cygpath -w "$1"; else echo "$1"; fi; }
SEP=":"; [ "$WIN" -eq 1 ] && SEP=";"
if [ "$WIN" -eq 1 ]; then cygpath -m -f "$LIST" | sed 's/.*/"&"/' > "$LIST.win" && mv "$LIST.win" "$LIST"
else sed 's/.*/"&"/' "$LIST" > "$LIST.q" && mv "$LIST.q" "$LIST"; fi
javac -encoding UTF-8 --release 25 -nowarn -implicit:class -sourcepath "$(winpath "$SRC")" -cp "$CP" \
  -d "$(winpath "$OUT/classes")" @"$(winpath "$LIST")" 2>&1 | grep -v '^Note:'
if [ "${PIPESTATUS[0]}" -ne 0 ]; then echo "COMPILE FAILED"; rm -rf "$OUT"; exit 2; fi
DEST="$ROOT/tools/testpilot/src/test/resources/nav"
mkdir -p "$DEST"
java -Xmx"${PREVIEW_XMX:-6g}" -cp "$(winpath "$OUT/classes")${SEP}$(winpath "$RES")${SEP}${CP}" \
  navdump.NavDump "$GEN" "$(winpath "$DEST/$NAME.txt.gz")" "$@"
RC=$?
rm -rf "$OUT"
exit $RC
