"""Put the newest Aura builds and the SkyBlock mod pack into one launcher instance's mods folder.

Called by Minecraftname/play_capes.py right before every launch, so a fresh build is never
"built but not installed" again. Also runnable by hand:

    py -3 tools/sync_instance_mods.py <instance mods dir> <mc version>

Managed jars (replaced when a newer one exists, never duplicated):
  auracapes-*, auraqol-*          from AuraCapes/build/libs and AuraQoL/build/libs (+mc<version> builds only)
  every mod in pack/mods-<mc>/    per its MANIFEST.json (SkyHanni, Skyblocker, Fabric API, Sodium, ...)
Anything else in the folder (e.g. testpilot) is left alone.
A managed mod switched off with Aura Client's /mods (x.jar.disabled) stays off and is kept current as
.jar.disabled; jars /mods queued in config/aura-mods.json are renamed before launch (see apply_pending).
"""
import json
import shutil
import sys
from pathlib import Path

HERE = Path(__file__).resolve().parents[1]            # AuraCapes/
ROOT = HERE.parent                                     # Ai Game/

# filename prefix that identifies each managed mod, whatever its version
PREFIX = {
    "auracapes": "auracapes-",
    "auraqol": "auraqol-",
    "skyhanni": "skyhanni-",
    "skyblocker-liap": "skyblocker-",
    "fabric-api": "fabric-api-",
    "fabric-language-kotlin": "fabric-language-kotlin-",
    "hypixel-mod-api": "hypixelmodapi-",
    "modmenu": "modmenu-",
    # performance. sodium's prefix includes "-fabric-" so it never matches sodium-extra's jar.
    "sodium": "sodium-fabric-",
    "lithium": "lithium-fabric-",
    "ferrite-core": "ferritecore-",
    "immediatelyfast": "immediatelyfast-",
    "entityculling": "entityculling-",
    "moreculling": "moreculling-",
    "cloth-config": "cloth-config-",
    "dynamic-fps": "dynamic-fps-",
    "sodium-extra": "sodium-extra-",
    "reeses-sodium-options": "reeses-sodium-options-",
}


def wanted(mc):
    """{managed key: source path} for this Minecraft version."""
    out = {}
    for key, folder in (("auracapes", HERE / "build/libs"), ("auraqol", ROOT / "AuraQoL/build/libs")):
        jar = folder / f"{key}-1.0.0+mc{mc}.jar"
        if jar.exists():
            out[key] = jar
    pack = HERE / "pack" / f"mods-{mc}"
    man = pack / "MANIFEST.json"
    if man.exists():
        for m in json.loads(man.read_text(encoding="utf-8")):
            if m["slug"] in PREFIX and (pack / m["file"]).exists():
                out[m["slug"]] = pack / m["file"]
    return out


# Same sets as Aura Client's in-game /mods presets (ModToggles.DEFAULT_PRESETS). Nothing else depends on
# these three, so switching them is always safe without the in-game dependency check.
HYPIXEL_ONLY = ["skyhanni", "skyblocker-liap", "hypixel-mod-api"]
PRESETS = {"hypixel": {"on": HYPIXEL_ONLY, "off": []}, "shadow": {"on": [], "off": HYPIXEL_ONLY}}


def apply_preset(mods_dir, name):
    """Switches the preset's mods on/off by renaming x.jar <-> x.jar.disabled. Call while the game is closed."""
    mods = Path(mods_dir)
    preset = PRESETS.get(name.lower())
    if preset is None:
        return [f"unknown preset {name!r} (have: {', '.join(PRESETS)})"]
    out = []
    for key in preset["off"]:
        for p in mods.glob("*.jar"):
            if p.name.lower().startswith(PREFIX[key]):
                try:
                    p.rename(p.with_name(p.name + ".disabled"))
                    out.append(f"{p.name} OFF ({name})")
                except PermissionError:
                    out.append(f"{p.name}: in use (is Minecraft already open?), left ON")
    for key in preset["on"]:
        if any(p.name.lower().startswith(PREFIX[key]) for p in mods.glob("*.jar")):
            continue
        off = sorted(p for p in mods.glob("*.jar.disabled") if p.name.lower().startswith(PREFIX[key]))
        if off:
            off[-1].rename(off[-1].with_name(off[-1].name[: -len(".disabled")]))
            out.append(f"{off[-1].name[: -len('.disabled')]} ON ({name})")
    return out


