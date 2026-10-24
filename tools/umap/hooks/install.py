"""Install (or remove) the UMAP hook in Ai Game/.claude/settings.json.

Deliberately surgical: it merges ONE matcher into the hooks block and leaves
every other key in the file exactly as it found it. There is an existing
settings.local.json in this folder full of hard-won permission entries, and
clobbering it would be a much worse outcome than the map not lighting up.

  py -3 hooks/install.py              install
  py -3 hooks/install.py --uninstall  remove
  py -3 hooks/install.py --status     say what is there now
"""
import json
import os
import shutil
import sys

HERE = os.path.dirname(os.path.abspath(__file__))
UMAP = os.path.dirname(HERE)
sys.path.insert(0, os.path.join(UMAP, 'tools'))
import common                       # noqa: E402  instance.json: this copy's port and roots
ROOT = common.CONTAINERS[0]
SETTINGS = os.path.join(ROOT, '.claude', 'settings.json')
MARK = 'umap_ping'
EVENTS = ['PreToolUse', 'SessionStart', 'Stop']


def command():
    """The cheapest ping this machine can make.

    This runs before EVERY tool call of every session in this folder, so the
    cost is not academic: the Python client measures 172 ms and the curl one
    76 ms, essentially all of it interpreter startup rather than network. curl
    ships with Windows 10+, so the .bat is the default and Python is the
    fallback for a box without it.
    """
    if shutil.which('curl'):
        return '"%s"' % os.path.join(HERE, 'umap_ping.bat')
    return 'py -3 "%s"' % os.path.join(HERE, 'umap_ping.py').replace('\\', '/')


def entry():
    return {'matcher': '*', 'hooks': [{'type': 'command', 'command': command(), 'timeout': 5}]}


def load():
    if not os.path.exists(SETTINGS):
        return {}
    try:
        with open(SETTINGS, encoding='utf-8-sig') as fh:
            return json.load(fh)
    except Exception as exc:
        print('  ! %s is not valid JSON (%s) - refusing to touch it' % (SETTINGS, exc))
        sys.exit(2)


def save(data):
    os.makedirs(os.path.dirname(SETTINGS), exist_ok=True)
    if os.path.exists(SETTINGS):
        shutil.copyfile(SETTINGS, SETTINGS + '.umap-backup')
    with open(SETTINGS, 'w', encoding='utf-8') as fh:
        json.dump(data, fh, indent=2)


def has_ours(group):
    for m in group:
        for h in m.get('hooks', []):
            if MARK in str(h.get('command', '')):
                return True
    return False


def install():
    if len(common.CONTAINERS) > 1:
        # A combined map has no single folder to hook. The per-folder maps
        # forward every event to it (`relay` in their instance.json).
        print('%s covers %d folders and is fed by their own maps (relay), so it'
              % (common.NAME, len(common.CONTAINERS)))
        print('needs no hook of its own. Install the hook from each folder\'s UMAP instead.')
        return
    with open(os.path.join(HERE, 'port.txt'), 'w') as fh:
        fh.write(str(common.PORT))
    data = load()
    hooks = data.setdefault('hooks', {})
    added = []
    for ev in EVENTS:
        group = hooks.setdefault(ev, [])
        if has_ours(group):
            continue
        group.append(entry())
        added.append(ev)
    save(data)
    print('installed on: %s' % (', '.join(added) if added else 'nothing (already there)'))
    print('settings: %s' % SETTINGS)
    print('\nStart a Claude Code session anywhere under:\n  %s' % ROOT)
    print('Every tool call now lights up the map at http://127.0.0.1:%d/' % common.PORT)
    print('\nRemove it later with:  py -3 "%s" --uninstall' % os.path.join(HERE, 'install.py'))


def uninstall():
    data = load()
    hooks = data.get('hooks', {})
    removed = 0
    for ev in list(hooks):
        kept = []
        for m in hooks[ev]:
            inner = [h for h in m.get('hooks', []) if MARK not in str(h.get('command', ''))]
            if inner:
                m['hooks'] = inner
                kept.append(m)
            elif not m.get('hooks'):
                kept.append(m)
            else:
                removed += 1
        hooks[ev] = kept
        if not hooks[ev]:
            del hooks[ev]
    if not hooks:
        data.pop('hooks', None)
    save(data)
    print('removed %d hook entr%s' % (removed, 'y' if removed == 1 else 'ies'))


def status():
    data = load()
    hooks = data.get('hooks', {})
    if not hooks:
        print('no hooks configured in %s' % SETTINGS)
        return
    for ev, group in hooks.items():
        for m in group:
            for h in m.get('hooks', []):
                tag = ' <- UMAP' if MARK in str(h.get('command', '')) else ''
                print('  %-14s %s%s' % (ev, h.get('command', ''), tag))


if __name__ == '__main__':
    arg = (sys.argv[1] if len(sys.argv) > 1 else '').lower()
    if arg in ('--uninstall', '-u', 'remove'):
        uninstall()
    elif arg in ('--status', '-s'):
        status()
    else:
        install()
