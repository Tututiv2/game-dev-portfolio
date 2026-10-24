--!strict
-- DevController.luau
-- Two INDEPENDENT dev menus, bottom-right, only built for authorized users
-- (DevConfig):
--   • 🧟 ZOMBIE DEV — combat / round cheats. Shown while you're in the arena.
--   • 💰 HUB DEV    — economy / market cheats. Shown while you're in the hub.
-- The menus swap based on the ZoneChanged remote so the right tools are always
-- in front of you. Buttons fire DevCommand (server re-validates auth); a couple
-- of toggles are client-only.

local Players = game:GetService("Players")
local ReplicatedStorage = game:GetService("ReplicatedStorage")

local Shared = ReplicatedStorage:WaitForChild("Shared")
local Remotes = require(Shared.Remotes)
local DevConfig = require(Shared.DevConfig)

local UiUtil = require(script.Parent.UiUtil)

local player = Players.LocalPlayer

-- Lazily fetch WeaponController only when a client-only toggle is used, so the
-- whole dev menu can never fail to load just because another controller errors.
local function weaponController(): any?
	local mod = script.Parent:FindFirstChild("WeaponController")
	if not mod then
		return nil
	end
	local ok, result = pcall(require, mod)
	return ok and result or nil
end

local DevController = {}

local devCommand: RemoteEvent

-- Each entry: { label, cmd, arg } for a server command.
type Entry = { label: string, cmd: string, arg: any? }

local ZOMBIE_ENTRIES: { Entry } = {
	{ label = "+10k Essence", cmd = "give_essence", arg = 10000 },
	{ label = "Power On", cmd = "power_on" },
	{ label = "Buy All Doors", cmd = "buy_all_doors" },
	{ label = "End Round", cmd = "end_round" },
	{ label = "Spawn 10 Zombies", cmd = "spawn_zombies", arg = 10 },
	{ label = "Max Ammo", cmd = "max_ammo" },
	{ label = "Nuke", cmd = "powerup", arg = "Nuke" },
	{ label = "Insta-Kill", cmd = "powerup", arg = "InstaKill" },
	{ label = "Double Points", cmd = "powerup", arg = "DoublePoints" },
	{ label = "Carpenter", cmd = "powerup", arg = "Carpenter" },
	{ label = "Bonus Points", cmd = "powerup", arg = "BonusPoints" },
	{ label = "Give Ray Gun", cmd = "give_weapon", arg = "RayGun" },
	{ label = "Give M4", cmd = "give_weapon", arg = "M4" },
	{ label = "Pack-a-Punch Gun", cmd = "pap_current" },
	{ label = "Heal", cmd = "heal" },
}

local HUB_ENTRIES: { Entry } = {
	{ label = "+10k Credits", cmd = "give_credits", arg = 10000 },
	{ label = "+1k Cash", cmd = "give_cash", arg = 1000 },
	{ label = "+10k Robux Spent", cmd = "sim_robux", arg = 10000 },
	{ label = "Give Secret Case", cmd = "give_secret_case", arg = 1 },
	{ label = "Give Luxury Case", cmd = "give_luxury_case", arg = 1 },
}

local function makeToggleLabel(label: string, onChange: (boolean) -> ()): (string, () -> string)
	local state = false
	local function display(): string
		return label .. ": " .. (state and "ON" or "OFF")
	end
	return display(), function()
		state = not state
		onChange(state)
		return display()
	end
end

type Menu = { toggle: TextButton, panel: Frame }