def apply_pending(mods, changes, problems):
    """Renames the jars Aura Client's /mods queued for switching off (config/aura-mods.json pendingDisable).

    The game applies these itself on a clean close; this covers a crash or a force-kill, and runs
    before every desktop-shortcut launch while the game is NOT running, so nothing holds the jar.
    """
    cfg = mods.parent / "config" / "aura-mods.json"
    if not cfg.exists():
        return
    try:
        data = json.loads(cfg.read_text(encoding="utf-8"))
    except (OSError, ValueError):
        problems.append("config/aura-mods.json is unreadable; /mods changes not applied")
        return
    keep = []
    for raw in data.get("pendingDisable", []):
        jar = Path(raw)
        if not jar.exists():
            continue  # already applied
        if jar.parent.resolve() != mods.resolve():
            keep.append(raw)  # belongs to another mods folder (another launcher); not ours to touch
            continue
        try:
            jar.rename(jar.with_name(jar.name + ".disabled"))
            changes.append(f"{jar.name} switched OFF (/mods)")
        except PermissionError:
            keep.append(raw)
            problems.append(f"{jar.name}: in use, could not switch it off; close Minecraft and launch again")
    if keep != data.get("pendingDisable", []):
        data["pendingDisable"] = keep
        tmp = cfg.with_name(cfg.name + ".tmp")
        tmp.write_text(json.dumps(data, indent=2), encoding="utf-8")
        tmp.replace(cfg)


def sync(mods_dir, mc):
    """Returns (changes, problems). Never raises for a locked file; reports it instead."""
    mods = Path(mods_dir)
    mods.mkdir(parents=True, exist_ok=True)
    changes, problems = [], []
    want = wanted(mc)
    # An instance holding TestPilot is a test rig (SkyClone playtests, AISkyShow): keep it to the Aura
    # jars only, so SkyHanni/Skyblocker never change what a test bot sees. Remove the pack if present.
    rig = any(p.name.lower().startswith("testpilot") for p in mods.glob("*.jar"))
    if rig:
        for key in [k for k in want if k not in ("auracapes", "auraqol")]:
            want.pop(key)
            if key == "fabric-api":
                continue  # the rig already has its own Fabric API; leave it
            for p in mods.glob("*.jar"):
                if p.name.lower().startswith(PREFIX[key]):
                    try:
                        p.unlink()
                        changes.append(f"removed {p.name} (test rig instance)")
                    except PermissionError:
                        problems.append(f"{p.name}: in use; close Minecraft and launch again")
    apply_pending(mods, changes, problems)
    for key, src in want.items():
        prefix = PREFIX[key]
        present = [p for p in mods.glob("*.jar") if p.name.lower().startswith(prefix)]
        # Switched off with /mods: keep it off, but still keep it current so switching it back on
        # gives the newest build rather than a stale one.
        off = [p for p in mods.glob("*.jar.disabled") if p.name.lower().startswith(prefix)]
        if off and not present:
            if len(off) == 1 and off[0].name == src.name + ".disabled" and off[0].stat().st_size == src.stat().st_size:
                continue
            try:
                for p in off:
                    p.unlink()
                shutil.copy2(src, mods / (src.name + ".disabled"))
                changes.append(f"{src.name} (updated, kept OFF by /mods)")
            except PermissionError:
                problems.append(f"{src.name}: could not update the switched-off copy")
            continue
        same = [p for p in present if p.name == src.name and p.stat().st_size == src.stat().st_size
                and p.stat().st_mtime >= src.stat().st_mtime - 2]
        if same and len(present) == 1:
            continue
        try:
            for p in present:
                p.unlink()
            shutil.copy2(src, mods / src.name)
            changes.append(f"{src.name}" + (f" (replaced {', '.join(p.name for p in present)})" if present else " (new)"))
        except PermissionError:
            problems.append(f"{src.name}: the game is running and holds the old jar; close Minecraft and launch again")
    return changes, problems


if __name__ == "__main__":
    ch, pr = sync(sys.argv[1], sys.argv[2])
    for c in ch:
        print("  +", c)
    for p in pr:
        print("  !", p)
    if not ch and not pr:
        print("  mods already current")
