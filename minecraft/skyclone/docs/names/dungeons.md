# dungeons — renames

Every proper noun of the reference game's Catacombs that the dungeons framework (part A) shows to players.
Internal ids keep the reference ids where one exists (`SPIRIT_LEAP`, `SUPERBOOM_TNT`, `REVIVE_STONE`, `BOSS_BONZO`);
generic words stay generic ("The Catacombs", "Dungeon Hub", "Healer", "Wither Key", "Blood Key", "Mimic", "Decoy",
"Revive Stone", "Tic Tac Toe", "Water Board", "Creeper Beams", "Teleport Maze", "Ice Path", "Ice Fill", "Boulder",
"Higher or Lower", "Quiz", the class and ability names). Sources: `tools/gen/dungeons/gen_dungeons.py` and the Java
listed per row.

## NPCs and places

| id | reference name | our name |
|---|---|---|
| dungeon_gatekeeper / dungeon_mort | Mort | Sexton Abel |
| dungeon_provisioner | Ophelia | Provisioner Margit |
| dungeon_class_altar | (class selection) | Class Keeper Vesna |
| dungeon_party_board | Party Finder board | Party Board |
| dungeon_guide (hub) | (dungeon NPC in the Hub) | Catacombs Guide |
| npc.croesus (anchor only, for part B) | Croesus | (part B names it) |
| watcher (`dungeons.watcher-name` dial) | The Watcher | The Onlooker |
| quiz NPC | Ouija / the quiz master | The Oracle |
| dungeon_square zone | (the dungeon hub square) | Gravewatch Square |
| mausoleum zone | (catacombs entrance building) | Catacombs Mausoleum |

## Items

| id | reference name | our name |
|---|---|---|
| SPIRIT_LEAP | Spirit Leap | Soul Leap |
| SUPERBOOM_TNT | Superboom TNT | Megablast TNT |
| INFLATABLE_JERRY | Inflatable Jerry | Inflatable Dummy |
| DUNGEON_DECOY | Decoy | Decoy |
| REVIVE_STONE | Revive Stone | Revive Stone |
| POTION_DUNGEON_1..7 | Dungeon Potion I-VII (`POTION_DUNGEON;1..7`) | Dungeon Potion I-VII (the `;` ids became `_`) |
| (map, not a sky item) | Magical Map | Dungeon Map |

## Puzzles

| id | reference name | our name |
|---|---|---|
| three_strangers | Three Weirdos | Three Strangers |
| (others) | Tic Tac Toe, Creeper Beams, Water Board, Teleport Maze, Higher or Lower, Boulder, Ice Path, Ice Fill, Quiz | unchanged (generic) |

## Bosses (fallback mobs; the names are the collections module's, reused)

| id | reference name | our name |
|---|---|---|
| dungeon_guardian_1 | Bonzo | Grinko |
| dungeon_guardian_2 | Scarf | Veilwright |
| dungeon_guardian_3 | The Professor | The Lecturer |
| dungeon_guardian_4 | Thorn | Bramblemaw |
| dungeon_guardian_5 | Livid | Lurid |
| dungeon_guardian_6 | Sadan | Gorrak |
| dungeon_guardian_7 | Necron (and the Wither Lords) | Morvane |

## Mobs

| id | reference name | our name |
|---|---|---|
| dungeon_bonelord | Skeletor | Bonelord |
| dungeon_witherbinder | Withermancer | Witherbinder |
| dungeon_veilwraith | Fels | Veilwraith |
| dungeon_fallen_explorer | Lost Adventurer | Fallen Explorer |
| dungeon_furious_excavator | Angry Archaeologist | Furious Excavator |
| dungeon_shade_stalker | Shadow Assassin | Shade Stalker |
| dungeon_gilded_monarch | King Midas | Gilded Monarch |
| dungeon_frostbound_explorer | Frozen Adventurer | Frostbound Explorer |
| dungeon_puzzle_blaze | (Higher or Lower blaze) | Pillar Blaze |
| dungeon_blood_defiler | Revoker | Defiler |
| dungeon_blood_maniac | Psycho | Maniac |
| dungeon_blood_harvester | Reaper | Harvester |
| dungeon_blood_gnawer | Cannibal | Gnawer |
| dungeon_blood_silent_one | Mute | Silent One |
| dungeon_blood_sludge | Ooze | Sludge |
| dungeon_blood_rotter | Putrid | Rotter |
| dungeon_blood_twisted | Freak | Twisted |
| dungeon_blood_bloodsucker | Leech | Bloodsucker |
| dungeon_blood_weeper | Tear | Weeper |
| dungeon_blood_leechling | Parasite | Leechling |
| dungeon_blood_kindler | Flamer | Kindler |
| dungeon_blood_bonehead | Skull | Bonehead |
| dungeon_blood_mister_gone | Mr. Dead | Mister Gone |
| dungeon_blood_darkhelm | Vader | Darkhelm |
| dungeon_blood_chiller | Frost | Chiller |
| dungeon_blood_shambler | Walker | Shambler |
| dungeon_blood_lost_soul | Wandering Soul | Lost Soul |
| (generic, unchanged) | Zombie Grunt, Skeleton Grunt, Crypt Lurker, Crypt Souleater, Lonely Spider, Cellar Spider, Zombie Soldier/Knight/Commander, Super Tank Zombie, Deathmite, Skeleton Master, Super Archer, Sniper, Zombie Lord, Skeleton Lord, Wither Guard, Wither Miner, Crypt Undead, Mimic | same |

