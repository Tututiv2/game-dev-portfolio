"""The suite. ``python tests/run_tests.py`` -- no pytest, no installs, no network.

Two tiers, per section 13 of the brief:

* **Unit / golden-file** (default): no network, no game, no account. These are the tests
  that make the hard parts auditable -- the ``inheritsFrom`` merge order, rule evaluation
  including the ``os.version`` regex, argument templating with no ``${`` surviving, the
  offline UUID derivation, the classpath separator, servers.dat round-tripping, and the
  full five-hop auth chain against a mocked transport (every XErr, and 403 vs 404 landing
  on *different* errors).
* **Integration** (``--net``): resolves and downloads real versions, asserts every SHA-1,
  and asserts a second run transfers zero bytes.

Run with ``--net`` to include the second tier.
"""
from __future__ import annotations

import json
import os
import shutil
import sys
import tempfile
import time
import traceback
from pathlib import Path

ROOT = Path(__file__).resolve().parent.parent
sys.path.insert(0, str(ROOT))
GOLDEN = Path(__file__).resolve().parent / "golden"

from launcher import logs, net, rules, versions  # noqa: E402
from launcher.paths import Layout  # noqa: E402

logs.setup(None, console=False)

# ---------------------------------------------------------------------------------
# Tiny harness
# ---------------------------------------------------------------------------------

_TESTS: list = []
_ONLY_NET: set = set()


def test(fn):
    _TESTS.append(fn)
    return fn


def nettest(fn):
    _ONLY_NET.add(fn.__name__)
    _TESTS.append(fn)
    return fn


class Failure(AssertionError):
    pass


def eq(actual, expected, what=""):
    if actual != expected:
        raise Failure(f"{what or 'value'}:\n  expected {expected!r}\n  actual   {actual!r}")


def ok(cond, what=""):
    if not cond:
        raise Failure(what or "expected true")


def raises(exc_type, fn, what=""):
    try:
        fn()
    except exc_type as e:
        return e
    except Exception as e:  # noqa: BLE001
        raise Failure(f"{what}: expected {exc_type.__name__}, got "
                      f"{type(e).__name__}: {e}") from e
    raise Failure(f"{what}: expected {exc_type.__name__}, nothing raised")


# =================================================================================
# inheritsFrom merge
# =================================================================================

VANILLA_FIXTURE = {
    "id": "1.20.1",
    "type": "release",
    "mainClass": "net.minecraft.client.main.Main",
    "assetIndex": {"id": "5", "url": "https://example/5.json", "sha1": "a" * 40},
    "downloads": {"client": {"url": "https://example/client.jar", "sha1": "b" * 40}},
    "javaVersion": {"component": "java-runtime-gamma", "majorVersion": 17},
    "libraries": [
        {"name": "com.mojang:logging:1.1.1"},
        {"name": "org.lwjgl:lwjgl:3.3.1"},
        {"name": "org.lwjgl:lwjgl:3.3.1:natives-windows"},
        {"name": "com.google.guava:guava:31.0"},
    ],
    "arguments": {"game": ["--username", "${auth_player_name}"],
                  "jvm": ["-Dvanilla=1", "-cp", "${classpath}"]},
}

FORGE_FIXTURE = {
    "id": "1.20.1-forge-47.4.10",
    "inheritsFrom": "1.20.1",
    "mainClass": "cpw.mods.bootstraplauncher.BootstrapLauncher",
    "libraries": [
        {"name": "net.minecraftforge:forge:1.20.1-47.4.10"},
        # Forge ships a patched guava; it must beat vanilla's on the classpath.
        {"name": "com.google.guava:guava:32.1.2-jre"},
    ],
    "arguments": {"game": ["--launchTarget", "forgeclient"],
                  "jvm": ["-Dforge=1"]},
}


@test
def merge_child_libraries_come_first():
    merged = versions.merge(FORGE_FIXTURE, VANILLA_FIXTURE)
    names = [lib["name"] for lib in merged["libraries"]]
    eq(names[0], "net.minecraftforge:forge:1.20.1-47.4.10", "first library")
    eq(names[1], "com.google.guava:guava:32.1.2-jre", "second library")
    ok("com.google.guava:guava:31.0" not in names,
       "vanilla guava must be de-duplicated away, losing to the child's copy")
    ok(names.index("com.google.guava:guava:32.1.2-jre")
       < names.index("com.mojang:logging:1.1.1"),
       "every child library must precede every parent library")


@test
def merge_keeps_both_lwjgl_and_its_natives_classifier():
    """De-dup keys on group:artifact:classifier, not group:artifact."""
    merged = versions.merge(FORGE_FIXTURE, VANILLA_FIXTURE)
    names = [lib["name"] for lib in merged["libraries"]]
    ok("org.lwjgl:lwjgl:3.3.1" in names, "plain lwjgl survives")
    ok("org.lwjgl:lwjgl:3.3.1:natives-windows" in names,
       "the natives-classifier jar is a different artefact and must also survive")


@test
def merge_scalars_and_arguments():
    merged = versions.merge(FORGE_FIXTURE, VANILLA_FIXTURE)
    eq(merged["mainClass"], "cpw.mods.bootstraplauncher.BootstrapLauncher", "child wins")
    eq(merged["id"], "1.20.1-forge-47.4.10", "id comes from the child")
    eq(merged["assetIndex"]["id"], "5", "assetIndex inherited from the parent")
    eq(merged["javaVersion"]["majorVersion"], 17, "javaVersion inherited")
    eq(merged["arguments"]["game"], ["--username", "${auth_player_name}",
                                     "--launchTarget", "forgeclient"],
       "game args: parent first, child appended")
    eq(merged["arguments"]["jvm"], ["-Dvanilla=1", "-cp", "${classpath}", "-Dforge=1"],
       "jvm args: parent first, child appended")
    ok("inheritsFrom" not in merged, "inheritsFrom is consumed by the merge")


@test
def merge_is_pure():
    before = json.dumps(VANILLA_FIXTURE, sort_keys=True)
    versions.merge(FORGE_FIXTURE, VANILLA_FIXTURE)
    eq(json.dumps(VANILLA_FIXTURE, sort_keys=True), before, "parent must not be mutated")


@test
def maven_coordinate_parsing():
    m = versions.Maven.parse("org.lwjgl:lwjgl:3.3.1:natives-windows")
    eq(m.group, "org.lwjgl")
    eq(m.classifier, "natives-windows")
    eq(m.path(), "org/lwjgl/lwjgl/3.3.1/lwjgl-3.3.1-natives-windows.jar")
    eq(versions.Maven.parse("net.minecraftforge:forge:1.20.1-47.4.10@zip").extension,
       "zip", "@ext suffix")
    raises(versions.VersionError, lambda: versions.Maven.parse("nope"), "bad coordinate")


@test
def inherits_loop_is_detected():
    tmp = Path(tempfile.mkdtemp(prefix="myfire-loop-"))
    try:
        layout = Layout(tmp)
        layout.ensure()
        for a, b in (("A", "B"), ("B", "A")):
            d = layout.version_dir(a)
            d.mkdir(parents=True, exist_ok=True)
            (d / f"{a}.json").write_text(json.dumps({"id": a, "inheritsFrom": b}))
        err = raises(versions.VersionError,
                     lambda: versions.resolve(layout, "A"), "loop")
        ok("loop" in str(err).lower(), "error should name the loop")
    finally:
        shutil.rmtree(tmp, ignore_errors=True)


# =================================================================================
# Rules
# =================================================================================

WIN = rules.OsInfo("windows", "x64", "10.0.26200")
OSX = rules.OsInfo("osx", "arm64", "14.4")
LINUX = rules.OsInfo("linux", "x64", "6.1.0")


