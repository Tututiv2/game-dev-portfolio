# pets — renames

Reference proper nouns shown to players, and what we call them. Internal ids keep the reference id.
Pet ability NAMES and all ability/item DESCRIPTIONS are our own wording (numbers kept); the full ability list is below.

## Pets, items, NPCs

| id | reference name | our name |
|---|---|---|
| npc pets_sitter (hub `npc.pet_sitter`) | Kat | Tabitha (the Pet Sitter; dial `pets.kat-name`) |
| (NPC concept) | George (Taming cap pets) | the Pet Collector (inside the Pet Sitter menu) |
| (NPC concept) | Zog (pet item shop) | Pet Supplies (inside the Pet Sitter menu) |
| BAL | Bal | Magmaw |
| GRANDMAS_KNITTING_NEEDLE | Grandma's Knitting Needle | Knitting Needle |
| HEPHAESTUS_ANVIL | Hephaestus Anvil | Forgemaster Anvil |
| HEPHAESTUS_PLUSHIE | Hephaestus Plushie | Forgemaster Plushie |
| HEPHAESTUS_RELIC | Hephaestus Relic | Forgemaster Relic |
| HEPHAESTUS_REMEDIES | Hephaestus Remedies | Forgemaster Remedies |
| HEPHAESTUS_SHELMET | Hephaestus Shelmet | Forgemaster Shelmet |
| HEPHAESTUS_SOUVENIR | Hephaestus Souvenir | Forgemaster Souvenir |
| HEPHAESTUS_URN | Hephaestus Urn | Forgemaster Urn |
| JERRY | Jerry | Barry |
| KAT_BOUQUET | Kat Bouquet | Sitter's Bouquet |
| KAT_FLOWER | Kat Flower | Sitter's Daisy |
| KUUDRA | Kuudra | Magma Leviathan |
| MINOS_RELIC | Minos Relic | Labyrinth Relic |
| PET_ITEM_TOY_JERRY | Jerry 3D Glasses | Barry 3D Glasses |
| PRECURSOR_DRONE | Precursor Drone | Relic Drone |
| SCATHA | Scatha | Tunnel Wyrm |
| ZOG_ANVIL | Zog Anvil | Tamer's Anvil |

## Pet skins (wave 5)

359 cosmetic skins (`PET_SKIN_*`), bought with earned Bits at the Pet Sitter. Skin names are the reference's plain
descriptive words with OUR pet name; the five invented proper nouns inside them are replaced (`SKIN_WORDS` in
`tools/gen/pets/gen_pets.py`). Every skin whose name changed:

