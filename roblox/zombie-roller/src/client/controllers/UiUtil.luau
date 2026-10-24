--!strict
-- UiUtil.luau
-- Small client-side UI helpers shared by the cases/inventory/dev UIs: rarity
-- colors, rounded corners, strokes, and a skin "card" builder so the spin strip,
-- reveal, and inventory all look consistent.

local ReplicatedStorage = game:GetService("ReplicatedStorage")

local Shared = ReplicatedStorage:WaitForChild("Shared")
local Rarities = require(Shared.Config.Rarities)
local Skins = require(Shared.Config.Skins)
local Weapons = require(Shared.Config.Weapons)
local GameConfig = require(Shared.Config.GameConfig)

local UiUtil = {}

function UiUtil.rarityColor(rarity: string): Color3
	local ok, r = pcall(Rarities.get, rarity :: any)
	return ok and r.color or Color3.fromRGB(200, 200, 200)
end

function UiUtil.corner(inst: Instance, radius: number)
	local c = Instance.new("UICorner")
	c.CornerRadius = UDim.new(0, radius)
	c.Parent = inst
	return c
end

function UiUtil.stroke(inst: Instance, color: Color3, thickness: number)
	local s = Instance.new("UIStroke")
	s.Color = color
	s.Thickness = thickness
	s.Parent = inst
	return s
end

function UiUtil.button(text: string, color: Color3): TextButton
	local b = Instance.new("TextButton")
	b.AutoButtonColor = true
	b.BackgroundColor3 = color
	b.Font = Enum.Font.GothamBold
	b.TextColor3 = Color3.fromRGB(255, 255, 255)
	b.TextSize = 16
	b.Text = text
	b.BorderSizePixel = 0
	UiUtil.corner(b, 8)
	return b
end

-- A simple "weapon art" placeholder card colored by rarity (used in spin strip).
-- Returns the card frame; caller parents it.
function UiUtil.rarityCard(rarity: string, skinName: string, weaponName: string, size: UDim2): Frame
	local color = UiUtil.rarityColor(rarity)
	local card = Instance.new("Frame")
	card.Size = size
	card.BackgroundColor3 = Color3.fromRGB(28, 28, 34)
	card.BorderSizePixel = 0
	UiUtil.corner(card, 8)
	UiUtil.stroke(card, color, 2)

	-- rarity accent bar at bottom
	local accent = Instance.new("Frame")
	accent.AnchorPoint = Vector2.new(0.5, 1)
	accent.Position = UDim2.new(0.5, 0, 1, -6)
	accent.Size = UDim2.new(1, -12, 0, 5)
	accent.BackgroundColor3 = color
	accent.BorderSizePixel = 0
	UiUtil.corner(accent, 3)
	accent.Parent = card

	-- "art" block (colored) standing in for the gun render
	local art = Instance.new("Frame")
	art.AnchorPoint = Vector2.new(0.5, 0)
	art.Position = UDim2.new(0.5, 0, 0, 8)
	art.Size = UDim2.new(1, -16, 0.55, 0)
	art.BackgroundColor3 = color
	art.BackgroundTransparency = 0.15
	art.BorderSizePixel = 0
	UiUtil.corner(art, 6)
	local grad = Instance.new("UIGradient")
	grad.Rotation = 60
	grad.Color = ColorSequence.new(color, Color3.fromRGB(20, 20, 24))
	grad.Parent = art
	art.Parent = card

	local nameLabel = Instance.new("TextLabel")
	nameLabel.BackgroundTransparency = 1
	nameLabel.AnchorPoint = Vector2.new(0.5, 1)
	nameLabel.Position = UDim2.new(0.5, 0, 1, -14)
	nameLabel.Size = UDim2.new(1, -10, 0, 30)
	nameLabel.Font = Enum.Font.GothamBold
	nameLabel.TextColor3 = Color3.fromRGB(245, 245, 245)
	nameLabel.TextSize = 13
	nameLabel.TextWrapped = true
	nameLabel.Text = string.format("%s | %s", weaponName, skinName)
	nameLabel.Parent = card

	return card
end

-- Builds an inventory card for a SkinInstance.
function UiUtil.skinCard(instance: any, size: UDim2): Frame
	local def = Skins.List[instance.skinId]
	local weaponDef = def and Weapons.List[def.weaponId]
	local skinName = def and def.displayName or instance.skinId
	local weaponName = weaponDef and weaponDef.displayName or "?"

	local card = UiUtil.rarityCard(instance.rarity, skinName, weaponName, size)

	-- float bar across the top edge
	local floatBar = Instance.new("Frame")
	floatBar.AnchorPoint = Vector2.new(0.5, 0)
	floatBar.Position = UDim2.new(0.5, 0, 0, 3)
	floatBar.Size = UDim2.new(1, -12, 0, 4)
	floatBar.BackgroundColor3 = Color3.fromRGB(60, 60, 66)
	floatBar.BorderSizePixel = 0
	UiUtil.corner(floatBar, 2)
	floatBar.Parent = card
	local floatFill = Instance.new("Frame")
	floatFill.Size = UDim2.new(math.clamp(instance.float, 0, 1), 0, 1, 0)
	floatFill.BackgroundColor3 = instance.float < 0.15 and Color3.fromRGB(90, 220, 120)
		or (instance.float < 0.38 and Color3.fromRGB(230, 200, 70) or Color3.fromRGB(220, 80, 60))
	floatFill.BorderSizePixel = 0
	UiUtil.corner(floatFill, 2)
	floatFill.Parent = floatBar

	-- badges (serial / tracker)
	if instance.serialNumber and instance.serialNumber > 0 then
		local serial = Instance.new("TextLabel")
		serial.BackgroundTransparency = 1
		serial.Position = UDim2.new(0, 6, 0, 10)
		serial.Size = UDim2.new(0.6, 0, 0, 16)
		serial.Font = Enum.Font.GothamBlack
		serial.TextXAlignment = Enum.TextXAlignment.Left
		serial.TextColor3 = Color3.fromRGB(255, 215, 90)
		serial.TextSize = 13
		serial.Text = "#" .. instance.serialNumber
		serial.Parent = card
	end
	if instance.isTracker then
		local tracker = Instance.new("TextLabel")
		tracker.BackgroundTransparency = 1
		tracker.AnchorPoint = Vector2.new(1, 0)
		tracker.Position = UDim2.new(1, -6, 0, 10)
		tracker.Size = UDim2.new(0.5, 0, 0, 16)
		tracker.Font = Enum.Font.GothamBlack
		tracker.TextXAlignment = Enum.TextXAlignment.Right
		tracker.TextColor3 = Color3.fromRGB(120, 230, 255)
		tracker.TextSize = 13
		tracker.Text = "T:" .. instance.trackerKills
		tracker.Parent = card
	end

	return card
end

function UiUtil.floatBand(float: number): string
	return GameConfig.floatBand(float)
end

return UiUtil
