--!strict
-- InventoryController.luau
-- Full-screen survivor-themed inventory (recreated from reference art): grunge
-- panel, 45-slot grid, category rail, detail pane with skull preview, and a
-- bottom player/currency bar. Populated from the player's real owned skins
-- (InventoryUpdated) and currencies (CurrencyUpdated).

local Players = game:GetService("Players")
local ReplicatedStorage = game:GetService("ReplicatedStorage")

local Shared = ReplicatedStorage:WaitForChild("Shared")
local Remotes = require(Shared.Remotes)
local Skins = require(Shared.Config.Skins)
local Weapons = require(Shared.Config.Weapons)
local Cases = require(Shared.Config.Cases)
local GameConfig = require(Shared.Config.GameConfig)

local UiUtil = require(script.Parent.UiUtil)
local CasesController = require(script.Parent.CasesController)

local player = Players.LocalPlayer

local InventoryController = {}

-- ── palette ──────────────────────────────────────────────────────────────────
local PANEL_BG = Color3.fromRGB(22, 17, 17)
local PANEL_BG2 = Color3.fromRGB(10, 8, 8)
local TILE = Color3.fromRGB(26, 21, 21)
local TILE2 = Color3.fromRGB(16, 13, 13)
local METAL = Color3.fromRGB(78, 69, 62)
local METAL_DK = Color3.fromRGB(44, 38, 35)
local RED = Color3.fromRGB(196, 42, 38)
local RED_DK = Color3.fromRGB(110, 26, 24)
local TEXT = Color3.fromRGB(223, 216, 210)
local MUTED = Color3.fromRGB(138, 128, 122)

local CAPACITY = 45
local COLS = 7
local CELL = 68

local CATEGORIES = {
	{ id = "WEAPONS", icon = "🔫" },
	{ id = "CASES", icon = "🎁" },
	{ id = "SNIPERS", icon = "🎯" },
	{ id = "MELEE", icon = "🔪" },
	{ id = "GEAR", icon = "🦺" },
	{ id = "GLOVES", icon = "🧤" },
	{ id = "MISC", icon = "⚙" },
}

-- ── module state ───────────────────────────────────────────────────────────
local gui: ScreenGui
local dim: TextButton
local panel: Frame
local slotFrames: { Frame } = {}
local slotData: { [number]: any } = {}
local capacityLabel: TextLabel
local selectedSlot: number? = nil
local selectedCategory = "WEAPONS"
local categoryButtons: { [string]: TextButton } = {}

-- detail pane refs
local detailHeader: TextLabel
local detailSub: TextLabel
local detailRarity: TextLabel
local detailStats: TextLabel
local openCaseBtn: TextButton
local equipBtn: TextButton
local scrapBtn: TextButton
local selectedCaseId: string? = nil
local selectedSkinId: string? = nil -- instanceId of the selected skin

local latestCases: { [string]: number } = {}
local equippedMap: { [string]: string } = {} -- weaponId -> instanceId

local requestEquip: RemoteFunction
local requestUnequip: RemoteFunction
local requestScrap: RemoteFunction

local function isEquipped(instance: any): boolean
	local def = Skins.List[instance.skinId]
	return def ~= nil and equippedMap[def.weaponId] == instance.instanceId
end

local function caseAccent(caseId: string): Color3
	if caseId == "luxury_case" then
		return Color3.fromRGB(190, 110, 255)
	elseif caseId == "secret_case" then
		return Color3.fromRGB(230, 70, 160)
	end
	return Color3.fromRGB(80, 140, 230)
end

-- bottom bar refs
local credLabel: TextLabel
local cashLabel: TextLabel

local latest: { any } = {}

-- ── small builders ───────────────────────────────────────────────────────────
local function plate(parent: Instance, color: Color3, strokeColor: Color3, radius: number): Frame
	local f = Instance.new("Frame")
	f.BackgroundColor3 = color
	f.BorderSizePixel = 0
	UiUtil.corner(f, radius)
	UiUtil.stroke(f, strokeColor, 1.5)
	f.Parent = parent
	return f
end

