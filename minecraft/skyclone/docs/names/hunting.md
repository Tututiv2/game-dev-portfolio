# hunting - renames

Every reference proper noun the hunting module shows players, and what it says instead. Internal ids keep the
reference ids (`SHARD_*`, `ATTRIBUTE_SHARD_*` attribute keys lower-snaked, tool ids). Generic words (Cod, Fly, Crow,
Bogged, Python, Kraken...) are kept. A shard that a mob module already renamed takes THAT module's name, so the
shard reads like its creature. Generated with `tools/gen/hunting/gen_hunting.py` (NAME_RENAMES, TEXT_RENAMES).

## Shards

| id | reference name | our name |
|---|---|---|
| SHARD_PHANPYRE | Phanpyre | Duskfang |
| SHARD_HOWLING_SPIRIT | Howling Spirit | Howling Wraith |
| SHARD_PHANFLARE | Phanflare | Marshflare |
| SHARD_HIDEONLEAF | Hideonleaf | Leaflurker |
| SHARD_CHILL | Chill | Frostfang |
| SHARD_HIDEONGIFT | Hideongift | Giftlurker |
| SHARD_BIRRIES | Birries | Berrybob |
| SHARD_TANK_ZOMBIE | Tank Zombie | Super Tank Zombie |
| SHARD_RABBIT_MAFIOSO | Rabbit Mafioso | Rabbit Racketeer |
| SHARD_CORALOT | Coralot | Reeflotl |
| SHARD_BEZAL | Bezal | Scorchling |
| SHARD_NIGHT_SQUID | Inkling | Night Squid |
| SHARD_YOG | Yog | Cinderling |
| SHARD_STRONGARM | Strongarm | Rimestone Mutt |
| SHARD_HIDEONSUN | Hideonsun | Sunlurker |
| SHARD_HONEYHOG | Honeyhog | Hivehog |
| SHARD_SHYWORM | Shyworm | Timidworm |
| SHARD_BRINELING | Brineling | Saltling |
| SHARD_CHOCOBUN | Chocobun | Cocoabun |
| SHARD_TIMIL | Timil | Springtail |
| SHARD_BUNBUN | Bunbun | Hopsy |
| SHARD_HONEYBUZZ | Honeybuzz | Nectarbuzz |
| SHARD_POLLENDART | Pollendart | Pollenzip |
| SHARD_PANDARAI | Pandarai | Bamboo Ronin |
| SHARD_LEVIATHAN | Leviathan | Abyssal Leviathan |
| SHARD_FENLORD | Fenlord | Bog Baron |
| SHARD_BAL | Bal | Magmaw |
| SHARD_HIDEONSACK | Hideonsack | Sacklurker |
| SHARD_MURDERWING | Murderwing | Gloomwing |
| SHARD_SEA_EMPEROR | Loch Emperor | Loch Sovereign |
| SHARD_SHELLWISE | Shellwise | Shellsage |
| SHARD_BARBARIAN_DUKE_X | Barbarian Duke X | Duke Brakkus the Tenth |
| SHARD_XYZ | XYZ | Arcanum Cube |
| SHARD_CAVERNSHADE | Cavernshade | Gloomshade |
| SHARD_KING_MINOS | King Minos | Labyrinth Inquisitor |
| SHARD_SILKBREEZE | Silkbreeze | Silkdrift |
| SHARD_GEMZIE | Gemzie | Gemkin |
| SHARD_RABBIT_GODMOTHER | Rabbit Godmother | Rabbit Matriarch |
| SHARD_BURNINGSOUL | Inferno Demonlord | Burningsoul Demon |
| SHARD_BONZO | Bonzo | Grinko |
| SHARD_BEEHEEMOTH | Beeheemoth | Hivecolossus |
| SHARD_SCARF | Scarf | Veilwright |
| SHARD_SCATHA | Scatha | Tunnel Wyrm |
| SHARD_NILBOG | Nilbog | Goblinkin |
| SHARD_LITTLEFOOT | Littlefoot | Little Wanderer |
| SHARD_TENEBRIS | Tenebris | Umbra |
| SHARD_NESSIE | Nessie | Loch Wyrm |
| SHARD_ENDSTONE_PROTECTOR | End Stone Protector | End Stone Colossus |
| SHARD_RABBIT_GODFATHER | Rabbit Godfather | Rabbit Patriarch |
| SHARD_WARTYBUG | Wartybug | Wartmite |
| SHARD_DOOMSPIRAL | Doomspiral | Dread Vortex |
| SHARD_HIDEONBOX | Hideonbox | Boxlurker |
| SHARD_LORD_JAWBUS | Lord Jawbus | Lord Gnashmaw |
| SHARD_HELLWISP | Hellwisp | Infernal Wisp |
| SHARD_CINDER_BAT | Cinderbat | Emberwing Bat |
| SHARD_POWER_DRAGON | Power Dragon | Might Dragon |
| SHARD_APEX_DRAGON | Apex Dragon | Verdant Dragon |
| SHARD_ETHERDRAKE | Etherdrake | Voiddrake |
| SHARD_MOLTHORN | Molthorn | Magmaspine |
| SHARD_STARBORN | Starborn | Starchild |
| SHARD_COCOALEECH | Cocoaleech | Cocoasucker |
| SHARD_RAINBUG | Rainbug | Prismbug |
| SHARD_TIMESTALK_CLONE | Timestalk Clone | Chrono Echo |
| SHARD_WUMPA | Wumpa | Frostbumble |
| SHARD_BAMBLOOM | Bambloom | Bamboo Bloom |
| SHARD_GLACITE_WALKER | Glacite Walker | Rimestone Walker |
| SHARD_BEACONMITE | Beaconmite | Signalmite |
| SHARD_INVISIBUG | Invisibug | Ghostbug |
| SHARD_HIDEONGEON | Hideongeon | Cryptlurker |
| SHARD_DROWNED | Drowned | Loch Lancer |
| SHARD_HIDEONDRA | Hideondra | Lavalurker |
| SHARD_ARACHNE | Arachne | Silkmother |
| SHARD_BITBUG | Bitbug | Bytebug |
| SHARD_SILENTDEPTH | Silentdepth | Hushdeep |
| SHARD_SKELETOR | Skeletor | Skullwarden |
| SHARD_THYST | Thyst | Amethyst Leech |
| SHARD_QUARTZFANG | Quartzfang | Quartzbite |
| SHARD_HIDEONRING | Hideonring | Ringlurker |
| SHARD_KADA_KNIGHT | Kada Knight | Ashen Knight |
| SHARD_CARROT_KING | Carrot King | Carrot Monarch |
| SHARD_MATCHO | Matcho | Tinderling |
| SHARD_LUMISQUID | Lumisquid | Lumen Squid |
| SHARD_JOYDIVE | Joydive | Gleediver |
| SHARD_STALAGMIGHT | Stalagmight | Stalagbrute |
| SHARD_FUNGLOOM | Fungloom | Fungleam |
| SHARD_HEWVER | Hewver | Hewbeaver |
| SHARD_WIKI_TIKI | Wiki Tiki | Tiki Totem |
| SHARD_SNOOZLE | Snoozle | Dozemole |
| SHARD_HIDEYHO | Hideyho | Hollowpeek |
| SHARD_HIDEONFLOOR | Hideonfloor | Floorlurker |
| SHARD_GIMMIEGOLD | Gimmiegold | Goldgrabber |
| SHARD_HIDEONWALL | Hideonwall | Masonlurker |
| SHARD_NOZZLENOSE | Nozzlenose | Snoutfish |
| SHARD_FLUFFLING | Fluffling | Fluffkin |
| SHARD_ZOMBUDDY | Zombuddy | Ghoulpal |
| SHARD_GOLDOLOT | Goldolot | Goldlotl |
| SHARD_TROGLOBYTE | Troglobyte | Cavebiter |
| SHARD_HIDEONCAVE | Hideoncave | Cavelurker |
| SHARD_CUBOA | Cuboa | Cubeboa |
| SHARD_MOSSYBIT | Mossybit | Mossling |
| SHARD_LOTUM | Lotum | Lotuspad |
| SHARD_BAMBULEAF | Bambuleaf | Bambooleaf |
| SHARD_SYCOPHANT | Sycophant | Ghoul Sycophant |
| SHARD_SOUL_OF_THE_ALPHA | Soul of the Alpha | Shade of the Alpha |
| SHARD_MOCHIBEAR | Mochibear | Dumpling Bear |
| SHARD_STRIDER_SURFER | Stridersurfer | Lava Strider |
| SHARD_FLIP_FLOPPER | Flipflopper | Flopfish |
| SHARD_AREITA | Areita | Weavra |
| SHARD_DUPLICO | Duplico | Doppel |
| SHARD_SHUDDERSQUID | Shuddersquid | Quiversquid |
| SHARD_SOLSNATCHER | Solsnatcher | Sunsnatcher |
| SHARD_SEPIALOT | Sepialot | Inklotl |
| SHARD_TEWTIL | Tewtil | Lilyturtle |
| SHARD_MINOS_HUNTER | Minos Hunter | Labyrinth Hunter |
| SHARD_DUSTYBIT | Dustybit | Dustling |
| SHARD_HONEYMITE | Honeymite | Nectarmite |
| SHARD_KEELED_SLUG | Keeled Slug | Slug |

