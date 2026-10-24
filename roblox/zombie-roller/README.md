# Zombie Roller

A round-based co-op zombies shooter for Roblox, with a server-authoritative case-opening and serial-numbered skin economy, written in Luau and synced with Rojo.

The game pairs Black Ops 2-style round survival (doors, power, wallbuys, Mystery Box, perks, Pack-a-Punch, power-ups, dog rounds) with a cosmetic skin economy: cases are inventory items, every roll happens on the server, and rare skins carry global serial numbers that stay with the item permanently. Skins are purely cosmetic. Everything is code-first: map stations are generated and discovered by tag, and all tuning lives in shared config tables.

## Features

- Rounds that spawn `round * 5` zombies with health scaling `base * 1.1^(round-1)`, walk/run/sprint tiers, intermissions and dog rounds
- Pathfinding zombies that breach through boarded windows; players hold E to rebuild boards (+10 per board)
- Buyable doors, a global power switch, wallbuys (repurchase refills ammo at half price), Mystery Box, perks, Pack-a-Punch (2x damage, bigger mag)
- Power-ups on about 2% of kills: Max Ammo, Double Points, Carpenter, Insta-Kill, Nuke
- First-person camera, delayed health regen, weapon switching, and a procedural viewmodel with sway, bob, recoil and muzzle flash
- Hub and arena zones; rounds run only while someone is in the arena
- Case economy: 8 rarity tiers, weighted cases plus a promotion tree, float wear, a 5% tracker roll, a CS:GO-style spin reveal, inventory grid with equip and scrap
- End-of-match credit reward and a developer-only dev menu (about 18 test commands)

## How it works

- **Server decides every valuable outcome.** The client sends requests over a single registry of remotes; services validate, mutate and replicate. `CaseService` rolls rarity, float and tracker on the server before the client animation starts.
- **Atomic global serials.** `SerialService` allocates serial numbers with `DataStore:UpdateAsync`, which is atomic across servers, so two simultaneous pulls of the same skin can never share a number. In Studio without API access it falls back to an in-memory counter so the game stays testable offline.
- **Pooled zombies.** Zombie models are reused rather than created and destroyed, and the Humanoid stays alive permanently with gameplay health tracked separately, to support large hordes without replication churn.
- **A review pass that closed data-loss and exploit paths.** A failed DataStore load used to hand the player an empty profile that autosave then wrote over their real data; reads now retry, and a profile that failed to load is never saved. Shots are rejected if the claimed ray origin is too far from the shooter's body, or if a vector is NaN or infinite (NaN passes every comparison). Currency and points reject negative or non-finite amounts, since a negative spend would credit the player. Arena credits only count rounds elapsed during the player's own run, closing an enter/exit farming loop. A run token stops an old round loop from doubling up after a restart.

## Tech

Luau (`--!strict` where practical), Rojo 7.5.1 pinned with Aftman, Roblox services: DataStoreService, PathfindingService, CollectionService, ProximityPrompt.

## Running it

```
aftman install
rojo serve                                   # then Connect from the Rojo plugin in Studio
rojo build --output ZombieRoller.rbxlx       # standalone place file
```

Enable "Studio Access to API Services" in Game Settings for DataStores. A built place file is not included.

**Status:** the zombies loop and the first half of the skin economy work on placeholder geometry. A hand-built map, downed/revive co-op, trading and the second game mode are planned but not built.
