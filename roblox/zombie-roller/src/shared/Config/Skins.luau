--!strict
-- Skins.luau
-- Skin DESIGNS (§4.7 design-vs-instance). Authored once. Instances are created
-- at drop time by CaseService. The Limited-tier designs are the pool that can be
-- promoted up to Secret / Secret RGB (§4.3) — same design, higher rarity.

local Types = require(script.Parent.Parent.Types)

type SkinDefinition = Types.SkinDefinition

local Skins = {}

Skins.List = {
	-- Common
	rust_m4 = { skinId = "rust_m4", displayName = "Rust", weaponId = "M4", baseRarity = "Common", maxFloat = 1.0 },
	woodland_pistol = { skinId = "woodland_pistol", displayName = "Woodland", weaponId = "Pistol", baseRarity = "Common", maxFloat = 1.0 },
	-- Rare
	azure_mp5k = { skinId = "azure_mp5k", displayName = "Azure", weaponId = "MP5K", baseRarity = "Rare", maxFloat = 0.9 },
	crimson_m4 = { skinId = "crimson_m4", displayName = "Crimson", weaponId = "M4", baseRarity = "Rare", maxFloat = 0.9 },
	-- Epic
	voltage_r870 = { skinId = "voltage_r870", displayName = "Voltage", weaponId = "R870", baseRarity = "Epic", maxFloat = 0.8 },
	neon_mp5k = { skinId = "neon_mp5k", displayName = "Neon Grid", weaponId = "MP5K", baseRarity = "Epic", maxFloat = 0.8 },
	-- Legendary
	phantom_m4 = { skinId = "phantom_m4", displayName = "Phantom", weaponId = "M4", baseRarity = "Legendary", maxFloat = 0.7 },
	-- Mythic
	dragon_raygun = { skinId = "dragon_raygun", displayName = "Dragonfire", weaponId = "RayGun", baseRarity = "Mythic", maxFloat = 0.5 },
	golden_m4 = { skinId = "golden_m4", displayName = "Gilded", weaponId = "M4", baseRarity = "Mythic", maxFloat = 0.5 },
	-- Limited (serial-numbered; also the pool for Secret / Secret RGB promotions)
	starfade_m4 = { skinId = "starfade_m4", displayName = "Starfade", weaponId = "M4", baseRarity = "Limited", maxFloat = 0.6, editionCap = 1000 },

	-- Exclusive (LUXURY CASE ONLY — never appear in any other case/pool) (§4 luxury)
	projectx_raygun = { skinId = "projectx_raygun", displayName = "Project: X", weaponId = "RayGun", baseRarity = "Secret", maxFloat = 0.4, editionCap = 250, exclusive = true },
	prism_twins_pistol = { skinId = "prism_twins_pistol", displayName = "Prism Twins", weaponId = "Pistol", baseRarity = "Secret", maxFloat = 0.4, editionCap = 250, exclusive = true },
	apex_m4 = { skinId = "apex_m4", displayName = "Project: X - Apex", weaponId = "M4", baseRarity = "SecretRGB", maxFloat = 0.35, editionCap = 100, exclusive = true },
	prism_apex_mp5k = { skinId = "prism_apex_mp5k", displayName = "Prism Apex", weaponId = "MP5K", baseRarity = "SecretRGB", maxFloat = 0.35, editionCap = 100, exclusive = true },
} :: { [string]: SkinDefinition }

-- Designs whose baseRarity matches `rarity` (exclusives are kept out of normal pools).
function Skins.getByRarity(rarity: string): { SkinDefinition }
	local out: { SkinDefinition } = {}
	for _, def in pairs(Skins.List) do
		if def.baseRarity == rarity and not def.exclusive then
			table.insert(out, def)
		end
	end
	return out
end

-- The pool used for Limited / Secret / Secret RGB drops (all share Limited designs).
function Skins.getLimitedPool(): { SkinDefinition }
	return Skins.getByRarity("Limited")
end

-- Luxury-case-only designs.
function Skins.getExclusivePool(): { SkinDefinition }
	local out: { SkinDefinition } = {}
	for _, def in pairs(Skins.List) do
		if def.exclusive then
			table.insert(out, def)
		end
	end
	return out
end

function Skins.get(skinId: string): SkinDefinition
	local def = Skins.List[skinId]
	assert(def, "Unknown skin: " .. tostring(skinId))
	return def
end

return Skins
