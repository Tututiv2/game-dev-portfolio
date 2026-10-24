# Factorio Mods: The Director and Fun Mods

Two Factorio 2.0 mods: an enemy "storyteller" that studies your base, and a toggleable bundle of cheats and quality-of-life tweaks.

**The Director** fills a gap I found by searching the mod portal. Tunnelling biters, quest systems and statically resistant biters all exist already. What did not exist was resistance that reacts to what you actually use, or a RimWorld-style director that picks where and when to hit you. **Fun Mods** collapses a handful of one-idea tweak mods into one entry with a toggle per feature, reimplemented natively rather than merged, so it never goes stale when an upstream mod updates.

## Features

**The Director**
- Surveys your base, finds the soft approach, waits for a weak moment, then strikes it
- Adaptive resistance: the swarm hardens against whatever damage type you rely on and forgets when you stop
- Recruits biters already nearby and spawns only the shortfall
- Learns from outcomes: a strike your turrets repel makes that approach less attractive for a while
- Intel is earned through three technologies, from "a strike happened" up to a full readout panel

**Fun Mods**
- A cheat panel (Ctrl+Shift+M or `/tfm`) with 35 live levers across tabs, plus an item-giver tab built on the game's own item picker
- A QoL layer: faster belts, better solar, bigger stacks, faster pavement, longer reach, infinite oil

## How it works

- **Pure, testable logic.** The Director's decision, escalation and memory model (`director.lua`) and the resistance model (`adaptation.lua`) use zero Factorio API, which is what makes them unit-testable inside a headless run.
- **Resistance by healing, not by prototypes.** Instead of cloning a resistant variant of every enemy prototype, a share of each hit is healed back as it lands. That works with modded enemies the mod knows nothing about and can be toggled mid-save with no restart. It is driven by a damage type's share, not its amount: any type under 15% earns nothing, so spreading across many weapons means the swarm never adapts. In a real world, after two game minutes of fire-only damage, fire hits land at 40.6 hp against a 100 hp baseline, while an unused damage type stays at exactly 100%.
- **Design rules as assertions.** "Defending works", "ignoring it escalates faster than waiting" (0.350 vs 0.040), and "it never invents a target" are checks in the self-test, not intentions.
- **Proving a negative before building around it.** I tested five ways to edit another mod's runtime settings (direct write, re-registering the prototype, looking for an owner field, and two more). All failed, because ownership is tracked engine-side. So the cheat panel writes game state directly (force bonuses, surface properties, map settings) and records a baseline on first write so Reset restores exactly what was there.
- **Stand-down rule.** Each QoL tweak disables itself while the mod it replaces is still enabled, so enabling both never multiplies twice (100x stacks instead of 10x). The test asserts this in both configurations.

## Tech

Lua on Factorio 2.0.77 (Space Age, Quality, Elevated Rails), PowerShell test harness.

## Running it

- `Deploy.bat` copies `tutis-fun-mods/` into `%APPDATA%\Factorio\mods`. Copy `the-director/` the same way.
- `Test.bat` (Factorio closed) runs `tools/test.ps1`: 34/34 Director checks, 6/6 Fun Mods checks, an in-world combat test using a throwaway test-rig mod, and a clean load against a 39-mod stack. Director tick cost is about 0.13 ms.

**Status:** the Director's endgame (a killable body on another planet) is designed and its state transition is tested, but nothing places that body yet. Cheat-panel GUI construction cannot be exercised headlessly and is guarded with `pcall`.
