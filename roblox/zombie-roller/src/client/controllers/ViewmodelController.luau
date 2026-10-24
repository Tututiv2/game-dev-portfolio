--!strict
-- ViewmodelController.luau
-- First-person gun VIEWMODEL. Renders a gun attached to the camera with idle
-- sway, walk bob, fire recoil, and a muzzle flash. Two sources for the gun mesh:
--   1. A real Model dropped into ReplicatedStorage.Assets named after the weapon's
--      `viewModelId` (or its id) — just add gun meshes to the `assets/` folder.
--   2. Otherwise a procedural PLACEHOLDER gun built from the weapon's `class`.
-- The gun is tinted by the rarity of the skin you've EQUIPPED on that weapon
-- (rarity-colored placeholder until real skin textures exist).
--
-- Parts are parented to the Camera so they render as a viewmodel and never
-- collide with or block the world.

local Players = game:GetService("Players")
local RunService = game:GetService("RunService")
local ReplicatedStorage = game:GetService("ReplicatedStorage")

local Shared = ReplicatedStorage:WaitForChild("Shared")
local Remotes = require(Shared.Remotes)
local Weapons = require(Shared.Config.Weapons)

local UiUtil = require(script.Parent.UiUtil)

local player = Players.LocalPlayer

local ViewmodelController = {}

local GUNMETAL = Color3.fromRGB(62, 64, 70)
local DARK = Color3.fromRGB(28, 28, 32)

-- Resting (hip) offset of the gun relative to the camera (right / down / forward).
local BASE_OFFSET = CFrame.new(0.85, -0.95, -1.4)
-- Aim-down-sights: gun pulled to the centre/eye line.
local ADS_OFFSET = CFrame.new(0, -0.3, -1.0)

type PartEntry = { part: BasePart, offset: CFrame, skinnable: boolean }

local camera: Camera
local gunParts: { PartEntry } = {}
local skinnableParts: { BasePart } = {}
local muzzlePart: BasePart? = nil

local currentWeaponId: string? = nil
local visible = false
local currentCF = CFrame.new()
local bobPhase = 0
local recoil = 0 -- decays to 0 each frame; bumped by kick()
local adsAlpha = 0 -- 0 = hip, 1 = fully aimed
local adsTarget = 0
local reloadStart = 0
local reloadDur = 0

local equippedMap: { [string]: string } = {} -- weaponId -> instanceId
local instById: { [string]: any } = {} -- instanceId -> SkinInstance

-- ── part builder ────────────────────────────────────────────────────────────
local function makePart(name: string, size: Vector3, pos: Vector3, color: Color3, material: Enum.Material, skinnable: boolean): BasePart
	local p = Instance.new("Part")
	p.Name = name
	p.Size = size
	p.Color = color
	p.Material = material
	p.Anchored = true
	p.CanCollide = false
	p.CanQuery = false
	p.CanTouch = false
	p.CastShadow = false
	p.Massless = true
	table.insert(gunParts, { part = p, offset = CFrame.new(pos), skinnable = skinnable })
	if skinnable then
		table.insert(skinnableParts, p)
	end
	return p
end