| id | reference name | our name |
|---|---|---|
| PET_SKIN_ARMADILLO_ARMARON | Armaron Armadillo Skin | Bastion Armadillo Skin |
| PET_SKIN_BAL_BLIZZARD | Blizzard Bal Skin | Blizzard Magmaw Skin |
| PET_SKIN_BAL_INFERNO | Inferno Bal Skin | Inferno Magmaw Skin |
| PET_SKIN_GRIFFIN_REINDRAKE | Reindrake Griffin Skin | Frostdrake Griffin Skin |
| PET_SKIN_JERRY_FROZEN | Frozen Jerry Skin | Frozen Barry Skin |
| PET_SKIN_JERRY_GREEN_ELF | Green Elf Jerry Skin | Green Elf Barry Skin |
| PET_SKIN_JERRY_HANDSOME | Handsome Jerry Skin | Handsome Barry Skin |
| PET_SKIN_JERRY_KING | King Jerry Skin | King Barry Skin |
| PET_SKIN_JERRY_LEPRECHAUN | Leprechaun Jerry Skin | Leprechaun Barry Skin |
| PET_SKIN_JERRY_MUMMY | Mummy Jerry Skin | Mummy Barry Skin |
| PET_SKIN_JERRY_RED_ELF | Red Elf Jerry Skin | Red Elf Barry Skin |
| PET_SKIN_KUUDRA_ABYSSAL | Abyssal Kuudra Skin | Abyssal Magma Leviathan Skin |
| PET_SKIN_KUUDRA_LOYALTY | Loyalty Kuudra Skin | Loyalty Magma Leviathan Skin |
| PET_SKIN_PRECURSOR_DRONE_PASTEL | Pastel Precursor Drone Skin | Pastel Relic Drone Skin |
| PET_SKIN_RAT_MR_CLAWS | Mr Claws Rat Skin | Sir Pinch Rat Skin |
| PET_SKIN_RAT_SQUEAKHEART | Squeakheart Rat Skin | Squeakling Rat Skin |
| PET_SKIN_SCATHA_ALBINO | Albino Scatha Skin | Albino Tunnel Wyrm Skin |
| PET_SKIN_SCATHA_DARK | Dark Scatha Skin | Dark Tunnel Wyrm Skin |
| PET_SKIN_SCATHA_GOLDEN | Golden Scatha Skin | Golden Tunnel Wyrm Skin |
| PET_SKIN_SCATHA_GUMMY | Gummy Worm Scatha Skin | Gummy Worm Tunnel Wyrm Skin |
| PET_SKIN_SCATHA_LUNAR_SNAKE | Lunar Snake Scatha Skin | Lunar Snake Tunnel Wyrm Skin |
| PET_SKIN_SCATHA_ZODIAC_SNAKE | Zodiac Snake Scatha Skin | Zodiac Snake Tunnel Wyrm Skin |
| PET_SKIN_SQUID_CHROMARI | Chromari Squid Skin | Prismara Squid Skin |

## Pet abilities

