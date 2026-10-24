#!/usr/bin/env python3
"""Fold the whole game into ONE self-contained .html file.

The dev build is ES modules with three.js vendored beside it, which is right for
working on and useless for handing to somebody: an artifact page and a file sent
to a phone both have to be a single document with no fetches in it.

So this is a tiny bundler. It does NOT concatenate the sources into one scope --
three.js is minified and its two halves both use one-letter identifiers, so a
flat concatenation collides immediately. Instead every module keeps its own
function scope and the import/export lines are rewritten into arguments and a
returned object, which is what the module system was doing for us anyway.

    py -3 tools/bundle.py          ->  dist/HOLLOW.html
"""
from __future__ import annotations

import re
from pathlib import Path

ROOT = Path(__file__).resolve().parent.parent
APP = ROOT / "app"
OUT = ROOT / "dist" / "GRAFT.html"

# Dependency order. Nothing here is discovered, because a wrong guess would fail
# at runtime on somebody's phone rather than here.
# Order matters: a module is built before anything that imports it. The event
# modules import world.js and world.js imports the engine, which is a cycle in
# the source -- it works at runtime because every use is inside a function, and
# it works here because __M is filled in this order and read lazily.
MODULES = [
    ("three.core", APP / "vendor/three/three.core.min.js"),
    ("three",      APP / "vendor/three/three.module.js"),
    ("const",      APP / "src/sim/const.js"),
    ("planet",     APP / "src/sim/planet.js"),
    ("catalog",    APP / "src/sim/catalog.js"),
    ("bignum",     APP / "src/sim/bignum.js"),
    ("scale",      APP / "src/sim/scale.js"),
    ("evapi",      APP / "src/sim/events/api.js"),
    ("blackhole",  APP / "src/sim/events/blackhole.js"),
    ("wormhole",   APP / "src/sim/events/wormhole.js"),
    ("impact",     APP / "src/sim/events/impact.js"),
    ("moonev",     APP / "src/sim/events/moon.js"),
    ("rift",       APP / "src/sim/events/rift.js"),
    ("starev",     APP / "src/sim/events/star.js"),
    ("registry",   APP / "src/sim/events/registry.js"),
    ("engine",     APP / "src/sim/events/engine.js"),
    ("boot",       APP / "src/sim/boot.js"),
    ("world",      APP / "src/sim/world.js"),
    ("fxapi",      APP / "src/view/fx/api.js"),
    ("fxblackhole", APP / "src/view/fx/blackhole.js"),
    ("fxwormhole", APP / "src/view/fx/wormhole.js"),
    ("fximpact",   APP / "src/view/fx/impact.js"),
    ("fxmoon",     APP / "src/view/fx/moon.js"),
    ("fxrift",     APP / "src/view/fx/rift.js"),
    ("fxstar",     APP / "src/view/fx/star.js"),
    ("fxindex",    APP / "src/view/fx/index.js"),
    ("gltf",       APP / "vendor/GLTFLoader.js"),
    ("assets",     APP / "src/view/assets.js"),
    ("packs",      APP / "src/view/packs.js"),
    ("cosmos",     APP / "src/view/cosmos.js"),
    ("render",     APP / "src/view/render.js"),
    ("controls",   APP / "src/view/controls.js"),
]
ENTRY = ("main", APP / "src/main.js")

# Which module a relative specifier means.
# A specifier is resolved RELATIVE TO THE FILE THAT WROTE IT, exactly as the
# browser does. The first version used one flat table of specifier -> module,
# which is wrong the moment two directories both contain an `api.js` or a
# `blackhole.js`: `./blackhole.js` means the sim event from sim/events/ and the
# FX module from view/fx/, and a flat table has to pick one and be wrong.
BY_PATH = {}          # resolved absolute path -> module name, filled in main()


def resolve(importer_path, specifier):
    """Absolute path a specifier points at, from the file that imported it."""
    return (importer_path.parent / specifier).resolve()


