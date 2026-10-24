--!strict
-- Client bootstrap.
-- Waits for the shared layer + remotes, then starts controllers. The client is
-- purely a presentation/input layer (§9.2, §13.4): it sends requests and
-- animates results the server hands back. It never decides damage, currency,
-- rolls, or serials.

local ReplicatedStorage = game:GetService("ReplicatedStorage")

local Shared = ReplicatedStorage:WaitForChild("Shared")
local Remotes = require(Shared.Remotes)

-- Ensure the remote surface is replicated before controllers reference it.
Remotes.get("RoundChanged")

print("[Boot] Client starting…")

local controllers = script:FindFirstChild("controllers")
if not controllers then
	warn("[Boot] FATAL: 'controllers' folder not found under the Client script. "
		.. "Check the Rojo sync of src/client. No UI will load.")
	return
end

-- Start each controller in isolation: a failure in one must never prevent the
-- others (HUD, first-person, buttons, dev menu) from loading.
local function start(name: string)
	local mod = controllers:FindFirstChild(name)
	if not mod then
		warn("[Boot] missing controller: " .. name)
		return
	end
	local okRequire, controller = pcall(require, mod)
	if not okRequire then
		warn(("[Boot] require %s FAILED: %s"):format(name, tostring(controller)))
		return
	end
	local okInit, err = pcall(function()
		controller.init()
	end)
	if not okInit then
		warn(("[Boot] init %s FAILED: %s"):format(name, tostring(err)))
	else
		print("[Boot] " .. name .. " ✓")
	end
end

for _, name in { "HudController", "ViewmodelController", "WeaponController", "CasesController", "InventoryController", "DevController" } do
	start(name)
end

print("[AftermarketApocalypse] Client online.")
