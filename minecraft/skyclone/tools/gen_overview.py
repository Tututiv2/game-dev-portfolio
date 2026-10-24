"""Builds SKYCLONE.html: the owner's one-page view of the game.

    py -3 tools/gen_overview.py        (from the SkyblockClone folder; re-run any time)

Reads the live tree - data counts, island previews, every docs/names table, every hand-off's
"partial" section - so the page never drifts from what was built. Stdlib only.
"""
import glob
import html
import json
import os
import re

ROOT = os.path.dirname(os.path.dirname(os.path.abspath(__file__)))
OUT = os.path.join(ROOT, "SKYCLONE.html")


def read(path):
    with open(path, encoding="utf-8") as f:
        return f.read()


def count(kind):
    n = 0
    for f in glob.glob(os.path.join(ROOT, "plugin/src/main/resources/data/*/%s*.json" % kind)):
        try:
            d = json.loads(read(f))
            if isinstance(d, list):
                n += len(d)
        except Exception:
            pass
    return n


ISLANDS = [
    ("hub", "Hub", "The town: village, bank, bazaar, auction house, farm, mountain + coal mine, graveyard + crypt, forest, colosseum, portal plaza."),
    ("private_island", "Private Island", "Your own floating island. Build, place minions, meet your island helper."),
    ("barn", "The Barn", "Wheat, carrot, potato, pumpkin, melon and cane fields."),
    ("mushroom_desert", "Mushroom Desert", "Mushrooms, cactus, cocoa and nether wart."),
    ("park", "The Park", "Five forests of harvestable trees and the Howling Cave."),
    ("galatea", "Moonmarsh", "The newer foraging marsh."),
    ("lotus_atoll", "Lotus Atoll", "Outer island: trophy frogs, the frog ledger, the wishing pool and the Lotus Eater."),
    ("torrhus_canyon", "Torrent Canyon", "Outer island: the Canyon Journal, Torrent Springs fishing, the helix grove."),
    ("critter_safari", "Critter Safari", "Hunting grounds: lasso, nets and traps for attribute-shard critters."),
    ("gold_mine", "Gold Mine", "First mining island."),
    ("deep_caverns", "Deep Caverns", "Six descending mining layers."),
    ("dwarven_mines", "Dwarven Mines", "The underground city: commissions, the forge, HOTM."),
    ("crystal_hollows", "Crystal Caverns", "Huge procedural cave world with gemstones and the Nucleus."),
    ("glacite_tunnels", "Frozen Tunnels", "Icy tunnels below the mines."),
    ("mineshaft", "Mineshaft", "Instanced mineshaft."),
    ("spiders_den", "Spider's Den", "Webs, caves and the spider queen's altar."),
    ("the_end", "The End", "Zealots, endermen and the dragon altar."),
    ("crimson_isle", "Crimson Isle", "Two factions, the dojo, mini-bosses and lava fishing."),
    ("kuudra", "Leviathan's Hollow", "The Magma Leviathan fight."),
    ("backwater_bayou", "Backwater Bayou", "The fishing hub."),
    ("garden", "The Garden", "Your own farm: plots, visitors, composter, pests."),
    ("jerrys_workshop", "Winter Workshop", "The seasonal winter island."),
    ("dungeon_hub", "Dungeon Hub", "Party finder, classes, the catacombs gate."),
    ("catacombs_sample", "Catacombs (one generated run)", "A dungeon floor, generated fresh every run."),
    ("boss_arena", "Boss arenas", "Dungeon boss rooms."),
    ("rift", "The Rift", "A strange dimension with its own clock and currency."),
]