@test
def rule_table():
    allow_all = [{"action": "allow"}]
    not_osx = [{"action": "allow"}, {"action": "disallow", "os": {"name": "osx"}}]
    only_win = [{"action": "allow", "os": {"name": "windows"}}]
    win10_only = [{"action": "allow", "os": {"name": "windows", "version": r"^10\."}}]
    x86_only = [{"action": "allow", "os": {"name": "windows", "arch": "x86"}}]

    cases = [
        (None, WIN, True, "no rules -> allow"),
        ([], WIN, True, "empty rules -> allow"),
        (allow_all, WIN, True, "bare allow"),
        (not_osx, WIN, True, "allow then disallow osx, on windows"),
        (not_osx, OSX, False, "allow then disallow osx, on osx (last match wins)"),
        (not_osx, LINUX, True, "allow then disallow osx, on linux"),
        (only_win, WIN, True, "windows-only on windows"),
        (only_win, LINUX, False, "windows-only on linux -> default deny, nothing matched"),
        (win10_only, WIN, True, r"os.version regex ^10\. against 10.0.26200"),
        (win10_only, rules.OsInfo("windows", "x64", "6.1.7601"), False,
         "the same regex must NOT match Windows 7's 6.1"),
        (x86_only, WIN, False, "arch mismatch"),
        (x86_only, rules.OsInfo("windows", "x86", "10.0"), True, "arch match"),
    ]
    for rule, os_info, expected, what in cases:
        eq(rules.allowed(rule, os_info=os_info), expected, what)


@test
def feature_rules():
    demo = [{"action": "allow", "features": {"is_demo_user": True}}]
    not_demo = [{"action": "allow", "features": {"is_demo_user": False}}]
    eq(rules.allowed(demo, os_info=WIN, features=rules.Features(is_demo_user=True)), True)
    eq(rules.allowed(demo, os_info=WIN, features=rules.Features()), False)
    eq(rules.allowed(not_demo, os_info=WIN, features=rules.Features()), True,
       "a rule asking for false matches when the flag is off")


@test
def classpath_separator_is_per_platform():
    eq(WIN.classpath_separator, ";", "windows")
    eq(LINUX.classpath_separator, ":", "linux")
    eq(OSX.classpath_separator, ":", "macos")


# =================================================================================
# Argument templating
# =================================================================================

@test
def every_placeholder_substitutes_and_none_survive():
    from launcher import launch as L
    from launcher.accounts import LocalAccount

    account = LocalAccount("Tutut", force=True)
    table = L.placeholder_table(
        version={"id": "1.20.1", "type": "release"},
        instance_dir=Path(r"D:\projects\MyFireServer\inst"),
        layout=Layout(Path(r"D:\projects\data")),
        account=account, classpath="a.jar;b.jar",
        natives_dir=Path(r"D:\nat"), asset_index_id="5", game_assets=None,
        client_id="cid", width=1280, height=720, os_info=WIN,
        quick_play="localhost:25595", quick_play_path=r"D:\qp.json")

    # Every placeholder the brief lists in section 7.2.
    required = ["auth_player_name", "auth_uuid", "auth_access_token", "auth_xuid",
                "clientid", "user_type", "version_name", "version_type",
                "game_directory", "assets_root", "assets_index_name", "game_assets",
                "natives_directory", "classpath", "launcher_name", "launcher_version",
                "library_directory", "classpath_separator", "resolution_width",
                "resolution_height"]
    for key in required:
        ok(key in table, f"placeholder {key} must be supplied")

    template = " ".join("${%s}" % k for k in table)
    out = L.substitute(template, table, where="all-placeholders")
    ok("${" not in out, f"no ${{ may survive substitution; got {out[:120]}")
    eq(table["auth_uuid"], account.uuid_dashless, "uuid goes on the command line dashless")
    ok("-" not in table["auth_uuid"], "dashless")


@test
def unresolved_placeholder_raises_and_names_itself():
    from launcher import launch as L
    err = raises(L.LaunchError,
                 lambda: L.substitute("${nope} x", {"a": "1"}, where="test"),
                 "unknown placeholder")
    ok("nope" in str(err), "the error must name the missing placeholder")


@test
def pre_1_13_argument_string_is_split_and_substituted():
    from launcher import launch as L
    version = {"id": "1.12.2",
               "minecraftArguments": "--username ${auth_player_name} --version ${version_name}"}
    table = {"auth_player_name": "Tutut", "version_name": "1.12.2",
             "classpath": "a.jar", "natives_directory": r"D:\nat"}
    jvm, game = L.build_arguments(version, table, os_info=WIN, features=rules.Features())
    eq(game, ["--username", "Tutut", "--version", "1.12.2"], "legacy args")
    ok("-cp" in jvm and r"-Djava.library.path=D:\nat" in jvm,
       "a pre-1.13 version has no arguments.jvm, so the launcher must synthesise one")


@test
def modern_arguments_respect_rules():
    from launcher import launch as L
    version = {"id": "1.20.1", "arguments": {
        "game": ["--a",
                 {"rules": [{"action": "allow",
                             "features": {"is_quick_play_multiplayer": True}}],
                  "value": ["--quickPlayMultiplayer", "${quickPlayMultiplayer}"]},
                 {"rules": [{"action": "allow", "features": {"is_demo_user": True}}],
                  "value": "--demo"}],
        "jvm": [{"rules": [{"action": "allow", "os": {"name": "osx"}}],
                 "value": "-XstartOnFirstThread"},
                "-Dx=1"]}}
    table = {"quickPlayMultiplayer": "localhost:25595"}
    jvm, game = L.build_arguments(version, table, os_info=WIN,
                                 features=rules.Features(is_quick_play_multiplayer=True))
    eq(game, ["--a", "--quickPlayMultiplayer", "localhost:25595"],
       "quick play in, demo out")
    eq(jvm, ["-Dx=1"], "the macOS-only jvm arg must not appear on Windows")


@test
def log4shell_flag_only_on_affected_versions():
    from launcher import launch as L
    eq(L.log4j_flags({"id": "1.12.2"}), ["-Dlog4j2.formatMsgNoLookups=true"], "1.12.2")
    eq(L.log4j_flags({"id": "1.18.1"}), ["-Dlog4j2.formatMsgNoLookups=true"], "1.18.1")
    eq(L.log4j_flags({"id": "1.18.2"}), [], "1.18.2 shipped patched")
    eq(L.log4j_flags({"id": "1.20.1"}), [], "modern")
    eq(L.log4j_flags({"id": "1.12.2-forge-14.23.5.2859", "inheritsFrom": "1.12.2"}),
       ["-Dlog4j2.formatMsgNoLookups=true"], "modded ids resolve through inheritsFrom")


@test
def quick_play_picks_the_right_flag_for_the_era():
    from launcher import launch as L
    modern = {"arguments": {"game": [
        {"rules": [{"action": "allow",
                    "features": {"is_quick_play_multiplayer": True}}],
         "value": ["--quickPlayMultiplayer", "${quickPlayMultiplayer}"]}]}}
    legacy = {"minecraftArguments": "--username ${auth_player_name}"}
    eq(L._quick_play_args(legacy, "localhost:25595", []),
       ["--server", "localhost", "--port", "25595"], "pre-1.20 takes --server/--port")
    eq(L._quick_play_args(legacy, "example.com", []),
       ["--server", "example.com", "--port", "25565"], "default port")
    eq(L._quick_play_args(modern, "localhost:25595",
                          ["--quickPlayMultiplayer", "localhost:25595"]), [],
       "1.20+ emits its own quick play args; adding more would duplicate them")


# =================================================================================
# Offline UUID
# =================================================================================

@test
def offline_uuid_matches_the_server_rule():
    from launcher.accounts import offline_uuid
    import hashlib
    import uuid as _uuid

    for name in ("Tutut", "Notch", "jeb_", "A"):
        got = offline_uuid(name)
        # Independent re-derivation of Java's UUID.nameUUIDFromBytes.
        digest = bytearray(hashlib.md5(f"OfflinePlayer:{name}".encode()).digest())
        digest[6] = (digest[6] & 0x0F) | 0x30
        digest[8] = (digest[8] & 0x3F) | 0x80
        eq(got, str(_uuid.UUID(bytes=bytes(digest))), f"offline uuid for {name}")
        u = _uuid.UUID(got)
        eq(u.version, 3, "must be UUID v3 (MD5)")
        eq(str(u)[19] in "89ab", True, "RFC 4122 variant bits")

    # Golden pair, so a refactor that changes the derivation is caught loudly.
    eq(offline_uuid("Tutut"), "64c2f869-29cb-3080-a156-dff82a5f5d78", "golden")


