--!strict
-- Shop.luau
-- Real-money side of the shop: Cash bundles bought with Robux, and the cumulative
-- Robux-spent tracker that grants a Luxury Case every threshold.
--
-- IMPORTANT: `productId` must be a real Developer Product id from YOUR published
-- game (create them in Studio: Game Settings can't, use the Creator Dashboard →
-- your game → Monetization → Developer Products). Until you fill these in, real
-- purchases won't fire; use the HUB DEV menu to simulate Robux spend for testing.

export type CashBundle = {
	id: string,
	cash: number,
	robux: number, -- display price (the real charge is set on the Developer Product)
	productId: number, -- Developer Product id (0 = not set up yet)
}

local Shop = {}

-- Every this many cumulative Robux spent on Cash grants one Luxury Case.
Shop.robuxPerLuxuryCase = 10000

Shop.CashBundles = {
	{ id = "cash_500", cash = 500, robux = 45, productId = 0 },
	{ id = "cash_2500", cash = 2500, robux = 225, productId = 0 },
	{ id = "cash_7500", cash = 7500, robux = 675, productId = 0 },
	{ id = "cash_25000", cash = 25000, robux = 2250, productId = 0 },
	{ id = "cash_75000", cash = 75000, robux = 6750, productId = 0 },
	{ id = "cash_250000", cash = 250000, robux = 22500, productId = 0 },
} :: { CashBundle }

function Shop.bundleByProductId(productId: number): CashBundle?
	for _, b in ipairs(Shop.CashBundles) do
		if b.productId == productId and productId ~= 0 then
			return b
		end
	end
	return nil
end

function Shop.bundleById(id: string): CashBundle?
	for _, b in ipairs(Shop.CashBundles) do
		if b.id == id then
			return b
		end
	end
	return nil
end

return Shop
