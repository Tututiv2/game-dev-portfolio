--!strict
-- Rarities.luau
-- The 8 rarity tiers (design doc §4.3). The top three (Limited/Secret/Secret RGB)
-- form a PROMOTION TREE: you can only reach Secret by upgrading a Limited, and
-- Secret RGB by upgrading a Secret. They are never rolled directly.

export type RarityId =
	"Common" | "Rare" | "Epic" | "Legendary" | "Mythic" | "Limited" | "Secret" | "SecretRGB"

export type Rarity = {
	id: RarityId,
	displayName: string,
	order: number, -- 1 = most common
	color: Color3,
	isSerialNumbered: boolean, -- gets a global serial number on creation
	isAnimated: boolean, -- RGB shimmer
}

local function rgb(r: number, g: number, b: number): Color3
	return Color3.fromRGB(r, g, b)
end

local Rarities = {}

Rarities.List = {
	Common = { id = "Common", displayName = "Common", order = 1, color = rgb(176, 195, 217), isSerialNumbered = false, isAnimated = false },
	Rare = { id = "Rare", displayName = "Rare", order = 2, color = rgb(75, 105, 255), isSerialNumbered = false, isAnimated = false },
	Epic = { id = "Epic", displayName = "Epic", order = 3, color = rgb(136, 71, 255), isSerialNumbered = false, isAnimated = false },
	Legendary = { id = "Legendary", displayName = "Legendary", order = 4, color = rgb(235, 75, 200), isSerialNumbered = false, isAnimated = false },
	Mythic = { id = "Mythic", displayName = "Mythic", order = 5, color = rgb(235, 65, 65), isSerialNumbered = false, isAnimated = false },
	Limited = { id = "Limited", displayName = "Limited", order = 6, color = rgb(255, 200, 60), isSerialNumbered = true, isAnimated = false },
	Secret = { id = "Secret", displayName = "Secret", order = 7, color = rgb(255, 240, 200), isSerialNumbered = true, isAnimated = false },
	SecretRGB = { id = "SecretRGB", displayName = "Secret RGB", order = 8, color = rgb(255, 255, 255), isSerialNumbered = true, isAnimated = true },
}

-- Ordered list (common -> rarest) for iteration / UI.
Rarities.Ordered = {
	Rarities.List.Common,
	Rarities.List.Rare,
	Rarities.List.Epic,
	Rarities.List.Legendary,
	Rarities.List.Mythic,
	Rarities.List.Limited,
	Rarities.List.Secret,
	Rarities.List.SecretRGB,
}

function Rarities.get(id: RarityId): Rarity
	local r = Rarities.List[id]
	assert(r, "Unknown rarity: " .. tostring(id))
	return r
end

return Rarities