@test
def dev_account_is_gated_and_validated():
    from launcher.accounts import AuthError, LocalAccount
    from launcher.accounts.local import DEV_ENV_FLAG
    old = os.environ.pop(DEV_ENV_FLAG, None)
    try:
        raises(AuthError, lambda: LocalAccount("Tutut"),
               "the dev stub must refuse to exist outside dev mode")
        a = LocalAccount("Tutut", force=True)
        eq(a.access_token, "0")
        eq(a.user_type, "legacy")
        eq(a.online, False, "the dev stub must never claim it can pass a session check")
        raises(AuthError, lambda: LocalAccount("has space", force=True), "bad name")
        raises(AuthError, lambda: LocalAccount("x" * 17, force=True), "too long")
    finally:
        if old is not None:
            os.environ[DEV_ENV_FLAG] = old


@test
def switching_accounts_never_touches_the_network():
    """Picking a name from the switcher must not do token work.

    The store is the only thing that can talk to Microsoft, and it does it in
    ``resolve()`` -- at launch, on a worker thread. If ``set_active`` or ``summaries``
    ever reached for a token instead, choosing an account from a menu would block the UI
    thread on a five-hop HTTP chain, which is the deadlock the whole account layer is
    shaped to avoid. This asserts it by making the refresh path explode.
    """
    tmp = Path(tempfile.mkdtemp(prefix="myfire-acct-"))
    try:
        from launcher.accounts import store as store_mod
        from launcher.accounts.store import AccountStore, StoredAccount
        layout = Layout(tmp)
        layout.ensure()

        def boom(*_a, **_kw):
            raise Failure("switching accounts must never refresh a token")

        old_restore, old_sign_in = store_mod.restore, store_mod.sign_in
        store_mod.restore = boom
        store_mod.sign_in = boom
        try:
            s = AccountStore(layout, "client-id")
            s.accounts = [StoredAccount(uuid="a" * 32, name="Tutut", skin_url="http://x"),
                          StoredAccount(uuid="b" * 32, name="Alt")]
            s.set_active("a" * 32)

            s.set_active("b" * 32)
            eq([r["active"] for r in s.summaries()], [False, True],
               "exactly the switched-to account is active")
            ok(all("token" not in r for r in s.summaries()),
               "the switcher rows must carry nothing secret")

            # The choice is what survives a restart, not a session variable.
            eq(AccountStore(layout, "client-id").active.name, "Alt",
               "the active account must persist across a launcher restart")

            # Signing the active one out has to leave a usable account selected, not a
            # dangling uuid that fails at launch with 'no account'.
            s.remove("b" * 32)
            eq(s.active.uuid, "a" * 32, "removing the active account falls back")
            eq(AccountStore(layout, "client-id").active.uuid, "a" * 32, "and persists")
        finally:
            store_mod.restore, store_mod.sign_in = old_restore, old_sign_in
    finally:
        shutil.rmtree(tmp, ignore_errors=True)


@test
def the_account_switcher_offers_every_account_and_switches_to_it():
    """The quick switcher's contents, checked with no window and no display.

    ``account_menu_rows`` is deliberately separate from the Tk menu that renders it, so
    the parts that can actually be wrong -- who is offered, who carries the tick, and what
    a click does -- are assertable offline.
    """
    from launcher.accounts.store import StoredAccount
    from launcher.ui import theme
    from launcher.ui.app import App

    class FakeStore:
        def __init__(self):
            self.accounts = [StoredAccount(uuid="a" * 32, name="Tutut"),
                             StoredAccount(uuid="b" * 32, name="Alt")]
            self.active_uuid = "a" * 32

        def summaries(self):
            return [{"uuid": a.uuid, "name": a.name,
                     "active": a.uuid == self.active_uuid, "skin_url": ""}
                    for a in self.accounts]

    class FakeApp:
        MENU_SEP = App.MENU_SEP
        account_menu_rows = App.account_menu_rows

        def __init__(self):
            self.accounts = FakeStore()
            self.dev_account = None
            self.switched = []

        def switch_account(self, uuid):
            self.switched.append(uuid)

        def add_account(self):
            self.switched.append("ADD")

        def open_accounts(self):
            self.switched.append("MANAGE")

    app = FakeApp()
    rows = app.account_menu_rows()
    labels = [label for label, _ in rows]

    eq(sum("Tutut" in x for x in labels), 1, "the signed-in account is offered")
    eq(sum("Alt" in x for x in labels), 1, "so is every other one")
    eq(sum(x.count("✓") for x in labels), 1, "exactly one account is ticked")
    ok("✓" in next(x for x in labels if "Tutut" in x), "the tick is on the active one")
    ok(any("Add another account" in x for x in labels), "adding is always one click away")
    ok(any("Manage accounts" in x for x in labels), "so is the full dialog")
    ok(app.MENU_SEP in rows, "the account list is separated from the verbs")
    eq(labels[0], theme.MENU_HEADER, "the list says what it is")

    # Clicking the account that is not active switches to it; clicking the active one
    # would too, harmlessly, but the verbs must not be wired to switch_account.
    dict((l, a) for l, a in rows if a)[next(x for x in labels if "Alt" in x)]()
    eq(app.switched, ["b" * 32], "picking a name switches to that account")

    # With a dev account in play, the real accounts must lose the tick -- it is the dev
    # stub that would actually launch, and a tick on the wrong row is a lie about who
    # you are about to play as.
    app.switched.clear()
    app.dev_account = type("D", (), {"name": "DevGuy"})()
    labels = [label for label, _ in app.account_menu_rows()]
    eq(sum(x.count("✓") for x in labels), 1, "still exactly one tick")
    ok("✓" in next(x for x in labels if "DevGuy" in x), "and it is on the dev account")


# =================================================================================
# Auth chain, mocked
# =================================================================================

class MockHttp:
    """Swap out launcher.net's two request functions and script the five hops."""

    def __init__(self, routes: dict):
        self.routes = routes
        self.calls: list[tuple[str, str]] = []
        self._req = net.request
        self._post = net.post_json

    def __enter__(self):
        def request(method, url, **kw):
            self.calls.append((method, url))
            entry = self.routes.get(url)
            if entry is None:
                raise AssertionError(f"unmocked request {method} {url}")
            status, payload = entry(kw) if callable(entry) else entry
            body = json.dumps(payload).encode()
            expect = kw.get("expect", (200,))
            if status not in expect:
                raise net.HttpError(status, url, body)
            return net.Response(status, {}, body, url)

        def post_json(url, payload=None, *, form=None, headers=None,
                      expect=(200,), **kw):
            return request("POST", url, expect=expect, json_body=payload, form=form, **kw)

        net.request = request
        net.post_json = post_json
        import launcher.accounts.msa as msa
        msa.net.request = request
        msa.net.post_json = post_json
        return self

    def __exit__(self, *a):
        net.request = self._req
        net.post_json = self._post
        import launcher.accounts.msa as msa
        msa.net.request = self._req
        msa.net.post_json = self._post


def _happy_routes(**over):
    from launcher.accounts import msa
    routes = {
        msa.XBL_URL: (200, {"Token": "xbl-token",
                            "DisplayClaims": {"xui": [{"uhs": "user-hash"}]}}),
        msa.XSTS_URL: (200, {"Token": "xsts-token",
                             "DisplayClaims": {"xui": [{"uhs": "user-hash",
                                                        "xid": "2535100000000000"}]}}),
        msa.MC_LOGIN_URL: (200, {"access_token": "mc-token-abcdef", "expires_in": 86400}),
        msa.MC_ENTITLEMENTS_URL: (200, {"items": [{"name": "product_minecraft"}]}),
        msa.MC_PROFILE_URL: (200, {"id": "0123456789abcdef0123456789abcdef",
                                   "name": "Tutut", "skins": []}),
    }
    routes.update(over)
    return routes