-- Procedural placeholder layouts per weapon class. Gun-local space: -Z is the
-- barrel direction, +X right, +Y up. Returns the muzzle tip position.
local function buildProcedural(class: string): Vector3
	if class == "pistol" then
		makePart("Body", Vector3.new(0.4, 0.5, 1.2), Vector3.new(0, 0, -0.4), GUNMETAL, Enum.Material.Metal, true)
		makePart("Barrel", Vector3.new(0.22, 0.22, 0.5), Vector3.new(0, 0.1, -1.1), GUNMETAL, Enum.Material.Metal, true)
		makePart("Grip", Vector3.new(0.36, 0.7, 0.42), Vector3.new(0, -0.5, 0.1), DARK, Enum.Material.SmoothPlastic, false)
		return Vector3.new(0, 0.1, -1.4)
	elseif class == "smg" then
		makePart("Body", Vector3.new(0.38, 0.48, 1.6), Vector3.new(0, 0, -0.5), GUNMETAL, Enum.Material.Metal, true)
		makePart("Barrel", Vector3.new(0.16, 0.16, 0.5), Vector3.new(0, 0.08, -1.3), GUNMETAL, Enum.Material.Metal, true)
		makePart("Mag", Vector3.new(0.28, 0.8, 0.4), Vector3.new(0, -0.55, -0.2), DARK, Enum.Material.SmoothPlastic, false)
		makePart("Grip", Vector3.new(0.3, 0.5, 0.34), Vector3.new(0, -0.4, 0.2), DARK, Enum.Material.SmoothPlastic, false)
		return Vector3.new(0, 0.08, -1.6)
	elseif class == "shotgun" then
		makePart("Body", Vector3.new(0.45, 0.5, 2.6), Vector3.new(0, 0, -0.9), GUNMETAL, Enum.Material.Metal, true)
		makePart("Barrel", Vector3.new(0.27, 0.27, 1.4), Vector3.new(0, 0.13, -2.0), GUNMETAL, Enum.Material.Metal, true)
		makePart("Pump", Vector3.new(0.4, 0.35, 0.6), Vector3.new(0, -0.3, -1.4), DARK, Enum.Material.SmoothPlastic, false)
		makePart("Stock", Vector3.new(0.38, 0.5, 0.8), Vector3.new(0, -0.05, 0.85), DARK, Enum.Material.SmoothPlastic, false)
		return Vector3.new(0, 0.13, -2.8)
	elseif class == "raygun" then
		makePart("Body", Vector3.new(0.5, 0.6, 1.4), Vector3.new(0, 0, -0.5), Color3.fromRGB(40, 120, 50), Enum.Material.Metal, true)
		makePart("Coil", Vector3.new(0.46, 0.46, 0.5), Vector3.new(0, 0.32, -1.0), Color3.fromRGB(120, 255, 120), Enum.Material.Neon, false)
		makePart("Orb", Vector3.new(0.55, 0.55, 0.55), Vector3.new(0, 0.1, -1.35), Color3.fromRGB(150, 255, 150), Enum.Material.Neon, false)
		makePart("Grip", Vector3.new(0.36, 0.62, 0.42), Vector3.new(0, -0.48, 0.1), DARK, Enum.Material.SmoothPlastic, false)
		return Vector3.new(0, 0.1, -1.55)
	end
	-- default: rifle
	makePart("Body", Vector3.new(0.4, 0.5, 2.4), Vector3.new(0, 0, -0.8), GUNMETAL, Enum.Material.Metal, true)
	makePart("Barrel", Vector3.new(0.18, 0.18, 1.0), Vector3.new(0, 0.1, -2.0), GUNMETAL, Enum.Material.Metal, true)
	makePart("Mag", Vector3.new(0.3, 0.7, 0.45), Vector3.new(0, -0.5, -0.3), DARK, Enum.Material.SmoothPlastic, false)
	makePart("Stock", Vector3.new(0.35, 0.45, 0.7), Vector3.new(0, -0.05, 0.75), DARK, Enum.Material.SmoothPlastic, false)
	makePart("Grip", Vector3.new(0.3, 0.55, 0.35), Vector3.new(0, -0.42, 0.05), DARK, Enum.Material.SmoothPlastic, false)
	return Vector3.new(0, 0.1, -2.6)
end

-- Use a real Model from ReplicatedStorage.Assets if one matches the weapon.
local function findAssetModel(def: any): Model?
	local assets = ReplicatedStorage:FindFirstChild("Assets")
	if not assets then
		return nil
	end
	local name = def.viewModelId or def.id
	local found = assets:FindFirstChild(name)
	if found and found:IsA("Model") and found.PrimaryPart then
		return found
	end
	return nil
end

