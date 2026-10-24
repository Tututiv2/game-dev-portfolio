--!strict
-- GameConfig.luau
-- Central tuning numbers for the zombies core loop (design doc §3) and the
-- economy reward formulas (§4.1). One source of truth — read from here, never
-- hard-code magic numbers in services.

local GameConfig = {}

-- ── Rounds / waves (§3.2) ────────────────────────────────────────────────
GameConfig.Round = {
	zombiesPerRound = function(round: number): number
		return round * 5
	end,
	-- health = base * 1.1^(round-1), capped around round 50.
	zombieBaseHealth = 150,
	healthGrowth = 1.1,
	healthCapRound = 50,
	intermissionSeconds = 8,
	-- Movement tiers by round.
	walkUntilRound = 4, -- 1-4 walk
	runUntilRound = 9, -- 5-9 run, 10+ sprint
	walkSpeed = 6,
	runSpeed = 11,
	sprintSpeed = 16,
	-- Hellhound (dog) rounds roughly every N rounds (§3.2).
	dogRoundInterval = 5,
}

-- ── Essence (in-match points) earned (§3.3) ──────────────────────────────
GameConfig.Points = {
	hit = 10,
	killBody = 90,
	killHeadshot = 130,
	rebuildBoard = 10,
}

-- ── Player / health (§3.8) ───────────────────────────────────────────────
GameConfig.Player = {
	baseMaxHealth = 100,
	juggernogMaxHealth = 250,
	regenDelaySeconds = 5,
	regenPerSecond = 25,
	downedBleedoutSeconds = 30,
	startingWalkSpeed = 16,
	sprintMultiplier = 1.5,
}

-- ── Power-up drop chance (§3.6) ──────────────────────────────────────────
GameConfig.PowerUps = {
	dropChance = 0.02,
	durationSeconds = 30,
	nukeBonus = 400,
	carpenterBonus = 200,
	bonusPointsSelf = 2000,
	bonusPointsOthers = 100,
}

-- ── Economy reward formula (§4.1) ────────────────────────────────────────
-- credits = (highestRound * 50) + (totalKills * 5) + (firstWinOfDay ? 200 : 0)
GameConfig.Economy = {
	creditsPerRound = 50,
	creditsPerKill = 5,
	firstWinOfDayBonus = 200,
	marketFeePercent = 0.05, -- 5% sink on sales (§5.2)
	trackerRollChance = 0.05, -- independent 5% on every drop (§4.6)
}

-- ── Scrap value: dismantling a skin → Credits, by rarity ──────────────────
-- Scaled against case costs (Field Crate 500 / Veteran 2000) so scrapping is a
-- meaningful way to recoup Credits toward your next case.
GameConfig.ScrapCredits = {
	Common = 50,
	Rare = 120,
	Epic = 250,
	Legendary = 500,
	Mythic = 1000,
	Limited = 2500,
	Secret = 5000,
	SecretRGB = 10000,
}

function GameConfig.scrapValue(rarity: string): number
	return GameConfig.ScrapCredits[rarity] or 25
end

-- ── Float / wear bands (§4.5) ────────────────────────────────────────────
GameConfig.FloatBands = {
	{ name = "Factory New", max = 0.07 },
	{ name = "Minimal Wear", max = 0.15 },
	{ name = "Field-Tested", max = 0.38 },
	{ name = "Well-Worn", max = 0.45 },
	{ name = "Battle-Scarred", max = 1.00 },
}

function GameConfig.floatBand(float: number): string
	for _, band in ipairs(GameConfig.FloatBands) do
		if float <= band.max then
			return band.name
		end
	end
	return "Battle-Scarred"
end

return GameConfig