MODULES = [
    ("Progression", [
        ("skills", "11 skills with the real XP curves, level rewards, skills menu"),
        ("collections", "90 collections, 790 tiers, recipe unlocks"),
        ("progression", "SkyBlock level, guided objectives, fairy souls, bestiary, museum"),
        ("crafting", "3x3 crafting menu with stack amounts, recipe book, quick-craft"),
        ("minions", "~60 minions, tiers I-XII, fuel, upgrades, offline production"),
        ("menus", "SkyBlock Menu, profile viewer, fast travel, calendar, settings"),
    ]),
    ("Gear", [
        ("materials", "the catalogue of materials and enchanted compressions"),
        ("armor", "armor sets with full-set bonuses"),
        ("weapons", "swords, bows, wands and their abilities"),
        ("enchanting", "every enchant implemented, enchanting table, anvil, experiments"),
        ("reforges", "reforges + stones, recombobulator, potato books, stars, gemstones"),
        ("pets", "pets with levels, abilities, pet items, the pet sitter"),
        ("accessories", "accessory bag, magical power, power stones, equipment slots"),
        ("alchemy", "brewing, potions, active effects"),
        ("storage", "ender chest, backpacks, wardrobe, sacks, quiver"),
    ]),
    ("Economy", [
        ("bank", "bank tiers + interest, secure /trade, booster cookie, community shop"),
        ("bazaar", "order book with buy orders/sell offers + NPC market makers"),
        ("auction", "auction house with BIN, bids, escrow, claims"),
        ("shops", "every NPC merchant, sell + buyback"),
        ("mayors", "elections, mayor perks, festivals, dark auction"),
    ]),
    ("Gathering & combat (your priorities)", [
        ("slayer", "six slayer bosses, every tier, RNG meter, slayer gear"),
        ("mining", "custom block breaking, HOTM tree, powders, commissions, forge, drills, gems"),
        ("farming", "crop fortune + replant, farming tools, contests, barn + desert islands"),
        ("garden", "plots, visitors, copper, composter, pests, crop milestones"),
        ("fishing", "sea creatures, trophy fish, rods + parts, baits, the bayou"),
        ("foraging", "trees + treecapitator, the Park, Moonmarsh"),
    ]),
    ("Worlds & endgame", [
        ("island", "private island, co-op, visiting"),
        ("hub", "the hub town + its mobs"),
        ("mines", "gold mine, deep caverns, dwarven mines"),
        ("hollows", "crystal caverns + frozen tunnels"),
        ("combatzones", "spider's den, the End, the dragon fight"),
        ("crimson", "crimson isle, factions, dojo, mini-bosses"),
        ("dungeons", "catacombs F1-F7 + master mode, classes, puzzles, bosses, chests"),
        ("kuudra", "the Magma Leviathan, tiers, supplies, ballista"),
        ("rift", "the Rift: time drain, motes, enigma souls"),
    ]),
    ("Wave 5", [
        ("hunting", "Hunting skill, 322 attribute shards, lasso/net/trap, fusion, the Critter Safari"),
        ("galatea", "the outer islands: Lotus Atoll frogs and Torrent Canyon"),
        ("chocolate", "the Chocolate Factory, rabbits, prestige, Hopsworth's egg hunt"),
        ("extras", "Ringphone contacts, City Projects, Fire Sales, skins, the harp"),
        ("soulflow", "Soulstream: internalize, Overflow, owner-gated drains"),
    ]),
]


def section(md, title_prefix):
    """Text of a '## title...' section of a hand-off (until the next '## ')."""
    m = re.search(r"^## " + re.escape(title_prefix) + r".*?$\n(.*?)(?=^## |\Z)", md, re.M | re.S)
    return m.group(1).strip() if m else ""


def md_inline(s):
    s = html.escape(s)
    s = re.sub(r"`([^`]+)`", r"<code>\1</code>", s)
    s = re.sub(r"\*\*([^*]+)\*\*", r"<b>\1</b>", s)
    return s


def partial_html(module):
    p = os.path.join(ROOT, "docs/handoff/%s.md" % module)
    if not os.path.exists(p):
        return "<p class=dim>No hand-off file.</p>"
    text = section(read(p), "What is partial")
    if not text:
        return "<p class=dim>Nothing listed as partial.</p>"
    items = [l.strip()[2:] for l in text.splitlines() if l.strip().startswith(("- ", "* "))]
    if not items:
        return "<p>" + md_inline(text[:1500]) + "</p>"
    return "<ul>" + "".join("<li>%s</li>" % md_inline(i) for i in items[:25]) + "</ul>"


