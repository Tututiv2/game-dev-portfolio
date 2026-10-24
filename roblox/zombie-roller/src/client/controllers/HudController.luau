--!strict
-- HudController.luau
-- Builds the in-match HUD entirely in code (no pre-made GUI to sync): Essence,
-- Round, Health, Ammo, plus a round-start announcement. Listens to the
-- PointsChanged / RoundChanged remotes. Other controllers (WeaponController)
-- push ammo updates via setAmmo().

local Players = game:GetService("Players")
local ReplicatedStorage = game:GetService("ReplicatedStorage")

local Shared = ReplicatedStorage:WaitForChild("Shared")
local Remotes = require(Shared.Remotes)

local UiUtil = require(script.Parent.UiUtil)

local player = Players.LocalPlayer

local HudController = {}

local essenceLabel: TextLabel
local roundLabel: TextLabel
local healthLabel: TextLabel
local ammoLabel: TextLabel
local currencyLabel: TextLabel
local announce: TextLabel
local hudGui: ScreenGui

local function makeLabel(parent: Instance, props: { [string]: any }): TextLabel
	local label = Instance.new("TextLabel")
	label.BackgroundTransparency = 1
	label.Font = Enum.Font.GothamBold
	label.TextColor3 = Color3.fromRGB(255, 255, 255)
	label.TextStrokeTransparency = 0.4
	label.TextScaled = false
	label.TextSize = 24
	for k, v in pairs(props) do
		(label :: any)[k] = v
	end
	label.Parent = parent
	return label
end