## Room tiles (our own names; the reference rooms are hand-built schematics with their own names, none reused)

Ossuary, Colonnade, Archive, Collapsed Hall, Cellblock, Root Garden, Chapel, Old Dig, Ember Rift, Entrance Hall,
Fairy Grove, Blood Chamber, Gauntlet (trap), Arena (miniboss).

---

# Part B (bosses, gear, loot)

Boss names follow the collections module (`docs/names/collections.md`). NOTE for the owner: other modules named Necron's
items "Warden's" (weapons `NECRON_BLADE` = Warden's Blade, materials `NECRON_HANDLE` = Warden's Handle, reforges
`SADAN_BROOCH` = Warden's Brooch) while collections calls the F7 boss Morvane. Part B keeps both: Morvane is "the
Warden" in the boss dialogue, and his armour is "Morvane's". Align in one pass if you prefer one name.

## Bosses, adds and arena NPCs

| id | reference name | our name |
|---|---|---|
| dboss_grinko | Bonzo | Grinko |
| dboss_grinko_undead | Bonzo's Undead | Circus Undead |
| dboss_veilwright | Scarf | Veilwright |
| dboss_veil_warrior/archer/mage/priest | Scarf's Undead Warrior/Archer/Mage/Priest | Undead Warrior/Archer/Mage/Priest |
| dboss_lecturer | The Professor | The Lecturer |
| dboss_guardian_healthy/reinforced/chaos/laser | Healthy/Reinforced/Chaos/Laser Guardian | same (generic) |
| dboss_bramblemaw | Thorn | Bramblemaw |
| dboss_spirit_* | Spirit Bull/Wolf/Bat/Rabbit/Chicken/Sheep/Bear | same (generic) |
| dboss_lurid_<colour> | Livid (and its eight copies: Vendetta, Crossed, Hockey, Doctor, Frog, Smile, Scream, Arcade) | Lurid (the copies are named only by colour) |
| dboss_gorrak | Sadan / The Giant One | Gorrak / The Giant One |
| dboss_giant_diamond | Diamante Giant | Sparkling Giant |
| dboss_giant_laser | L.A.S.R. | Beam Giant |
| dboss_giant_bigfoot | Bigfoot | Strider Giant |
| dboss_giant_boulder | Jolly Pink Giant | Rosy Giant |
| dboss_terracotta(_archer) | Terracotta | Terracotta (generic) |
| dboss_gravax | Maxor | Gravax |
| dboss_tempestra | Storm | Tempestra |
| dboss_aurik | Goldor | Aurik |
| dboss_morvane | Necron | Morvane (the Warden) |
| dboss_hollow_king | The Wither King | The Hollow King |
| dboss_dragon_apex/flame/ice/power/soul | Apex/Flame/Ice/Power/Soul Dragon | Verdant/Ember/Frost/Might/Soul Dragon |
| dboss_withermancer | Withermancer | Witherbinder (as part A) |
| dboss_shade_stalker | Shadow Assassin | Shade Stalker (as part A) |
| dungeon_vault_keeper | Croesus | Vault Keeper Orlin |
| dungeon_essence_keeper | (essence shop) | Essence Keeper Thessaly |
| (menu) | Mort's shop | Gatekeeper's Stock |
| (menu) | Ophelia's Superboom/Spirit Leap shop | Provisioner (part A) |

## Gear and items

| id | reference name | our name |
|---|---|---|
| SKELETOR_* | Skeletor armour | Bonelord armour |
| SHADOW_ASSASSIN_*, STARRED_SHADOW_ASSASSIN_* | Shadow Assassin armour | Shade Stalker armour |
| POWER_WITHER_* | Necron's armour | Morvane's armour |
| TANK_WITHER_* | Goldor's armour | Aurik's armour |
| SPEED_WITHER_* | Maxor's armour | Gravax's armour |
| WISE_WITHER_* | Storm's armour | Tempestra's armour |
| BONZO_MASK, STARRED_BONZO_MASK | Bonzo's Mask | Grinko's Mask |
| GOLD_/DIAMOND_BONZO_HEAD ... NECRON_HEAD | Golden/Diamond Bonzo ... Necron Head | Golden/Diamond Grinko, Veilwright, Lecturer, Bramblemaw, Lurid, Gorrak, Morvane Head |
| PRECURSOR_EYE | Precursor Eye | Forerunner Eye (matches reforges' Forerunner Gear) |
| KISMET_FEATHER | Kismet Feather | Fate Feather |
| INFINITE_SPIRIT_LEAP | Infinileap | Endless Soul Leap |
| WITHER_RELIC_* | (Wither King relics) | Verdant/Ember/Frost/Might/Soul Relic |
| (set bonus) Shoal | Shoal | Phalanx |
| (set bonus) Zombie Knight | Zombie Knight | Oathbound |
| (set bonus) Training | Training | Drill Sergeant |
| (set bonus) Dungeon Lord | Dungeon Lord | Lord of the Halls |
| (set bonus) Skeleton Soldier / Master | Skeleton Soldier / Master | Volley / Marksman |
| (set bonus) Skeletor | Skeletor | Bone Collector |
| (set bonus) Bouncing Arrow | Bouncing Arrow | Ricochet |
| (set bonus) Efficient training | Efficient training | Ever Learning |
| (set bonus) Shadow Assassin | Shadow Assassin | Silent Hunt |
| (ability) Clownin' Around | Clownin' Around | Last Laugh |
| (ability) Second Wind, Spirit Glide, Eye Beam, Witherborn, Soul Whisper, Sinew, Implosion, Shadow Warp, Wither Shield, Wither Impact | same | generic words, kept |
| (generic, unchanged) | Zombie Soldier/Knight/Commander/Lord, Skeleton Soldier/Master/Lord, Crypt Witherlord, Bouncy, Adaptive, Necromancer Lord, Wither, Spirit Mask/Boots, Mender Helmet/Fedora/Crown, Dark/Shadow/Wither Goggles, Stone/Metal/Steel Chestplate, Sniper Helmet, Dungeon Chest Key, Energy Crystal | same |

## Essence shop perks (NEU essenceshops.json)

| id | reference name | our name |
|---|---|---|
| catacombs_boss_luck, catacombs_looting, revive_stone, catacombs_health/defense/strength/intelligence/crit_damage | Boss Luck, Looting, Help of the Fairies, Health/Defense/Strength/Intelligence/Critical Essence | same (generic) |
| permanent_* , forbidden_blessing | Forbidden Health/Defense/Speed/Intelligence/Strength, Forbidden Blessing | same (generic) |
| dungeonbreaker_secrets | Echos of the Lost | Echoes of the Lost (our effect: +2 Magic Find per level in the Catacombs) |

## Arenas (our own names)

Grinko's big top (F1), Veilwright's lecture court (F2), the Lecturer's drowned hall (F3), Bramblemaw's grove (F4),
Lurid's hall of mirrors (F5), Gorrak's sculpture hall (F6), the Withered Vault (F7: foundry, crusher deck, gauntlet,
lava ring, throne room).

## Wave 5 (2026-10-06)

| id | reference name | our name |
|---|---|---|
| dboss_apostle | Apostle (the Wither King's adds) | Hollow Apostle |
| dboss_wither_husk | Wither Husk | Wither Husk (generic, unchanged) |
| dboss_giant_* on Master VI | Mutant Giants (Diamond Giant, L.A.S.R., Bigfoot, Jolly Pink Giant) | Mutant Sparkling / Beam / Strider / Rosy Giant (nameplate only; the ids stay) |
| DYE_LIVID | Livid Dye | Lurid Dye |
| DYE_NECRON | Necron Dye | Morvane Dye |
| STARRED_SHADOW_FURY, STARRED_LAST_BREATH, STARRED_ITEM_SPIRIT_BOW, STARRED_BAT_WAND, STARRED_BONE_BOOMERANG, STARRED_FELTHORN_REAPER, STARRED_BONZO_STAFF, STARRED_STONE_BLADE, STARRED_ICE_SPRAY_WAND | ⚚ + the base weapon's reference name | ⚚ + the weapons module's name for the base (e.g. "⚚ Balloonist's Staff"); no new nouns |
| (class ghost abilities) | Instant Wall, Ghost Axe, Healing Bow, Stun Potion | same (generic) |
| (class passives) | Indomitable, Overheal | same (generic) |
