--!strict
-- WeaponController.luau
-- Client input + first-person camera + loadout handling. The server owns which
-- weapons you have (LoadoutChanged) and validates every shot; this controller
-- tracks per-gun ammo for display, lets you switch guns (1/2 or Q), and sends
-- the aim ray on fire. Damage is always decided server-side.

local Players = game:GetService("Players")
local UserInputService = game:GetService("UserInputService")
local RunService = game:GetService("RunService")
local ReplicatedStorage = game:GetService("ReplicatedStorage")

local Shared = ReplicatedStorage:WaitForChild("Shared")
local Remotes = require(Shared.Remotes)
local Weapons = require(Shared.Config.Weapons)

local HudController = require(script.Parent.HudController)
local ViewmodelController = require(script.Parent.ViewmodelController)

local player = Players.LocalPlayer
local camera = workspace.CurrentCamera

local WeaponController = {}

type AmmoState = { mag: number, reserve: number }

local owned: { string } = { Weapons.StartingWeapon }
local ammo: { [string]: AmmoState } = {}
local upgradedSet: { [string]: boolean } = {}
local currentId = Weapons.StartingWeapon
local reloading = false
local firing = false
local lastShot = 0
local infiniteAmmo = false
local combat = false -- true only while in the arena
local ads = false -- true = aiming down sights (right mouse held)
local defaultFov = 70
local viewmodelShown = false
local ADS_FOV_DELTA = 18

function WeaponController.setInfiniteAmmo(value: boolean)
	infiniteAmmo = value
end

local fireRemote: RemoteEvent
local setEquipped: RemoteEvent

-- Effective stats account for Pack-a-Punch (bigger mag/reserve, new name).
local function effMag(weaponId: string): number
	local def = Weapons.get(weaponId)
	return upgradedSet[weaponId] and math.floor(def.magSize * Weapons.PAP.magMult) or def.magSize
end

local function effReserve(weaponId: string): number
	local def = Weapons.get(weaponId)
	return upgradedSet[weaponId] and math.floor(def.reserveAmmo * Weapons.PAP.reserveMult) or def.reserveAmmo
end

local function effName(weaponId: string): string
	local def = Weapons.get(weaponId)
	return upgradedSet[weaponId] and Weapons.papName(def) or def.displayName
end

local function ensureAmmo(weaponId: string)
	if not ammo[weaponId] then
		ammo[weaponId] = { mag = effMag(weaponId), reserve = effReserve(weaponId) }
	end
end

local function refreshHud()
	local state = ammo[currentId]
	HudController.setAmmo(effName(currentId), state.mag, state.reserve)
end

local function equip(weaponId: string)
	ensureAmmo(weaponId)
	currentId = weaponId
	reloading = false
	if setEquipped then
		setEquipped:FireServer(weaponId)
	end
	ViewmodelController.setWeapon(weaponId)
	refreshHud()
end

local function reload()
	local def = Weapons.get(currentId)
	local state = ammo[currentId]
	local magSize = effMag(currentId)
	if reloading or state.mag >= magSize or state.reserve <= 0 then
		return
	end
	reloading = true
	ViewmodelController.playReload(def.reloadTime)
	task.wait(def.reloadTime)
	-- Guard: player may have swapped guns mid-reload.
	if currentId ~= def.id then
		reloading = false
		return
	end
	local needed = magSize - state.mag
	local take = math.min(needed, state.reserve)
	state.mag += take
	state.reserve -= take
	reloading = false
	refreshHud()
end

local function isAlive(): boolean
	local char = player.Character
	local humanoid = char and char:FindFirstChildOfClass("Humanoid")
	return humanoid ~= nil and humanoid.Health > 0
end

local function tryFire()
	local def = Weapons.get(currentId)
	local state = ammo[currentId]
	if not combat or reloading or not isAlive() then
		return
	end
	if state.mag <= 0 then
		task.spawn(reload)
		return
	end
	local now = os.clock()
	if now - lastShot < 1 / def.fireRate then
		return
	end
	lastShot = now
	if not infiniteAmmo then
		state.mag -= 1
	end
	refreshHud()

	fireRemote:FireServer(camera.CFrame.Position, camera.CFrame.LookVector, def.id)
	ViewmodelController.kick()

	if state.mag <= 0 and not infiniteAmmo then
		task.spawn(reload)
	end
end

local function swapTo(index: number)
	local weaponId = owned[index]
	if weaponId and weaponId ~= currentId then
		equip(weaponId)
	end
end

