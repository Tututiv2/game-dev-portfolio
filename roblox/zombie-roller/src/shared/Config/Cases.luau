--!strict
-- Cases.luau
-- Case DESIGNS + drop tables (§4.8, §4.9).
-- F2P cases (credits) use weighted rarity tables capped at Mythic.
-- Premium cases (cash) use the promotion tree: baseline Mythic, small chance to
-- reach Limited, then 5% to promote Limited->Secret, then 5% Secret->RGB.

export type CaseKind = "f2p" | "premium"

export type CaseDef = {
	id: string,
	displayName: string,
	kind: CaseKind,
	currency: string, -- "credits" | "cash" | "luxury" (luxury = spend a Luxury Case token)
	cost: number,
	weights: { [string]: number }?, -- f2p only
	floatCurve: number, -- >1 skews cleaner (lower float); premium higher
	pool: string?, -- "exclusive" => draws only from Luxury-only designs
	tagline: string?, -- shop subtitle
}

local Cases = {}

Cases.List = {
	field_crate = {
		id = "field_crate",
		displayName = "Field Crate",
		kind = "f2p",
		currency = "credits",
		cost = 500,
		floatCurve = 1.0,
		weights = { Common = 60, Rare = 25, Epic = 12, Legendary = 2.8, Mythic = 0.2 },
	} :: CaseDef,
	veteran_crate = {
		id = "veteran_crate",
		displayName = "Veteran Crate",
		kind = "f2p",
		currency = "credits",
		cost = 2000,
		floatCurve = 1.3,
		weights = { Common = 40, Rare = 30, Epic = 20, Legendary = 8.5, Mythic = 1.5 },
	} :: CaseDef,
	secret_case = {
		id = "secret_case",
		displayName = "Secret Case",
		kind = "premium",
		currency = "cash",
		cost = 2490,
		floatCurve = 2.0,
		tagline = "5% Chance to Obtain Serialized Skin!",
	} :: CaseDef,
	luxury_case = {
		id = "luxury_case",
		displayName = "Luxury Case",
		kind = "premium",
		currency = "luxury", -- opened with a Luxury Case token (earned via Robux spent)
		cost = 1,
		floatCurve = 2.5,
		pool = "exclusive",
		tagline = "Exclusive items — found nowhere else!",
	} :: CaseDef,
}

-- Premium promotion-tree constants (§4.9).
Cases.Premium = {
	limitedChance = 0.05, -- 5% to reach a serialized (Limited+) skin
	promoteChance = 0.05, -- Limited->Secret, and Secret->RGB
}

function Cases.get(caseId: string): CaseDef
	local c = Cases.List[caseId]
	assert(c, "Unknown case: " .. tostring(caseId))
	return c
end

return Cases
