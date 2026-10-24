"""Shared constants and text handling for the UMAP scan/build pipeline.

Deliberately stdlib-only so it runs under any interpreter on this box.
"""
import json
import os
import re

HERE = os.path.abspath(os.path.join(os.path.dirname(__file__), '..'))


def _instance():
    """`instance.json` beside server.py says which brain this copy is.

    The same code runs as several maps (the public one, the private one, and a
    combined one over both), so what differs between them is data, not code:

      name    shown in the page title and /api/health
      port    the server's port; the hook reads it from hooks/port.txt
      root    the folder every path is relative to, relative to this one ("..")
      roots   the folders whose CHILDREN are the projects, relative to root (["."])
      relay   ports of other brains that also want every agent event

    Missing file or missing key means the original single-folder behaviour.
    """
    try:
        with open(os.path.join(HERE, 'instance.json'), encoding='utf-8-sig') as fh:
            return json.load(fh)
    except (OSError, ValueError):
        return {}


INSTANCE = _instance()
NAME = INSTANCE.get('name') or 'UMAP'
PORT = int(os.environ.get('UMAP_PORT') or INSTANCE.get('port') or 5580)
RELAY = [int(p) for p in INSTANCE.get('relay') or [] if int(p) != PORT]
ROOT = os.path.abspath(os.path.join(HERE, INSTANCE.get('root') or '..'))
# Each container's child folders are projects. One container (the default) is
# the original layout; several is how one map covers more than one folder.
CONTAINERS = [os.path.abspath(os.path.join(ROOT, r)) for r in INSTANCE.get('roots') or ['.']]

# Directories that are never first-party work: dependencies, build output,
# engine installs, caches, generated worlds, and the pre-review backup mirror.
PRUNE_DIRS = {
    'node_modules', '.git', '.svn', '.hg', '.venv', 'venv', 'env', '__pycache__',
    '.gradle', '.idea', '.vscode', '.vs', '.mypy_cache', '.pytest_cache', '.cache',
    'build', 'dist', 'out', 'bin', 'obj', 'target', 'release', 'win-unpacked',
    'site-packages', 'Scripts', 'Include', 'pkgs', 'wheels',
    'Library', 'Temp', 'Logs', 'UserSettings', 'PackageCache', 'ScriptAssemblies',
    'Unity Hub', 'GeneratedAssets', 'MonoBleedingEdge',
    'jre17', 'jre8', 'jdk', 'runtime', 'jvm',
    # downloaded game installs and third-party model packs, not authored here
    'instances', 'runtimes', 'yes_steve_model', 'assets_index',
    '_review_backups', '_chrome_tabs',
    # credentials the owner keeps beside a project; never embed or index these
    '_private',
    'world', 'world_nether', 'world_the_end', 'playerdata', 'region', 'stats',
    'crash-reports', 'logs', 'versions', 'libraries',
    'coverage', '__MACOSX', 'ComfyUI', 'models', 'checkpoints',
}
PRUNE_NAME_PREFIX = ('.',)

# Vendored third-party single files that live outside node_modules.
VENDOR_HINTS = (
    '/vendor/', '/vendored/', '/third_party/', '/thirdparty/',
    '.min.js', '.min.css', '-min.js', '/jquery', '/bootstrap',
)

TEXT_EXT = {
    '.py': 'python', '.pyw': 'python',
    '.js': 'js', '.mjs': 'js', '.cjs': 'js', '.jsx': 'js',
    '.ts': 'ts', '.tsx': 'ts',
    '.java': 'java', '.kt': 'java',
    '.cs': 'csharp',
    '.lua': 'lua', '.luau': 'lua',
    '.sk': 'skript',
    '.html': 'web', '.htm': 'web', '.css': 'web', '.svelte': 'web', '.vue': 'web',
    '.glsl': 'glsl', '.vert': 'glsl', '.frag': 'glsl', '.vsh': 'glsl', '.fsh': 'glsl',
    '.hlsl': 'glsl', '.shader': 'glsl', '.compute': 'glsl',
    '.md': 'doc', '.txt': 'doc', '.rst': 'doc',
    '.json': 'config', '.yml': 'config', '.yaml': 'config', '.toml': 'config',
    '.ini': 'config', '.cfg': 'config', '.properties': 'config', '.xml': 'config',
    '.bat': 'script', '.cmd': 'script', '.ps1': 'script', '.sh': 'script',
    '.gradle': 'config', '.csproj': 'config', '.sln': 'config', '.slnx': 'config',
    '.c': 'native', '.h': 'native', '.cpp': 'native', '.hpp': 'native', '.rs': 'native',
    '.sql': 'config',
}

