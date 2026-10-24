# bank — renames

Every proper noun of the reference game that the bank module shows to players, with our name.
Internal ids keep the reference id (never shown). Generic words stay generic: Bank, Banker, Bits, Fame,
Community Shop, Co-op / Personal Account, the account tier names (Starter, Gold, Deluxe, Super Deluxe,
Premier, Luxurious, Palatial - plain adjectives), the fame rank titles (New Player ... Paragon - plain
English titles), and the Community Shop upgrade names (Island Size, Minion Slots, Magic Find...).

| id | reference name | our name |
|---|---|---|
| `BOOSTER_COOKIE` | Booster Cookie | Boon Cookie |
| `FREE_COOKIE` | Booster Cookie (FREE) | Boon Cookie (Gift) |
| (buff) | Cookie Buff | Boon Buff |
| `PERSONAL_BANK_ITEM` | Personal Bank Item | Pocket Bank |
| NPC `bank_community_shop` (hub `npc.community_shop`) | Elizabeth | Rosamund |
| NPC `bank_banker` (hub `npc.banker`) | Banker (hub) | Banker Odell |
| NPC `bank_banker_2` (hub `npc.banker_2`) | Banker (second desk) | Banker Prue |
| upgrade `hotm_slots` | HotM / HotF slots | Mountain Tree Slots |

Text: the cookie, Pocket Bank and every menu/lore line are written in our own words; the numbers
(4 days, +25 Wisdom, +15 Magic Find, 4,800 bits, 250 per 30 min, tier capacities and costs, interest
bands, fame thresholds, bits prices) are the research's.

NPC bodies use skin textures taken from the reference data (`BANKER_BARRY_NPC`, `BANKER_BONSAI_NPC`,
`ELIZABETH_NPC` in the NEU repo), the same practice the reforges module uses. Delete
`data/bank/npcs.json` entries (or re-run the generator with other ids) to fall back to default bodies.