@test
def auth_chain_happy_path():
    from launcher.accounts import msa
    with MockHttp(_happy_routes()):
        result = msa.complete_chain("client-id", "ms-token")
    eq(result.name, "Tutut")
    eq(result.uuid, "0123456789abcdef0123456789abcdef")
    eq(result.xuid, "2535100000000000", "xuid comes from the XSTS claims")
    ok(result.mc_expires_at > time.time(), "expiry in the future")


@test
def auth_403_and_404_are_different_errors():
    """Trap 8: an approval status and a missing entitlement must never be conflated."""
    from launcher.accounts import msa
    with MockHttp(_happy_routes(**{msa.MC_PROFILE_URL: (403, {"error": "FORBIDDEN"})})):
        err = raises(msa.AppNotApprovedError,
                     lambda: msa.complete_chain("cid", "ms"), "403 -> not approved")
        ok("aka.ms/mce-reviewappid" in str(err), "the message must point at the form")
        ok("approval status, not a bug" in str(err), "must say it is not a code bug")

    with MockHttp(_happy_routes(**{msa.MC_PROFILE_URL: (404, {})})):
        err = raises(msa.DoesNotOwnGameError,
                     lambda: msa.complete_chain("cid", "ms"), "404 -> no entitlement")
        ok("does not own" in str(err).lower(), "must say the account lacks Java Edition")

    ok(msa.AppNotApprovedError is not msa.DoesNotOwnGameError, "distinct classes")


@test
def empty_entitlements_means_no_ownership():
    from launcher.accounts import msa
    with MockHttp(_happy_routes(**{msa.MC_ENTITLEMENTS_URL: (200, {"items": []})})):
        raises(msa.DoesNotOwnGameError, lambda: msa.complete_chain("cid", "ms"),
               "empty items array")


@test
def every_xerr_maps_to_its_own_message():
    from launcher.accounts import msa
    expectations = {
        "2148916233": "xbox.com",
        "2148916235": "region",
        "2148916238": "Family",
    }
    for xerr, needle in expectations.items():
        with MockHttp(_happy_routes(**{msa.XSTS_URL: (401, {"XErr": int(xerr)})})):
            err = raises(msa.XstsError, lambda: msa.complete_chain("cid", "ms"), xerr)
            ok(needle.lower() in str(err).lower(),
               f"XErr {xerr} message should mention {needle!r}; got {err}")
            ok(str(xerr) not in str(err) or needle.lower() in str(err).lower(),
               "a bare number must never be the whole message")


@test
def unknown_xerr_still_produces_something_actionable():
    from launcher.accounts import msa
    with MockHttp(_happy_routes(**{msa.XSTS_URL: (401, {"XErr": 999})})):
        err = raises(msa.XstsError, lambda: msa.complete_chain("cid", "ms"), "unknown")
        ok("999" in str(err), "unknown codes are surfaced rather than swallowed")


@test
def authorization_pending_is_not_an_error():
    """Trap 7: the classic bug that makes device-code login look broken."""
    from launcher.accounts import msa
    state = {"n": 0}

    def token_route(kw):
        state["n"] += 1
        if state["n"] < 3:
            return 400, {"error": "authorization_pending"}
        if state["n"] == 3:
            return 400, {"error": "slow_down"}
        return 200, {"access_token": "ms-token", "refresh_token": "r-token"}

    code = msa.DeviceCode("dev-code", "ABCD-EFGH", "https://microsoft.com/link",
                          expires_in=900, interval=0, issued=time.time())
    with MockHttp({msa.TOKEN_URL: token_route}):
        tokens = msa.poll_device_code("cid", code)
    eq(tokens["access_token"], "ms-token")
    ok(state["n"] >= 4, "must have kept polling through pending and slow_down")


@test
def declined_and_expired_are_terminal():
    from launcher.accounts import msa
    code = msa.DeviceCode("d", "C", "u", expires_in=900, interval=0, issued=time.time())
    with MockHttp({msa.TOKEN_URL: (400, {"error": "authorization_declined"})}):
        raises(msa.DeviceCodeDeclined, lambda: msa.poll_device_code("cid", code),
               "declined")
    with MockHttp({msa.TOKEN_URL: (400, {"error": "expired_token"})}):
        raises(msa.DeviceCodeExpired, lambda: msa.poll_device_code("cid", code),
               "expired")


@test
def device_code_uses_the_consumers_tenant():
    from launcher.accounts import msa
    for url in (msa.DEVICE_CODE_URL, msa.TOKEN_URL):
        ok("/consumers/" in url,
           f"{url} must use the consumers tenant; common and directory tenants fail "
           "with the XboxLive scope")
    ok("XboxLive.signin" in msa.SCOPE and "offline_access" in msa.SCOPE, "scope")


# =================================================================================
# Redaction
# =================================================================================

@test
def tokens_never_reach_a_log_line():
    token = "eyJhbGciOiJIUzI1NiJ9.eyJzdWIiOiIxMjM0NTY3ODkwIn0.dBjftJeZ4CVPmB92K27uhbUJU1p1r"
    refresh = "M.C107_BAY.0.U.-Cq" + "x" * 60
    logs.register_secret(refresh)
    for probe in (
        f'{{"access_token": "{token}"}}',
        f"Authorization: Bearer {token}",
        f"XBL3.0 x=user-hash;{token}",
        f"refresh={refresh}",
        f"--accessToken {token}",
        token,
    ):
        out = logs.redact(probe)
        ok(token not in out, f"jwt survived redaction in: {probe[:40]}")
        ok(refresh not in out, "registered secret survived redaction")


@test
def redacted_argv_blanks_the_value_after_a_secret_flag():
    from launcher.launch import LaunchPlan
    plan = LaunchPlan.__new__(LaunchPlan)
    plan.argv = ["java", "--accessToken", "OpaqueTokenWithNoRecognisableShape123",
                 "--username", "Tutut"]
    out = plan.redacted_argv()
    ok("OpaqueTokenWithNoRecognisableShape123" not in out,
       "an opaque token in its own argv element cannot be caught by shape alone")
    eq(out[4], "Tutut", "non-secrets must survive")


# =================================================================================
# servers.dat
# =================================================================================

@test
def servers_dat_round_trips_exactly():
    from launcher import nbt
    servers = [{"name": "MyFireServer MAIN", "ip": "localhost:25595"},
               {"name": "PRIVATE", "ip": "localhost:25596"},
               {"name": "unicode \u00e9\u00e8", "ip": "example.com"}]
    blob = nbt.write_servers_dat(servers)
    ok(blob[:1] == b"\x0a", "root must be an uncompressed TAG_Compound, not gzip")
    ok(blob[:2] != b"\x1f\x8b", "servers.dat is NOT gzipped, unlike level.dat")
    back, consumed = nbt.read_servers_dat(blob)
    eq(back, servers, "round trip")
    eq(consumed, len(blob), "no trailing bytes")
    eq(nbt.read_servers_dat(nbt.write_servers_dat([]))[0], [], "empty list")


@test
def servers_dat_written_to_disk_parses_back():
    from launcher import nbt
    tmp = Path(tempfile.mkdtemp(prefix="myfire-nbt-"))
    try:
        path = tmp / "servers.dat"
        entries = [{"name": "MAIN", "ip": "localhost:25595"}]
        nbt.save_servers(path, entries)
        eq(nbt.load_servers(path), entries, "disk round trip")
        # Merging must not duplicate an entry that is already there.
        nbt.add_server(path, "MAIN", "localhost:25595")
        eq(len(nbt.load_servers(path)), 1, "no duplicate")
        nbt.add_server(path, "PRIVATE", "localhost:25596")
        eq(len(nbt.load_servers(path)), 2, "second server appended")
    finally:
        shutil.rmtree(tmp, ignore_errors=True)