| pet | reference ability | our name |
|---|---|---|
| AMMONITE | Heart of the Sea | Deep Pulse |
| AMMONITE | Expert Cave Fisher | Cave Angler |
| AMMONITE | Gift of the Ammonite | Spiral Blessing |
| ANKYLOSAURUS | Armored Tank | Plated Might |
| ANKYLOSAURUS | Unyielding | Unbreakable Will |
| ANKYLOSAURUS | Clubbed Tail | Tail Club |
| ARMADILLO | Ridable | Saddle Up |
| ARMADILLO | Tunneller | Burrower |
| ARMADILLO | Rolling Miner | Rolling Start |
| ARMADILLO | Long Claws | Long Claws |
| ARMADILLO | Well-Worked | Efficient Gait |
| BABY_YETI | Yeti Fury | Frost Temper |
| BABY_YETI | Cold Breeze | Snowbound |
| BABY_YETI | Frosty Familiarity | Old Friends |
| BABY_YETI | Family Gathering | Pack Instinct |
| BAL | Furnace | Forge Heart |
| BAL | Dispersion | Heat Sink |
| BAL | Cannibal | Kinslayer |
| BAT | Candy Lover | Sweet Tooth |
| BAT | Nightmare | Night Wings |
| BAT | Wings of Steel | Steel Wings |
| BAT | Sonar | Echo Sense |
| BEE | Hive | Swarm |
| BEE | Busy Buzz Buzz | Worker Bee |
| BEE | Honey Harvester | Honey Hunter |
| BEE | Powered by Pollen | Pollinator |
| BINGO | Lucky Looting | Lucky Haul |
| BINGO | Climber | Climber |
| BINGO | Fast Learner | Quick Study |
| BINGO | Chimera | Chimera |
| BINGO | Scavenger | Scrounger |
| BINGO | Consumer | Long Brew |
| BINGO | Power Of Completion | Card Complete |
| BLACK_CAT | Hunter | Prowler |
| BLACK_CAT | Omen | Ill Omen |
| BLACK_CAT | Supernatural | Nine Lives |
| BLACK_CAT | Looting | Cat Burglar |
| BLAZE | Nether Embodiment | Hellborn |
| BLAZE | Bling Armor | Flame Polish |
| BLAZE | Fusion-Style Potato | Double Bake |
| BLUE_WHALE | Ingest | Blubber |
| BLUE_WHALE | Bulk | Bulk |
| BLUE_WHALE | Archimedes | Buoyancy |
| CHICKEN | Free Range | Free Roam |
| CHICKEN | Eggstra Loot | Nest Egg |
| CHICKEN | Light Feet | Featherfall |
| CROW | Quick Hands | Quick Talons |
| CROW | Camouflage | Shadowcloak |
| CROW | Insightful | Keen Eye |
| DOLPHIN | Pod Tactics | Pod Swim |
| DOLPHIN | Echolocation | Sonar Click |
| DOLPHIN | Splash Surprise | Splash Stun |
| DROPLET_WISP | Drophammer | Dousing Strike |
| DROPLET_WISP | Bulwark | Ember Ward |
| DROPLET_WISP | Blaze Slayer | Blaze Hunter |
| EERIE | Fearnesy | Shared Dread |
| EERIE | Fearama | Dread Edge |
| EERIE | Fearcreasing | Fear Harvest |
| ELEPHANT | Stomp | Heavy Tread |
| ELEPHANT | Walking Fortress | Living Fortress |
| ELEPHANT | Trunk Efficiency | Trunk Harvest |
| ELEPHANT | Abundant Harvest | Bountiful |
| ENDERMAN | Enderian | Void Kin |
| ENDERMAN | Teleport Savvy | Blink Strike |
| ENDERMAN | Zealot Madness | Zealous |
| ENDERMAN | Enderman Slayer | Void Hunter |
| ENDERMITE | More Stonks | Stone Snacker |
| ENDERMITE | Daily Commuter | Commuter |
| ENDERMITE | Mite Bait | Mite Lure |
| ENDERMITE | Sacrificer | Altar Favour |
| ENDER_DRAGON | End Strike | Void Bane |
| ENDER_DRAGON | One With The Dragon | Dragonbond |
| ENDER_DRAGON | Superior | Apex |
| FLYING_FISH | Quick Reel | Swift Line |
| FLYING_FISH | Water Bender | Tide Strength |
| FLYING_FISH | Lava Bender | Tide and Magma |
| FLYING_FISH | Deep Sea Diver | Deep Diver |
| FLYING_FISH | Magmatic Diver | Magma Diver |
| FLYING_FISH | Rapid Decay | Rapid Rot |
| FROG | Hop | Leapfrog |
| FROG | Happy Tree Friends | Chorus |
| FROG | Hunting Enjoyer | Pond Skimmer |
| FROG | Home Sweet Home | Lily Throne |
| FROST_WISP | Icehammer | Frost Strike |
| FROST_WISP | Bulwark | Ember Ward |
| FROST_WISP | Blaze Slayer | Blaze Hunter |
| FROST_WISP | Extinguish | Cool Spring |
| GHOUL | Undead Slayer | Grave Hunter |
| GHOUL | Army of the Dead | Soul Hoard |
| GHOUL | Reaper Soul | Reaper's Due |
| GIRAFFE | Good Heart | Big Heart |
| GIRAFFE | Higher Ground | High Vantage |
| GIRAFFE | Long Neck | Long Reach |
| GLACIAL_WISP | Icehammer | Frost Strike |
| GLACIAL_WISP | Bulwark | Ember Ward |
| GLACIAL_WISP | Blaze Slayer | Blaze Hunter |
| GLACIAL_WISP | Extinguish | Cool Spring |
| GLACITE_GOLEM | Powder-powered | Powder Engine |
| GLACITE_GOLEM | Iceborn | Iceborn |
| GLACITE_GOLEM | Frozen Perfection | Frozen Perfection |
| GOBLIN | Grunt Work | Grunt Labour |
| GOBLIN | Fetid Thief | Sticky Fingers |
| GOBLIN | Free-range Eggs | Egg Hunter |
| GOLDEN_DRAGON | Gold's Power | Midas Touch |
| GOLDEN_DRAGON | Shining Scales | Gilded Scales |
| GOLDEN_DRAGON | Dragon's Greed | Hoarder |
| GOLDEN_DRAGON | Legendary Treasure | Dragon Hoard |
| GOLDEN_DRAGON | Symbiosis | Kinship |
| GOLEM | Last Stand | Final Bulwark |
| GOLEM | Ricochet | Iron Rebound |
| GOLEM | Toss | Hurl |
| GRANDMA_WOLF | Kill Combo | Kill Streak |
| GRIFFIN | Odyssey | Legend Seeker |
| GRIFFIN | Sacred Strength | Hallowed Might |
| GRIFFIN | King of Kings | Crowned |
| GRIFFIN | Ancient Earth | Old Earth |
| GUARDIAN | Laserbeam | Prism Beam |
| GUARDIAN | Enchanting Wisdom Boost | Lorekeeper |
| GUARDIAN | Mana Pool | Mana Tide |
| GUARDIAN | Lucky Seven | Seventh Seal |
| HEDGEHOG | Spiky Quills | Quills |
| HEDGEHOG | Fearsome Farmer | Pest Terror |
| HEDGEHOG | Hunter's Insight | Field Notes |
| HERMIT_CRAB | Comfort Zone | Shell Shelter |
| HERMIT_CRAB | Seafloor Scalper | Seabed Scour |
| HERMIT_CRAB | Crab Rave | Crab Dance |
| HERMIT_CRAB | Hotspot Hazard | Hot Water |
| HORSE | High Stride | Long Stride |
| HORSE | Stampede | Gallop |
| HORSE | Trample | Trample |
| HOUND | Scavenger | Scrounger |
| HOUND | Finder | Bloodhound |
| HOUND | Pack Slayer | Pack Breaker |
| JADE_DRAGON | Forest Power | Grove Might |
| JADE_DRAGON | Jade Scale | Jade Scales |
| JADE_DRAGON | Dragon's Pride | Proud Bark |
| JADE_DRAGON | Apex Predator | Canopy King |
| JADE_DRAGON | Symbiosis | Kinship |
| JELLYFISH | Radiant Scyphozoa | Glowing Bell |
| JELLYFISH | Stored Energy | Stored Charge |
| JELLYFISH | Powerful Potions | Strong Brew |
| JERRY | Jerry | Barry |
| JERRY | Jerry | Barry |
| JERRY | Jerry | Barry |
| JERRY | Jerry | Barry |
| KUUDRA | Crimson | Crimson Tithe |
| KUUDRA | Wither Bait | Wither Lure |
| KUUDRA | Kuudra Fortune | Lava Fortune |
| KUUDRA | Trophy Bait | Trophy Lure |
| KUUDRA | Kuudra Specialist | Leviathan Bane |
| LION | Primal Force | Primal Force |
| LION | First Pounce | Opening Pounce |
| LION | King of the Jungle | Pride Leader |
| MAGMA_CUBE | Slimy Minions | Slime Foreman |
| MAGMA_CUBE | Salt Blade | Salted Edge |
| MAGMA_CUBE | Hot Ember | Ember Glow |
| MAMMOTH | Wooly Coat | Woolly Coat |
| MAMMOTH | Tusk Luck | Tusk Luck |
| MAMMOTH | Corpse Crusher | Corpse Crusher |
| MEGALODON | Blood Scent | Blood in the Water |
| MEGALODON | Enhanced Scales | Festival Scales |
| MEGALODON | Feeding Frenzy | Frenzy |
| MITHRIL_GOLEM | Mithril Affinity | Mithril Kin |
| MITHRIL_GOLEM | Subterranean Battler | Tunnel Brawler |
| MITHRIL_GOLEM | The Smell Of Powder | Powder Nose |
| MITHRIL_GOLEM | Refined Senses | Keen Senses |
| MOLE | Archaeologist | Digger |
| MOLE | Magnetic Nose | Magnet Snout |
| MOLE | Nucleic Veins | Core Seeker |
| MONKEY | Treeborn | Canopy Born |
| MONKEY | Vine Swing | Vine Swing |
| MONKEY | Evolved Axes | Axe Knack |
| MOOSHROOM_COW | Mushroom Eater | Fungal Snack |
| MOOSHROOM_COW | Farming Strength | Strong Back |
| MOOSHROOM_COW | Bovine Blessing | Bovine Gift |
| MOSQUITO | Smooth Jazz | Smooth Jazz |
| MOSQUITO | Buzzin' Barterer | Buzzing Trader |
| MOSQUITO | Bloodsucker's Betrayal | Turncoat |
| OCELOT | Foraging Wisdom Boost | Woodwise |
| OCELOT | Tree Hugger | Tree Hugger |
| OCELOT | Tree Essence | Sap Spark |
| ORCHID_MANTIS | Intelligent Specimen | Bright Mind |
| ORCHID_MANTIS | Swift Sickles | Swift Sickles |
| ORCHID_MANTIS | Orchid Nectar | Orchid Nectar |
| OWL | Training Refunds | Thrifty Trainer |
| OWL | Efficient Trainer | Study Buddy |
| OWL | Fast Learner | Night Study |
| PARROT | Flamboyant | Show-off |
| PARROT | Repeat | Encore |
| PARROT | Bird Discourse | Rallying Squawk |
| PARROT | Parrot Feather Infusion | Feathered Brew |
| PENGUIN | Thick Blubber | Thick Blubber |
| PENGUIN | Chilly Reception | Huddle |
| PENGUIN | Subzero Hero | Subzero Swimmer |
| PHOENIX | Rekindle | Rebirth |
| PHOENIX | Fourth Flare | Fourth Ember |
| PHOENIX | Magic Bird | Firewings |
| PHOENIX | Eternal Coins | Undying Purse |
| PIG | Hamfisted | Ham Fist |
| PIG | Shining Stampede | Shine Rush |
| PIG | Pig Parade | Pig Parade |
| PIGMAN | Bacon Farmer | Swineherd |
| PIGMAN | Pork Master | Pork Champion |
| PIGMAN | Giant Slayer | Giant Killer |
| PRECURSOR_DRONE | Contraband | Contraband |
| PRECURSOR_DRONE | Grungle | Single Throw |
| PRECURSOR_DRONE | Mining Off Camera | Off the Books |
| RABBIT | Happy Feet | Spring Step |
| RABBIT | Farming Wisdom Boost | Field Wisdom |
| RABBIT | Efficient Farming | Burrow Crew |
| RABBIT | Chocolate Injections | Sugar Rush |
| RAT | Morph | Shapeshift |
| RAT | CHEESE! | Cheese Nose |
| RAT | Rat's Blessing | Rodent Blessing |
| RAT | Extreme Speed | Turbo Tail |
| REINDEER | Winter Sprint | Sleigh Run |
| REINDEER | Infused | Winter Infusion |
| REINDEER | Snow Power | Gift Sense |
| REINDEER | Icy Wind | Icy Gust |
| RIFT_FERRET | Orbs are Fun | Orb Chaser |
| ROCK | Ridable | Boulder Seat |
| ROCK | Sailing Stone | Rolling Stone |
| ROCK | Fortify | Fortify |
| ROCK | Steady Ground | Steady Ground |
| ROSE_DRAGON | Garden Power | Bloom Power |
| ROSE_DRAGON | Rosy Scales | Rosy Scales |
| ROSE_DRAGON | Dragon's Gluttony | Feast |
| ROSE_DRAGON | Spiritual Perfection | Garden Grace |
| ROSE_DRAGON | Symbiosis | Kinship |
| SCATHA | Burrowing | Tunnel Luck |
| SCATHA | Drill Infusion | Drill Infusion |
| SCATHA | Bejeweled Eyes | Gem Gaze |
| SEAL | Showboater | Showboat |
| SEAL | Peak Performance | Peak Form |
| SEAL | Amphibious | Amphibious |
| SHEEP | Mana Saver | Woolly Focus |
| SHEEP | Overheal | Overheal |
| SHEEP | Dungeon Wizard | Dungeon Scholar |
| SILVERFISH | Magnetic | Magnetic |
| SILVERFISH | Experienced Burrower | Old Burrower |
| SILVERFISH | Dexterity | Nimble |
| SKELETON | Bone Arrows | Bone Arrows |
| SKELETON | Combo | Volley |
| SKELETON | Skeletal Defense | Bone Sentry |
| SKELETON_HORSE | High Stride | Long Stride |
| SKELETON_HORSE | Stampede | Bone Gallop |
| SKELETON_HORSE | Trample | Trample |
| SLOTH | Slow Start | Slow Start |
| SLOTH | Stronk Arm | Mighty Throw |
| SLOTH | Starlyn's Favorite | Grove Favourite |
| SLUG | Slow and Steady | Patience |
| SLUG | Pest Friends | Pest Pal |
| SLUG | Repugnant Aroma | Foul Aroma |
| SNAIL | Red Sand Enjoyer | Red Sand Lover |
| SNAIL | Slow and Steady | Slow and Sure |
| SNAIL | Slimy Reach | Slime Trail |
| SNOWMAN | Blizzard | Whiteout |
| SNOWMAN | Frostbite | Frostbite |
| SNOWMAN | Snow Cannon | Snow Cannon |
| SNOWMAN | Ouch! | Packed Ice |
| SPIDER | One With The Spider | Arachnid Bond |
| SPIDER | Web-weaver | Web Weaver |
| SPIDER | Spider Whisperer | Web Foreman |
| SPIDER | Web Battlefield | Web Field |
| SPINOSAURUS | Sharp Attitude | Sharp Teeth |
| SPINOSAURUS | Pursuit | Trophy Chase |
| SPINOSAURUS | Primordial Fisher | Storm Fisher |
| SPIRIT | Spirit Assistance | Ghost Ally |
| SPIRIT | Spirit Cooldowns | Restless |
| SPIRIT | Half Life | Half Life |
| SQUID | More Ink | Ink Spill |
| SQUID | Ink Specialty | Ink Master |
| SQUID | Fishing Wisdom Boost | Sea Wisdom |
| SUBZERO_WISP | Icehammer | Frost Strike |
| SUBZERO_WISP | Bulwark | Ember Ward |
| SUBZERO_WISP | Blaze Slayer | Blaze Hunter |
| SUBZERO_WISP | Extinguish | Cool Spring |
| SUBZERO_WISP | Ephemeral Stability | Still Mind |
| TARANTULA | Webbed Cells | Venom Tolerance |
| TARANTULA | Eight Legs | Eight Legs |
| TARANTULA | Arachnid Slayer | Arachnid Hunter |
| TARANTULA | Web Battlefield | Web Field |
| TIGER | Merciless Swipe | Merciless |
| TIGER | Hemorrhage | Bleed |
| TIGER | Apex Predator | Lone Hunter |
| TURTLE | Turtle Tactics | Shell Tactics |
| TURTLE | Genius Amniote | Shell Circle |
| TURTLE | Unflippable | Unflippable |
| TURTLE | Turtle Shell | Hard Shell |
| TYRANNOSAURUS | Close Combat | Point Blank |
| TYRANNOSAURUS | Ferocious Roar | Roar |
| TYRANNOSAURUS | Tyrant | Tyrant |
| WITCH | Toil and Trouble | Bubble and Brew |
| WITCH | Alchemism | Quick Brew |
| WITCH | Witching Hour | Witching Hour |
| WITHER_SKELETON | Stronger Bones | Hard Bones |
| WITHER_SKELETON | Wither Blood | Wither Blood |
| WITHER_SKELETON | Death's Touch | Withering Touch |
| WOLF | Alpha Dog | Alpha |
| WOLF | Pack Leader | Pack Leader |
| WOLF | Combat Wisdom Boost | Battle Wisdom |
| ZOMBIE | Bite Shield | Bite Guard |
| ZOMBIE | Rotten Blade | Rotting Edge |
| ZOMBIE | Living Dead | Living Dead |
