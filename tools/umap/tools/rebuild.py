"""Rescan and re-fit, under whichever interpreter on this box has numpy.

This box has two Pythons and only one of them can do the maths: `py -3` is
3.13 with a bare stdlib, while numpy 2.2.6 and scipy 1.15.3 live in the 3.10
that ComfyUI uses. Rather than pick one and be wrong half the time, this runs
under either and re-execs itself into a capable one -- and if there is none, it
says exactly which package is missing instead of dying inside a traceback.
"""
import os
import subprocess
import sys

HERE = os.path.dirname(os.path.abspath(__file__))
UMAP = os.path.dirname(HERE)
VENV = os.path.join(UMAP, '.venv', 'Scripts', 'python.exe')

# The venv comes first because it is the one with umap-learn in it, which is
# measurably the better fit (see tools/layout.py). Everything after it can
# still build the map, using the built-in implementation.
CANDIDATES = [
    [VENV],
    [sys.executable],
    ['python'],
    ['py', '-3.10'], ['py', '-3.11'], ['py', '-3.12'],
    ['python3'], ['py', '-3'],
]
PROBE = ('import numpy, scipy\n'
         'try:\n'
         '    import umap; e = "umap-learn " + umap.__version__\n'
         'except Exception:\n'
         '    e = "built-in fit"\n'
         'print(numpy.__version__, scipy.__version__, "|", e)')


def capable(cmd):
    if cmd and cmd[0].endswith('.exe') and not os.path.exists(cmd[0]):
        return False, ''
    try:
        out = subprocess.run(cmd + ['-c', PROBE], capture_output=True, text=True, timeout=90)
        return out.returncode == 0, out.stdout.strip()
    except Exception:
        return False, ''


def find():
    """Best interpreter available, preferring one that also has umap-learn."""
    seen = set()
    fallback = None
    for cmd in CANDIDATES:
        key = tuple(cmd)
        if key in seen:
            continue
        seen.add(key)
        ok, ver = capable(cmd)
        if not ok:
            continue
        if 'umap-learn' in ver:
            return cmd, ver
        if fallback is None:
            fallback = (cmd, ver)
    return fallback if fallback else (None, '')


def main():
    cmd, ver = find()
    if not cmd:
        print('No interpreter on this machine has both numpy and scipy.')
        print('Make an isolated one -- never pip into a shared environment:')
        print('    py -3 -m venv "%s"' % os.path.join(UMAP, '.venv'))
        print('    "%s" -m pip install numpy scipy umap-learn' % VENV)
        return 2
    print('using %s\n      %s\n' % (' '.join(cmd), ver))
    if 'umap-learn' not in ver:
        print('  note: this interpreter has no umap-learn, so the built-in fit will')
        print('        be used. It works, and it scores slightly worse. To get the')
        print('        better one:  "%s" -m pip install umap-learn\n' % VENV)
    for step in ('scan.py', 'build.py'):
        print('--- %s' % step)
        rc = subprocess.call(cmd + [os.path.join(HERE, step)])
        if rc != 0:
            print('%s failed (exit %d)' % (step, rc))
            return rc
    return 0


if __name__ == '__main__':
    sys.exit(main())
