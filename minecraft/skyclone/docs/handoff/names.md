# Hand-off: names (cross-module NPC roster)

There is no `names` module. This lane is the **whole roster of people** a player meets: registered NPCs,
garden visitors, mayors, contest ghosts, dungeon puzzle strangers, the Rift's clerks. Each module picked its
names on its own, and nobody checked them against each other.

## 2026-10-04: wave-4 playtest "name collisions" (fixed at the root)

**Finding:** "Bram" was the hub blacksmith (Smith Bram), the Mayor, and the contest NPC farmers
(Bram Barley, Bram Clover); the Forge had Forgemaster Brann; Ranger Hollis in the Spider's Den shared a
name with Town Crier Hollis. Four AIs play this on camera and tell each other who to go and see, so one
name has to mean one person.

**Root cause:** no roster-wide check existed. The finding was the visible tip: an audit of every NPC,
visitor, mayor and name pool found **34 shared name words**, e.g. five Hollises, five Brams, four Ysoldes,
three Ilses, three Fens, Corvin/Corwin twice each.

**The rule now** (`plugin/src/main/java/com/skyclone/core/ui/NpcNames.java`): split every display name into
words, drop job titles and plain nouns (`COMMON`), and no remaining word may belong to two different people.
Two entries are one person when their full names match after colour codes and a leading "Mayor " are
removed. So the mayor, the mayor's garden visitor and the mayor's island NPC are one character, and a
visitor who mirrors a shop NPC must use that NPC's **full** name (as `Alchemist Wendel` already did).
`SAME_PERSON` allows exactly three words on purpose: `gerald` (Jerry: island helper, mayor, Saint Gerald),
`orla` (Mayor Orla runs her own ritual as Huntress Orla), `deepforge` (a family of dwarves).

**Guards (both new):**
- `plugin/src/test/java/com/skyclone/core/ui/NpcNamesTest.java` (10 tests, all pass): builds the roster
  from every data JSON (objects with name+anchor, garden visitors, mayors, auction agents, slayer npcName)
  and from mod sources (`NpcDef.of`, the `npc`/`reg`/`regular` helpers, foraging quests, `*NAME*`
  constants, `*-name` dials, the Rift `CLERK_NAMES`), checks it has >250 entries, then asserts no clash,
  and that the contest-ghost first names, dungeon `Strangers.NAMES` and auction seller personas share no
  word with any NPC. It also proves the rule: an injected "Smith Bram" next to "Mayor Bram" is caught.
- Self-test **`ui.npc-names-unique`** (registered in `UiCore` next to `ui.npc-anchors`): the same rule
  over the LIVE registry (`Sky.npcs().all()`, which includes garden visitors) plus every mayor in
  `data/mayors/mayors.json`. Not yet run on a live server (this pass did not start one).

A failure prints `word: Name (source) | Name (source)`. If the word is a job ("Ranger"), add it to
`NpcNames.COMMON`; if it is a name, rename one side and update `docs/names/<module>.md`.

## Renames (display names only; every NPC id, anchor and requirement key is unchanged)