local function gradient(inst: Instance, top: Color3, bottom: Color3, rotation: number)
	local g = Instance.new("UIGradient")
	g.Color = ColorSequence.new(top, bottom)
	g.Rotation = rotation
	g.Parent = inst
	return g
end

local function text(parent: Instance, props: { [string]: any }): TextLabel
	local l = Instance.new("TextLabel")
	l.BackgroundTransparency = 1
	l.Font = Enum.Font.GothamBold
	l.TextColor3 = TEXT
	l.TextSize = 16
	l.TextXAlignment = Enum.TextXAlignment.Left
	for k, v in pairs(props) do
		(l :: any)[k] = v
	end
	l.Parent = parent
	return l
end

-- ── detail pane ────────────────────────────────────────────────────────────
local function hideActionButtons()
	if openCaseBtn then
		openCaseBtn.Visible = false
	end
	if equipBtn then
		equipBtn.Visible = false
	end
	if scrapBtn then
		scrapBtn.Visible = false
	end
end

local function showEmptyDetail()
	detailHeader.Text = "SELECT AN ITEM"
	detailHeader.TextColor3 = TEXT
	detailSub.Text = "Choose an item to view its details"
	detailSub.TextColor3 = MUTED
	detailRarity.Text = ""
	detailStats.Text = ""
	selectedCaseId = nil
	selectedSkinId = nil
	hideActionButtons()
end

local function showCaseDetail(caseId: string, count: number)
	hideActionButtons()
	selectedSkinId = nil
	local case = Cases.List[caseId]
	local accent = caseAccent(caseId)
	detailHeader.Text = case and case.displayName or caseId
	detailHeader.TextColor3 = accent
	detailRarity.Text = string.format("OWNED  x%d", count)
	detailRarity.TextColor3 = accent
	detailSub.Text = "Case Item"
	detailSub.TextColor3 = MUTED
	detailStats.Text = (case and case.tagline or "A sealed case.") .. "\n\nOpen it for a skin, or trade it to another player."
	selectedCaseId = caseId
	openCaseBtn.Visible = true
	openCaseBtn.BackgroundColor3 = accent
end

local function showDetail(instance: any)
	selectedCaseId = nil
	selectedSkinId = instance.instanceId
	hideActionButtons()
	local def = Skins.List[instance.skinId]
	local weaponDef = def and Weapons.List[def.weaponId]
	local skinName = def and def.displayName or instance.skinId
	local weaponName = weaponDef and weaponDef.displayName or "?"

	detailHeader.Text = string.format("%s | %s", weaponName, skinName)
	detailHeader.TextColor3 = TEXT
	detailRarity.Text = string.upper(instance.rarity)
	detailRarity.TextColor3 = UiUtil.rarityColor(instance.rarity)

	local lines = {
		string.format("Float: %.4f  (%s)", instance.float, UiUtil.floatBand(instance.float)),
	}
	if instance.serialNumber and instance.serialNumber > 0 then
		table.insert(lines, string.format("Serial: #%d", instance.serialNumber))
	end
	if instance.isTracker then
		table.insert(lines, string.format("Tracker kills: %d", instance.trackerKills or 0))
	end
	local equipped = isEquipped(instance)
	detailSub.Text = equipped and ("Equipped on " .. weaponName) or "Owned"
	detailSub.TextColor3 = equipped and Color3.fromRGB(120, 230, 140) or MUTED
	detailStats.Text = table.concat(lines, "\n")

	-- EQUIP toggles to UNEQUIP when this is the active skin on its weapon.
	equipBtn.Visible = true
	if equipped then
		equipBtn.Text = "UNEQUIP"
		equipBtn.BackgroundColor3 = METAL_DK
	else
		equipBtn.Text = "EQUIP"
		equipBtn.BackgroundColor3 = Color3.fromRGB(60, 120, 200)
	end

	scrapBtn.Visible = true
	scrapBtn.Text = string.format("SCRAP  +%d 🪙", GameConfig.scrapValue(instance.rarity))
end

