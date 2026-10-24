--!strict
-- CasesController.luau
-- The case shop + the signature CS:GO-style opening animation (§4.10). The
-- SERVER decides the result first (RequestOpenCase); we only animate it: a strip
-- of cards scrolls and eases to a stop on the winning card, then a reveal panel
-- shows name / rarity / float+band / serial / tracker.

local Players = game:GetService("Players")
local RunService = game:GetService("RunService")
local TweenService = game:GetService("TweenService")
local MarketplaceService = game:GetService("MarketplaceService")
local ReplicatedStorage = game:GetService("ReplicatedStorage")

local Shared = ReplicatedStorage:WaitForChild("Shared")
local Remotes = require(Shared.Remotes)
local Cases = require(Shared.Config.Cases)
local Skins = require(Shared.Config.Skins)
local Weapons = require(Shared.Config.Weapons)
local Rarities = require(Shared.Config.Rarities)
local GameConfig = require(Shared.Config.GameConfig)
local Shop = require(Shared.Config.Shop)

local UiUtil = require(script.Parent.UiUtil)

local player = Players.LocalPlayer

local CasesController = {}

local CARD_W = 120
local CARD_PAD = 10
local STRIP_COUNT = 50
local WIN_INDEX = 43

local gui: ScreenGui
local panel: Frame
local requestBuy: RemoteFunction
local requestOpen: RemoteFunction
local requestClaim: RemoteFunction
local busy = false

-- shop state (from ShopUpdated)
local robuxSpent = 0
local luxuryClaimable = 0
local ownedCases: { [string]: number } = {}
local trackerFill: Frame
local trackerLabel: TextLabel
local claimBtn: TextButton