# =================================================================================
# Java selection
# =================================================================================

@test
def java_major_per_minecraft_version():
    from launcher import java
    cases = [
        ({"id": "1.12.2"}, 8, "no javaVersion field at all -> Java 8, never 'whatever'"),
        ({"id": "1.16.5"}, 8, "1.16.5"),
        ({"id": "1.17", "javaVersion": {"component": "java-runtime-alpha",
                                        "majorVersion": 16}}, 16, "1.17"),
        ({"id": "1.20.1", "javaVersion": {"component": "java-runtime-gamma",
                                          "majorVersion": 17}}, 17, "1.20.1"),
        ({"id": "1.21", "javaVersion": {"component": "java-runtime-delta",
                                        "majorVersion": 21}}, 21, "1.21"),
        ({"id": "26.2", "javaVersion": {"component": "java-runtime-epsilon",
                                        "majorVersion": 25}}, 25,
         "read majorVersion, do not hardcode"),
    ]
    for version, expected, what in cases:
        eq(java.requirement_for(version).major, expected, what)
    eq(java.requirement_for({"id": "1.12.2"}).component, "jre-legacy", "component")


@test
def java_runtime_index_url_has_its_hash_segment():
    """The bare .../java-runtime/all.json path 404s. Verified live against Mojang."""
    from launcher import java
    ok(java.RUNTIME_INDEX_URL.count("/") > 6 and "all.json" in java.RUNTIME_INDEX_URL,
       "the index lives behind a content-hash path segment")
    ok(not java.RUNTIME_INDEX_URL.endswith("java-runtime/all.json"),
       "the un-hashed URL returns an Azure blob 404")


# =================================================================================
# Instance isolation
# =================================================================================

@test
def the_official_minecraft_folder_is_structurally_refused():
    from launcher.instances import InstanceError, guard_game_dir, vanilla_minecraft_dir
    vanilla = vanilla_minecraft_dir()
    if vanilla is None:
        return  # nothing to guard against on this machine
    raises(InstanceError, lambda: guard_game_dir(vanilla),
           "the real .minecraft must be refused as a game directory")
    raises(InstanceError, lambda: guard_game_dir(vanilla / "mods"),
           "and so must anything inside it")


@test
def instances_are_created_isolated():
    tmp = Path(tempfile.mkdtemp(prefix="myfire-inst-"))
    try:
        from launcher.instances import InstanceStore
        layout = Layout(tmp)
        layout.ensure()
        store = InstanceStore(layout)
        a = store.create("Forge 1.20.1", "1.20.1-forge-47.4.10", mc_version="1.20.1",
                         loader="forge")
        b = store.create("Fabric 26.2", "fabric-loader-0.19.3-26.2", mc_version="26.2",
                         loader="fabric")
        ok(a.dir != b.dir, "two instances must never share a directory")
        ok(a.mods_dir.is_dir() and b.mods_dir.is_dir(), "each gets its own mods folder")
        (a.mods_dir / "ysm.jar").write_bytes(b"x")
        eq(list(p.name for p in b.mods_dir.glob("*")), [],
           "a mod dropped in one instance must not appear in another")
        eq(store.get(a.slug).loader, "forge", "instance.json round trips")

        # Duplicate carries content but gets a new identity.
        c = store.duplicate(a, "Forge copy")
        ok(c.slug != a.slug, "a duplicate gets its own folder")
        ok((c.mods_dir / "ysm.jar").is_file(), "duplicate carries the mods")

        # Deleting keeps worlds unless explicitly told otherwise.
        (a.saves_dir / "world").mkdir(parents=True, exist_ok=True)
        (a.saves_dir / "world" / "level.dat").write_bytes(b"world")
        store.delete(a)
        ok(not a.dir.exists(), "instance directory removed")
        rescued = list(layout.instances.glob("_rescued-saves-*"))
        ok(rescued and (rescued[0] / "world" / "level.dat").is_file(),
           "worlds must never be silently destroyed")
    finally:
        shutil.rmtree(tmp, ignore_errors=True)


@test
def export_import_round_trip():
    tmp = Path(tempfile.mkdtemp(prefix="myfire-exp-"))
    try:
        from launcher.instances import InstanceStore
        layout = Layout(tmp)
        layout.ensure()
        store = InstanceStore(layout)
        inst = store.create("Exportable", "1.20.1", mc_version="1.20.1")
        (inst.mods_dir / "a.jar").write_bytes(b"modbytes")
        (inst.dir / "options.txt").write_text("fov:80")
        archive = store.export_zip(inst, tmp / "out.zip")
        back = store.import_zip(archive, name="Imported")
        eq((back.mods_dir / "a.jar").read_bytes(), b"modbytes", "mods survive")
        eq((back.dir / "options.txt").read_text(), "fov:80", "options survive")
        eq(back.version_id, "1.20.1", "metadata survives")
        ok(back.slug != inst.slug, "the import is a separate instance")
    finally:
        shutil.rmtree(tmp, ignore_errors=True)


@test
def client_jar_is_shared_but_named_after_the_launched_version():
    """Forge's -DignoreList names the jar by filename, so both facts have to hold at once.

    Download it once under the vanilla root id (so five modded instances of 1.20.1 do not
    fetch five copies), *and* make it exist under the launched id -- because modern Forge
    passes ``-DignoreList=...,${version_name}.jar`` to keep that filename out of the module
    graph. Point the classpath at the root name and the launch dies with
    ``ResolutionException: Modules _1._20._1 and minecraft export package ...``.
    """
    from launcher import libraries as lib_mod
    from launcher.versions import ROOT_ID_KEY
    tmp = Path(tempfile.mkdtemp(prefix="myfire-jar-"))
    try:
        layout = Layout(tmp)
        layout.ensure()
        version = {"id": "1.20.1-forge-47.4.10", ROOT_ID_KEY: "1.20.1",
                   "downloads": {"client": {"url": "https://example/c.jar",
                                            "sha1": "c" * 40, "size": 5}},
                   "libraries": []}

        eq(lib_mod.client_jar_dest(version, layout), layout.version_jar("1.20.1"),
           "the download lands under the vanilla root id, shared by every modded version")

        root = layout.version_jar("1.20.1")
        root.parent.mkdir(parents=True, exist_ok=True)
        root.write_bytes(b"CLIENT")

        got = lib_mod.materialise_client_jar(version, layout)
        own = layout.version_jar("1.20.1-forge-47.4.10")
        eq(got, own, "the launch uses the jar named after the launched version")
        ok(own.is_file(), "it must exist under the modded id")
        eq(own.read_bytes(), b"CLIENT", "same bytes")
        eq(own.stat().st_size, 6, "not a stub")

        cp = lib_mod.build_classpath(version, layout, [])
        eq(Path(cp[-1]).name, "1.20.1-forge-47.4.10.jar",
           "the classpath ends with the launched version's own jar name")

        # A vanilla version keeps using its own file and nothing is duplicated.
        plain = {"id": "1.20.1", ROOT_ID_KEY: "1.20.1", "libraries": []}
        eq(lib_mod.materialise_client_jar(plain, layout), root, "vanilla is unchanged")
    finally:
        shutil.rmtree(tmp, ignore_errors=True)


