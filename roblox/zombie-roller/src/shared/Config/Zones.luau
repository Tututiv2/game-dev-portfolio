--!strict
-- Zones.luau
-- Where the HUB and the ZOMBIE ARENA live in the world. The hub is the social /
-- market / case-opening area you spawn into; the arena is the BO2-style combat
-- area you enter via a pad. Kept well apart so arena zombies never wander into
-- the hub. Coordinates assume a default 512x512 baseplate (±256) — adjust if
-- you build a bigger map.

local Zones = {}

Zones.GroundY = 0

Zones.Hub = {
	spawn = Vector3.new(0, 3, 16),
	enterPad = Vector3.new(0, 0, 0),
}

Zones.Arena = {
	origin = Vector3.new(150, 0, 0),
	spawn = Vector3.new(150, 3, 10),
	ringRadius = 40, -- fallback zombie spawn ring (used only if no windows)
}

return Zones