-- Builds a toggle button + scrolling panel and wires up the entries.
local function buildMenu(gui: ScreenGui, title: string, accent: Color3, entries: { Entry }, withToggles: boolean): Menu
	local panel = Instance.new("Frame")
	panel.AnchorPoint = Vector2.new(1, 1)
	panel.Position = UDim2.new(1, -12, 1, -56)
	panel.Size = UDim2.new(0, 210, 0, 360)
	panel.BackgroundColor3 = Color3.fromRGB(16, 16, 20)
	panel.BackgroundTransparency = 0.08
	panel.BorderSizePixel = 0
	panel.Visible = false
	UiUtil.corner(panel, 10)
	UiUtil.stroke(panel, accent, 1.5)
	panel.Parent = gui

	local heading = Instance.new("TextLabel")
	heading.Size = UDim2.new(1, -8, 0, 22)
	heading.Position = UDim2.new(0, 4, 0, 4)
	heading.BackgroundTransparency = 1
	heading.Font = Enum.Font.GothamBold
	heading.TextColor3 = accent
	heading.TextSize = 14
	heading.Text = title
	heading.TextXAlignment = Enum.TextXAlignment.Left
	heading.Parent = panel

	local scroll = Instance.new("ScrollingFrame")
	scroll.Size = UDim2.new(1, -8, 1, -32)
	scroll.Position = UDim2.new(0, 4, 0, 28)
	scroll.BackgroundTransparency = 1
	scroll.BorderSizePixel = 0
	scroll.ScrollBarThickness = 5
	scroll.CanvasSize = UDim2.new(0, 0, 0, 0)
	scroll.AutomaticCanvasSize = Enum.AutomaticSize.Y
	scroll.Parent = panel
	local layout = Instance.new("UIListLayout")
	layout.Padding = UDim.new(0, 5)
	layout.Parent = scroll

	local function addButton(text: string, onClick: (TextButton) -> ())
		local b = UiUtil.button(text, Color3.fromRGB(44, 44, 54))
		b.Size = UDim2.new(1, -6, 0, 30)
		b.TextSize = 14
		b.Parent = scroll
		b.Activated:Connect(function()
			onClick(b)
		end)
		return b
	end

	for _, entry in ipairs(entries) do
		addButton(entry.label, function()
			devCommand:FireServer(entry.cmd, entry.arg)
		end)
	end

	if withToggles then
		local godText, godFlip = makeToggleLabel("God Mode", function(on)
			devCommand:FireServer("god_mode", on)
		end)
		addButton(godText, function(b)
			b.Text = godFlip()
		end)

		local ammoText, ammoFlip = makeToggleLabel("Infinite Ammo", function(on)
			local wc = weaponController()
			if wc then
				wc.setInfiniteAmmo(on)
			end
		end)
		addButton(ammoText, function(b)
			b.Text = ammoFlip()
		end)
	end

	local toggle = UiUtil.button(title, accent)
	toggle.AnchorPoint = Vector2.new(1, 1)
	toggle.Position = UDim2.new(1, -12, 1, -12)
	toggle.Size = UDim2.new(0, 130, 0, 38)
	toggle.TextSize = 14
	toggle.Visible = false
	toggle.Parent = gui
	toggle.Activated:Connect(function()
		panel.Visible = not panel.Visible
	end)

	return { toggle = toggle, panel = panel }
end

function DevController.init()
	if not DevConfig.isAuthorized(player) then
		warn("[Dev] menu hidden: you are not authorized (not in Studio, and not the "
			.. "game owner). Add your UserId to DevConfig.AllowedUserIds. Your UserId = "
			.. tostring(player.UserId))
		return
	end
	devCommand = Remotes.get("DevCommand") :: RemoteEvent
	print("[Dev] menu enabled for " .. player.Name .. " (UserId " .. player.UserId .. ")")

	local gui = Instance.new("ScreenGui")
	gui.Name = "AA_DevMenu"
	gui.ResetOnSpawn = false
	gui.IgnoreGuiInset = true
	gui.DisplayOrder = 100
	gui.Parent = player:WaitForChild("PlayerGui")

	local zombieMenu = buildMenu(gui, "🧟 ZOMBIE DEV", Color3.fromRGB(90, 200, 120), ZOMBIE_ENTRIES, true)
	local hubMenu = buildMenu(gui, "💰 HUB DEV", Color3.fromRGB(240, 200, 90), HUB_ENTRIES, false)

	local function showZone(zone: string)
		local inArena = zone == "arena"
		zombieMenu.toggle.Visible = inArena
		hubMenu.toggle.Visible = not inArena
		-- hide whichever panel doesn't belong to the current zone
		if inArena then
			hubMenu.panel.Visible = false
		else
			zombieMenu.panel.Visible = false
		end
	end

	showZone("hub") -- default until the server tells us otherwise

	local zoneChanged = Remotes.get("ZoneChanged") :: RemoteEvent
	zoneChanged.OnClientEvent:Connect(function(zone)
		showZone(zone)
	end)
end

return DevController