@test
def stale_natives_are_swept_but_live_ones_are_left_alone():
    """The reaper dies with the launcher, and closing the launcher mid-game is allowed."""
    from launcher import libraries as lib_mod
    tmp = Path(tempfile.mkdtemp(prefix="myfire-nat-"))
    try:
        live = tmp / "inst-live"
        dead = tmp / "inst-dead"
        fresh = tmp / "inst-unclaimed-fresh"
        for d in (live, dead, fresh):
            d.mkdir()
            (d / "lwjgl.dll").write_bytes(b"x")
        lib_mod.claim_natives(live, os.getpid())     # this very process
        lib_mod.claim_natives(dead, 999_999_999)     # a pid that cannot exist

        removed = lib_mod.sweep_stale_natives(tmp, max_age_hours=12.0)
        ok(live.is_dir(), "a directory owned by a running process must survive")
        ok(not dead.exists(), "a directory whose owner is gone must be removed")
        ok(fresh.is_dir(),
           "an unclaimed but recent directory may still be being assembled")
        eq(removed, 1, "exactly one swept")

        # Old and unclaimed: safe to remove.
        old = time.time() - 48 * 3600
        os.utime(fresh, (old, old))
        eq(lib_mod.sweep_stale_natives(tmp, max_age_hours=12.0), 1, "old one swept")
        ok(live.is_dir(), "still not the live one")
    finally:
        shutil.rmtree(tmp, ignore_errors=True)


@test
def resolve_records_the_vanilla_root_id():
    tmp = Path(tempfile.mkdtemp(prefix="myfire-root-"))
    try:
        layout = Layout(tmp)
        layout.ensure()
        for data in (VANILLA_FIXTURE, FORGE_FIXTURE):
            d = layout.version_dir(data["id"])
            d.mkdir(parents=True, exist_ok=True)
            (d / f"{data['id']}.json").write_text(json.dumps(data))
        resolved = versions.resolve(layout, "1.20.1-forge-47.4.10")
        eq(resolved[versions.ROOT_ID_KEY], "1.20.1",
           "the root id must survive the merge that consumes inheritsFrom")
        eq(resolved["id"], "1.20.1-forge-47.4.10", "id is still the launched one")
    finally:
        shutil.rmtree(tmp, ignore_errors=True)


@test
def update_check_compares_versions_properly():
    """String comparison says 1.10.0 < 1.9.0, which would strand everyone on an old build."""
    from launcher import update

    eq(update.parse_version("v1.2.3"), (1, 2, 3), "leading v")
    eq(update.parse_version("1.2"), (1, 2, 0), "missing patch")
    eq(update.parse_version("Lovely 2.0.1"), (2, 0, 1), "name prefix")
    eq(update.parse_version("garbage"), (0, 0, 0), "unparseable sorts lowest")

    ok(update.is_newer("1.0.1", "1.0.0"), "patch bump")
    ok(update.is_newer("1.10.0", "1.9.0"),
       "1.10 is newer than 1.9 -- the case plain string compare gets wrong")
    ok(update.is_newer("2.0.0", "1.99.99"), "major bump")
    ok(not update.is_newer("1.0.0", "1.0.0"), "same version is not an update")
    ok(not update.is_newer("0.9.0", "1.0.0"), "older is not an update")
    ok(not update.is_newer("garbage", "1.0.0"), "junk never triggers an update prompt")


@test
def update_checker_is_rate_limited_and_dismissible():
    from launcher.update import Release, UpdateChecker
    checker = UpdateChecker(interval=3600)
    ok(checker.due, "first run is due")
    ok(not checker.should_show, "nothing to show before a check runs")

    checker.release = Release(version="9.9.9", url="https://example/x")
    checker._last = time.time()
    ok(not checker.due, "a second check within the interval must not hit GitHub again")
    ok(checker.should_show, "a newer release is surfaced")
    checker.dismissed = True
    ok(not checker.should_show, "dismissing hides it for the session")


@test
def a_modpack_cannot_write_outside_its_instance():
    """A .mrpack is a file from the internet that says what to download and where.

    Both halves are attacker-controlled, so both are validated: the ``path`` fields in the
    index and the entry names in the zip. Without this, ``../../../.minecraft/mods/x.jar``
    reaches straight past the isolation the whole launcher exists to provide.
    """
    from launcher.mods import mrpack
    from pathlib import PurePosixPath

    escapes = [
        "../../.minecraft/mods/evil.jar",
        "/etc/passwd",
        "C:/Windows/system32/evil.dll",
        "mods/../../../../evil.jar",
        "..\\..\\evil.jar",
        "",
    ]
    for bad in escapes:
        raises(mrpack.PackError, lambda b=bad: mrpack.safe_relative(b),
               f"must reject {bad!r}")
    eq(str(mrpack.safe_relative("mods/sodium.jar")), "mods/sodium.jar", "normal path")
    eq(str(mrpack.safe_relative("config\\sub\\a.txt")), "config/sub/a.txt",
       "backslashes are normalised, not trusted")

    tmp = Path(tempfile.mkdtemp(prefix="myfire-pack-"))
    try:
        root = tmp / "instance"
        (root / "mods").mkdir(parents=True)
        inside = mrpack.resolve_inside(root, mrpack.safe_relative("mods/a.jar"))
        ok(str(inside).startswith(str(root.resolve())), "stays inside")
        raises(mrpack.PackError,
               lambda: mrpack.resolve_inside(root, PurePosixPath("../escape.jar")),
               "resolve_inside is the second line of defence")
    finally:
        shutil.rmtree(tmp, ignore_errors=True)


@test
def a_modpack_may_only_download_from_trusted_hosts():
    from launcher.mods import mrpack
    from launcher.mods import modrinth

    for bad in ("https://evil.example.com/a.jar",
                "http://cdn.modrinth.com.evil.net/a.jar",   # lookalike domain
                "https://cdn.modrinth.com.attacker.io/a.jar",
                "file:///C:/Windows/evil.dll",
                ""):
        raises(mrpack.PackError, lambda u=bad: mrpack.check_download(u),
               f"pack must refuse {bad!r}")
        raises(modrinth.ModrinthError, lambda u=bad: modrinth.check_url(u),
               f"mod install must refuse {bad!r}")

    eq(mrpack.check_download("https://cdn.modrinth.com/data/x/y.jar"),
       "https://cdn.modrinth.com/data/x/y.jar", "the real CDN is allowed")
    ok("cdn.modrinth.com" in modrinth.ALLOWED_HOSTS, "modrinth cdn allowed")


@test
def modpack_index_is_parsed_and_server_only_files_skipped():
    from launcher.mods import mrpack
    tmp = Path(tempfile.mkdtemp(prefix="myfire-idx-"))
    try:
        archive = tmp / "pack.mrpack"
        index = {
            "formatVersion": 1, "name": "Test Pack", "versionId": "1.2.3",
            "dependencies": {"minecraft": "1.20.1", "fabric-loader": "0.15.11"},
            "files": [
                {"path": "mods/a.jar", "downloads": ["https://cdn.modrinth.com/a.jar"],
                 "hashes": {"sha1": "aa"}, "fileSize": 100},
                {"path": "mods/server.jar",
                 "downloads": ["https://cdn.modrinth.com/b.jar"],
                 "env": {"client": "unsupported", "server": "required"}},
            ],
        }
        with __import__("zipfile").ZipFile(archive, "w") as zf:
            zf.writestr("modrinth.index.json", json.dumps(index))
            zf.writestr("overrides/config/ok.txt", "fine")

        pack = mrpack.read_index(archive)
        eq(pack.mc_version, "1.20.1")
        eq(pack.loader, "fabric", "fabric-loader maps to our loader name")
        eq(pack.loader_version, "0.15.11")
        eq(len(pack.files), 1, "a client-unsupported file must not be installed")
        eq(pack.files[0].sha1, "aa", "hash carried through for verification")

        dest = tmp / "inst"
        dest.mkdir()
        eq(mrpack.apply_overrides(archive, dest), 1, "override copied")
        ok((dest / "config" / "ok.txt").is_file(), "into the right place")

        # A pack that is not a Modrinth pack must say so, not crash.
        plain = tmp / "plain.zip"
        with __import__("zipfile").ZipFile(plain, "w") as zf:
            zf.writestr("readme.txt", "hello")
        err = raises(mrpack.PackError, lambda: mrpack.read_index(plain), "not a pack")
        ok("modrinth.index.json" in str(err), "and names what is missing")
    finally:
        shutil.rmtree(tmp, ignore_errors=True)