## Attributes (ability names)

| attribute | reference name | our name |
|---|---|---|
| moonglade_mastery | Moonglade Mastery | Moonmarsh Mastery |
| yog_membrane | Yog Membrane | Cinder Membrane |
| torrhus_mastery | Torrhus Mastery | Torrent Mastery |
| torrhus_diver | Torrhus Diver | Torrent Diver |
| moonglade_serendipity | Moonglade Serendipity | Moonmarsh Serendipity |
| kuudra_box | Kuudra's Box | Leviathan's Box |
| atomized_glacite | Atomized Glacite | Atomized Rimestone |
| torrhus_serendipity | Torrhus Serendipity | Torrent Serendipity |
| kat_favorite | Kat's Favorite | Sitter's Favorite |
| starlyn_training | Starlyn Training | Starfall Training |
| galatea_training | Galatea Training | Moonmarsh Training |

## Words inside effect text

| reference | ours |
|---|---|
| Moonglade (Marsh) | (the) Moonmarsh - foraging/galatea's name |
| Torrhus (Canyon) | Torrent (Canyon) - foraging's name |
| Galatea | the Moonmarsh |
| Starlyn Contests | Starfall Contests - foraging's name |
| Mines of Divan | Mines of Varnas - mining's name |
| Lotus Atoll | Lotus Lagoon - fishing's name |
| Hoppity's Hunt | Hopsworth's Hunt - garden's Hopsworth |
| David's Cloak | Huntmaster's Cloak - accessories' name |
| Grandma Wolf | Granny Moss - combatzones' name |
| Kat / Fann | the Pet Sitter / the Pet Trainer |
| Kuudra (Teeth, Box) | Leviathan - crimson/kuudra's name |
| Wiki Tiki | Tiki Totem - fishing's name |
| Glacite | Rimestone - materials/mining's name |
| Yog Membrane | Cinder Membrane |