# Minified three.js puts its import and BOTH of its export statements mid-line,
# not at the start of one, and anchoring on ^ silently matched neither. A bundler
# that misses an export emits a file that parses as garbage at load.
IMPORT_NAMED = re.compile(r"""import\s*\{([^}]*)\}\s*from\s*['"]([^'"]+)['"]\s*;?""")
IMPORT_STAR = re.compile(r"""import\s*\*\s*as\s+(\w+)\s+from\s*['"]([^'"]+)['"]\s*;?""")
# A RE-export (`export{A,B}from"./x.js"`) is not an export of anything local:
# those names were never in this module's scope. Stripping it as if it were
# an ordinary export list leaves a bare `from"./x.js";` behind, which parses
# as a syntax error a third of a megabyte into the file. Match it FIRST.
EXPORT_FROM = re.compile(r"""export\s*\{([^}]*)\}\s*from\s*['"]([^'"]+)['"]\s*;?""")
EXPORT_LIST = re.compile(r"""export\s*\{([^}]*)\}\s*;?""")
EXPORT_DECL = re.compile(r"""^(\s*)export\s+(async\s+)?(function|class|const|let|var)\s+(\w+)""", re.M)
# `export default DEF;` and `import DEF from "./x.js"` -- the event modules use
# both, and a bundler that only knows named exports emits a file with a bare
# `export default` still in it, which is a syntax error a third of the way in.
EXPORT_DEFAULT = re.compile(r"""export\s+default\s+([A-Za-z_$][\w$]*)\s*;?""")
IMPORT_DEFAULT = re.compile(r"""import\s+([A-Za-z_$][\w$]*)\s+from\s*['"]([^'"]+)['"]\s*;?""")
# `import './boot.js';` -- a bare side-effect import, which binds nothing and is
# exactly how the catalog gets its event-trigger entries registered. It is not
# any of the three forms above and slipped straight through into the output.
IMPORT_BARE = re.compile(r"""import\s*['"]([^'"]+)['"]\s*;?""")


def parse_pairs(body: str):
    """`A as b, C` -> [(A, b), (C, C)]"""
    out = []
    for part in body.split(","):
        part = part.strip()
        if not part:
            continue
        if " as " in part:
            a, b = part.split(" as ", 1)
            out.append((a.strip(), b.strip()))
        else:
            out.append((part, part))
    return out


def transform(name: str, src: str, path, is_entry: bool = False):
    """Rewrite one ES module into a function body. Returns (js, deps)."""
    deps: list[str] = []
    prelude: list[str] = []

    def named(m):
        specifier = m.group(2)
        target = resolve(path, specifier)
        if target not in BY_PATH:
            raise SystemExit(f"{name}: import {specifier!r} resolves to {target}, not in MODULES")
        dep = BY_PATH[target]
        if dep not in deps:
            deps.append(dep)
        order = [n for n, _ in MODULES] + [ENTRY[0]]
        if order.index(dep) > order.index(name):
            raise SystemExit(
                f"{name}: named import from {dep!r}, which is built later. "
                f"Named imports are snapshotted, so this would be undefined. "
                f"Reorder MODULES or use `import * as`.")
        binds = ", ".join(f"{local} = __M[{dep!r}].{orig}" for orig, local in parse_pairs(m.group(1)))
        prelude.append(f"  const {binds};")
        return ""

    def star(m):
        specifier = m.group(2)
        target = resolve(path, specifier)
        if target not in BY_PATH:
            raise SystemExit(f"{name}: import {specifier!r} resolves to {target}, not in MODULES")
        dep = BY_PATH[target]
        if dep not in deps:
            deps.append(dep)
        # LAZY on purpose. The event modules and world.js import each other, and
        # real ESM resolves that with live bindings. Snapshotting `__M['world']`
        # here captured `undefined` for ever, because world.js is built after the
        # events that it (transitively) imports. A proxy defers to call time,
        # which is when every one of these is actually used.
        prelude.append(
            f"  const {m.group(1)} = new Proxy({{}}, {{"
            f" get: (_t, _k) => (__M[{dep!r}] || {{}})[_k],"
            f" has: (_t, _k) => _k in (__M[{dep!r}] || {{}}) }});")
        return ""

    def default_import(m):
        specifier = m.group(2)
        target = resolve(path, specifier)
        if target not in BY_PATH:
            raise SystemExit(f"{name}: import {specifier!r} resolves to {target}, not in MODULES")
        dep = BY_PATH[target]
        if dep not in deps:
            deps.append(dep)
        prelude.append(f"  const {m.group(1)} = __M[{dep!r}].default;")
        return ""

    src = IMPORT_STAR.sub(star, src)
    src = IMPORT_NAMED.sub(named, src)
    src = IMPORT_DEFAULT.sub(default_import, src)

    def bare_import(m):
        specifier = m.group(1)
        target = resolve(path, specifier)
        if target not in BY_PATH:
            raise SystemExit(f"{name}: side-effect import {specifier!r} resolves to {target}, not in MODULES")
        dep = BY_PATH[target]
        if dep not in deps:
            deps.append(dep)
        # nothing to bind: the module is an IIFE that already ran, in order
        return ""

    src = IMPORT_BARE.sub(bare_import, src)

    exports: list[tuple[str, str]] = []
    reexports: list[tuple[str, str, str]] = []

    def exp_from(m):
        specifier = m.group(2)
        target = resolve(path, specifier)
        if target not in BY_PATH:
            raise SystemExit(f"{name}: re-export {specifier!r} resolves to {target}, which is not in MODULES")
        dep = BY_PATH[target]
        if dep not in deps:
            deps.append(dep)
        for orig_name, pub in parse_pairs(m.group(1)):
            reexports.append((orig_name, pub, dep))
        return ""

    src = EXPORT_FROM.sub(exp_from, src)

    def exp_list(m):
        exports.extend(parse_pairs(m.group(1)))
        return ""

    default_name = []

    def exp_default(m):
        default_name.append(m.group(1))
        return ""

    src = EXPORT_DEFAULT.sub(exp_default, src)
    src = EXPORT_LIST.sub(exp_list, src)

    def exp_decl(m):
        exports.append((m.group(4), m.group(4)))
        return f"{m.group(1)}{m.group(2) or ''}{m.group(3)} {m.group(4)}"

    src = EXPORT_DECL.sub(exp_decl, src)

    ret = ""
    if (exports or reexports or default_name) and not is_entry:
        fields = [f"{pub}: {local}" for local, pub in exports]
        if default_name:
            fields.append(f"default: {default_name[0]}")
        fields += [f"{pub}: __M[{dep!r}].{orig_name}" for orig_name, pub, dep in reexports]
        ret = "\n  return { " + ", ".join(fields) + " };\n"

    body = "\n".join(prelude) + "\n" + src + ret
    return body, deps