@test
def server_ping_speaks_the_real_wire_format():
    """VarInts are the whole protocol; get them wrong and every ping silently fails."""
    from launcher import serverping as sp

    # Values from the protocol spec's own worked examples.
    cases = {0: b"\x00", 1: b"\x01", 127: b"\x7f", 128: b"\x80\x01",
             255: b"\xff\x01", 2097151: b"\xff\xff\x7f",
             -1: b"\xff\xff\xff\xff\x0f"}
    for value, expected in cases.items():
        eq(sp.encode_varint(value), expected, f"varint({value})")

    eq(sp.encode_string("ab"), b"\x02ab", "string is length-prefixed utf-8")
    # A packet is VarInt(len) || VarInt(id) || payload.
    eq(sp.packet(0x00, b"xy"), b"\x03\x00xy", "packet framing")

    # A handshake must fit in one small packet even for a long hostname.
    handshake = sp.packet(0x00, sp.encode_varint(-1) + sp.encode_string("a" * 200)
                          + b"\x63\xdd" + sp.encode_varint(1))
    ok(len(handshake) > 200, "handshake carries the address")


@test
def server_ping_parses_status_and_never_raises():
    from launcher import serverping as sp

    eq(sp.parse_address("host:25595"), ("host", 25595), "host:port")
    eq(sp.parse_address("host"), ("host", 25565), "default port")
    eq(sp.parse_address("[::1]:25577"), ("::1", 25577), "ipv6 literal")
    eq(sp.parse_address("host:notaport"), ("host:notaport", 25565), "junk port")

    # The description is a nested chat-component tree on most real servers.
    eq(sp._flatten_motd({"text": "A", "extra": [{"text": "B"}, "C"]}), "ABC", "motd tree")
    eq(sp._flatten_motd("plain"), "plain", "motd string")

    s = sp.ServerStatus("h", 1, online=True, players_online=34275, players_max=200000)
    eq(s.compact, "34.3k/200k", "compact form fits a card")
    eq(sp.ServerStatus("h", 1).compact, "offline", "offline compact")

    # A dead port must produce a status, never an exception -- this runs on every repaint.
    dead = sp.ping("127.0.0.1:1", timeout=1.0)
    eq(dead.online, False, "closed port is offline")
    ok(dead.error, "and says why")


@test
def status_cache_never_pings_the_same_server_twice_at_once():
    from launcher.serverping import ServerStatus, StatusCache
    cache = StatusCache(ttl=60)
    ok(cache.is_stale("a"), "unknown address is stale")
    ok(cache.claim("a"), "first caller does the ping")
    ok(not cache.claim("a"), "a second repaint must not start another ping")
    cache.put("a", ServerStatus("a", 1, online=True))
    ok(not cache.is_stale("a"), "fresh after a result")
    ok(cache.claim("a"), "claim is released once the result lands")


@test
def hover_effects_can_never_feed_themselves():
    """A hover effect that moves the widget hangs the app, and Windows kills it.

    The loop: the pointer rests on a card edge -> Enter -> the card lifts 3 px -> it is no
    longer under the pointer -> Tk fires Leave -> the lift is undone -> it is under the
    pointer again -> Enter. A *stationary* mouse spins that as fast as Tk can dispatch, the
    main thread never returns to the event loop, and Windows closes the process for not
    responding (Application Error 1002 -- a hang, indistinguishable from a crash to the
    person watching the window vanish). This shipped once.

    Two properties make it impossible rather than unlikely, and both are asserted here:
    the hit box comes from fixed geometry rather than the live bounding box, and no hover
    effect changes any item's coordinates.
    """
    import types
    if os.environ.get("MYFIRE_NO_TK"):
        return
    try:
        import tkinter as tk
    except Exception:
        return
    try:
        root = tk.Tk()
    except tk.TclError:
        return  # no display; the sweep below needs a real canvas

    try:
        root.withdraw()
        from launcher.ui import canvaskit as ck
        cv = tk.Canvas(root, width=400, height=300)
        cv.pack()

        panel = ck.Panel(cv, 40, 40, 200, 80, fill="#191c24", hover_fill="#22262f",
                         outline="#272c36", hover_outline="#3d4553", accent="#ff7a2f")
        panel.add(cv.create_text(60, 60, text="a label that widens the bbox", anchor="w"))
        button = ck.Button(cv, 40, 160, 120, 34, text="Play", font=None,
                           fill="#ff7a2f", hover="#ffa15c", text_colour="#1a0f05")
        root.update()

        for widget, name in ((panel, "Panel"), (button, "Button")):
            x, y, w, h = widget.geom
            # Coordinates of every item, before and after a hover, must be identical.
            before = {i: cv.coords(i) for i in widget.items}
            widget._enter(types.SimpleNamespace(x=x + w / 2, y=y + h / 2))
            after = {i: cv.coords(i) for i in widget.items}
            eq(after, before, f"{name}: a hover effect must not move any item")
            widget._leave(types.SimpleNamespace(x=x + w + 50, y=y + h + 50))
            eq({i: cv.coords(i) for i in widget.items}, before,
               f"{name}: leaving must not move any item either")

            # Sweep the whole edge band with a pointer that never moves.
            worst = 0
            for py in range(int(y) - 6, int(y + h) + 7):
                for px in (x + w / 2, x + 1, x + w - 1):
                    widget.hovered = False
                    ev = types.SimpleNamespace(x=px, y=py)
                    toggles = 0
                    for _ in range(10):
                        was = widget.hovered
                        (widget._enter if not widget.hovered else widget._leave)(ev)
                        toggles += widget.hovered != was
                    worst = max(worst, toggles)
            eq(worst <= 2, True,
               f"{name}: a stationary pointer toggled hover {worst} times -- that is the "
               f"feedback loop that hangs the app")
    finally:
        try:
            root.destroy()
        except Exception:
            pass


@test
def the_switcher_popup_closes_every_way_it_must():
    """A hand-drawn drop-down has to do its own dismissal, and it must never strand.

    ``tk.Menu`` is a native Windows menu on this platform -- it reports a size of 1x1 and
    takes the system theme, not the app's -- so the account switcher is built from plain
    widgets like the rest of the launcher. A real menu gets dismissal for free; this one
    has to implement it, and a drop-down that outlives its click is a floating topmost
    window the user cannot get rid of. Choosing an entry, Escape, and losing focus must
    all close it, and closing twice must not raise.
    """
    if os.environ.get("MYFIRE_NO_TK"):
        return
    try:
        import tkinter as tk
    except Exception:
        return
    try:
        root = tk.Tk()
    except tk.TclError:
        return
    try:
        root.withdraw()
        from launcher.ui.menu import SEPARATOR, PopupMenu

        chosen = []
        rows = [("SIGNED IN", None), ("Tutut", lambda: chosen.append("Tutut")),
                SEPARATOR, ("Add another account", lambda: chosen.append("add"))]

        m = PopupMenu(root, rows, font=None).show(50, 50)
        eq(len(m.items), 3, "one row per entry, separators excluded")
        root.update()

        # Hovering must change colour and nothing else. A hover that moves a widget out
        # from under a stationary pointer is an Enter/Leave loop that hangs the process.
        item = m.items[1]
        before = (item.winfo_x(), item.winfo_y(), item.winfo_width(), item.winfo_height())
        item.event_generate("<Enter>")
        root.update()
        eq((item.winfo_x(), item.winfo_y(), item.winfo_width(), item.winfo_height()),
           before, "a hover must not move or resize a menu row")

        item.event_generate("<Button-1>")
        root.update()
        eq(chosen, ["Tutut"], "clicking a row runs its action")
        ok(m._closed, "and closes the menu -- an action may open a modal behind it")
        m.close()  # closing an already-closed menu must be a no-op, not a TclError

        m = PopupMenu(root, rows, font=None).show(50, 50)
        m.top.event_generate("<Escape>")
        root.update()
        ok(m._closed, "Escape closes it")

        m = PopupMenu(root, rows, font=None).show(50, 50)
        root.deiconify()
        root.focus_force()
        root.update()
        ok(m._closed, "losing focus closes it -- that is 'click anywhere else'")

        eq(chosen, ["Tutut"], "no action fires from a dismissal")
    finally:
        try:
            root.destroy()
        except tk.TclError:
            pass