local function buildFromAsset(model: Model): Vector3
	local clone = model:Clone()
	local pivot = clone:GetPivot()
	local muzzleTip = Vector3.new(0, 0, -3)
	for _, inst in ipairs(clone:GetDescendants()) do
		if inst:IsA("BasePart") then
			inst.Anchored = true
			inst.CanCollide = false
			inst.CanQuery = false
			inst.CanTouch = false
			inst.CastShadow = false
			inst.Massless = true
			table.insert(gunParts, { part = inst, offset = pivot:Inverse() * inst.CFrame, skinnable = false })
		elseif inst:IsA("Attachment") and inst.Name == "Muzzle" then
			muzzleTip = (pivot:Inverse() * inst.WorldCFrame).Position
		end
	end
	-- We reparent the individual parts; the empty clone shell is discarded.
	for _, entry in ipairs(gunParts) do
		entry.part.Parent = nil
	end
	clone:Destroy()
	return muzzleTip
end

-- ── skin tint ───────────────────────────────────────────────────────────────
local function applyTint()
	if not currentWeaponId then
		return
	end
	local instanceId = equippedMap[currentWeaponId]
	local inst = instanceId and instById[instanceId]
	local color = inst and UiUtil.rarityColor(inst.rarity) or nil
	for _, part in ipairs(skinnableParts) do
		if color then
			part.Color = color
			part.Material = Enum.Material.Metal
		else
			part.Color = GUNMETAL
			part.Material = Enum.Material.Metal
		end
	end
end

-- ── lifecycle ─────────────────────────────────────────────────────────────────
local function clearGun()
	for _, entry in ipairs(gunParts) do
		entry.part:Destroy()
	end
	gunParts = {}
	skinnableParts = {}
	muzzlePart = nil
end

local function setParentAll(parent: Instance?)
	for _, entry in ipairs(gunParts) do
		entry.part.Parent = parent
	end
end

function ViewmodelController.setWeapon(weaponId: string)
	if not Weapons.List[weaponId] then
		return
	end
	currentWeaponId = weaponId
	clearGun()

	local def = Weapons.get(weaponId)
	local muzzleTip: Vector3
	local assetModel = findAssetModel(def)
	if assetModel then
		muzzleTip = buildFromAsset(assetModel)
	else
		muzzleTip = buildProcedural(def.class or "rifle")
	end

	-- Muzzle flash (hidden until a shot).
	local flash = Instance.new("Part")
	flash.Name = "MuzzleFlash"
	flash.Shape = Enum.PartType.Ball
	flash.Size = Vector3.new(0.6, 0.6, 0.6)
	flash.Color = Color3.fromRGB(255, 220, 120)
	flash.Material = Enum.Material.Neon
	flash.Anchored = true
	flash.CanCollide = false
	flash.CanQuery = false
	flash.CanTouch = false
	flash.CastShadow = false
	flash.Massless = true
	flash.Transparency = 1
	table.insert(gunParts, { part = flash, offset = CFrame.new(muzzleTip), skinnable = false })
	muzzlePart = flash

	applyTint()
	setParentAll(visible and camera or nil)
end

function ViewmodelController.setVisible(v: boolean)
	visible = v
	setParentAll(v and camera or nil)
	if v then
		-- snap to the camera so it doesn't fly in from a stale position
		currentCF = camera.CFrame * BASE_OFFSET
	else
		adsTarget = 0
		adsAlpha = 0
	end
end

-- ADS progress (0 = hip, 1 = aimed). How aimed the gun is is read each frame.
function ViewmodelController.setAds(on: boolean)
	adsTarget = on and 1 or 0
end

function ViewmodelController.getAdsAlpha(): number
	return adsAlpha
end

-- Play a reload dip/tilt over `duration` seconds.
function ViewmodelController.playReload(duration: number)
	reloadStart = os.clock()
	reloadDur = duration
end