ASSET_EXT = {
    '.png': 'image', '.jpg': 'image', '.jpeg': 'image', '.webp': 'image',
    '.gif': 'image', '.bmp': 'image', '.tga': 'image', '.psd': 'image', '.svg': 'image',
    '.wav': 'audio', '.mp3': 'audio', '.ogg': 'audio', '.flac': 'audio', '.m4a': 'audio',
    '.mp4': 'video', '.mkv': 'video', '.webm': 'video', '.mov': 'video', '.avi': 'video',
    '.glb': 'model', '.gltf': 'model', '.obj': 'model', '.fbx': 'model', '.blend': 'model',
    '.ttf': 'font', '.otf': 'font', '.woff': 'font', '.woff2': 'font',
    '.jar': 'binary', '.dll': 'binary', '.exe': 'binary', '.so': 'binary',
    '.zip': 'binary', '.7z': 'binary', '.safetensors': 'binary', '.ckpt': 'binary',
    '.pt': 'binary', '.pth': 'binary', '.onnx': 'binary',
    '.schem': 'binary', '.nbt': 'binary', '.dat': 'binary', '.mca': 'binary',
}

# Unity/engine bookkeeping noise: real files, zero semantic content.
IGNORE_EXT = {'.meta', '.mat', '.asset', '.prefab', '.unity', '.pdb', '.mdb',
              '.lock', '.log', '.tmp', '.bak', '.orig', '.map'}

MAX_READ = 240_000          # bytes read per text file for tokenising
MAX_TEXT_FILE = 2_000_000   # bigger than this and it is data, not source

# A big .txt/.json is a data blob (a name bank, a word list, an animation dump),
# not a document. It gets counted as an asset instead of embedded as prose.
DATA_TEXT_EXT = {'.txt', '.json', '.xml', '.csv', '.properties'}
DATA_TEXT_BYTES = 300_000

_CAMEL = re.compile(r'[A-Z]+(?![a-z])|[A-Z][a-z]+|[a-z]+')
_SPLIT = re.compile(r'[^A-Za-z0-9]+')

_STOP_WORDS = """
the a an and or but if then else for while do return true false null none void new this self
public private protected static final const let var function def class import from export
default delete typeof instanceof in of is not as with try catch finally throw throws
int str float bool string number object array list dict set map get put add remove size len
value key name type data item index count max min sum abs round floor ceil print log console
error warn info debug test assert expect describe should when given
package using namespace include require exports end begin done
you your it its we our they their he she his her them
that these those there here what which who whom whose how why where
be been being am are was were has have had having will would can could shall should may might must
one two three four five six seven eight nine ten first second next last other same
also just only very more most much many few some any all yes non
because too into out up down over under after before again once
com org net www http https body div span href src style script
utf encoding version copyright license author todo fixme note
"""
STOP = set(_STOP_WORDS.split())


def tokenize(text, limit=4000):
    """Identifier-aware tokenizer: splits camelCase and snake_case, lowercases,
    drops language noise. Returns a list of tokens, order preserved, capped."""
    out = []
    for raw in _SPLIT.split(text):
        if not raw or len(raw) > 40:
            continue
        for piece in _CAMEL.findall(raw):
            p = piece.lower()
            if len(p) < 3 or len(p) > 24 or p in STOP:
                continue
            out.append(p)
            if len(out) >= limit:
                return out
    return out


def path_tokens(relpath):
    """Tokens from the path itself. Folder and file names carry a lot of meaning."""
    return tokenize(relpath.replace(os.sep, ' ').replace('.', ' '), limit=64)


def should_prune(dirname):
    return dirname in PRUNE_DIRS or dirname.startswith(PRUNE_NAME_PREFIX)


def is_vendored(relposix):
    low = '/' + relposix.lower()
    return any(h in low for h in VENDOR_HINTS)


# The map's own build products. Embedding these made the map contain a
# description of itself that changed on every rebuild -- so it could never be
# stable, and the staleness check reported 73 files "changed" four seconds
# after a build, all of them written BY that build. `data/curated/` is
# deliberately not here: those are authored inputs, not outputs.
GENERATED = (
    'umap/data/folders/',
    'umap/data/corpus.json',
    'umap/data/sweep',
    'umap/web/data/',
)


def is_generated(relposix):
    low = relposix.replace('\\', '/').lower()
    return any(low.startswith(g) or ('/' + g) in ('/' + low) for g in GENERATED)


