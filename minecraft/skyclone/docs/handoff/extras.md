# extras — hand-off

New module (wave 5). Package `com.skyclone.mod.extras`, entry **`ExtrasModule`** (the integrator adds it to
`core.ModuleList`). Data in `plugin/src/main/resources/data/extras/` is GENERATED: `py -3 tools/gen/extras/gen_extras.py`
(reads neu-repo items + `constants/abiphone.json` + `constants/sblevels.json`, research `items.json`, and every other
module's registered item ids; prints anything it had to drop). Renames: `docs/names/extras.md` (generated).

## Wave 5 (2026-10-06)

### Done
| System | What | Verified |
|---|---|---|
| **Harp** (research 08 §6.3, the reference's Melody's Harp) | `/harp` (song list) at the harpist NPC (`extras.harp.npc` = `foraging_harpist`, foraging's Lyra, within `near-npc-blocks` 12; anywhere if that NPC is not registered). 13 songs: 6-row menu, 7 lanes, notes fall one row per beat (rows 0-4), struck on the quartz key row (or the note itself) when they reach the bottom row; the key turns to the lane's wool when it is time. Misses + wrong notes are mistakes; `max-mistakes`+1 fails; 0 = perfect. First completion: permanent **Intelligence** (1,1,1,2,2,2,3,3,3,4,4,0,0 = **+26**, research) via StatProvider `extras`, and **SkyBlock XP** per song from neu `sblevels.json` (4..48, total **236**, key `harp:<SONG_ID>`). All 13 completed -> `MELODY_HAIR` (accessories' item; dial). Best accuracy, perfects tracked. Melodies are real notes: public-domain tunes written out; the reference's three copyrighted tunes replaced with original melodies + names | unit tests (every song parses, a perfect play is perfect for all 13, idle fails, wrong note at 0 allowance fails); self-tests `extras.songs`, `extras.harp-engine` |
| **Phone** (§6.4, the reference's Abiphone; ours: **Ringphone**) | 18 models registered (`ABIPHONE_*`, capacities/discs/features from each neu item's lore: 6..13 contacts, 0..25 ringtone slots; Do Not Disturb, Ringtones, Snake, Tic Tac Toe, Speed Dial by model). Right-click = phone home (ability `extras_phone_open`), left-click on Flip+ = speed dial. `TRIO_CONTACTS_ADDON` registered (right-click, +3 slots, max 25 uses; sold by the bank's bits shop). **50 contacts** from neu `constants/abiphone.json`, each mapped to the NPC on THIS server doing that job (display name is read live from that NPC, so one name stays one person), with the reference requirement (items consumed unless `keep`, coins paid, requirement keys like `slayer:spider:9` / `faction:mage:12000`, essence, full set, another contact). Add: click the NPC while holding a phone, or from the Contacts Directory when within 6 blocks. Calling: ringtone, a line, then the NPC's service runs (`Sky.commands().run`, e.g. `/merchants bartender`, `/slayer`, `/trapper`, `/forge`; the Community Shop keeper opens the extras Community desk). +10 SkyBlock XP per contact (`abiphone:<id>`, neu `abiphone_contacts_xp`). Incoming calls (1-in-`incoming-minutes` per online minute) with a clickable `[ANSWER]`; DND sends them to the call log. Contacts app (call / set speed dial / delete), Directory, Ringtones (any harp song you completed), Snake (9x5), Tic Tac Toe (minimax with a blunder dial). `Features.Abiphone` provided (`addContact` respects capacity; `hasContact` accepts a contact id OR an NPC id) | unit tests (capacity, features, contacts consistent, snake, tic-tac-toe never loses at blunder 0 in 200 games); self-tests `extras.contacts`, `extras.phone-capacity` |
| **City Projects** (§6.8 tab; research has no table -> designed, all TUNE) | 6 projects (Harbor Restoration, Grand Library Wing, Miners' Guildhall, Farmers' Granary, Graveyard Watchtower, Festival Grounds), 3 run at a time for `cycle-days` (30), walking the list. Each has 4 components a player funds ONCE (coins + enchanted materials, `cost-scale`) for a **permanent personal stat perk** (+2..5 of a gathering/combat stat) and SkyBlock XP (`city:<project>:<component>`). Server-wide contributions reaching `goal` (40 x `goal-scale`) switch on a **community perk for everyone** until the cycle ends: +5% Fishing/Enchanting/Mining/Farming XP (SkillXpGain), +5% mob coins (MobKill), +2 Magic Find (loot MF modifier). Progress in table `extras_city`; completion is broadcast. `/cityprojects` | unit test rotation; self-test `extras.city`; `/sb extras city` |
| **Fire Sales** | `/firesales`. One sale per `cycle-hours` (168), open `open-hours` (96), `count` (5) skins - the live API sample's shape (5 skins, 950 stock, 650 gems, ~4 days). Picked from a fixed shuffled order of every sellable skin, so nothing repeats until all 142 have been on sale. Paid in **earned Bits** (`bits-per-gem` 15, TUNE: 650 gems -> 9,750 Bits); server-wide stock reserved in a DB transaction (`extras_firesale`) BEFORE the Bits are taken, released if the spend fails; `per-player` 1. Broadcast when a sale opens (never on boot). Shows the next sale's skins while closed | unit test (every skin on sale once before repeats); self-test `extras.firesale` (all cosmetic, no repeats) |
| **Item skins** | 142 helmet/power-orb skins from neu (every one whose target some module registered; 3 dropped: "any Dragon Helmet" / "Perfect Helmet" ids absent, 29 backpack skins left for storage). Cosmetic only. Apply: right-click the skin or `/skins` -> pick the item; removing gives the skin back. Stored in `ItemData.extra.skin` (lore line `Skin: ...`) + a module PDC key that survives core re-renders; a light pass (`skins.pass-seconds` 5, on inventory close and on ProfileReady) re-applies the head texture (and PLAYER_HEAD material) after core redraws an item from its def | compile + self-test `extras.items-exist`; **not seen in a client** |
| **Century Cakes** | NOT built here: progression already has all 20 (`/cakes`, `docs/handoff/progression.md`) | - |

`bash tools/check.sh extras` -> **CHECK OK**. Unit tests: `plugin/src/test/java/com/skyclone/mod/extras/ExtrasPureTest.java`, **11/11 pass**
(run with a reflective runner against the plugin classpath; gradle not run - lane rule). The server was NOT started.

### Commands
| Player | Does | Menu path |
|---|---|---|
| `/harp` | song list (at the harpist) | Phone -> Ringtones -> The Harp; foraging's Lyra (Request 1) |
| `/phone [contact\|answer]` (`/abiphone`, `/ringphone`) | phone home / call / answer | right-click a phone |
| `/community` | Community Shop, City Projects, Fire Sales | call the Community Shop keeper on the phone |
| `/cityprojects` (`/cityproject`, `/city`) | City Projects | Community desk |
| `/firesales` (`/firesale`) | Fire Sales | Community desk |
| `/skins` (`/itemskins`) | apply/remove skins | Fire Sales -> Your Skins; right-click a skin |

Admin `/sb extras`: `info <p>` · `phone <p> [PHONE_ID]` · `trios <p> <n>` · `contact <p> <id|all|clear>` · `contacts` (which
contacts have an NPC / a registered command) · `call <p> <contact>` (ring them) · `harp <p> <SONG_ID|all|reset>` ·
`play <p> <SONG_ID>` (start a song, no distance check) · `city [<p> fund <project> <component> | progress <project> <n>]` ·
`firesale [list|next|reset]` · `skin <p> <SKIN_ID> <slot 0-40>`.

Requirement keys: `abiphone:phone`, `abiphone:contacts:<n>`, `abiphone:contact:<id>`, `harp:songs:<n>`, `harp:perfect:<n>`,
`harp:song:<SONG_ID>`.

### Dials
`extras.harp.max-mistakes` 5 · `.int-scale` 1 · `.near-npc-blocks` 12 · `.npc` foraging_harpist · `.speed-scale` 1 · `.final-reward` true ·
`extras.phone.incoming-minutes` 45 · `.add-on-click` true · `.ttt-blunder` 0.25 · `.snake-ticks` 5 · `.contact-sbxp` 10 ·
`extras.city.enabled` · `.cycle-days` 30 · `.per-cycle` 3 · `.goal-scale` 1 · `.perk-scale` 1 · `.cost-scale` 1 ·
`extras.firesale.enabled` · `.cycle-hours` 168 · `.open-hours` 96 · `.count` 5 · `.bits-per-gem` 15 · `.stock-scale` 1 · `.per-player` 1 ·
`extras.skins.pass-seconds` 5.

### Self-tests
`extras.items-exist` · `extras.songs` · `extras.harp-engine` · `extras.contacts` · `extras.phone-capacity` · `extras.firesale` · `extras.city`.

### Still left
- Live run: nothing here has been clicked in a client (harp timing feel, skin texture swap on a worn helmet, the
  NPC-click add racing the NPC's own menu).
- Pet skins (359 `PET_SKIN_*`), backpack skins (29), dyes: need the pets/storage modules to draw `extra.skin` (Requests).
- Abicases (accessories) and their "+1 Accessory Power per 2 contacts" (Request 4).
- Phone apps not built: none missing from the neu lore list; reference "missed-call quests" (Jerry etc.) are only flavour calls.
- 36 reference contacts have no counterpart NPC on this server (Galatea, Rift, bingo, bread contacts...); they are simply absent.
- Contacts with requirement items nobody registers (`BUILDERS_WAND` for the builder) were made requirement-free (TUNE, generator report).

### Playtest fix (wave5-newcontent, PLAYTEST #6)
- **Dialer Pim's phone line lied**: "I keep every model in the back", while his Hub shop sells only the Ringphone Basic
  (the other 17 models are at Phone Dealer Ude, Crimson Isle). His line is now *"I only stock the Basic here. Every other
  model is with {npc:shops_phone_dealer} on the Crimson Isle."* and his tip "Sells the Ringphone Basic."
- Root cause was a typed proper noun in a line that points at another module's NPC. Phone lines now support
  **`{npc:<npc id>}`**, filled at call time with that NPC's live display name (`Catalog.fillLine`, pure; unknown NPC ->
  title-cased id, never a raw brace), so a rename in shops can never leave the line naming nobody. Changed in
  `tools/gen/extras/gen_extras.py` and `data/extras/contacts.json` together (regen stays consistent).
- Checks: unit `phoneLinesFillNpcNamesLive` (fill, fallback, no contact line leaves a placeholder) and
  `phoneVendorPointsAtTheDealerWhoStocksTheModels` (Pim names the dealer; shops' `phone_dealer` really stocks every phone
  but the Basic); live self-test `extras.contacts` now fails if any `{npc:...}` names an NPC the registry does not have.

## Requests
1. **foraging:** Lyra's "Play the Harp" button should run `Sky.commands().run(p, "harp")` (this is the faithful falling-note
   harp, 13 songs, +26 Int, SkyBlock XP). **Until then BOTH harps grant Intelligence** (foraging's memory-game harp via
   `foraging.harp-int-scale`, ours via `extras.harp.int-scale`): retire one (set `foraging.harp-int-scale 0` or ours to 0).
2. **bank:** Community Shop menu: buttons for `/cityprojects` and `/firesales` (the reference has them as tabs there); and a
   way to grant **fame**: research 05 gives 200 fame per City Project contribution (e.g. `Features.Bank.addFame(p, n)`); our
   City code would call it from `City.fund`.
3. **core:** `Features.Abiphone.contacts(Player)` (default 0) so others can read the count without the requirement-key
   workaround (`abiphone:contacts:N`). And a skull-texture override on render (`ItemData.extra.skin_texture` or a
   `ItemService.addSkullOverride(id, (def, data) -> texture)` hook) so skinned items keep their look without our pass.
4. **accessories:** register `ABICASE_BLUE_AQUA/BLUE/GREEN/RED/YELLOW`, `ABICASE_REZAR`, `ABICASE_SUMSUNG_1/2` (the bits shop
   sells them; only the generic `ABICASE` exists) with "+1 Accessory Power per 2 contacts" (read
   `Sky.requirements().check(p, "abiphone:contacts:" + n)` or Request 3).
5. **shops:** let a phone call trade, not only browse: e.g. honour `Sky.commands().run(p, "merchants", shopId)` as a trade when
   `Sky.service(Features.Abiphone.class).hasContact(p, "shops_" + shopId)` (we accept NPC ids).
6. **menus:** FeatureIndex / SkyBlock Menu entries for `/phone`, `/community`, `/skins` (and `/harp` if wanted).
7. **pets / storage:** pet skins and backpack skins: if you draw `ItemData.extra.skin` (an item id with a `skull`), extras can
   sell them in Fire Sales too (add their ids to the generator).

## Known risks
- **Double Intelligence** from two harps until Request 1 is settled.
- Clicking a contact NPC with a phone adds the contact AND runs that NPC's normal click (core cannot be told to skip it).
- Skin looks are re-applied by a 5 s pass: between a core re-render (reforge, enchant) and the pass, the item shows its
  normal texture. Changing material via `ItemStack.withType` on a non-head helmet is untested in a client.
