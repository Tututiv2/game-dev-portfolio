#!/usr/bin/env bash
# Compile ONE module (or core) in isolation with plain javac, into its own temp folder.
#
#   bash tools/check.sh <module-id>     e.g.  bash tools/check.sh slayer
#   bash tools/check.sh core            api + core only
#   bash tools/check.sh all             everything (what the integrator runs)
#
# Why not gradle: ~30 agents work at once and gradle holds a lock on one build directory.
# Each javac run here writes to its own folder, so any number can run side by side.
#
# A module compiles against: api + core + itself. NEVER against another module — if your
# module needs another module's class to compile, the design is wrong; go through Sky.service.
set -u
ROOT="$(cd "$(dirname "$0")/.." && pwd)"
SRC="$ROOT/plugin/src/main/java"
CPFILE="$ROOT/plugin/build/classpath.txt"
MOD="${1:-}"
if [ -z "$MOD" ]; then echo "usage: check.sh <module-id|core|all>"; exit 2; fi
if [ ! -f "$CPFILE" ]; then
  (cd "$ROOT/plugin" && ./gradlew.bat writeClasspath -q --console=plain >/dev/null 2>&1)
fi
# `tools` is not a plugin package: it is TestPilot (a Fabric CLIENT mod, compiled against the
# Minecraft client, not the Paper classpath) plus its Python drivers. Its own gradle build is safe
# to run beside the plugin agents: it has its own project dir and lock, and runs NavTest.
if [ "$MOD" = "tools" ]; then
  TP="$ROOT/tools/testpilot"
  RC=0
  # gradle's OWN exit status: this used to be `( gradle | grep -v ... ); RC=${PIPESTATUS[0]}`, which
  # is grep's status (the subshell is one command) - 0 whenever anything was printed, failing tests
  # included, and 1 when an up-to-date build printed nothing.
  GOUT="$(cd "$TP" && ./gradlew.bat build -q --console=plain 2>&1)"; RC=$?
  [ -n "$GOUT" ] && printf '%s\n' "$GOUT" | grep -v -e '^WARNING' -e '^Note:'
  [ -f "$TP/build/test-results/test/TEST-com.testpilot.NavTest.xml" ] || { echo "NavTest did not run"; RC=1; }
  [ -f "$TP/build/test-results/test/TEST-com.testpilot.NavWorldTest.xml" ] || { echo "NavWorldTest did not run"; RC=1; }
  PY=py; command -v py >/dev/null 2>&1 || PY=python
  for f in "$TP"/*.py; do $PY -3 -m py_compile "$f" 2>/dev/null || $PY -m py_compile "$f" || RC=1; done
  # the nav-fixture dumper (tools/navdump.sh) compiles against the plugin's generators: keep it compiling
  NDOUT="${TMPDIR:-/tmp}/skyclone-navdump-check-$$"; mkdir -p "$NDOUT"
  NDSRC="$ROOT/tools/navdump/NavDump.java"; NDSP="$SRC"; NDO="$NDOUT"
  if command -v cygpath >/dev/null 2>&1; then NDSRC="$(cygpath -w "$NDSRC")"; NDSP="$(cygpath -w "$SRC")"; NDO="$(cygpath -w "$NDOUT")"; fi
  javac -encoding UTF-8 --release 25 -nowarn -implicit:class -sourcepath "$NDSP" -cp "$(cat "$CPFILE")" -d "$NDO" "$NDSRC" 2>&1 | grep -v '^Note:'
  [ "${PIPESTATUS[0]}" -eq 0 ] || { echo "tools/navdump/NavDump.java does not compile"; RC=1; }
  rm -rf "$NDOUT"
  BAD=$(LC_ALL=C.UTF-8 grep -rlP '[\x00-\x08\x0B\x0C\x0E-\x1F]' "$TP/src" "$TP"/*.py "$ROOT/tools"/*.sh "$ROOT/tools/navdump" --include='*.java' --include='*.py' --include='*.sh' --include='*.json' 2>/dev/null)
  if [ -n "$BAD" ]; then echo "CONTROL CHARACTERS IN SOURCE:"; echo "$BAD"; RC=1; fi
  if [ "$RC" -eq 0 ]; then echo "CHECK OK: tools (TestPilot build + NavTest + NavWorldTest, NavDump, $(ls "$TP"/*.py | wc -l) python files)"; else echo "CHECK FAILED: tools"; fi
  exit $RC
fi
CP="$(cat "$CPFILE")"
OUT="${TMPDIR:-/tmp}/skyclone-check-$MOD-$$"
mkdir -p "$OUT"
LIST="$OUT/sources.txt"

find "$SRC/com/skyclone/api" "$SRC/com/skyclone/core" "$SRC/com/skyclone/gen" -name '*.java' 2>/dev/null > "$LIST"
[ -f "$SRC/com/skyclone/SkyPlugin.java" ] && echo "$SRC/com/skyclone/SkyPlugin.java" >> "$LIST"
case "$MOD" in
  core) ;;
  all)  find "$SRC/com/skyclone/mod" -name '*.java' 2>/dev/null >> "$LIST" ;;
  *)    if [ ! -d "$SRC/com/skyclone/mod/$MOD" ]; then echo "no package com.skyclone.mod.$MOD"; exit 2; fi
        find "$SRC/com/skyclone/mod/$MOD" -name '*.java' >> "$LIST" ;;
esac

# Windows javac wants Windows paths in the @file.
if command -v cygpath >/dev/null 2>&1; then
  # Quoted, forward-slash paths: the project lives under "Ai Game" (a space), and inside a quoted
  # @file entry javac treats backslashes as escapes.
  cygpath -m -f "$LIST" | sed 's/.*/"&"/' > "$LIST.win" && mv "$LIST.win" "$LIST"
fi

javac -encoding UTF-8 --release 25 -nowarn -Xmaxerrs 200 -cp "$CP" -d "$OUT/classes" @"$LIST" 2>&1 | grep -v '^Note:'
RC=${PIPESTATUS[0]}

# Control characters in source compile fine and are invisible in an editor; this portfolio has
# shipped that bug four times. Fail on any byte < 0x20 other than tab/newline/CR.
SCOPE="$SRC/com/skyclone"
[ "$MOD" != "all" ] && [ "$MOD" != "core" ] && SCOPE="$SRC/com/skyclone/mod/$MOD"
# LC_ALL=C.UTF-8: under Git Bash's default locale `grep -P` refuses to run, and with stderr hidden
# that refusal looked exactly like "no control characters found".
BAD=$(LC_ALL=C.UTF-8 grep -rlP '[\x00-\x08\x0B\x0C\x0E-\x1F]' "$SCOPE" --include='*.java' 2>/dev/null)
if [ -n "$BAD" ]; then echo "CONTROL CHARACTERS IN SOURCE:"; echo "$BAD"; RC=1; fi

if [ "$RC" -eq 0 ]; then echo "CHECK OK: $MOD ($(wc -l < "$LIST") files)"; else echo "CHECK FAILED: $MOD"; fi
rm -rf "$OUT"
exit $RC