# What is IN a file, not just what it is called. These feed the map three ways:
# into the token stream (so two files that define the same things land near each
# other), into the node record (so the panel can say what a file contains), and
# into search (so you can look for a function you half remember).
_SYMBOL_RX = {
    'python': re.compile(r'^\s*(?:async\s+)?(?:def|class)\s+([A-Za-z_]\w*)', re.M),
    'js': re.compile(r'^\s*(?:export\s+)?(?:async\s+)?(?:function\s+([A-Za-z_$]\w*)'
                     r'|class\s+([A-Za-z_$]\w*)'
                     r'|(?:const|let|var)\s+([A-Za-z_$]\w*)\s*=\s*(?:async\s*)?(?:\([^)]*\)|[A-Za-z_$]\w*)\s*=>)', re.M),
    'java': re.compile(r'^\s*(?:public|private|protected|static|final|abstract|\s)*'
                       r'(?:class|interface|enum|record)\s+(\w+)'
                       r'|^\s*(?:public|private|protected)\s+(?:static\s+)?[\w<>\[\],. ]+\s+(\w+)\s*\(', re.M),
    'csharp': re.compile(r'^\s*(?:public|private|protected|internal|static|sealed|abstract|\s)*'
                         r'(?:class|struct|interface|enum)\s+(\w+)'
                         r'|^\s*(?:public|private|protected|internal)\s+(?:static\s+)?[\w<>\[\],. ]+\s+(\w+)\s*\(', re.M),
    'lua': re.compile(r'^\s*(?:local\s+)?function\s+([\w.:]+)', re.M),
    'glsl': re.compile(r'^\s*(?:void|float|vec[234]|mat[234]|int|bool)\s+(\w+)\s*\(', re.M),
    'native': re.compile(r'^\s*(?:[\w:*&<>\[\] ]+?)\s+(\w+)\s*\([^;]*\)\s*\{', re.M),
    'doc': re.compile(r'^#{1,4}\s+(.+?)\s*$', re.M),
    'config': re.compile(r'^\s*"([A-Za-z_][\w \-]{2,40})"\s*:', re.M),
    'script': re.compile(r'^\s*(?::(\w+)|(\w+)\s*\(\)\s*\{|function\s+(\w+))', re.M),
    'skript': re.compile(r'^(command\s+/[\w\-]+|on\s+[\w ]+):', re.M),
    'web': re.compile(r'^\s*(?:function\s+([A-Za-z_$]\w*)|class\s+([A-Za-z_$]\w*)'
                      r'|#([A-Za-z][\w\-]{2,30})\s*\{)', re.M),
}
_SYMBOL_RX['ts'] = _SYMBOL_RX['js']

_SUMMARY_SKIP = re.compile(r'^\s*(?:#!|<!DOCTYPE|<\?xml|/\*+\s*$|\*+/|["\']{3}\s*$|\s*$)')


def symbols(text, lang, limit=18):
    """The named things a file defines, in source order, de-duplicated."""
    rx = _SYMBOL_RX.get(lang)
    if not rx or not text:
        return []
    out, seen = [], set()
    for m in rx.finditer(text[:MAX_READ]):
        name = next((g for g in m.groups() if g), None)
        if not name:
            continue
        name = name.strip()
        if len(name) < 3 or len(name) > 48 or name.lower() in seen:
            continue
        seen.add(name.lower())
        out.append(name)
        if len(out) >= limit:
            break
    return out


def summary(text, lang, limit=200):
    """One line saying what this file is, taken from the file itself.

    A markdown file's first heading or paragraph, a source file's first
    docstring or comment. No inference, no guessing -- if the file does not
    say, this returns ''.
    """
    if not text:
        return ''
    lines = text[:8000].splitlines()
    if lang == 'doc':
        for ln in lines[:40]:
            s = ln.strip().lstrip('#').strip()
            if s and not s.startswith(('---', '===', '<!--', '|', '>')) and len(s) > 3:
                return s[:limit]
        return ''
    for ln in lines[:25]:
        s = ln.strip()
        if not s or _SUMMARY_SKIP.match(s):
            continue
        for pre in ('"""', "'''", '///', '//!', '//', '#', '/*', '*', '--', '@rem', 'REM'):
            if s.startswith(pre):
                s = s[len(pre):].strip(' *"\'/')
                if len(s) > 3:
                    return s[:limit]
                break
        else:
            return ''
    return ''


def read_text(path, limit=MAX_READ):
    """Read a text file defensively. Returns '' rather than raising."""
    try:
        with open(path, 'rb') as fh:
            raw = fh.read(limit)
    except OSError:
        return ''
    if b'\x00' in raw[:4096]:
        return ''  # binary wearing a text extension
    for enc in ('utf-8', 'cp1252', 'latin-1'):
        try:
            return raw.decode(enc)
        except UnicodeDecodeError:
            continue
    return ''