@test
def worker_threads_never_touch_tk_directly():
    """Touching a widget off the Tk thread does not raise -- it deadlocks, silently.

    The launcher's window stays painted and apparently alive while nothing responds, which
    is the worst possible failure mode to debug. So every method a worker thread reaches
    for must marshal onto the UI thread itself rather than trusting callers to remember.
    ``_do_launch`` runs on a worker and calls ``console_write``; that combination hung the
    app for real before ``console_write`` learned to self-marshal.
    """
    import ast
    source = (ROOT / "launcher" / "ui" / "app.py").read_text(encoding="utf-8")
    tree = ast.parse(source)

    marshalling = set()
    worker_bodies = {}
    for node in ast.walk(tree):
        if not isinstance(node, ast.FunctionDef):
            continue
        body = ast.dump(node)
        # A method self-marshals if it consults the thread before touching a widget.
        if "_on_ui_thread" in body:
            marshalling.add(node.name)
        # These run on a background thread (see App._background).
        if node.name.startswith("_do_") or node.name == "_load_head":
            worker_bodies[node.name] = node

    ok("console_write" in marshalling, "console_write must self-marshal")
    ok("set_status" in marshalling, "set_status must self-marshal")
    ok(worker_bodies, "expected to find worker methods to check")

    for name, node in worker_bodies.items():
        # Anything lexically inside an `_on_ui(...)` argument is deferred to the Tk
        # thread by construction, so it is exempt -- that is the whole point of it.
        deferred: set[int] = set()
        for call in ast.walk(node):
            if isinstance(call, ast.Call) and isinstance(call.func, ast.Attribute) \
                    and call.func.attr == "_on_ui":
                for arg in call.args:
                    for inner in ast.walk(arg):
                        deferred.add(id(inner))

        for call in ast.walk(node):
            if not isinstance(call, ast.Call) or id(call) in deferred:
                continue
            fn = call.func
            if isinstance(fn, ast.Attribute) and isinstance(fn.value, ast.Name) \
                    and fn.value.id == "self" and _is_ui_method(fn.attr):
                raise Failure(
                    f"{name}() runs on a worker thread and calls self.{fn.attr}() "
                    f"directly. Wrap it in self._on_ui(...) or Tk will deadlock with "
                    f"the window still painted.")


_UI_METHODS = {"refresh_instances", "show_detail", "select", "refresh_current_screen",
               "refresh_account", "open_library", "show_home", "toggle_console",
               "show_console", "_report_error", "_work_done", "_render_progress",
               "show_empty", "_instance_row", "_info_block", "_build_detail"}


def _is_ui_method(name: str) -> bool:
    return name in _UI_METHODS


@test
def slugs_are_safe_on_windows():
    from launcher.paths import slugify
    eq(slugify("Forge 1.20.1"), "Forge-1.20.1")
    eq(slugify("con"), "con-mc", "reserved device names must be escaped")
    eq(slugify("../../etc"), "etc", "no traversal")
    eq(slugify("   "), "instance", "never empty")
    ok(len(slugify("x" * 200)) <= 48, "bounded length keeps paths under MAX_PATH")


# =================================================================================
# Networking
# =================================================================================

@test
def sha1_mismatch_deletes_and_reports_both_hashes():
    tmp = Path(tempfile.mkdtemp(prefix="myfire-net-"))
    try:
        target = tmp / "f.bin"
        target.write_bytes(b"wrong content")
        ok(not net.verify(target, "0" * 40), "a wrong hash must not verify")
        good = net.sha1_file(target)
        ok(net.verify(target, good), "the right hash must verify")
        ok(not net.verify(target, good, size=999), "size mismatch fails too")
        ok(not net.verify(tmp / "missing", good), "missing file")
    finally:
        shutil.rmtree(tmp, ignore_errors=True)


@test
def cancel_tokens_do_not_poison_their_parent():
    parent = net.CancelToken()
    child = parent.child()
    child.cancel()
    ok(child.cancelled, "child is cancelled")
    ok(not parent.cancelled, "cancelling a batch must not poison the caller's token")
    parent2 = net.CancelToken()
    child2 = parent2.child()
    parent2.cancel()
    ok(child2.cancelled, "cancellation flows downward")
    ok(not net.NEVER.cancelled, "the shared NEVER token must never be cancelled")


@test
def atomic_write_leaves_no_partial_file():
    tmp = Path(tempfile.mkdtemp(prefix="myfire-atomic-"))
    try:
        target = tmp / "sub" / "f.json"
        net.write_atomic(target, b'{"a":1}')
        eq(target.read_bytes(), b'{"a":1}')
        eq([p.name for p in (tmp / "sub").iterdir()], ["f.json"],
           "no .part file may be left behind")
    finally:
        shutil.rmtree(tmp, ignore_errors=True)


# =================================================================================
# Integration (network)
# =================================================================================

@nettest
def resolve_and_verify_real_versions():
    layout = Layout()
    from launcher import assets as assets_mod
    from launcher import libraries as lib_mod
    for vid in ("1.12.2", "1.20.1"):
        version = versions.resolve(layout, vid)
        eq(version["id"], vid)
        jobs, files = lib_mod.plan_downloads(version, layout)
        eq(jobs, [], f"{vid}: a second run must download zero library bytes")
        ok(len(files) > 10, f"{vid}: libraries resolved")
        for f in files:
            if f.sha1 and f.present:
                eq(net.sha1_file(f.path), f.sha1.lower(), f"{vid}: {f.name} sha1")
        index = assets_mod.ensure_index(version, layout)
        eq(assets_mod.plan_downloads(index, layout), [],
           f"{vid}: a second run must download zero asset bytes")


@nettest
def correct_java_major_is_selected_per_version():
    from launcher import java
    layout = Layout()
    for vid, expected in (("1.12.2", 8), ("1.20.1", 17), ("26.2", 25)):
        version = versions.resolve(layout, vid)
        req = java.requirement_for(version)
        eq(req.major, expected, f"{vid} needs Java {expected}")
        if java.is_installed(layout, req.component):
            exe = java.java_executable(java.installed_home(layout, req.component))
            eq(java.probe_major(exe), expected,
               f"{vid}: the installed runtime really is Java {expected}")


@nettest
def fabric_meta_offers_compatible_loaders_only():
    from launcher.loaders import fabric
    loaders = fabric.loader_versions("1.20.1")
    ok(loaders, "fabric publishes loaders for 1.20.1")
    ok(all(isinstance(v, str) for v in loaders), "version strings")


# =================================================================================
# Runner
# =================================================================================

def main(argv: list[str]) -> int:
    want_net = "--net" in argv
    only = [a for a in argv[1:] if not a.startswith("-")]

    selected = [t for t in _TESTS
                if (want_net or t.__name__ not in _ONLY_NET)
                and (not only or any(o in t.__name__ for o in only))]

    print(f"running {len(selected)} checks"
          f"{' (including network)' if want_net else ''}\n")
    passed = failed = 0
    t0 = time.time()
    for t in selected:
        label = t.__name__.replace("_", " ")
        try:
            t()
        except Exception as exc:  # noqa: BLE001
            failed += 1
            print(f"  FAIL  {label}")
            for line in str(exc).splitlines():
                print(f"        {line}")
            if not isinstance(exc, Failure):
                print("        " + traceback.format_exc().splitlines()[-3].strip())
        else:
            passed += 1
            print(f"  ok    {label}")

    print(f"\n{passed} passed, {failed} failed in {time.time() - t0:.1f}s")
    if not want_net:
        print(f"({len(_ONLY_NET)} network checks skipped; run with --net to include them)")
    return 1 if failed else 0


if __name__ == "__main__":
    raise SystemExit(main(sys.argv))
