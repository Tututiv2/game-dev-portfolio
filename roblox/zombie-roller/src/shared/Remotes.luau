--!strict
-- Remotes.luau
-- Single registry of every RemoteEvent / RemoteFunction. Server creates them,
-- client waits for them. Keeping all wire names in one file prevents typo bugs
-- and documents the entire client<->server surface in one place.
--
-- Server-authority rule (§9.2, §13.4): the client only ever REQUESTS actions
-- (e.g. "open Field Crate"); the server validates, rolls, and returns results.

local RunService = game:GetService("RunService")
local ReplicatedStorage = game:GetService("ReplicatedStorage")

-- Declare the wire surface here. "Event" = RemoteEvent, "Function" = RemoteFunction.
local DEFINITIONS = {
	-- Zombies core loop
	PointsChanged = "Event", -- server -> client: essence updated
	RoundChanged = "Event", -- server -> client: new round / dog round
	-- Economy / cases (client requests, server decides)
	RequestBuyCase = "Function", -- client -> server: buy a case ITEM into inventory
	RequestOpenCase = "Function", -- client -> server: open an OWNED case item, returns the rolled instance
	RequestClaimLuxury = "Function", -- client -> server: claim an earned Luxury Case from the tracker
	InventoryUpdated = "Event", -- server -> client: full inventory list
	CurrencyUpdated = "Event", -- server -> client: { credits, cash }
	ShopUpdated = "Event", -- server -> client: { robuxSpent, luxuryClaimable, cases }
	-- Equip / scrap (skins on weapons)
	RequestEquipSkin = "Function", -- client -> server: equip an owned skin instance to its weapon
	RequestUnequipWeapon = "Function", -- client -> server: clear the equipped skin on a weaponId
	RequestScrapSkin = "Function", -- client -> server: dismantle a skin instance -> Credits
	EquipUpdated = "Event", -- server -> client: { [weaponId] = instanceId } currently equipped
	-- End-of-match payout
	MatchSummary = "Event", -- server -> client: { round, kills, credits, firstWin }
	-- Shooting (input only; server is authoritative on damage)
	FireWeapon = "Event", -- client -> server: I fired, here's my aim ray
	-- Loadout (server owns which weapons you have)
	LoadoutChanged = "Event", -- server -> client: { weapons = {id..}, equip = id? }
	AmmoRefill = "Event", -- server -> client: refill reserve for a weapon ("all" or weaponId)
	SetEquipped = "Event", -- client -> server: my currently-held weaponId (for Pack-a-Punch)
	WeaponUpgraded = "Event", -- server -> client: this weaponId got Pack-a-Punched
	-- Perks
	PerkGranted = "Event", -- server -> client: a perkId you now hold (for HUD later)
	-- Feedback
	Announce = "Event", -- server -> client: { text, color } big on-screen flash (power-ups etc.)
	ZoneChanged = "Event", -- server -> client: "hub" | "arena"
	-- Dev menu (server re-validates authorization on every command)
	DevCommand = "Event", -- client -> server: (commandName, arg)
}

local FOLDER_NAME = "AA_Remotes"

local Remotes = {}

local function buildOnServer(): Folder
	local folder = ReplicatedStorage:FindFirstChild(FOLDER_NAME)
	if not folder then
		folder = Instance.new("Folder")
		folder.Name = FOLDER_NAME
		folder.Parent = ReplicatedStorage
	end
	for name, kind in pairs(DEFINITIONS) do
		if not folder:FindFirstChild(name) then
			local inst = Instance.new(kind == "Function" and "RemoteFunction" or "RemoteEvent")
			inst.Name = name
			inst.Parent = folder
		end
	end
	return folder :: Folder
end

local _folder: Folder? = nil

local function getFolder(): Folder
	if _folder then
		return _folder
	end
	if RunService:IsServer() then
		_folder = buildOnServer()
	else
		_folder = ReplicatedStorage:WaitForChild(FOLDER_NAME) :: Folder
	end
	return _folder :: Folder
end

-- Returns the RemoteEvent/RemoteFunction by name (creates on server, waits on client).
function Remotes.get(name: string): Instance
	assert(DEFINITIONS[name], "Unknown remote: " .. tostring(name))
	local folder = getFolder()
	local inst = folder:WaitForChild(name, 10)
	assert(inst, "Remote not found (timed out): " .. name)
	return inst
end

-- Call once on the server at boot to eagerly create all remotes.
function Remotes.init()
	getFolder()
end

return Remotes
