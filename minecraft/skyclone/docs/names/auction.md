# auction — renames

The Auction House registers no items. Every name below is shown to players; ids are internal.

| id | reference name | our name |
|---|---|---|
| `auction_master` (NPC, hub `npc.auction_master`) | Auction Master | **Auctioneer Dorian** (title `AUCTION HOUSE`) |
| `auction_agent_1` (NPC, hub `npc.auction_agent_1`) | Auction Agent | **Agent Lysa** (title `BROWSE`) |
| `auction_agent_2` (NPC, hub `npc.auction_agent_2`) | Auction Agent | **Agent Pemberton** (title `YOUR AUCTIONS`) |
| Agent consignment sellers (shown as `[Agent] <name>`) | — (no equivalent; our NPC market maker) | Isembard Mortlake, Liesl Brackwell, Ottavio Quenby, Saoirse Ashgrove, Teodor Pargeter, Wilhelmina Holloway, Ebba Treloar, Caspian Fairweather, Rosalind Merriweather, Augustin Gaunt, Philippa Cresswell, Leopold Tolliver, Imogen Whitlock, Henrietta Pennyfeather, Delphine Ostrander, Bartholomew Coyle (renamed 2026-10-04: the old pool shared first/last names with ten world NPCs, e.g. Hollis/Town Crier Hollis; self-test auction.agent-names-unique now guards it) |
| Vault Auction (menu, `/vaultauction`) | Shen's Auction | **Vault Auction** |
| Vault Special lots | Shen's Special | **Special Lots** |
| `auction_vault` (NPC, hub `npc.auction_vault`, only if the hub builder adds that anchor) | Shen | **Vaultkeeper Ossery** (title `VAULT AUCTION`) |
| Ironman Vault lots (wave 5) | Ironman Shen's Auction | **Ironman** / **Ironman Special** lots |
| Regalia Room (menu, `/regaliaroom`, hub zone `regalia_room` if built) | Regalia Room (beneath the Hub Auction House, needs Shen's Regalia) | **Regalia Room** (generic; the item is accessories' *Collector's Regalia*) |
| `regalia_room` (NPC, hub `npc.regalia_room`, only if the hub builder adds that anchor) | — (ours: keeper of the Vault ledger) | **Ledgerwarden Verity** (title `REGALIA ROOM`; grep-checked unique against every NPC name) |
| mayor perk text | "QUAD TAXES" (Derpy) | "The mayor has raised the claim tax xN!" (the perk itself is the mayors module's to name; we only read its id from the dial `auction.quad-tax-perk`) |

Generic terms kept as-is: Auction House, Auctions Browser, Buy It Now / BIN, View Bids, Manage Auctions,
Create Auction, Bid History, Claim All, the six tab names (Weapons, Armor, Accessories, Consumables, Blocks,
Tools & Misc).

All NPC dialogue, menu lore and chat lines are our own wording (`data/auction/agents.json` +
`AuctionMenus.java` / `AuctionHouse.java`). Names and dialogue are data: edit `tools/gen/auction/gen_auction_data.py`
(`AGENTS`) and re-run it to change them.
