# kuudra - renames

Ids keep the reference game's spelling (so `KUUDRA_TEETH`, `BOSS_KUUDRA`, `kuudra_follower` mean the same thing in
every module); only what players see is ours. The boss name follows the **crimson** module, which named it first
("the Magma Leviathan", its dock "Leviathan's Hollow", keys "Leviathan Key"). Display strings live in
`tools/gen/kuudra/gen_kuudra.py` (items, mobs, perks) and in the Java (`KMenus`, `Run`, `KuudraModule`).

## Boss, places, NPCs

| id | reference name | our name |
|---|---|---|
| `leviathan_basic` .. `leviathan_infernal` (mob) | Kuudra | Magma Leviathan |
| island `kuudra` | Kuudra's Hollow | Leviathan's Hollow |
| zone `kuudra_lair` | (Kuudra's lair, Infernal) | The Lair |
| zone `kuudra_stomach` | (Kuudra's belly / stomach) | The Gullet |
| zone `kuudra_platform` | (ballista platform) | Ballista Platform |
| npc `kuudra_ferryman` | (Kuudra exit / reward NPC) | Ferryman Brask |
| npc `kuudra_quartermaster` | (Kuudra perk shop) | Quartermaster Ysra |
| tier names | Basic, Hot, Burning, Fiery, Infernal | kept (plain words; crimson's keys already use them) |

## The brood (mob ids `kuudra_<kind>_<tier>`)

| kind | reference name | our name |
|---|---|---|
| follower | Kuudra Follower | Leviathan Follower |
| berserker | Kuudra Berserker | Leviathan Berserker |
| knocker | Kuudra Knocker | Leviathan Knocker |
| landmine | Kuudra Landmine | Leviathan Landmine |
| slasher | Kuudra Slasher | Leviathan Slasher |
| magma_cube | Inferno Magma Cube | kept (generic words) |
| blight, dropship, explosive_imp, magma_follower, wandering_blaze, wither_sentry, blazing_golem | Blight, Dropship, Explosive Imp, Magma Follower, Wandering Blaze, Wither Sentry, Blazing Golem | kept (generic words) |
| cindermite | Chaosmite | Cindermite |

(The progression module's bestiary already used "Leviathan Follower/Berserker/Knocker/Landmine/Slasher"; these match.)

## Items owned here

| id | reference name | our name |
|---|---|---|
| `KUUDRA_SHOP_ITEM` | Open Shop | Hunt Perks |
| `HEAT_CORE` | Heat Core | kept (generic words) |
| `TENTACLE_DYE` | Tentacle Dye | kept (generic words) |
| `WHEEL_OF_FATE` | Wheel of Fate | Wheel of Embers (wave 5) |
| `ANANKE_FEATHER` | Ananke Feather | kept (Greek myth name; hunting's `SHARD_ANANKE` is "Ananke" too) - 2026-10-06 |
| recipe station `kuudra:mandible_salvage` | (Kuudra Mandible salvage) | Leviathan Salvage - 2026-10-06 |

## The intro questline (wave 5)

| id | reference name | our name |
|---|---|---|
| npc `leviathan_dock` (crimson's) | Elle | Dockhand Nell (crimson named her; she wears the reference Elle skin) |
| questline (`KIntro`, requirement `kuudra:intro`) | (Elle's introduction to Kuudra) | The Leviathan's Wake |
| steps `meet`/`pledge`/`cull`/`report`/`hunt` | (not published) | A Boat for the Hollow / Colours of the Isle / Stand the Heat / Back to the Dock / Into the Hollow |
| hunting attribute `kuudra_box` (hunting's name) | Kuudra's Box | Leviathan's Box |

Chest shards keep the hunting module's names (`docs/names/hunting.md`); this module never names a shard.

## Perks (our own set; the reference names its perks differently and they are not copied)

| id | our name |
|---|---|
| sure_grip | Sure Grip |
| master_mason | Master Mason |
| cannon_pass | Cannon Pass |
| gut_pick | Gut Pick |
| second_wind | Second Wind |
| lifeline | Lifeline (the reference's "Support route") |
| ember_edge | Ember Edge |
| lava_hide | Lava Hide |
| overcharge | Overcharge |
| fleetfoot | Fleetfoot |
| fuel_magnet | Fuel Magnet |
| mending_tonic | Mending Tonic |

## One name everywhere (was: an inconsistency, kuudra Request 4) - settled 2026-10-02

The **accessories** module used to call this boss **"Magmaw"** (`Magmaw's Kidney/Lung/Heart`, `Magmaw Follower
Artifact`, `Burning/Fiery/Infernal Magmaw Core`) and menus' Fast Travel fallback said **"Magmaw's Hollow"**, while
crimson, progression (bestiary) and this module said **"Magma Leviathan" / "Leviathan's Hollow"**.

That was not just two spellings of one name: **"Magmaw" is already a DIFFERENT boss** - the Crystal Hollows Topaz
boss (reference: Bal, `hollows` mob `ch_magmaw`), the `BAL` pet and its bestiary entry (`progression`, which even
lists `magmaw` as a match name). So "Magmaw's Heart" read as a drop from the hollows boss, and "the accessories/pets
Magmaw" that menus thought it was matching was Bal's pet. The majority name, which also avoids the collision, now
holds everywhere the Kuudra boss is meant:

| where | was | now |
|---|---|---|
| accessories `KUUDRAS_KIDNEY/LUNG/HEART` | Magmaw's Kidney/Lung/Heart | Leviathan's Kidney/Lung/Heart |
| accessories `KUUDRA_FOLLOWER_ARTIFACT/RELIC` | Magmaw Follower Artifact/Relic | Leviathan Follower Artifact/Relic (= the bestiary's "Leviathan Follower") |
| accessories `BURNING/FIERY/INFERNAL_KUUDRA_CORE` | Burning/Fiery/Infernal Magmaw Core | Burning/Fiery/Infernal Leviathan Core |
| accessories lore (organs, molten set, follower damage) | "the Magmaw fight", "fighting Magmaw", "Magmaw Follower monsters" | "the Leviathan hunt", "fighting the Magma Leviathan", "Leviathan Follower monsters" |
| menus `TravelLayout` kuudra fallback | Magmaw's Hollow | Leviathan's Hollow (kuudra's `IslandDef` already wins at runtime) |

Display strings only: no id changed, so saves, recipes, hooks (`hook: kuudra`, `kuudra_follower`) are untouched.
Accessories' names come from `WORD_RENAMES` in `tools/gen/accessories/gen_accessories.py` (regenerate, never
hand-edit `items.json` / `equipment.json`).

**If the owner wants a different boss name**, it is still one call, but it has to be made in all of these at once:
crimson (`Leviathan Key`, the dock, `CrimsonMenus`), this module (`gen_kuudra.py`, `KMenus`, `Run`, `KuudraModule`,
the generator's zone names), accessories' `WORD_RENAMES`, progression's bestiary, and menus' `TravelLayout`
fallback - and it must not be "Magmaw", which the Crystal Hollows boss owns.
