#!/usr/bin/env bash
# Render an island generator to PNGs + a walkability report, offline (no server).
#
#   bash tools/preview.sh <fully.qualified.GeneratorClass> [seed] [crop=x1,z1,x2,z2] [--full]
#   bash tools/preview.sh com.skyclone.gen.sample.SampleIslandGenerator
#   bash tools/preview.sh com.skyclone.mod.hub.HubGenerator 42
#   bash tools/preview.sh com.skyclone.mod.hub.HubGenerator crop=-60,-60,60,60   (zoomed crop_*.png of one district)
#
# Writes previews/<islandId>/{top.png, iso.png, iso_nw.png, walk.png, report.txt} and prints
# their paths. LOOK at them with the Read tool: top.png is the map, iso*.png the 3D views, walk.png
# shows what a player can reach from spawn (green), what is cut off (orange) and every anchor
# (red X = unreachable or buried in a block).
#
# Exit code: 0 = OK, 1 = spawn missing or an anchor/portal unreachable/inside a block,
#            2 = compile error or generator crash.
#
# Compiles ONLY what the generator actually references (javac -sourcepath), so half-written code
# elsewhere in your module (or in other modules) cannot stop you previewing. Pass --full to
# compile api + core + gen + your whole module package instead (what check.sh does).
# Heap: PREVIEW_XMX (default 6g) — the hub-sized islands need a few GB.
set -u
ROOT="$(cd "$(dirname "$0")/.." && pwd)"
SRC="$ROOT/plugin/src/main/java"
RES="$ROOT/plugin/src/main/resources"
CPFILE="$ROOT/plugin/build/classpath.txt"
GEN="${1:-}"
if [ -z "$GEN" ]; then echo "usage: preview.sh <fully.qualified.GeneratorClass> [seed] [--full]"; exit 2; fi
shift
EXTRA=()
FULL=0
for a in "$@"; do
  case "$a" in
    --full) FULL=1 ;;
    *) EXTRA+=("$a") ;;
  esac
done

GEN_FILE="$SRC/$(echo "$GEN" | tr '.' '/').java"
if [ ! -f "$GEN_FILE" ]; then echo "no source file for $GEN (looked for $GEN_FILE)"; exit 2; fi

if [ ! -f "$CPFILE" ]; then
  (cd "$ROOT/plugin" && ./gradlew.bat writeClasspath -q --console=plain >/dev/null 2>&1)
fi
CP="$(cat "$CPFILE")"

SIMPLE="${GEN##*.}"
OUT="${TMPDIR:-/tmp}/skyclone-preview-$SIMPLE-$$"
mkdir -p "$OUT/classes"
LIST="$OUT/sources.txt"

WIN=0
if command -v cygpath >/dev/null 2>&1; then WIN=1; fi
winpath() { if [ "$WIN" -eq 1 ]; then cygpath -w "$1"; else echo "$1"; fi; }
SEP=":"; [ "$WIN" -eq 1 ] && SEP=";"

echo "$GEN_FILE" > "$LIST"
echo "$SRC/com/skyclone/gen/tools/Preview.java" >> "$LIST"
if [ "$FULL" -eq 1 ]; then
  find "$SRC/com/skyclone/api" "$SRC/com/skyclone/core" "$SRC/com/skyclone/gen" -name '*.java' >> "$LIST"
  MODPKG="$(echo "$GEN" | sed -n 's/^com\.skyclone\.mod\.\([a-z0-9_]*\)\..*/\1/p')"
  if [ -n "$MODPKG" ] && [ -d "$SRC/com/skyclone/mod/$MODPKG" ]; then
    find "$SRC/com/skyclone/mod/$MODPKG" -name '*.java' >> "$LIST"
  fi
fi
# Quoted forward-slash paths: the project lives under "Ai Game" (a space), and inside a quoted
# @file entry javac treats backslashes as escapes (same handling as tools/check.sh).
if [ "$WIN" -eq 1 ]; then cygpath -m -f "$LIST" | sed 's/.*/"&"/' > "$LIST.win" && mv "$LIST.win" "$LIST"
else sed 's/.*/"&"/' "$LIST" > "$LIST.q" && mv "$LIST.q" "$LIST"; fi

echo "Compiling $GEN ..."
javac -encoding UTF-8 --release 25 -nowarn -implicit:class -Xmaxerrs 60 \
  -sourcepath "$(winpath "$SRC")" -cp "$CP" -d "$(winpath "$OUT/classes")" @"$(winpath "$LIST")" 2>&1 | grep -v '^Note:'
if [ "${PIPESTATUS[0]}" -ne 0 ]; then echo "COMPILE FAILED"; rm -rf "$OUT"; exit 2; fi

PREVIEWS="$ROOT/previews"
mkdir -p "$PREVIEWS"
RUNCP="$(winpath "$OUT/classes")${SEP}$(winpath "$RES")${SEP}${CP}"
java -Xmx"${PREVIEW_XMX:-6g}" -Djava.awt.headless=true -cp "$RUNCP" com.skyclone.gen.tools.Preview "$GEN" "$(winpath "$PREVIEWS")" "${EXTRA[@]}"
RC=$?
rm -rf "$OUT"
exit $RC