-- Recoil kick + muzzle flash (called by WeaponController on a successful shot).
function ViewmodelController.kick()
	recoil = math.min(recoil + 1, 3)
	if muzzlePart then
		muzzlePart.Transparency = 0.1
		task.delay(0.05, function()
			if muzzlePart then
				muzzlePart.Transparency = 1
			end
		end)
	end
end

local function isAlive(): boolean
	local char = player.Character
	local humanoid = char and char:FindFirstChildOfClass("Humanoid")
	return humanoid ~= nil and humanoid.Health > 0
end

local function onRender(dt: number)
	if not visible or #gunParts == 0 then
		return
	end
	if not isAlive() then
		setParentAll(nil)
		return
	end
	-- ensure parented (cheap no-op if already)
	if gunParts[1].part.Parent ~= camera then
		setParentAll(camera)
	end

	-- Walk bob driven by horizontal speed.
	local speed = 0
	local char = player.Character
	local root = char and char:FindFirstChild("HumanoidRootPart")
	if root and root:IsA("BasePart") then
		local v = root.AssemblyLinearVelocity
		speed = math.sqrt(v.X * v.X + v.Z * v.Z)
	end
	-- Ease ADS, and damp bob/sway while aiming.
	adsAlpha += (adsTarget - adsAlpha) * math.clamp(dt * 12, 0, 1)
	local bobScale = 1 - adsAlpha * 0.85

	bobPhase += dt * (6 + speed * 0.5)
	local bobAmt = math.clamp(speed / 16, 0, 1) * bobScale
	local bob = CFrame.new(math.cos(bobPhase) * 0.04 * bobAmt, math.abs(math.sin(bobPhase)) * 0.05 * bobAmt, 0)

	local baseOffset = BASE_OFFSET:Lerp(ADS_OFFSET, adsAlpha)
	local target = camera.CFrame * baseOffset * bob
	-- Smooth sway: lag toward the target each frame (snappier while aiming).
	currentCF = currentCF:Lerp(target, math.clamp(dt * (14 + adsAlpha * 16), 0, 1))

	-- Recoil decays toward 0; applied as a back+up kick.
	recoil += (0 - recoil) * math.clamp(dt * 12, 0, 1)
	local recoilCF = CFrame.new(0, 0, recoil * 0.35) * CFrame.Angles(recoil * 0.18, 0, 0)

	-- Reload dip: lower + tilt the gun, peaking mid-reload.
	local reloadCF = CFrame.new()
	local elapsed = os.clock() - reloadStart
	if reloadDur > 0 and elapsed < reloadDur then
		local dip = math.sin((elapsed / reloadDur) * math.pi)
		reloadCF = CFrame.new(0, -0.45 * dip, -0.1 * dip) * CFrame.Angles(math.rad(-55) * dip, math.rad(15) * dip, 0)
	end

	local rootCF = currentCF * reloadCF * recoilCF
	for _, entry in ipairs(gunParts) do
		entry.part.CFrame = rootCF * entry.offset
	end
end

function ViewmodelController.init()
	camera = workspace.CurrentCamera

	workspace:GetPropertyChangedSignal("CurrentCamera"):Connect(function()
		if workspace.CurrentCamera then
			camera = workspace.CurrentCamera
			if visible then
				setParentAll(camera)
			end
		end
	end)

	local equipUpdated = Remotes.get("EquipUpdated") :: RemoteEvent
	equipUpdated.OnClientEvent:Connect(function(map: { [string]: string })
		equippedMap = map or {}
		applyTint()
	end)

	local inventoryUpdated = Remotes.get("InventoryUpdated") :: RemoteEvent
	inventoryUpdated.OnClientEvent:Connect(function(list: { any })
		instById = {}
		for _, inst in ipairs(list or {}) do
			instById[inst.instanceId] = inst
		end
		applyTint()
	end)

	RunService:BindToRenderStep("AA_Viewmodel", Enum.RenderPriority.Camera.Value + 1, onRender)
end

return ViewmodelController