-- ── slots ────────────────────────────────────────────────────────────────────
local function clearSelection()
	if selectedSlot and slotFrames[selectedSlot] then
		local s = slotFrames[selectedSlot]:FindFirstChildOfClass("UIStroke")
		if s then
			local inst = slotData[selectedSlot]
			s.Color = inst and UiUtil.rarityColor(inst.rarity) or METAL
			s.Thickness = 1.5
		end
		local glow = slotFrames[selectedSlot]:FindFirstChild("Glow")
		if glow then
			glow.Visible = false
		end
	end
	selectedSlot = nil
end

local function selectSlot(index: number)
	clearSelection()
	selectedSlot = index
	local frame = slotFrames[index]
	local s = frame:FindFirstChildOfClass("UIStroke")
	if s then
		s.Color = RED
		s.Thickness = 2.5
	end
	local glow = frame:FindFirstChild("Glow") :: Frame?
	if glow then
		glow.Visible = true
	end
	local inst = slotData[index]
	if inst and inst.isCase then
		showCaseDetail(inst.caseId, inst.count)
	elseif inst then
		showDetail(inst)
	else
		showEmptyDetail()
	end
end

local function buildSlot(index: number, parent: Instance): Frame
	local slot = Instance.new("Frame")
	slot.Name = "Slot" .. index
	slot.BackgroundColor3 = TILE
	slot.BorderSizePixel = 0
	UiUtil.corner(slot, 4)
	gradient(slot, TILE, TILE2, 90)
	UiUtil.stroke(slot, METAL, 1.5)

	-- red selection glow (hidden until selected)
	local glow = Instance.new("Frame")
	glow.Name = "Glow"
	glow.BackgroundColor3 = RED
	glow.BackgroundTransparency = 0.78
	glow.Size = UDim2.new(1, 0, 1, 0)
	glow.BorderSizePixel = 0
	glow.Visible = false
	UiUtil.corner(glow, 4)
	glow.Parent = slot

	-- rarity art block (only shown when filled)
	local art = Instance.new("Frame")
	art.Name = "Art"
	art.AnchorPoint = Vector2.new(0.5, 0.5)
	art.Position = UDim2.new(0.5, 0, 0.5, 0)
	art.Size = UDim2.new(1, -14, 1, -14)
	art.BackgroundColor3 = Color3.fromRGB(40, 32, 32)
	art.BorderSizePixel = 0
	art.Visible = false
	UiUtil.corner(art, 3)
	art.Parent = slot

	local tag = Instance.new("TextLabel")
	tag.Name = "Tag"
	tag.BackgroundTransparency = 1
	tag.AnchorPoint = Vector2.new(0.5, 1)
	tag.Position = UDim2.new(0.5, 0, 1, -2)
	tag.Size = UDim2.new(1, -4, 0, 14)
	tag.Font = Enum.Font.GothamBold
	tag.TextColor3 = TEXT
	tag.TextSize = 9
	tag.TextTruncate = Enum.TextTruncate.AtEnd
	tag.Text = ""
	tag.Visible = false
	tag.Parent = slot

	local btn = Instance.new("TextButton")
	btn.BackgroundTransparency = 1
	btn.Text = ""
	btn.Size = UDim2.new(1, 0, 1, 0)
	btn.Parent = slot
	btn.Activated:Connect(function()
		selectSlot(index)
	end)

	slot.Parent = parent
	return slot
end