-- ── random filler for the spin strip ──────────────────────────────────────────
local FILLER_RARITIES = { "Common", "Common", "Common", "Rare", "Rare", "Epic", "Legendary", "Mythic" }
local function randomFiller(): (string, string, string)
	local rarity = FILLER_RARITIES[math.random(#FILLER_RARITIES)]
	local pool = Skins.getByRarity(rarity)
	if #pool == 0 then
		pool = Skins.getLimitedPool()
	end
	local def = pool[math.random(#pool)]
	local weaponDef = Weapons.List[def.weaponId]
	return rarity, def.displayName, weaponDef and weaponDef.displayName or "?"
end

-- ── reveal panel ────────────────────────────────────────────────────────────────
local function showReveal(instance: any, onClose: () -> ())
	local def = Skins.List[instance.skinId]
	local weaponDef = def and Weapons.List[def.weaponId]
	local color = UiUtil.rarityColor(instance.rarity)

	local overlay = Instance.new("Frame")
	overlay.Size = UDim2.new(1, 0, 1, 0)
	overlay.BackgroundColor3 = Color3.fromRGB(0, 0, 0)
	overlay.BackgroundTransparency = 0.35
	overlay.BorderSizePixel = 0
	overlay.ZIndex = 50
	overlay.Parent = gui

	local card = Instance.new("Frame")
	card.AnchorPoint = Vector2.new(0.5, 0.5)
	card.Position = UDim2.new(0.5, 0, 0.5, 0)
	card.Size = UDim2.new(0, 380, 0, 300)
	card.BackgroundColor3 = Color3.fromRGB(24, 24, 30)
	card.BorderSizePixel = 0
	card.ZIndex = 51
	UiUtil.corner(card, 14)
	UiUtil.stroke(card, color, 3)
	card.Parent = overlay

	local rarityLabel = Instance.new("TextLabel")
	rarityLabel.BackgroundTransparency = 1
	rarityLabel.Position = UDim2.new(0, 0, 0, 14)
	rarityLabel.Size = UDim2.new(1, 0, 0, 28)
	rarityLabel.Font = Enum.Font.GothamBlack
	rarityLabel.TextColor3 = color
	rarityLabel.TextSize = 22
	rarityLabel.ZIndex = 52
	rarityLabel.Text = Rarities.get(instance.rarity).displayName:upper()
	rarityLabel.Parent = card

	local nameLabel = Instance.new("TextLabel")
	nameLabel.BackgroundTransparency = 1
	nameLabel.Position = UDim2.new(0, 0, 0, 48)
	nameLabel.Size = UDim2.new(1, 0, 0, 34)
	nameLabel.Font = Enum.Font.GothamBold
	nameLabel.TextColor3 = Color3.fromRGB(245, 245, 245)
	nameLabel.TextSize = 26
	nameLabel.ZIndex = 52
	nameLabel.Text = string.format("%s | %s", weaponDef and weaponDef.displayName or "?", def and def.displayName or instance.skinId)
	nameLabel.Parent = card

	local bandLabel = Instance.new("TextLabel")
	bandLabel.BackgroundTransparency = 1
	bandLabel.Position = UDim2.new(0, 0, 0, 96)
	bandLabel.Size = UDim2.new(1, 0, 0, 24)
	bandLabel.Font = Enum.Font.Gotham
	bandLabel.TextColor3 = Color3.fromRGB(210, 210, 210)
	bandLabel.TextSize = 16
	bandLabel.ZIndex = 52
	bandLabel.Text = string.format("%s   (float %.4f)", GameConfig.floatBand(instance.float), instance.float)
	bandLabel.Parent = card

	-- float bar
	local floatTrack = Instance.new("Frame")
	floatTrack.AnchorPoint = Vector2.new(0.5, 0)
	floatTrack.Position = UDim2.new(0.5, 0, 0, 128)
	floatTrack.Size = UDim2.new(0, 300, 0, 8)
	floatTrack.BackgroundColor3 = Color3.fromRGB(50, 50, 56)
	floatTrack.BorderSizePixel = 0
	floatTrack.ZIndex = 52
	UiUtil.corner(floatTrack, 4)
	floatTrack.Parent = card
	local floatFill = Instance.new("Frame")
	floatFill.Size = UDim2.new(math.clamp(instance.float, 0, 1), 0, 1, 0)
	floatFill.BackgroundColor3 = color
	floatFill.BorderSizePixel = 0
	floatFill.ZIndex = 53
	UiUtil.corner(floatFill, 4)
	floatFill.Parent = floatTrack

	local extras = {}
	if instance.serialNumber and instance.serialNumber > 0 then
		table.insert(extras, "★ #" .. instance.serialNumber)
	end
	if instance.isTracker then
		table.insert(extras, "TRACKER")
	end
	table.insert(extras, "OG")
	local extraLabel = Instance.new("TextLabel")
	extraLabel.BackgroundTransparency = 1
	extraLabel.Position = UDim2.new(0, 0, 0, 150)
	extraLabel.Size = UDim2.new(1, 0, 0, 26)
	extraLabel.Font = Enum.Font.GothamBlack
	extraLabel.TextColor3 = Color3.fromRGB(255, 215, 90)
	extraLabel.TextSize = 18
	extraLabel.ZIndex = 52
	extraLabel.Text = table.concat(extras, "   ")
	extraLabel.Parent = card

	local again = UiUtil.button("Open Another", color)
	again.Size = UDim2.new(0, 150, 0, 40)
	again.AnchorPoint = Vector2.new(0.5, 1)
	again.Position = UDim2.new(0.5, -80, 1, -18)
	again.ZIndex = 52
	again.Parent = card

	local close = UiUtil.button("Close", Color3.fromRGB(70, 70, 80))
	close.Size = UDim2.new(0, 120, 0, 40)
	close.AnchorPoint = Vector2.new(0.5, 1)
	close.Position = UDim2.new(0.5, 90, 1, -18)
	close.ZIndex = 52
	close.Parent = card

	again.Activated:Connect(function()
		overlay:Destroy()
		onClose()
	end)
	close.Activated:Connect(function()
		overlay:Destroy()
	end)
end

-- ── spin animation ──────────────────────────────────────────────────────────────
local function playSpin(instance: any, reopen: () -> ())
	local overlay = Instance.new("Frame")
	overlay.Size = UDim2.new(1, 0, 1, 0)
	overlay.BackgroundColor3 = Color3.fromRGB(0, 0, 0)
	overlay.BackgroundTransparency = 0.4
	overlay.BorderSizePixel = 0
	overlay.ZIndex = 40
	overlay.ClipsDescendants = true
	overlay.Parent = gui

	local window = Instance.new("Frame")
	window.AnchorPoint = Vector2.new(0.5, 0.5)
	window.Position = UDim2.new(0.5, 0, 0.5, 0)
	window.Size = UDim2.new(0, 720, 0, 200)
	window.BackgroundColor3 = Color3.fromRGB(18, 18, 22)
	window.BorderSizePixel = 0
	window.ClipsDescendants = true
	window.ZIndex = 41
	UiUtil.corner(window, 12)
	window.Parent = overlay

	local strip = Instance.new("Frame")
	strip.BackgroundTransparency = 1
	strip.Size = UDim2.new(0, STRIP_COUNT * (CARD_W + CARD_PAD), 1, 0)
	strip.Position = UDim2.new(0, 0, 0, 0)
	strip.ZIndex = 42
	strip.Parent = window

	local def = Skins.List[instance.skinId]
	local winWeapon = def and Weapons.List[def.weaponId]
	for i = 1, STRIP_COUNT do
		local rarity, skinName, weaponName
		if i == WIN_INDEX then
			rarity = instance.rarity
			skinName = def and def.displayName or instance.skinId
			weaponName = winWeapon and winWeapon.displayName or "?"
		else
			rarity, skinName, weaponName = randomFiller()
		end
		local cardSize = UDim2.new(0, CARD_W, 0, 160)
		local card = UiUtil.rarityCard(rarity, skinName, weaponName, cardSize)
		card.Position = UDim2.new(0, (i - 1) * (CARD_W + CARD_PAD) + CARD_PAD / 2, 0.5, -80)
		card.ZIndex = 42
		card.Parent = strip
	end

	-- center pointer
	local pointer = Instance.new("Frame")
	pointer.AnchorPoint = Vector2.new(0.5, 0)
	pointer.Position = UDim2.new(0.5, 0, 0, 0)
	pointer.Size = UDim2.new(0, 3, 1, 0)
	pointer.BackgroundColor3 = Color3.fromRGB(255, 230, 120)
	pointer.BorderSizePixel = 0
	pointer.ZIndex = 44
	pointer.Parent = window

	RunService.Heartbeat:Wait() -- let AbsoluteSize populate
	local windowWidth = window.AbsoluteSize.X
	local cardStride = CARD_W + CARD_PAD
	local winCenter = (WIN_INDEX - 1) * cardStride + CARD_PAD / 2 + CARD_W / 2
	local jitter = math.random(-CARD_W // 3, CARD_W // 3)
	local targetX = windowWidth / 2 - winCenter + jitter

	local tween = TweenService:Create(
		strip,
		TweenInfo.new(5.2, Enum.EasingStyle.Quart, Enum.EasingDirection.Out),
		{ Position = UDim2.new(0, targetX, 0, 0) }
	)
	tween:Play()
	tween.Completed:Connect(function()
		task.wait(0.5)
		overlay:Destroy()
		showReveal(instance, reopen)
	end)
end

-- ── open / buy / claim requests ──────────────────────────────────────────────
-- Open an OWNED case item (server consumes it). `reopen` runs after the reveal
-- closes — used so the shop or inventory can restore itself. Public so the
-- inventory's CASES tab can trigger the same flow + animation.
function CasesController.openCaseFlow(caseId: string, reopen: (() -> ())?)
	if busy then
		return
	end
	busy = true
	-- pcall: if the invoke throws (server error / shutdown), `busy` must still
	-- clear or the case UI is bricked for the rest of the session.
	local ok, result = pcall(function()
		return requestOpen:InvokeServer(caseId)
	end)
	busy = false
	if not ok or type(result) ~= "table" or result.error then
		warn("[Shop] " .. tostring((type(result) == "table" and result.error) or result or "open failed"))
		return
	end
	playSpin(result.instance, reopen or function() end)
end

function CasesController.getCases(): { [string]: number }
	return ownedCases
end

local function buyRequest(caseId: string)
	if busy then
		return
	end
	local ok, result = pcall(function()
		return requestBuy:InvokeServer(caseId)
	end)
	if not ok or type(result) ~= "table" or result.error then
		warn("[Shop] " .. tostring((type(result) == "table" and result.error) or result or "buy failed"))
	end
end

local function claimRequest()
	if busy then
		return
	end
	pcall(function()
		return requestClaim:InvokeServer()
	end)
end

-- ── shop building blocks ──────────────────────────────────────────────────────
local function sectionHeader(parent: Instance, layoutOrder: number, accent: Color3, text: string)
	local h = Instance.new("TextLabel")
	h.LayoutOrder = layoutOrder
	h.Size = UDim2.new(1, 0, 0, 26)
	h.BackgroundTransparency = 1
	h.Font = Enum.Font.GothamBlack
	h.TextColor3 = accent
	h.TextSize = 16
	h.TextXAlignment = Enum.TextXAlignment.Left
	h.Text = text
	h.Parent = parent
end

local function caseRow(parent: Instance, layoutOrder: number, case: any)
	local featured = case.id == "secret_case"
	local accent = featured and Color3.fromRGB(230, 70, 160) or Color3.fromRGB(80, 140, 230)
	local row = Instance.new("Frame")
	row.LayoutOrder = layoutOrder
	row.Size = UDim2.new(1, 0, 0, featured and 66 or 56)
	row.BackgroundColor3 = Color3.fromRGB(30, 28, 36)
	row.BorderSizePixel = 0
	UiUtil.corner(row, 8)
	UiUtil.stroke(row, accent, featured and 2 or 1)
	row.Parent = parent

	local name = Instance.new("TextLabel")
	name.BackgroundTransparency = 1
	name.Position = UDim2.new(0, 12, 0, 8)
	name.Size = UDim2.new(0.62, 0, 0, 22)
	name.Font = Enum.Font.GothamBold
	name.TextXAlignment = Enum.TextXAlignment.Left
	name.TextColor3 = Color3.fromRGB(245, 245, 245)
	name.TextSize = 17
	name.Text = case.displayName
	name.Parent = row

	local sub = Instance.new("TextLabel")
	sub.BackgroundTransparency = 1
	sub.Position = UDim2.new(0, 12, 0, 30)
	sub.Size = UDim2.new(0.62, 0, 0, 18)
	sub.Font = Enum.Font.Gotham
	sub.TextXAlignment = Enum.TextXAlignment.Left
	sub.TextColor3 = Color3.fromRGB(180, 180, 185)
	sub.TextSize = 12
	sub.Text = case.tagline or "Buy to add to your inventory, then open or trade."
	sub.Parent = row

	-- BUY adds the case ITEM to your inventory (spend currency). Open it later
	-- from the inventory's CASES tab.
	local buy = UiUtil.button(string.format("BUY  •  %d %s", case.cost, case.currency), accent)
	buy.AnchorPoint = Vector2.new(1, 0.5)
	buy.Position = UDim2.new(1, -12, 0.5, 0)
	buy.Size = UDim2.new(0, 160, 0, 36)
	buy.TextSize = 13
	buy.Parent = row
	buy.Activated:Connect(function()
		buyRequest(case.id)
	end)
end

local function cashRow(parent: Instance, layoutOrder: number, bundle: any)
	local row = Instance.new("Frame")
	row.LayoutOrder = layoutOrder
	row.Size = UDim2.new(1, 0, 0, 48)
	row.BackgroundColor3 = Color3.fromRGB(28, 40, 30)
	row.BorderSizePixel = 0
	UiUtil.corner(row, 8)
	UiUtil.stroke(row, Color3.fromRGB(90, 200, 110), 1)
	row.Parent = parent

	local name = Instance.new("TextLabel")
	name.BackgroundTransparency = 1
	name.Position = UDim2.new(0, 12, 0, 0)
	name.Size = UDim2.new(0.55, 0, 1, 0)
	name.Font = Enum.Font.GothamBlack
	name.TextXAlignment = Enum.TextXAlignment.Left
	name.TextColor3 = Color3.fromRGB(230, 245, 230)
	name.TextSize = 18
	name.Text = string.format("%s Cash", tostring(bundle.cash))
	name.Parent = row

	local buy = UiUtil.button(string.format("R$ %d", bundle.robux), Color3.fromRGB(70, 175, 90))
	buy.AnchorPoint = Vector2.new(1, 0.5)
	buy.Position = UDim2.new(1, -12, 0.5, 0)
	buy.Size = UDim2.new(0, 130, 0, 34)
	buy.TextSize = 14
	buy.Parent = row
	buy.Activated:Connect(function()
		if bundle.productId ~= 0 then
			MarketplaceService:PromptProductPurchase(player, bundle.productId)
		else
			buy.Text = "set product id"
			task.delay(1.2, function()
				buy.Text = string.format("R$ %d", bundle.robux)
			end)
		end
	end)
end

local function refreshShop()
	if not trackerFill then
		return
	end
	local per = Shop.robuxPerLuxuryCase
	trackerFill.Size = UDim2.new(math.clamp((robuxSpent % per) / per, 0, 1), 0, 1, 0)
	trackerLabel.Text = string.format("%d / %d Robux  →  next Luxury Case", robuxSpent % per, per)

	-- CLAIM is grey/disabled until a Luxury Case is earned, then turns green.
	local ready = luxuryClaimable > 0
	claimBtn.AutoButtonColor = ready
	claimBtn.BackgroundColor3 = ready and Color3.fromRGB(60, 190, 90) or Color3.fromRGB(60, 54, 66)
	claimBtn.TextColor3 = ready and Color3.fromRGB(255, 255, 255) or Color3.fromRGB(150, 145, 150)
	claimBtn.Text = ready and string.format("CLAIM  (%d)", luxuryClaimable) or "CLAIM"
end

local function buildLuxurySection(parent: Instance, layoutOrder: number)
	local box = Instance.new("Frame")
	box.LayoutOrder = layoutOrder
	box.Size = UDim2.new(1, 0, 0, 116)
	box.BackgroundColor3 = Color3.fromRGB(34, 26, 44)
	box.BorderSizePixel = 0
	UiUtil.corner(box, 8)
	UiUtil.stroke(box, Color3.fromRGB(150, 60, 200), 1.5)
	box.Parent = parent

	local desc = Instance.new("TextLabel")
	desc.BackgroundTransparency = 1
	desc.Position = UDim2.new(0, 12, 0, 8)
	desc.Size = UDim2.new(1, -24, 0, 18)
	desc.Font = Enum.Font.Gotham
	desc.TextXAlignment = Enum.TextXAlignment.Left
	desc.TextColor3 = Color3.fromRGB(210, 190, 230)
	desc.TextSize = 12
	desc.Text = string.format("Every %d Robux spent on Cash = 1 Luxury Case (exclusive items!)", Shop.robuxPerLuxuryCase)
	desc.Parent = box

	local track = Instance.new("Frame")
	track.Position = UDim2.new(0, 12, 0, 34)
	track.Size = UDim2.new(1, -24, 0, 14)
	track.BackgroundColor3 = Color3.fromRGB(20, 16, 26)
	track.BorderSizePixel = 0
	UiUtil.corner(track, 7)
	track.Parent = box
	trackerFill = Instance.new("Frame")
	trackerFill.Size = UDim2.new(0, 0, 1, 0)
	trackerFill.BackgroundColor3 = Color3.fromRGB(190, 110, 255)
	trackerFill.BorderSizePixel = 0
	UiUtil.corner(trackerFill, 7)
	trackerFill.Parent = track

	trackerLabel = Instance.new("TextLabel")
	trackerLabel.BackgroundTransparency = 1
	trackerLabel.Position = UDim2.new(0, 12, 0, 50)
	trackerLabel.Size = UDim2.new(1, -24, 0, 16)
	trackerLabel.Font = Enum.Font.GothamBold
	trackerLabel.TextXAlignment = Enum.TextXAlignment.Left
	trackerLabel.TextColor3 = Color3.fromRGB(220, 205, 235)
	trackerLabel.TextSize = 12
	trackerLabel.Text = ""
	trackerLabel.Parent = box

	-- CLAIM grants a Luxury Case ITEM into your inventory. Grey until you've
	-- spent enough Robux, then it turns green.
	claimBtn = UiUtil.button("CLAIM", Color3.fromRGB(60, 54, 66))
	claimBtn.AnchorPoint = Vector2.new(0.5, 1)
	claimBtn.Position = UDim2.new(0.5, 0, 1, -10)
	claimBtn.Size = UDim2.new(1, -24, 0, 34)
	claimBtn.TextSize = 15
	claimBtn.AutoButtonColor = false
	claimBtn.Parent = box
	claimBtn.Activated:Connect(claimRequest)
end

local function buildPanel()
	panel = Instance.new("Frame")
	panel.AnchorPoint = Vector2.new(0.5, 0.5)
	panel.Position = UDim2.new(0.5, 0, 0.5, 0)
	panel.Size = UDim2.new(0, 480, 0, 500)
	panel.BackgroundColor3 = Color3.fromRGB(20, 19, 24)
	panel.BorderSizePixel = 0
	panel.Visible = false
	UiUtil.corner(panel, 12)
	UiUtil.stroke(panel, Color3.fromRGB(70, 64, 80), 2)
	panel.Parent = gui

	local title = Instance.new("TextLabel")
	title.BackgroundTransparency = 1
	title.Position = UDim2.new(0, 16, 0, 10)
	title.Size = UDim2.new(1, -60, 0, 36)
	title.Font = Enum.Font.GothamBlack
	title.TextXAlignment = Enum.TextXAlignment.Left
	title.TextColor3 = Color3.fromRGB(255, 255, 255)
	title.TextSize = 26
	title.Text = "SHOP"
	title.Parent = panel

	local close = UiUtil.button("✕", Color3.fromRGB(60, 54, 66))
	close.AnchorPoint = Vector2.new(1, 0)
	close.Position = UDim2.new(1, -12, 0, 12)
	close.Size = UDim2.new(0, 34, 0, 32)
	close.Parent = panel
	close.Activated:Connect(function()
		panel.Visible = false
	end)

	local list = Instance.new("ScrollingFrame")
	list.Position = UDim2.new(0, 12, 0, 52)
	list.Size = UDim2.new(1, -24, 1, -64)
	list.BackgroundTransparency = 1
	list.BorderSizePixel = 0
	list.ScrollBarThickness = 6
	list.CanvasSize = UDim2.new(0, 0, 0, 0)
	list.AutomaticCanvasSize = Enum.AutomaticSize.Y
	list.Parent = panel
	local layout = Instance.new("UIListLayout")
	layout.Padding = UDim.new(0, 8)
	layout.SortOrder = Enum.SortOrder.LayoutOrder
	layout.Parent = list

	local order = 0
	local function nextOrder(): number
		order += 1
		return order
	end

	-- Cases (Secret Case featured first; luxury opened via its own button below).
	sectionHeader(list, nextOrder(), Color3.fromRGB(230, 70, 160), "CASES")
	if Cases.List.secret_case then
		caseRow(list, nextOrder(), Cases.List.secret_case)
	end
	for _, case in pairs(Cases.List) do
		if case.id ~= "secret_case" and case.currency ~= "luxury" then
			caseRow(list, nextOrder(), case)
		end
	end

	-- Buy Cash with Robux.
	sectionHeader(list, nextOrder(), Color3.fromRGB(90, 210, 120), "BUY CASH (ROBUX)")
	for _, bundle in ipairs(Shop.CashBundles) do
		cashRow(list, nextOrder(), bundle)
	end

	-- Cumulative Robux → Luxury Case tracker.
	sectionHeader(list, nextOrder(), Color3.fromRGB(190, 110, 255), "CUMULATIVE ROBUX SPENT")
	buildLuxurySection(list, nextOrder())

	refreshShop()
end

function CasesController.init()
	requestBuy = Remotes.get("RequestBuyCase") :: RemoteFunction
	requestOpen = Remotes.get("RequestOpenCase") :: RemoteFunction
	requestClaim = Remotes.get("RequestClaimLuxury") :: RemoteFunction

	gui = Instance.new("ScreenGui")
	gui.Name = "AA_Shop"
	gui.ResetOnSpawn = false
	gui.IgnoreGuiInset = true
	gui.ZIndexBehavior = Enum.ZIndexBehavior.Sibling
	gui.Parent = player:WaitForChild("PlayerGui")

	buildPanel()

	local toggle = UiUtil.button("SHOP", Color3.fromRGB(80, 140, 230))
	toggle.AnchorPoint = Vector2.new(1, 0)
	toggle.Position = UDim2.new(0.5, -6, 0, 8)
	toggle.Size = UDim2.new(0, 110, 0, 36)
	toggle.Parent = gui
	toggle.Activated:Connect(function()
		panel.Visible = not panel.Visible
	end)

	local shopUpdated = Remotes.get("ShopUpdated") :: RemoteEvent
	shopUpdated.OnClientEvent:Connect(function(info: any)
		robuxSpent = info.robuxSpent or 0
		luxuryClaimable = info.luxuryClaimable or 0
		ownedCases = info.cases or {}
		refreshShop()
		if CasesController.onCasesChanged then
			CasesController.onCasesChanged(ownedCases)
		end
	end)
end

return CasesController
