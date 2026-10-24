--!strict
-- Weapons.luau
-- Weapon DESIGNS (stats). The starting two from the Unity prototype (§10):
-- a pistol and an M4. Hitscan (raycast) weapons. Damage is applied SERVER-side
-- (§9.2) — these numbers are read by CombatService, not the client.

export type WeaponDef = {
	id: string,
	displayName: string,
	damage: number, -- per bullet, body shot
	headshotMultiplier: number,
	fireRate: number, -- rounds per second
	magSize: number,
	reserveAmmo: number,
	reloadTime: number, -- seconds
	automatic: boolean, -- hold to fire vs click
	range: number, -- max hitscan distance (studs)
	class: string?, -- viewmodel shape family: "pistol"|"smg"|"rifle"|"shotgun"|"raygun"
	-- Drop a Model named <viewModelId or id> into ReplicatedStorage.Assets (the
	-- `assets/` folder) to replace the procedural placeholder with a real mesh.
	viewModelId: string?,
}

local Weapons = {}

Weapons.List = {
	Pistol = {
		id = "Pistol",
		displayName = "M1911",
		damage = 40,
		headshotMultiplier = 2,
		fireRate = 5,
		magSize = 12,
		reserveAmmo = 96,
		reloadTime = 1.4,
		automatic = false,
		range = 300,
		class = "pistol",
	} :: WeaponDef,
	M4 = {
		id = "M4",
		displayName = "M4 Carbine",
		damage = 34,
		headshotMultiplier = 2,
		fireRate = 10,
		magSize = 30,
		reserveAmmo = 180,
		reloadTime = 2.2,
		automatic = true,
		range = 500,
		class = "rifle",
	} :: WeaponDef,
	MP5K = {
		id = "MP5K",
		displayName = "MP5K",
		damage = 22,
		headshotMultiplier = 2,
		fireRate = 14,
		magSize = 30,
		reserveAmmo = 240,
		reloadTime = 1.9,
		automatic = true,
		range = 300,
		class = "smg",
	} :: WeaponDef,
	R870 = {
		id = "R870",
		displayName = "R870 Shotgun",
		damage = 140,
		headshotMultiplier = 1.5,
		fireRate = 1.3,
		magSize = 6,
		reserveAmmo = 48,
		reloadTime = 2.6,
		automatic = false,
		range = 70,
		class = "shotgun",
	} :: WeaponDef,
	RayGun = {
		id = "RayGun",
		displayName = "Ray Gun",
		damage = 350,
		headshotMultiplier = 1,
		fireRate = 4,
		magSize = 20,
		reserveAmmo = 160,
		reloadTime = 2.5,
		automatic = false,
		range = 400,
		class = "raygun",
	} :: WeaponDef,
}

Weapons.StartingWeapon = "Pistol"

-- Pack-a-Punch upgrade (§3.4): 2x damage, bigger magazine, new name.
Weapons.PAP = {
	damageMult = 2,
	magMult = 1.5,
	reserveMult = 1.5,
}

function Weapons.get(id: string): WeaponDef
	local w = Weapons.List[id]
	assert(w, "Unknown weapon: " .. tostring(id))
	return w
end

-- Display name for a Pack-a-Punched weapon.
function Weapons.papName(def: WeaponDef): string
	return def.displayName .. " ✦"
end

return Weapons