local function buildGui()
	local gui = Instance.new("ScreenGui")
	gui.Name = "AA_Hud"
	gui.ResetOnSpawn = false
	gui.IgnoreGuiInset = true
	gui.ZIndexBehavior = Enum.ZIndexBehavior.Sibling
	gui.Parent = player:WaitForChild("PlayerGui")
	hudGui = gui

	-- Bottom-left, sitting just above the health label (clear of Roblox's topbar).
	essenceLabel = makeLabel(gui, {
		AnchorPoint = Vector2.new(0, 1),
		Position = UDim2.new(0, 16, 1, -54),
		Size = UDim2.new(0, 300, 0, 34),
		TextXAlignment = Enum.TextXAlignment.Left,
		TextColor3 = Color3.fromRGB(255, 215, 90),
		Text = "Essence: 0",
		TextSize = 28,
	})

	roundLabel = makeLabel(gui, {
		AnchorPoint = Vector2.new(0.5, 0),
		Position = UDim2.new(0.5, 0, 0, 12),
		Size = UDim2.new(0, 200, 0, 40),
		TextXAlignment = Enum.TextXAlignment.Center,
		Text = "Round 0",
		TextSize = 32,
	})

	healthLabel = makeLabel(gui, {
		AnchorPoint = Vector2.new(0, 1),
		Position = UDim2.new(0, 16, 1, -16),
		Size = UDim2.new(0, 220, 0, 34),
		TextXAlignment = Enum.TextXAlignment.Left,
		TextColor3 = Color3.fromRGB(120, 230, 120),
		Text = "HP: 100",
	})

	ammoLabel = makeLabel(gui, {
		AnchorPoint = Vector2.new(1, 1),
		Position = UDim2.new(1, -20, 1, -16),
		Size = UDim2.new(0, 240, 0, 40),
		TextXAlignment = Enum.TextXAlignment.Right,
		Text = "-- / --",
		TextSize = 30,
	})

	-- Persistent currencies (top-center, under the round number).
	currencyLabel = makeLabel(gui, {
		AnchorPoint = Vector2.new(0.5, 0),
		Position = UDim2.new(0.5, 0, 0, 54),
		Size = UDim2.new(0, 360, 0, 24),
		TextXAlignment = Enum.TextXAlignment.Center,
		TextColor3 = Color3.fromRGB(210, 235, 255),
		Text = "Credits 0    Cash 0",
		TextSize = 18,
	})

	announce = makeLabel(gui, {
		AnchorPoint = Vector2.new(0.5, 0.5),
		Position = UDim2.new(0.5, 0, 0.32, 0),
		Size = UDim2.new(0, 600, 0, 80),
		TextXAlignment = Enum.TextXAlignment.Center,
		Text = "",
		TextSize = 56,
		TextTransparency = 1,
	})
end

local function flashAnnounce(text: string, color: Color3)
	announce.Text = text
	announce.TextColor3 = color
	announce.TextTransparency = 0
	task.spawn(function()
		task.wait(1.6)
		for i = 0, 1, 0.08 do
			announce.TextTransparency = i
			task.wait(0.03)
		end
		announce.TextTransparency = 1
	end)
end

-- End-of-match summary card (round reached, kills, credits earned). Slides in,
-- holds, then fades — non-blocking so the player respawns straight into the hub.
local function showSummary(info: { round: number, kills: number, credits: number, firstWin: boolean })
	local panel = Instance.new("Frame")
	panel.AnchorPoint = Vector2.new(0.5, 0.5)
	panel.Position = UDim2.new(0.5, 0, 0.42, 0)
	panel.Size = UDim2.new(0, 360, 0, 210)
	panel.BackgroundColor3 = Color3.fromRGB(18, 16, 22)
	panel.BackgroundTransparency = 0.05
	panel.BorderSizePixel = 0
	UiUtil.corner(panel, 12)
	UiUtil.stroke(panel, Color3.fromRGB(255, 80, 80), 2)
	panel.Parent = hudGui

	makeLabel(panel, {
		Position = UDim2.new(0, 0, 0, 14),
		Size = UDim2.new(1, 0, 0, 32),
		TextXAlignment = Enum.TextXAlignment.Center,
		Text = "RUN OVER",
		TextColor3 = Color3.fromRGB(255, 90, 90),
		TextSize = 30,
	})

	local function row(y: number, left: string, right: string, color: Color3)
		makeLabel(panel, {
			Position = UDim2.new(0, 24, 0, y),
			Size = UDim2.new(1, -48, 0, 26),
			TextXAlignment = Enum.TextXAlignment.Left,
			Text = left,
			TextSize = 18,
			TextColor3 = Color3.fromRGB(220, 220, 225),
		})
		makeLabel(panel, {
			Position = UDim2.new(0, 24, 0, y),
			Size = UDim2.new(1, -48, 0, 26),
			TextXAlignment = Enum.TextXAlignment.Right,
			Text = right,
			TextSize = 18,
			TextColor3 = color,
		})
	end

	row(58, "Round reached", tostring(info.round), Color3.fromRGB(255, 255, 255))
	row(86, "Zombies killed", tostring(info.kills), Color3.fromRGB(255, 255, 255))
	row(120, "Credits earned", "+" .. tostring(info.credits), Color3.fromRGB(120, 230, 140))
	if info.firstWin then
		row(146, "First win bonus", "included", Color3.fromRGB(255, 215, 90))
	end

	makeLabel(panel, {
		Position = UDim2.new(0, 0, 1, -34),
		Size = UDim2.new(1, 0, 0, 24),
		TextXAlignment = Enum.TextXAlignment.Center,
		Text = "Spend Credits in the SHOP",
		TextSize = 14,
		TextColor3 = Color3.fromRGB(150, 150, 160),
	})

	task.spawn(function()
		task.wait(5)
		for i = 0, 1, 0.06 do
			panel.BackgroundTransparency = 0.05 + i * 0.95
			for _, d in ipairs(panel:GetDescendants()) do
				if d:IsA("TextLabel") then
					d.TextTransparency = i
				end
			end
			task.wait(0.03)
		end
		panel:Destroy()
	end)
end

function HudController.setAmmo(weaponName: string, mag: number, reserve: number)
	if ammoLabel then
		ammoLabel.Text = string.format("%s   %d / %d", weaponName, mag, reserve)
	end
end

-- Show combat-only HUD (Essence/Round/HP/Ammo) in the arena; hide it in the hub.
-- Currencies stay visible everywhere (they're the market/economy display).
function HudController.setCombat(enabled: boolean)
	for _, label in { essenceLabel, roundLabel, healthLabel, ammoLabel } do
		if label then
			label.Visible = enabled
		end
	end
end

local function bindHealth(character: Model)
	local humanoid = character:WaitForChild("Humanoid") :: Humanoid
	local function update()
		healthLabel.Text = string.format("HP: %d", math.max(0, math.floor(humanoid.Health)))
	end
	humanoid.HealthChanged:Connect(update)
	update()
end

function HudController.init()
	buildGui()

	local pointsChanged = Remotes.get("PointsChanged") :: RemoteEvent
	pointsChanged.OnClientEvent:Connect(function(total: number)
		essenceLabel.Text = string.format("Essence: %d", total)
	end)

	local roundChanged = Remotes.get("RoundChanged") :: RemoteEvent
	roundChanged.OnClientEvent:Connect(function(info: { round: number, isDogRound: boolean })
		roundLabel.Text = string.format("Round %d", info.round)
		if info.isDogRound then
			flashAnnounce("HELLHOUNDS", Color3.fromRGB(255, 90, 60))
		else
			flashAnnounce(string.format("ROUND %d", info.round), Color3.fromRGB(255, 80, 80))
		end
	end)

	-- Power-up / event callouts.
	local announceRemote = Remotes.get("Announce") :: RemoteEvent
	announceRemote.OnClientEvent:Connect(function(info: { text: string, color: Color3 })
		flashAnnounce(info.text, info.color or Color3.fromRGB(255, 255, 255))
	end)

	local perkGranted = Remotes.get("PerkGranted") :: RemoteEvent
	perkGranted.OnClientEvent:Connect(function(perkId: string)
		flashAnnounce(perkId .. "!", Color3.fromRGB(120, 220, 255))
	end)

	local currencyUpdated = Remotes.get("CurrencyUpdated") :: RemoteEvent
	currencyUpdated.OnClientEvent:Connect(function(info: { credits: number, cash: number })
		currencyLabel.Text = string.format("Credits %d    Cash %d", info.credits or 0, info.cash or 0)
	end)

	local matchSummary = Remotes.get("MatchSummary") :: RemoteEvent
	matchSummary.OnClientEvent:Connect(showSummary)

	-- Combat HUD only appears in the arena.
	local zoneChanged = Remotes.get("ZoneChanged") :: RemoteEvent
	zoneChanged.OnClientEvent:Connect(function(zone: string)
		HudController.setCombat(zone == "arena")
	end)
	HudController.setCombat(false) -- start in the hub

	if player.Character then
		bindHealth(player.Character)
	end
	player.CharacterAdded:Connect(bindHealth)
end

return HudController
