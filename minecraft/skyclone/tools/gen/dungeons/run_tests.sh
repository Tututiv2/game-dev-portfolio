#!/usr/bin/env bash
# Run the dungeons unit tests WITHOUT gradle (other agents may hold the gradle lock or have half-written modules):
# compiles api + gen + the dungeons package + its tests with javac into the scratch folder, then runs JUnit 5
# through the platform launcher found in the gradle cache.
#
#   bash tools/gen/dungeons/run_tests.sh
set -u
ROOT="$(cd "$(dirname "$0")/../../.." && pwd)"
P="$ROOT/plugin"
W="${TMPDIR:-/tmp}/skyclone/dungeons/runner"
mkdir -p "$W"
WM="$(cygpath -m "$W" 2>/dev/null || echo "$W")"
cat > "$W/RunTests.java" <<'JAVA'
import org.junit.platform.launcher.*;
import org.junit.platform.launcher.core.*;
import org.junit.platform.launcher.listeners.*;
import static org.junit.platform.engine.discovery.DiscoverySelectors.selectPackage;
public class RunTests {
  public static void main(String[] a) {
    LauncherDiscoveryRequest req = LauncherDiscoveryRequestBuilder.request().selectors(selectPackage(a[0])).build();
    Launcher l = LauncherFactory.create();
    SummaryGeneratingListener s = new SummaryGeneratingListener();
    l.execute(req, s);
    s.getSummary().printTo(new java.io.PrintWriter(System.out));
    s.getSummary().printFailuresTo(new java.io.PrintWriter(System.out), 20);
  }
}
JAVA
G="$HOME/.gradle/caches/modules-2/files-2.1"
JU=$(find "$G/org.junit.jupiter" "$G/org.junit.platform" "$G/org.opentest4j" "$G/org.apiguardian" -name "*.jar" ! -name "*sources*" \
     | grep -E "5\.11\.4|1\.11\.4|opentest4j|apiguardian" | while read -r f; do cygpath -m "$f" 2>/dev/null || echo "$f"; done | tr '\n' ';')
CP="$(cat "$P/build/classpath.txt");$JU"
rm -rf "$W/classes" && mkdir -p "$W/classes"
(cd "$P" && find src/main/java/com/skyclone/api src/main/java/com/skyclone/gen src/main/java/com/skyclone/mod/dungeons src/test/java/com/skyclone/mod/dungeons -name "*.java") > "$W/src.txt"
(cd "$P" && cygpath -m -f "$W/src.txt" 2>/dev/null || cat "$W/src.txt") | sed 's/.*/"&"/' > "$W/src2.txt"
(cd "$P" && javac -encoding UTF-8 --release 25 -nowarn -cp "$CP" -d "$WM/classes" @"$WM/src2.txt" "$WM/RunTests.java" 2>&1 | grep -v '^Note')
(cd "$P" && java -cp "$WM/classes;src/main/resources;$CP" RunTests com.skyclone.mod.dungeons)
