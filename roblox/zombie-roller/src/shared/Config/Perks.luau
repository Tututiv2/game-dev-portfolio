--!strict
-- Perks.luau
-- Perk DESIGNS (§3.5). Bought from vending machines, require power, last until
-- death. Starting with Jugger-Nog (the most important one). More are data-only
-- additions later: Speed Cola, Double Tap, Stamin-Up, Quick Revive, Mule Kick.

export type PerkDef = {
	id: string,
	displayName: string,
	cost: number,
	color: Color3,
	-- Effect knobs read by PerkService. Fields are optional per perk.
	maxHealth: number?, -- Jugger-Nog: overrides max health
	reloadMultiplier: number?, -- Speed Cola
	fireRateMultiplier: number?, -- Double Tap
	sprintMultiplier: number?, -- Stamin-Up
}

local Perks = {}

Perks.List = {
	Juggernog = {
		id = "Juggernog",
		displayName = "Jugger-Nog",
		cost = 2500,
		color = Color3.fromRGB(220, 60, 60),
		maxHealth = 250,
	} :: PerkDef,
	SpeedCola = {
		id = "SpeedCola",
		displayName = "Speed Cola",
		cost = 3000,
		color = Color3.fromRGB(120, 220, 120),
		reloadMultiplier = 0.5,
	} :: PerkDef,
}

function Perks.get(id: string): PerkDef
	local p = Perks.List[id]
	assert(p, "Unknown perk: " .. tostring(id))
	return p
end

return Perks