local function refresh()
	-- Reset every slot to empty.
	for i = 1, CAPACITY do
		slotData[i] = nil
		local slot = slotFrames[i]
		;(slot:FindFirstChild("Art") :: Frame).Visible = false
		;(slot:FindFirstChild("Tag") :: TextLabel).Visible = false
		local s = slot:FindFirstChildOfClass("UIStroke")
		if s then
			s.Color = METAL
			s.Thickness = 1.5
		end
		;(slot:FindFirstChild("Glow") :: Frame).Visible = false
	end
	selectedSlot = nil

	local filled = 0

	if selectedCategory == "CASES" then
		-- One slot per owned case type, with a count.
		latestCases = CasesController.getCases()
		local i = 0
		for caseId, count in pairs(latestCases) do
			if count > 0 then
				i += 1
				if i > CAPACITY then
					break
				end
				local case = Cases.List[caseId]
				local color = caseAccent(caseId)
				slotData[i] = { isCase = true, caseId = caseId, count = count }
				local slot = slotFrames[i]

				local art = slot:FindFirstChild("Art") :: Frame
				art.Visible = true
				art.BackgroundColor3 = color
				art.BackgroundTransparency = 0.15

				local tag = slot:FindFirstChild("Tag") :: TextLabel
				tag.Visible = true
				tag.Text = string.format("%s  x%d", case and case.displayName or caseId, count)

				local s = slot:FindFirstChildOfClass("UIStroke")
				if s then
					s.Color = color
					s.Thickness = 2
				end
			end
		end
		filled = i
	else
		-- Only WEAPONS holds our skins for now; other tabs are empty.
		local items = selectedCategory == "WEAPONS" and latest or {}
		for i, instance in ipairs(items) do
			if i > CAPACITY then
				break
			end
			slotData[i] = instance
			local slot = slotFrames[i]
			local def = Skins.List[instance.skinId]
			local weaponDef = def and Weapons.List[def.weaponId]
			local color = UiUtil.rarityColor(instance.rarity)

			local art = slot:FindFirstChild("Art") :: Frame
			art.Visible = true
			art.BackgroundColor3 = color
			art.BackgroundTransparency = 0.15

			local equipped = isEquipped(instance)
			local tag = slot:FindFirstChild("Tag") :: TextLabel
			tag.Visible = true
			tag.Text = (equipped and "✓ " or "") .. (weaponDef and weaponDef.displayName or instance.skinId)

			local s = slot:FindFirstChildOfClass("UIStroke")
			if s then
				s.Color = equipped and Color3.fromRGB(120, 230, 140) or color
				s.Thickness = equipped and 3 or 2
			end
		end
		filled = math.min(#items, CAPACITY)
	end

	capacityLabel.Text = string.format("SLOT CAPACITY  %d/%d", filled, CAPACITY)
	showEmptyDetail()
end

-- ── category rail ────────────────────────────────────────────────────────────
local function selectCategory(id: string)
	selectedCategory = id
	for cid, btn in pairs(categoryButtons) do
		local active = cid == id
		btn.BackgroundColor3 = active and RED_DK or METAL_DK
		btn.TextColor3 = active and Color3.fromRGB(255, 235, 230) or MUTED
		local s = btn:FindFirstChildOfClass("UIStroke")
		if s then
			s.Color = active and RED or METAL
		end
	end
	refresh()
end

-- ── panel ────────────────────────────────────────────────────────────────────
local function buildPanel()
	panel = Instance.new("Frame")
	panel.AnchorPoint = Vector2.new(0.5, 0.5)
	panel.Position = UDim2.new(0.5, 0, 0.5, 0)
	panel.Size = UDim2.new(0, 1000, 0, 600)
	panel.BackgroundColor3 = PANEL_BG
	panel.BorderSizePixel = 0
	panel.Visible = false
	UiUtil.corner(panel, 8)
	gradient(panel, PANEL_BG, PANEL_BG2, 90)
	UiUtil.stroke(panel, METAL, 2)
	-- scale to fit smaller viewports
	local scale = Instance.new("UIScale")
	scale.Parent = panel
	local function fit()
		local vp = workspace.CurrentCamera and workspace.CurrentCamera.ViewportSize or Vector2.new(1280, 720)
		scale.Scale = math.min(1, vp.X / 1040, vp.Y / 640)
	end
	fit()
	if workspace.CurrentCamera then
		workspace.CurrentCamera:GetPropertyChangedSignal("ViewportSize"):Connect(fit)
	end
	panel.Parent = gui

	-- subtle red vignette overlay
	local vignette = Instance.new("Frame")
	vignette.BackgroundColor3 = RED_DK
	vignette.BackgroundTransparency = 0.86
	vignette.Size = UDim2.new(1, 0, 1, 0)
	vignette.BorderSizePixel = 0
	UiUtil.corner(vignette, 8)
	gradient(vignette, RED_DK, PANEL_BG2, 35)
	vignette.Parent = panel

	-- ── header ──
	local titlePlate = plate(panel, METAL_DK, METAL, 6)
	titlePlate.Position = UDim2.new(0, 16, 0, 14)
	titlePlate.Size = UDim2.new(0, 320, 0, 48)
	gradient(titlePlate, Color3.fromRGB(50, 43, 39), Color3.fromRGB(26, 22, 20), 90)
	text(titlePlate, {
		Position = UDim2.new(0, 16, 0, 0),
		Size = UDim2.new(1, -16, 1, 0),
		Font = Enum.Font.SpecialElite,
		Text = "INVENTORY",
		TextSize = 34,
		TextColor3 = Color3.fromRGB(238, 232, 228),
		TextStrokeTransparency = 0.5,
	})

	local closeBtn = Instance.new("TextButton")
	closeBtn.AnchorPoint = Vector2.new(1, 0)
	closeBtn.Position = UDim2.new(1, -16, 0, 14)
	closeBtn.Size = UDim2.new(0, 48, 0, 40)
	closeBtn.BackgroundColor3 = METAL_DK
	closeBtn.Text = "✕"
	closeBtn.Font = Enum.Font.GothamBold
	closeBtn.TextColor3 = TEXT
	closeBtn.TextSize = 22
	closeBtn.AutoButtonColor = true
	UiUtil.corner(closeBtn, 6)
	UiUtil.stroke(closeBtn, METAL, 1.5)
	closeBtn.Parent = panel
	closeBtn.Activated:Connect(function()
		InventoryController.setOpen(false)
	end)

	-- ── grid panel ──
	local gridPanel = plate(panel, TILE2, METAL, 6)
	gridPanel.Position = UDim2.new(0, 16, 0, 76)
	gridPanel.Size = UDim2.new(0, 560, 0, 380)
	gradient(gridPanel, Color3.fromRGB(20, 16, 16), Color3.fromRGB(10, 8, 8), 90)

	local gridScroll = Instance.new("ScrollingFrame")
	gridScroll.Position = UDim2.new(0, 14, 0, 14)
	gridScroll.Size = UDim2.new(1, -28, 1, -28)
	gridScroll.BackgroundTransparency = 1
	gridScroll.BorderSizePixel = 0
	gridScroll.ScrollBarThickness = 6
	gridScroll.ScrollBarImageColor3 = RED
	gridScroll.CanvasSize = UDim2.new(0, 0, 0, 0)
	gridScroll.AutomaticCanvasSize = Enum.AutomaticSize.Y
	gridScroll.Parent = gridPanel
	local gridLayout = Instance.new("UIGridLayout")
	gridLayout.CellSize = UDim2.new(0, CELL, 0, CELL)
	gridLayout.CellPadding = UDim2.new(0, 8, 0, 8)
	gridLayout.SortOrder = Enum.SortOrder.LayoutOrder
	gridLayout.Parent = gridScroll

	for i = 1, CAPACITY do
		local slot = buildSlot(i, gridScroll)
		slot.LayoutOrder = i
		slotFrames[i] = slot
	end

	-- capacity + actions row
	capacityLabel = text(panel, {
		Position = UDim2.new(0, 22, 0, 462),
		Size = UDim2.new(0, 240, 0, 24),
		Font = Enum.Font.GothamBold,
		Text = "SLOT CAPACITY  0/45",
		TextSize = 14,
		TextColor3 = MUTED,
	})

	local autoSort = UiUtil.button("AUTO SORT", METAL_DK)
	autoSort.AnchorPoint = Vector2.new(1, 0)
	autoSort.Position = UDim2.new(0, 540, 0, 460)
	autoSort.Size = UDim2.new(0, 120, 0, 26)
	autoSort.TextSize = 13
	UiUtil.stroke(autoSort, METAL, 1.5)
	autoSort.Parent = panel
	autoSort.Activated:Connect(refresh)

	local gear = UiUtil.button("⚙", METAL_DK)
	gear.AnchorPoint = Vector2.new(1, 0)
	gear.Position = UDim2.new(0, 576, 0, 460)
	gear.Size = UDim2.new(0, 26, 0, 26)
	gear.TextSize = 15
	UiUtil.stroke(gear, METAL, 1.5)
	gear.Parent = panel

	-- ── category rail ──
	local rail = Instance.new("Frame")
	rail.BackgroundTransparency = 1
	rail.Position = UDim2.new(0, 590, 0, 76)
	rail.Size = UDim2.new(0, 150, 0, 380)
	rail.Parent = panel
	local railLayout = Instance.new("UIListLayout")
	railLayout.Padding = UDim.new(0, 6)
	railLayout.Parent = rail

	for _, cat in ipairs(CATEGORIES) do
		local btn = Instance.new("TextButton")
		btn.Size = UDim2.new(1, 0, 0, 46)
		btn.BackgroundColor3 = METAL_DK
		btn.AutoButtonColor = true
		btn.Text = ""
		btn.BorderSizePixel = 0
		UiUtil.corner(btn, 6)
		UiUtil.stroke(btn, METAL, 1.5)
		btn.Parent = rail

		text(btn, {
			Position = UDim2.new(0, 12, 0, 0),
			Size = UDim2.new(0, 28, 1, 0),
			Text = cat.icon,
			TextSize = 18,
			TextColor3 = TEXT,
		})
		text(btn, {
			Name = "Label",
			Position = UDim2.new(0, 44, 0, 0),
			Size = UDim2.new(1, -50, 1, 0),
			Text = cat.id,
			TextSize = 15,
			TextColor3 = MUTED,
		})
		categoryButtons[cat.id] = btn
		btn.Activated:Connect(function()
			selectCategory(cat.id)
		end)
	end

	-- ── detail pane ──
	local preview = plate(panel, TILE2, METAL, 6)
	preview.Position = UDim2.new(0, 752, 0, 76)
	preview.Size = UDim2.new(0, 232, 0, 232)
	gradient(preview, Color3.fromRGB(46, 16, 16), Color3.fromRGB(12, 9, 9), 25)

	local skull = Instance.new("TextLabel")
	skull.BackgroundTransparency = 1
	skull.Size = UDim2.new(1, 0, 1, 0)
	skull.Font = Enum.Font.GothamBlack
	skull.Text = "💀"
	skull.TextSize = 120
	skull.TextColor3 = RED
	skull.TextTransparency = 0.45
	skull.Parent = preview

	detailHeader = text(panel, {
		Position = UDim2.new(0, 752, 0, 320),
		Size = UDim2.new(0, 232, 0, 24),
		Font = Enum.Font.GothamBold,
		Text = "SELECT AN ITEM",
		TextSize = 17,
	})
	detailRarity = text(panel, {
		Position = UDim2.new(0, 752, 0, 344),
		Size = UDim2.new(0, 232, 0, 18),
		Font = Enum.Font.GothamBlack,
		Text = "",
		TextSize = 13,
	})
	detailSub = text(panel, {
		Position = UDim2.new(0, 752, 0, 364),
		Size = UDim2.new(0, 232, 0, 18),
		Font = Enum.Font.Gotham,
		Text = "Choose an item to view its details",
		TextSize = 12,
		TextColor3 = MUTED,
		TextWrapped = true,
	})
	detailStats = text(panel, {
		Position = UDim2.new(0, 752, 0, 388),
		Size = UDim2.new(0, 232, 0, 60),
		Font = Enum.Font.Gotham,
		Text = "",
		TextSize = 13,
		TextColor3 = TEXT,
		TextWrapped = true,
		TextYAlignment = Enum.TextYAlignment.Top,
	})

	-- Shown only for case items; opens via the shared shop spin animation.
	openCaseBtn = UiUtil.button("OPEN CASE", Color3.fromRGB(80, 140, 230))
	openCaseBtn.Position = UDim2.new(0, 752, 0, 458)
	openCaseBtn.Size = UDim2.new(0, 232, 0, 36)
	openCaseBtn.TextSize = 15
	openCaseBtn.Visible = false
	openCaseBtn.Parent = panel
	openCaseBtn.Activated:Connect(function()
		local caseId = selectedCaseId
		if not caseId then
			return
		end
		InventoryController.setOpen(false)
		CasesController.openCaseFlow(caseId)
	end)

	-- Skin actions (equip/unequip + scrap), side by side; shown only for skins.
	equipBtn = UiUtil.button("EQUIP", Color3.fromRGB(60, 120, 200))
	equipBtn.Position = UDim2.new(0, 752, 0, 458)
	equipBtn.Size = UDim2.new(0, 112, 0, 36)
	equipBtn.TextSize = 14
	equipBtn.Visible = false
	equipBtn.Parent = panel
	equipBtn.Activated:Connect(function()
		local id = selectedSkinId
		if not id then
			return
		end
		local inst = nil
		for _, instance in ipairs(latest) do
			if instance.instanceId == id then
				inst = instance
				break
			end
		end
		if inst and isEquipped(inst) then
			local def = Skins.List[inst.skinId]
			if def then
				requestUnequip:InvokeServer(def.weaponId)
			end
		else
			requestEquip:InvokeServer(id)
		end
	end)

	scrapBtn = UiUtil.button("SCRAP", RED_DK)
	scrapBtn.Position = UDim2.new(0, 872, 0, 458)
	scrapBtn.Size = UDim2.new(0, 112, 0, 36)
	scrapBtn.TextSize = 14
	scrapBtn.Visible = false
	UiUtil.stroke(scrapBtn, RED, 1.5)
	scrapBtn.Parent = panel
	scrapBtn.Activated:Connect(function()
		local id = selectedSkinId
		if not id then
			return
		end
		-- Two-step confirm to avoid accidental scraps.
		if scrapBtn.Text:sub(1, 4) ~= "SURE" then
			scrapBtn.Text = "SURE? SCRAP"
			task.delay(2, function()
				if scrapBtn and selectedSkinId == id then
					local inst = nil
					for _, instance in ipairs(latest) do
						if instance.instanceId == id then
							inst = instance
							break
						end
					end
					if inst then
						scrapBtn.Text = string.format("SCRAP  +%d 🪙", GameConfig.scrapValue(inst.rarity))
					end
				end
			end)
			return
		end
		requestScrap:InvokeServer(id)
	end)

	-- ── bottom player / currency bar ──
	local footer = plate(panel, METAL_DK, METAL, 6)
	footer.Position = UDim2.new(0, 16, 0, 500)
	footer.Size = UDim2.new(1, -32, 0, 84)
	gradient(footer, Color3.fromRGB(40, 34, 31), Color3.fromRGB(20, 17, 16), 90)

	local avatar = plate(footer, TILE2, METAL, 6)
	avatar.Position = UDim2.new(0, 12, 0.5, -28)
	avatar.Size = UDim2.new(0, 56, 0, 56)
	text(avatar, { Size = UDim2.new(1, 0, 1, 0), Text = "💀", TextSize = 30, TextXAlignment = Enum.TextXAlignment.Center, TextColor3 = RED })

	text(footer, {
		Position = UDim2.new(0, 80, 0, 14),
		Size = UDim2.new(0, 220, 0, 22),
		Font = Enum.Font.GothamBlack,
		Text = string.upper(player.DisplayName),
		TextSize = 18,
	})
	text(footer, {
		Position = UDim2.new(0, 80, 0, 38),
		Size = UDim2.new(0, 70, 0, 16),
		Font = Enum.Font.GothamBold,
		Text = "RANK 25",
		TextSize = 12,
		TextColor3 = MUTED,
	})
	local xpTrack = Instance.new("Frame")
	xpTrack.Position = UDim2.new(0, 150, 0, 42)
	xpTrack.Size = UDim2.new(0, 150, 0, 8)
	xpTrack.BackgroundColor3 = TILE2
	xpTrack.BorderSizePixel = 0
	UiUtil.corner(xpTrack, 4)
	xpTrack.Parent = footer
	local xpFill = Instance.new("Frame")
	xpFill.Size = UDim2.new(0.69, 0, 1, 0)
	xpFill.BackgroundColor3 = RED
	xpFill.BorderSizePixel = 0
	UiUtil.corner(xpFill, 4)
	xpFill.Parent = xpTrack
	text(footer, {
		Position = UDim2.new(0, 310, 0, 38),
		Size = UDim2.new(0, 150, 0, 16),
		Font = Enum.Font.Gotham,
		Text = "12,450 / 18,000 XP",
		TextSize = 11,
		TextColor3 = MUTED,
	})

	-- currencies (center)
	local function stat(xOffset: number, icon: string, color: Color3): TextLabel
		text(footer, {
			Position = UDim2.new(0.5, xOffset, 0.5, -16),
			Size = UDim2.new(0, 28, 0, 32),
			Text = icon,
			TextSize = 20,
			TextXAlignment = Enum.TextXAlignment.Center,
			TextColor3 = color,
		})
		return text(footer, {
			Position = UDim2.new(0.5, xOffset + 30, 0.5, -16),
			Size = UDim2.new(0, 90, 0, 32),
			Font = Enum.Font.GothamBold,
			Text = "0",
			TextSize = 18,
		})
	end
	credLabel = stat(-110, "🪙", Color3.fromRGB(240, 200, 90))
	cashLabel = stat(60, "💵", Color3.fromRGB(120, 220, 130))

	local back = UiUtil.button("BACK TO SAFEHOUSE  ›", RED_DK)
	back.AnchorPoint = Vector2.new(1, 0.5)
	back.Position = UDim2.new(1, -14, 0.5, 0)
	back.Size = UDim2.new(0, 210, 0, 40)
	back.TextSize = 15
	UiUtil.stroke(back, RED, 1.5)
	back.Parent = footer
	back.Activated:Connect(function()
		InventoryController.setOpen(false)
	end)
end

function InventoryController.setOpen(open: boolean)
	panel.Visible = open
	dim.Visible = open
	if open then
		selectCategory(selectedCategory)
	end
end

function InventoryController.init()
	gui = Instance.new("ScreenGui")
	gui.Name = "AA_Inventory"
	gui.ResetOnSpawn = false
	gui.IgnoreGuiInset = true
	gui.DisplayOrder = 20
	gui.ZIndexBehavior = Enum.ZIndexBehavior.Sibling
	gui.Parent = player:WaitForChild("PlayerGui")

	dim = Instance.new("TextButton")
	dim.BackgroundColor3 = Color3.fromRGB(0, 0, 0)
	dim.BackgroundTransparency = 0.4
	dim.Size = UDim2.new(1, 0, 1, 0)
	dim.Text = ""
	dim.AutoButtonColor = false
	dim.Visible = false
	dim.Parent = gui
	dim.Activated:Connect(function()
		InventoryController.setOpen(false)
	end)

	buildPanel()

	local toggle = UiUtil.button("INVENTORY", Color3.fromRGB(150, 120, 220))
	toggle.AnchorPoint = Vector2.new(0, 0)
	toggle.Position = UDim2.new(0.5, 6, 0, 8)
	toggle.Size = UDim2.new(0, 110, 0, 36)
	toggle.Parent = gui
	toggle.Activated:Connect(function()
		InventoryController.setOpen(not panel.Visible)
	end)

	requestEquip = Remotes.get("RequestEquipSkin") :: RemoteFunction
	requestUnequip = Remotes.get("RequestUnequipWeapon") :: RemoteFunction
	requestScrap = Remotes.get("RequestScrapSkin") :: RemoteFunction

	local inventoryUpdated = Remotes.get("InventoryUpdated") :: RemoteEvent
	inventoryUpdated.OnClientEvent:Connect(function(list: { any })
		latest = list or {}
		if panel.Visible then
			refresh()
		end
	end)

	local equipUpdated = Remotes.get("EquipUpdated") :: RemoteEvent
	equipUpdated.OnClientEvent:Connect(function(map: { [string]: string })
		equippedMap = map or {}
		if panel.Visible then
			refresh()
		end
	end)

	local currencyUpdated = Remotes.get("CurrencyUpdated") :: RemoteEvent
	currencyUpdated.OnClientEvent:Connect(function(info: { credits: number, cash: number })
		credLabel.Text = string.format("%d", info.credits or 0)
		cashLabel.Text = string.format("%d", info.cash or 0)
	end)

	-- Owned cases come from the shop's ShopUpdated stream (CasesController).
	CasesController.onCasesChanged = function(cases)
		latestCases = cases or {}
		if panel.Visible and selectedCategory == "CASES" then
			refresh()
		end
	end
end

return InventoryController