def names():
    rows = []
    for f in sorted(glob.glob(os.path.join(ROOT, "docs/names/*.md"))):
        mod = os.path.splitext(os.path.basename(f))[0]
        for line in read(f).splitlines():
            if not line.startswith("|") or line.startswith("|---") or line.startswith("| id") or line.startswith("|--"):
                continue
            cells = [c.strip() for c in line.strip().strip("|").split("|")]
            if len(cells) < 3:
                continue
            rid, ref, ours = cells[0].strip("`"), cells[1], cells[2]
            if not ours or ours.lower() in ("our name", "---"):
                continue
            rows.append({"m": mod, "id": rid, "ref": ref, "ours": ours})
    return rows


def main():
    items, mobs, recipes = count("items"), count("mobs"), count("recipes")
    mods = sum(len(g[1]) for g in MODULES)
    name_rows = names()

    isl = []
    for iid, name, desc in ISLANDS:
        img = "previews/%s/iso.png" % iid
        if not os.path.exists(os.path.join(ROOT, img)):
            continue
        isl.append('<figure class=card><a href="%s" target=_blank><img loading=lazy src="%s" alt="%s"></a>'
                   '<figcaption><b>%s</b><span>%s</span></figcaption></figure>' % (img, img, html.escape(name), html.escape(name), html.escape(desc)))

    sysg = []
    for group, entries in MODULES:
        cards = []
        for mid, line in entries:
            cards.append('<details class=mod><summary><b>%s</b><span>%s</span></summary>'
                         '<div class=partial><h4>Not finished / simplified (from its hand-off)</h4>%s</div></details>'
                         % (mid, html.escape(line), partial_html(mid)))
        sysg.append("<h3>%s</h3><div class=mods>%s</div>" % (html.escape(group), "".join(cards)))

    page = TEMPLATE
    page = page.replace("{{STATS}}", "".join(
        '<div class=stat><b>%s</b><span>%s</span></div>' % (v, k) for k, v in [
            ("modules", mods), ("items", "{:,}".format(items)), ("mobs", "{:,}".format(mobs)),
            ("recipes", "{:,}".format(recipes)), ("islands", len(isl)), ("names to review", "{:,}".format(len(name_rows)))]))
    page = page.replace("{{ISLANDS}}", "".join(isl))
    page = page.replace("{{SYSTEMS}}", "".join(sysg))
    page = page.replace("{{NAMES_JSON}}", json.dumps(name_rows, ensure_ascii=False).replace("</", "<\\/"))
    with open(OUT, "w", encoding="utf-8", newline="\n") as f:
        f.write(page)
    print("wrote", OUT, "-", len(name_rows), "names,", len(isl), "islands")