def main() -> None:
    for name, path in MODULES + [ENTRY]:
        BY_PATH[path.resolve()] = name

    parts = ["(function(){\n'use strict';\nvar __M = {};\n"]
    for name, path in MODULES + [ENTRY]:
        src = path.read_text(encoding="utf-8")
        body, deps = transform(name, src, path, is_entry=(name == ENTRY[0]))
        for d in deps:
            if d not in [n for n, _ in MODULES]:
                raise SystemExit(f"{name}: depends on unknown module {d!r}")
        parts.append(f"/* ---- {name} ---- */\n__M[{name!r}] = (function(){{\n{body}\n}})();\n")
    parts.append("})();\n")
    js = "".join(parts)

    # A literal </script> inside a string would close the tag early.
    js = js.replace("</script", "<\\/script")

    html = (APP / "index.html").read_text(encoding="utf-8")
    css = (APP / "style.css").read_text(encoding="utf-8")

    html = html.replace('<link rel="stylesheet" href="style.css">', f"<style>\n{css}\n</style>")
    html = html.replace('<script type="module" src="src/main.js"></script>', f"<script>\n{js}\n</script>")

    OUT.parent.mkdir(parents=True, exist_ok=True)
    OUT.write_text(html, encoding="utf-8")
    kb = OUT.stat().st_size / 1024
    print(f"wrote {OUT.relative_to(ROOT)}  ({kb:.0f} KB, {len(MODULES) + 1} modules)")
    if "src/main.js" in html or "style.css" in html:
        raise SystemExit("BUNDLE STILL REFERENCES EXTERNAL FILES")

    # A leftover module keyword is a syntax error that only shows up when the
    # page is opened. Catch it here, where the fix is one line away, rather than
    # on a phone with no console.
    # Strip comments before looking for leaks. The FX contract documents itself
    # with example `export function build(ctx)` lines, and a guard that trips on
    # its own documentation is a guard somebody disables. Read code, not prose.
    code = re.sub(r"/\*[\s\S]*?\*/", "", js)
    code = re.sub(r"(^|[^:])//.*$", r"", code, flags=re.M)

    leaks = [
        ("export", re.compile(r"(^|[;}\s])export[\s{]")),
        ("import", re.compile(r"(^|[;}\s])import[\s{*]")),
        ("dangling from", re.compile(r"(^|[;}\s])from\s*['\"]\.")),
    ]
    for label, rx in leaks:
        hit = rx.search(code)
        if hit:
            around = code[max(0, hit.start() - 70):hit.start() + 70]
            raise SystemExit(f"BUNDLE LEAKED {label}: ...{around}...")
    print("self-contained: no module keywords leaked")


if __name__ == "__main__":
    main()