Kept, as the one holder of the name: **Town Crier Hollis** (the AISkyShow primer, macros and tests name
him), **Mayor Bram**, Keeper Ilse, Warden Ysolde, Warden Fen, Archmage Corvin, Granny Moss, Quarryman Rook,
Pestwarden Pell, Fisher Gideon, Ferryman Brask, Fletcher Quill, Farmer Joss, Bagwright Jory (accessories'
bag NPC and its visitor), Mayor Osric, Mayor Pip, Mayor Orla, Alchemist Wendel, Essence Keeper Thessaly,
Fur-trader Alder, visitors Tobin (Tobin's Snowglobe is his) and Gus.

| Was | Now | Module / files |
|---|---|---|
| Smith Bram | **Smith Ottmar** | reforges (`ReforgesModule`, gen_reforges) |
| Woodcutter Bram | Woodcutter Haldane | foraging |
| Agent Bram | Agent Pemberton | auction (`agents.json`, gen_auction_data) |
| Bram the Miner | Cobbett the Miner | shops townsfolk |
| contest ghosts Bram, Jory, Lune, Nell, Pip, Quill, Wren, Finn, Vern | Bettany, Jessamy, Linnet, Gunnar, Hamish, Silas, Wilf, Crispin, Bertil | farming `ContestMath.FIRST` |
| Forgemaster Brann | **Forgemaster Kettrick** | mining |
| Coffer-keeper Brann | Coffer-keeper Mordaunt | crimson (`npcs.json`, gen_npcs) |
| Mayor Hollis (finnegan) | Mayor Wystan | mayors.json, garden visitor, gen_mayors, gen_garden |
| Ranger Hollis | **Ranger Ravensby** | combatzones (NPC, menu title, dial text) |
| Delver Hollis | Delver Thornley | mines |
| Beekeeper Hollis | Beekeeper Honora | shops |
| Fossil Digger Brask | Fossil Digger Ambleside | mining |
| Provisioner Ilse | Provisioner Margit | dungeons |
| Supply Keeper Ilse | Supply Keeper Dagny | hollows |
| Prospector Hilde (vs Quartermaster Hild) | Prospector Gundry | hollows |
| Hollows Merchant Ysolde | Hollows Merchant Ondine | mining |
| Lamplighter Ysolde | Lamplighter Luminelle | shops; the Lumina garden visitor is the same reference character, so she is now "Lamplighter Luminelle" too |
| Treasurer Ysolde | Treasurer Lucienne | crimson |
| Anvil-keeper Orla | Anvil-keeper Sigrun | reforges |
| Range Master Jory | Range Master Corliss | hub (TargetPractice, VillagePlace, dial text) |
| Scrapper Joss | Scrapper Nettle | shops |
| Gravedigger Moss | Gravedigger Sexby | shops |
| Token Trader Moss | Token Trader Fitz | mayors (carnival) |
| Archivist Pell | Archivist Lisette | mines |
| Rook (townsfolk) | Hesketh | shops townsfolk |
| Warden Rook | Warden Ashby | foraging |
| Shady Corvin | Shady Malloy | shops |
| Old Farmer Corwin | Old Farmer Tewksbury | farming |
| Ranger Corwin | Ranger Ellery | foraging |
| Owl-keeper Fen | Owl-keeper Whitcombe | shops |
| Sapwitch Fen | Sapwitch Elowen | foraging |
| Glade Peddler Fenn | Glade Peddler Tarquin | rift |
| Archer Gideon | Archer Peregrine | shops |
| Mayor Alder (barry) | Mayor Leofric | mayors.json, gen_mayors |
| Old Osric (townsfolk) | Old Ebbert | shops townsfolk |
| Barker Pip | Barker Jolyon | hub `npcs.json` |
| Pip Twofold | Ubaldo Twofold | rift (Split or Steal host) |
| Tailor Thessaly (visitor Taylor) | Tailor Clementine | garden |
| Baker Wendel (event NPC + visitor) | Baker Haverly | mayors, garden |
| Surveyor Tolbin (vs Farmhand Tobin) | Surveyor Pruitt | farming, shops |
| Farmhand Tobin (vs visitor Tobin) | Farmhand Prosper | hub `npcs.json` |
| Dozing Miner Gus (vs visitor Gus) | Dozing Miner Aurel | hub `npcs.json` |
| Scribe Quill | Scribe Thaddeus | shops townsfolk |
| Frostbitten Axel (visitor) | Frostbitten Aleks | garden; same reference character as the Jerry's Workshop shop NPC, so now the same name |
| Clerk Pell, Clerk Rook | Clerk Pomfrey, Clerk Rumbold | rift |
| dungeon strangers Corvin, Fennick, Hessa, Orrin, Pell, Tavi | Cuthbert, Fabian, Horatio, Osgood, Prewitt, Tullus | dungeons `puzzle/Strangers` |

Every new name was checked against the whole roster (exact and one-letter-off) AND against the reference
game's data in `research/data` (NEU + SkyHanni repos): four first picks (Lumina, Brynmor, Cosmo, Tybalt) were
real reference NPCs and were replaced. `ShopsPureTest.referenceNamesAreRenamed` caught the first two.

Generators (`tools/gen/<id>/*.py`) and `docs/names/<id>.md` rows were updated with the data, so a
regeneration keeps the new names. Historic playtest notes (`docs/PLAYTEST.md`, `docs/playtest/**`) still
say the old names on purpose.

## 2026-10-06: wave-5 regression "Bottle of Gerald's Wit" (naming #9, fixed at the root)

**Finding:** the auction house listed "Bottle of Gerald's Wit" (reference *Bottle of Jyrre*) while Gerald is the
island helper, the Mayor and Saint Gerald. A player (or an AI on stream) reads that as Gerald's bottle; it is not.

**Root cause:** the one-name-one-person guard only compared NPCs with NPCs. Items were never checked, so an item
could borrow a roster character's name and nothing failed.

**Rename** (display names only; ids, the `jyrre` tag/effect id and the `alchemy.jyrre-*` dials are unchanged):
`BOTTLE_OF_JYRRE` Lost Bottle of Midnight, `NEW_BOTTLE_OF_JYRRE` Bottle of Midnight, `REFINED_BOTTLE_OF_JYRRE`
Refined Bottle of Midnight, effect `jyrre` "Midnight Wit" (label "Midnight Wit +120"). "Midnight" is what
materials' fallback catalogue already called the Refined bottle, so this also settles alchemy request 13.
Files: `data/alchemy/items.json`, `data/alchemy/effects.json`, `tools/gen/alchemy/gen_alchemy.py` (so a
regeneration keeps it), player-facing strings in `AlchemyAdmin`/`AlchemyMenus`/`AlchemyDials`/`AlchemySelfTests`
(+ comments), `docs/names/alchemy.md`, `docs/handoff/alchemy.md`. ("Midnight" was also a reference chocolate
rabbit, which chocolate renamed to Copper Pudding, so nothing in our game is called Midnight but these bottles.)

**The rule now** (`NpcNames.possessives` / `itemOwnerClashes`): an item whose name says it is SOMEBODY'S
("X's ...") may not use a word any roster character answers to, unless the item is declared that character's in
`NpcNames.OWNED_ITEMS` (item id -> word: Harvesta's talismans, Old Gill's teeth, Grinko's masks, the Leviathan's
organs, the Tyrant's ring/talisman and goon, Tobin's Snowglobe). `NpcNames.ITEM_TITLES` holds owner words that
mean "a kind of person" rather than one person (Diver's Mask, Fairy's Polo, Pig's Ear, Herbalist's Coupon...).

**Guards (all new):**
- `NpcNamesTest.anItemNamedAfterSomeoneElsesNameIsCaught`, `possessivesSkipTitlesAndPlainNouns`, and
  `noShippingItemIsNamedAfterACharacterItDoesNotBelongTo` (reads ~7,500 item entries from every data JSON
  against the 250+-name roster; also fails if an `OWNED_ITEMS` id no longer exists, so a stale allowance cannot
  hide the next clash). Proven by putting "Bottle of Gerald's Wit" back: it fails with
  `gerald belongs to Saint Gerald (visitor st_jerry)`. 13/13 pass.
- Live self-test **`ui.item-owner-names`** (registered in `UiCore` beside `ui.npc-names-unique`): the same rule
  over the REGISTERED items (`Sky.items().all()`, i.e. the name that won registration) against the live roster.
  Not yet run on a live server (this pass did not start one).

**Not covered, on purpose:** non-possessive uses ("Gerald Talisman" style) are not checked, because most roster
"names" from the JSON walk are places and creatures (Magma Fields, Ender Dragon, Spider Tamer) and a plain word
match produced ~760 hits that are all legitimate. Owner-review candidates the scan surfaced, left alone because
the word is a title and the live name differs: materials' fallback "Weaver's Calling" for `ARACHNE_KEEPER_FRAGMENT`
(players see combatzones' "Silkmother's Calling", it registers first) and "Tamer's Anvil" (`ZOG_ANVIL`) next to
the hub's Spider Tamer.

## Still open (deliberately not changed)
- **Near-misses that are different words**: Hild/Hilde is fixed, but e.g. Orlin/Orrin, Jemima/Jemma,
  Lupa/Lupo, Maren/Marten, Marlo/Marlowe, Fennick/Fenwick, Gwenna/Wenna, Dorian/Dorran, Estrel/Kestrel/Ostrel,
  Brannoc (slayer) vs Mayor Bram remain. They are different spellings, so the guard passes; worth a look
  if the AIs mix any of them up on stream.
- `core/ui/NpcHolo.java` still uses "Ranger Hollis" as a throwaway fixture inside the nametag self-test.
  It is never registered, so it is not a world NPC.