## Items

| id | reference name | our name |
|---|---|---|
| ABYSMAL_LASSO | Abysmal Lasso | Frayed Lasso |
| VENATOR_GENESIS | Worn Huntaxe - Genesis | Worn Huntaxe - Origo |
| SILVA_DOMINUS | Sharpened Huntaxe - Dominus | Sharpened Huntaxe - Silvanus |
| CURSUS_FERAE | Reinforced Huntaxe - Cursus | Reinforced Huntaxe - Venatrix |
| APEX_PRAEDATOR | Savage Huntaxe - Praedator | Savage Huntaxe - Ferox |
| NEX_TITANUM | Prime Huntaxe - Nex Titanum | Prime Huntaxe - Rex Ferarum |
| (ability) | Absorptio / Vis Temperata / Retentio | Borrowed Edge / Tempered Blow / Set the Snare |
| SAFARI_TICKET_* | (Safari Ticket tiers) | Basic / Economy / Premium / First-Class Safari Ticket (generic, kept) |

Lassos (Vinerip, Entangler, Everstretch), nets (Basic/Medium/Turbo/Gigantic Fishing Net), black holes
(Small/Medium/Large Pocket Black Hole), huntraps (Small/Medium/Large/Greater/Astral Huntrap), salts (Lushlilac
Bonbon, Oceandy, Candycomb, Brine Tonic), Critter Capsule, Safari Essence and Rainbow Feather are descriptive and kept.

## NPCs and places (the Critter Safari island, `critter_safari`)

| id | reference | ours |
|---|---|---|
| hunting_clerk | Safari Receptionist (tickets) | Clerk Isaura |
| hunting_board | Safari Manager (ticket upgrades) | Safari Notice Board |
| hunting_keeper | Archie (Safari Essence perks) | Keeper Sabeline |
| hunting_warden | Andrea (Safari milestones) | Warden Odalric |
| hunting_dorran | David Hunterborough (hunting lessons) | Hunter Ossian |
| island critter_safari | Critter Safari | Critter Safari (generic, kept) |
| zones | Cavern / Forest / Haunted / Icy biome | same (generic) |

