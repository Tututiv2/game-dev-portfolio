--!strict
-- Types.luau
-- Shared Luau type definitions. The most important is SkinInstance (§4.7):
-- the unique record stored per owned item. Float, serial, and Tracker counts
-- all travel WITH this record through trades (§9.5).

local Rarities = require(script.Parent.Config.Rarities)

export type RarityId = Rarities.RarityId

-- A skin DESIGN (authored once). Lives in Config/Skins.luau.
export type SkinDefinition = {
	skinId: string, -- e.g. "starfade_m4"
	displayName: string, -- e.g. "Starfade M4"
	weaponId: string, -- which gun it applies to
	baseRarity: RarityId, -- the rarity tier this design belongs to
	maxFloat: number, -- per-skin float cap (§4.5), default 1.0
	editionCap: number?, -- for serial-numbered skins (e.g. 1000)
	exclusive: boolean?, -- only obtainable from Luxury Cases; never in normal pools
}

-- A skin INSTANCE (the actual owned item — thousands can exist) (§4.7).
export type SkinInstance = {
	instanceId: string, -- globally unique GUID for this exact item
	skinId: string, -- which design
	rarity: RarityId, -- final rarity after promotion rolls
	float: number, -- 0.0 - 1.0 wear
	serialNumber: number, -- 0 if not Limited+
	isTracker: boolean,
	trackerKills: number,
	originalOwnerUserId: number, -- set once, NEVER changes (prestige field)
	ownerUserId: number, -- current holder (changes on trade/sale)
	previousOwnerIds: { number }, -- full ownership history
	acquiredAt: number, -- os.time() at creation
}

-- The result the server hands the client to animate a case opening (§4.10).
export type CaseRollResult = {
	caseId: string,
	instance: SkinInstance,
	isPityGrant: boolean,
}

return {}