TEMPLATE = r"""<!doctype html>
<html lang=en><head><meta charset=utf-8><meta name=viewport content="width=device-width,initial-scale=1">
<title>SkyClone Overview</title>
<style>
:root{--bg:#0f1218;--panel:#171c25;--line:#273042;--text:#e6e9ef;--dim:#8d97a8;--gold:#f5c542;--green:#5fd38d;--red:#ef6b6b;--aqua:#5cc8e8}
@media (prefers-color-scheme: light){:root:not([data-theme=dark]){--bg:#f4f5f8;--panel:#fff;--line:#d8dce4;--text:#1b2030;--dim:#5d6678;--gold:#a87700;--green:#1f8a4c;--red:#c43c3c;--aqua:#0d7fa3}}
*{box-sizing:border-box}body{margin:0;background:var(--bg);color:var(--text);font:15px/1.5 system-ui,Segoe UI,sans-serif}
header{padding:28px 16px 8px;max-width:1200px;margin:auto}h1{margin:0;font-size:30px;letter-spacing:.5px}h1 small{color:var(--dim);font-weight:400;font-size:15px}
main{max-width:1200px;margin:auto;padding:0 16px 60px}h2{margin:36px 0 12px;font-size:21px;border-bottom:1px solid var(--line);padding-bottom:6px}h3{margin:22px 0 8px;font-size:16px;color:var(--gold)}
.stats{display:flex;flex-wrap:wrap;gap:10px;margin-top:16px}.stat{background:var(--panel);border:1px solid var(--line);border-radius:10px;padding:10px 16px;min-width:120px}.stat b{display:block;font-size:24px;color:var(--gold)}.stat span{color:var(--dim);font-size:13px}
.play{background:var(--panel);border:1px solid var(--line);border-radius:10px;padding:14px 18px}.play ol{margin:6px 0;padding-left:20px}code{background:rgba(127,127,127,.15);padding:1px 5px;border-radius:4px}
.grid{display:grid;grid-template-columns:repeat(auto-fill,minmax(270px,1fr));gap:12px}.card{margin:0;background:var(--panel);border:1px solid var(--line);border-radius:10px;overflow:hidden}.card img{width:100%;aspect-ratio:16/10;object-fit:cover;display:block;background:#0b0e14}.card figcaption{padding:8px 12px}.card figcaption b{display:block}.card figcaption span{color:var(--dim);font-size:13px}
.mods{display:grid;grid-template-columns:repeat(auto-fill,minmax(340px,1fr));gap:8px}.mod{background:var(--panel);border:1px solid var(--line);border-radius:8px;padding:8px 12px}.mod summary{cursor:pointer;list-style:none}.mod summary b{color:var(--aqua);margin-right:8px}.mod summary span{color:var(--dim);font-size:14px}.partial h4{margin:10px 0 4px;font-size:13px;color:var(--red)}.partial ul{margin:0;padding-left:18px;font-size:13px}.dim{color:var(--dim)}
.tools{display:flex;flex-wrap:wrap;gap:8px;align-items:center;margin-bottom:10px}input,select,button{font:inherit;color:var(--text);background:var(--panel);border:1px solid var(--line);border-radius:8px;padding:7px 10px}input{flex:1;min-width:200px}button{cursor:pointer}button.primary{background:var(--gold);color:#111;border-color:var(--gold);font-weight:600}
.tablewrap{overflow-x:auto;border:1px solid var(--line);border-radius:10px}table{border-collapse:collapse;width:100%;font-size:14px}th,td{text-align:left;padding:6px 10px;border-bottom:1px solid var(--line);vertical-align:top}th{position:sticky;top:0;background:var(--panel)}tr.row{cursor:pointer}tr.row:hover{background:rgba(127,127,127,.08)}tr.veto td{background:rgba(239,107,107,.14)}tr.veto td.ours{text-decoration:line-through;color:var(--red)}td.mod{color:var(--dim)}td.id{font-family:ui-monospace,Consolas,monospace;font-size:12px;color:var(--dim)}
#count{color:var(--dim);font-size:13px}#toast{position:fixed;bottom:16px;left:50%;transform:translateX(-50%);background:var(--green);color:#111;padding:8px 14px;border-radius:8px;display:none;font-weight:600}
</style></head><body>
<header><h1>SkyClone <small>working title · a full SkyBlock-style game as one Paper plugin</small></h1>
<div class=stats>{{STATS}}</div></header>
<main>
<h2>Play it</h2>
<div class=play><ol>
<li>Double-click <code>Dev Server.bat</code> in the SkyblockClone folder (or ask Claude to start it).</li>
<li>In Minecraft 26.2 (26.1.x and 26.3 also work, via ViaVersion): Multiplayer → Direct Connect → <code>localhost:25630</code>.</li>
<li>You land on your private island. The sidebar <b>Objective</b> walks you through the early game; the Nether Star in slot 9 is the SkyBlock Menu for everything else.</li>
<li>Admin tools (once op'd): <code>/sb help</code>, <code>/sb give &lt;player&gt; &lt;item&gt;</code>, <code>/sb selftest</code>, <code>/commands</code> lists every command.</li>
</ol></div>

<h2>Islands</h2>
<p class=dim>Every island is generated by code. These are the builders' own preview renders (click to enlarge).</p>
<div class=grid>{{ISLANDS}}</div>

<h2>Systems</h2>
<p class=dim>Click a system to see what its builder said is still unfinished or simplified.</p>
{{SYSTEMS}}

<h2>Names to review</h2>
<p class=dim>The reference game's proper nouns were renamed so nothing is copied. These are the agents' picks — <b>nothing here is final until you approve it</b>. Click any row you don't like (it turns red), then press <b>Copy my vetoes</b> and paste the list to Claude. Your picks are remembered in this browser.</p>
<div class=tools><input id=q placeholder="Search names, ids, modules..."><select id=mf><option value="">all modules</option></select><select id=vf><option value="">all</option><option value=veto>vetoed only</option></select><button class=primary id=copy>Copy my vetoes</button><button id=clear>Clear vetoes</button><span id=count></span></div>
<div class=tablewrap><table><thead><tr><th>module</th><th>reference name</th><th>our name</th><th>id</th></tr></thead><tbody id=tb></tbody></table></div>
</main><div id=toast>Copied!</div>
<script>
const ROWS={{NAMES_JSON}};
const KEY='skyclone-vetoes';
let vetoes=new Set();try{vetoes=new Set(JSON.parse(localStorage.getItem(KEY)||'[]'))}catch(e){}
const save=()=>{try{localStorage.setItem(KEY,JSON.stringify([...vetoes]))}catch(e){}};
const k=r=>r.m+'|'+r.id;
const mf=document.getElementById('mf'),q=document.getElementById('q'),vf=document.getElementById('vf'),tb=document.getElementById('tb'),cnt=document.getElementById('count');
[...new Set(ROWS.map(r=>r.m))].sort().forEach(m=>{const o=document.createElement('option');o.value=o.textContent=m;mf.appendChild(o)});
const esc=s=>String(s).replace(/[&<>"]/g,c=>({'&':'&amp;','<':'&lt;','>':'&gt;','"':'&quot;'}[c]));
function render(){const t=q.value.trim().toLowerCase(),m=mf.value,v=vf.value;let shown=0,html='';
 for(let i=0;i<ROWS.length;i++){const r=ROWS[i];if(m&&r.m!==m)continue;if(v&&!vetoes.has(k(r)))continue;
  if(t&&!(r.ours+' '+r.ref+' '+r.id+' '+r.m).toLowerCase().includes(t))continue;
  if(++shown>1500)continue;
  html+='<tr class="row'+(vetoes.has(k(r))?' veto':'')+'" data-i="'+i+'"><td class=mod>'+esc(r.m)+'</td><td>'+esc(r.ref)+'</td><td class=ours>'+esc(r.ours)+'</td><td class=id>'+esc(r.id)+'</td></tr>'}
 tb.innerHTML=html;cnt.textContent=shown+' shown'+(shown>1500?' (first 1500 drawn - search to narrow)':'')+' · '+vetoes.size+' vetoed'}
tb.addEventListener('click',e=>{const tr=e.target.closest('tr.row');if(!tr)return;const r=ROWS[+tr.dataset.i];const kk=k(r);vetoes.has(kk)?vetoes.delete(kk):vetoes.add(kk);save();render()});
[q,mf,vf].forEach(el=>el.addEventListener('input',render));
document.getElementById('copy').addEventListener('click',async()=>{const lines=ROWS.filter(r=>vetoes.has(k(r))).map(r=>'VETO '+r.m+' '+r.id+' : '+r.ours+' (was '+r.ref+')');
 const text=lines.length?lines.join('\n'):'(no vetoes - all names approved)';try{await navigator.clipboard.writeText(text)}catch(e){prompt('Copy this:',text)}
 const t=document.getElementById('toast');t.style.display='block';setTimeout(()=>t.style.display='none',1400)});
document.getElementById('clear').addEventListener('click',()=>{vetoes.clear();save();render()});
render();
</script></body></html>
"""

if __name__ == "__main__":
    main()
