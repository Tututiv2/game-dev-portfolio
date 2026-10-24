"""
Compile the three project assemblies with UNITY'S OWN Roslyn, at the language
version Unity actually forces, WITHOUT going through the editor.

Why this exists
---------------
`csc` defaults to C# 10 or later. Unity 6 compiles this project at
**-langversion:9.0** (read it yourself: the `-langversion` line in
`Library/Bee/artifacts/*.dag/Aftermarket.Editor.rsp`). So code that compiles
fine with a default `dotnet build` can still break the editor assembly the
moment Unity touches it -- which is exactly what happened last time an
interpolation hole was allowed to span a newline (CS8967 is a C# 11 feature).

A default-flags compile is therefore NOT verification. This script reuses
Unity's real response files, so every -define, every -r: reference and the
-langversion all come from Unity rather than from a guess.

What it changes about the rsp
-----------------------------
Two things, both necessary:

  * The **source list is regenerated** from what is on disk right now. The rsp
    is a snapshot from Unity's last compile, so a file added since then is
    absent from it and would be silently skipped -- a "clean compile" that never
    saw the new code.
  * **-out / -refout / -doc are redirected** into Tools/_verify/ so this never
    overwrites the artifacts Unity's own incremental build is tracking.

WHAT THIS CANNOT SEE -- read this before trusting a failure
-----------------------------------------------------------
The `-r:` reference list comes from the rsp, and the rsp is only rewritten when
UNITY compiles. So **editing an .asmdef's `references` has no effect here until
Unity has run once.** Adding "Unity.InputSystem" to Aftermarket.Editor.asmdef
and immediately running this reports

    error CS0234: The type or namespace name 'InputSystem' does not exist

which is a stale-rsp artefact, not a real error. Open the project (or run a
batchmode pass) and re-run this; the reference will be there.

The same applies to the PREBUILT DLLs the rsp references. Each assembly is
compiled here against Unity's last-built copy of its siblings, not against the
source in this tree. So adding a public member to Aftermarket.Editor and
immediately compiling Aftermarket.Tests.EditMode reports

    error CS0117: 'ZombieImporter' does not contain a definition for 'k_EnemyLayer'

even though both files are correct -- Tests is being compiled against yesterday's
Editor.dll. Let Unity build once and it goes green. (This happened; the same run
that produced those three errors then had Unity rebuild and pass 148 tests.)

The reverse is the dangerous direction and is worth stating plainly: because the
references are a snapshot, this can also compile code GREEN against an
assembly list that no longer matches the asmdefs. It is a fast check on
*language-level* correctness -- which is what it was written for, since C# 9 is
the thing that actually broke the editor assembly -- not a substitute for
letting Unity build.

Usage:  python Tools/verify_compile.py
Exit 0 = every assembly compiled clean at Unity's own language version.
"""
import os
import re
import subprocess
import sys
import glob

ROOT = os.path.dirname(os.path.dirname(os.path.abspath(__file__)))
UNITY_DATA = r"D:\projects\Game creation\Unity Hub\6000.3.1f1\Editor\Data"
DOTNET = os.path.join(UNITY_DATA, "NetCoreRuntime", "dotnet.exe")
CSC = os.path.join(UNITY_DATA, "DotNetSdkRoslyn", "csc.dll")
OUTDIR = os.path.join(ROOT, "Tools", "_verify")

# assembly name -> the folders its .cs files come from (asmdef scope)
ASSEMBLIES = [
    ("Aftermarket.Runtime", ["Assets/_Project/Scripts"]),
    ("Aftermarket.Editor", ["Assets/_Project/Editor"]),
    ("Aftermarket.Tests.EditMode", ["Assets/Tests/EditMode"]),
]


def find_dag():
    dags = glob.glob(os.path.join(ROOT, "Library", "Bee", "artifacts", "*.dag"))
    if not dags:
        sys.exit("No Library/Bee/artifacts/*.dag -- open the project in Unity once first.")
    # newest, in case an old dag is left lying around
    return max(dags, key=os.path.getmtime)


def sources_for(folders):
    found = []
    for folder in folders:
        for dirpath, _, files in os.walk(os.path.join(ROOT, folder)):
            for f in files:
                if f.endswith(".cs"):
                    found.append(os.path.relpath(os.path.join(dirpath, f), ROOT).replace("\\", "/"))
    return sorted(found)


def main():
    dag = find_dag()
    os.makedirs(OUTDIR, exist_ok=True)
    failures = []

    for name, folders in ASSEMBLIES:
        rsp = os.path.join(dag, name + ".rsp")
        if not os.path.exists(rsp):
            print("SKIP %s -- no rsp at %s" % (name, rsp))
            continue

        lines = open(rsp, encoding="utf-8").read().splitlines()
        kept, langversion = [], None
        for line in lines:
            stripped = line.strip()
            # Drop the stale source list; it is regenerated from disk below.
            if stripped.startswith('"') and stripped.lower().endswith('.cs"'):
                continue
            # Redirect every output so Unity's incremental artifacts are untouched.
            m = re.match(r'^(-out|-refout|-doc):"(.*)"$', stripped)
            if m:
                kept.append('%s:"%s"' % (m.group(1),
                                         os.path.join(OUTDIR, os.path.basename(m.group(2)))
                                         .replace("\\", "/")))
                continue
            if stripped.startswith("-langversion:"):
                langversion = stripped.split(":", 1)[1]
            kept.append(line)

        srcs = sources_for(folders)
        kept += ['"%s"' % s for s in srcs]

        tmp = os.path.join(OUTDIR, name + ".verify.rsp")
        with open(tmp, "w", encoding="utf-8") as fh:
            fh.write("\n".join(kept))

        print("=" * 70)
        print("%s -- %d source files, langversion=%s" % (name, len(srcs), langversion))
        proc = subprocess.run([DOTNET, CSC, "@" + tmp], cwd=ROOT,
                              capture_output=True, text=True)
        out = (proc.stdout or "") + (proc.stderr or "")
        errors = [l for l in out.splitlines() if ": error " in l]
        warns = [l for l in out.splitlines() if ": warning " in l]
        if errors:
            failures.append(name)
            print("FAILED -- %d error(s):" % len(errors))
            for e in errors[:40]:
                print("   " + e)
        else:
            print("OK (%d warning(s))" % len(warns))
            for w in warns[:10]:
                print("   " + w)

    print("=" * 70)
    if failures:
        print("COMPILE FAILED: " + ", ".join(failures))
        return 1
    print("All assemblies compiled clean at Unity's own language version.")
    return 0


if __name__ == "__main__":
    sys.exit(main())