local function cycle()
	local idx = table.find(owned, currentId) or 1
	local nextIdx = (idx % #owned) + 1
	swapTo(nextIdx)
end

-- Classic camera everywhere — scroll wheel zooms in/out in hub AND zombie mode.
local function applyCamera()
	player.CameraMode = Enum.CameraMode.Classic
	player.CameraMinZoomDistance = 0.5
	player.CameraMaxZoomDistance = 128
end

local function isFirstPerson(): boolean
	local char = player.Character
	local root = char and char:FindFirstChild("HumanoidRootPart") :: BasePart?
	if not root then
		return false
	end
	return (camera.CFrame.Position - root.Position).Magnitude < 1.5
end

local function updateViewmodelVisibility()
	local shouldShow = combat and isFirstPerson()
	if shouldShow == viewmodelShown then
		return
	end
	viewmodelShown = shouldShow
	ViewmodelController.setVisible(shouldShow)
	if shouldShow then
		ViewmodelController.setWeapon(currentId)
	end
end

function WeaponController.setCombat(enabled: boolean)
	combat = enabled
	firing = false
	ads = false
	ViewmodelController.setAds(false)
	applyCamera()
	HudController.setCombat(enabled)
	if enabled then
		ViewmodelController.setWeapon(currentId)
	end
	viewmodelShown = false
	updateViewmodelVisibility()
end

function WeaponController.init()
	fireRemote = Remotes.get("FireWeapon") :: RemoteEvent
	setEquipped = Remotes.get("SetEquipped") :: RemoteEvent
	ensureAmmo(currentId)
	defaultFov = camera.FieldOfView

	-- Zone drives combat mode (camera + firing). Start in the hub.
	local zoneChanged = Remotes.get("ZoneChanged") :: RemoteEvent
	zoneChanged.OnClientEvent:Connect(function(zone: string)
		WeaponController.setCombat(zone == "arena")
	end)
	WeaponController.setCombat(false)

	-- Pack-a-Punch: server tells us a gun was upgraded.
	local weaponUpgraded = Remotes.get("WeaponUpgraded") :: RemoteEvent
	weaponUpgraded.OnClientEvent:Connect(function(weaponId: string)
		if not Weapons.List[weaponId] then
			return
		end
		upgradedSet[weaponId] = true
		ammo[weaponId] = ammo[weaponId] or { mag = 0, reserve = 0 }
		ammo[weaponId].mag = effMag(weaponId)
		ammo[weaponId].reserve = effReserve(weaponId)
		refreshHud()
	end)

	-- Server tells us our weapons; auto-equip a freshly acquired one.
	local loadoutChanged = Remotes.get("LoadoutChanged") :: RemoteEvent
	loadoutChanged.OnClientEvent:Connect(function(info: { weapons: { string }, equip: string? })
		owned = info.weapons or owned
		for _, id in ipairs(owned) do
			ensureAmmo(id)
		end
		if info.equip then
			equip(info.equip)
		elseif not table.find(owned, currentId) then
			equip(owned[1])
		else
			refreshHud()
		end
	end)

	-- Ammo refills (wallbuy repurchase / Max Ammo).
	local ammoRefill = Remotes.get("AmmoRefill") :: RemoteEvent
	ammoRefill.OnClientEvent:Connect(function(which: string)
		local function refillOne(id: string)
			ammo[id] = ammo[id] or { mag = effMag(id), reserve = effReserve(id) }
			ammo[id].reserve = effReserve(id)
		end
		if which == "all" then
			for _, id in ipairs(owned) do
				refillOne(id)
			end
		elseif Weapons.List[which] then
			refillOne(which)
		end
		refreshHud()
	end)

	player.CharacterAdded:Connect(function()
		applyCamera()
		refreshHud()
	end)

	UserInputService.InputBegan:Connect(function(input, gameProcessed)
		if gameProcessed then
			return
		end
		if input.UserInputType == Enum.UserInputType.MouseButton1 then
			firing = true
			if not Weapons.get(currentId).automatic then
				tryFire()
			end
		elseif input.UserInputType == Enum.UserInputType.MouseButton2 then
			if combat and isFirstPerson() then
				ads = true
				ViewmodelController.setAds(true)
			end
		elseif input.KeyCode == Enum.KeyCode.R then
			task.spawn(reload)
		elseif input.KeyCode == Enum.KeyCode.Q then
			cycle()
		elseif input.KeyCode == Enum.KeyCode.One then
			swapTo(1)
		elseif input.KeyCode == Enum.KeyCode.Two then
			swapTo(2)
		end
	end)

	UserInputService.InputEnded:Connect(function(input)
		if input.UserInputType == Enum.UserInputType.MouseButton1 then
			firing = false
		elseif input.UserInputType == Enum.UserInputType.MouseButton2 then
			ads = false
			ViewmodelController.setAds(false)
		end
	end)

	RunService.Heartbeat:Connect(function()
		if firing and Weapons.get(currentId).automatic then
			tryFire()
		end
		updateViewmodelVisibility()
		-- Drop ADS if the player zooms out to third person.
		if ads and not isFirstPerson() then
			ads = false
			ViewmodelController.setAds(false)
		end
		local targetFov = (combat and ads and isFirstPerson()) and (defaultFov - ADS_FOV_DELTA) or defaultFov
		camera.FieldOfView += (targetFov - camera.FieldOfView) * 0.2
	end)

	refreshHud()
end

return WeaponController
