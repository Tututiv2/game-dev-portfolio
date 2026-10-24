--!strict
-- DevConfig.luau
-- Who is allowed to use the dev menu. Authorized = the game owner (you), anyone
-- in Studio (for testing), or an explicit allowlist. The SERVER re-checks this
-- on every dev command — the client UI gate is just convenience (§9.2).

local RunService = game:GetService("RunService")

local DevConfig = {}

-- Add extra trusted UserIds here if you want collaborators to have the menu.
DevConfig.AllowedUserIds = {} :: { number }

function DevConfig.isAuthorized(player: Player): boolean
	if RunService:IsStudio() then
		return true
	end
	-- Owner of a user-owned game.
	if game.CreatorType == Enum.CreatorType.User and player.UserId == game.CreatorId then
		return true
	end
	if table.find(DevConfig.AllowedUserIds, player.UserId) then
		return true
	end
	return false
end

return DevConfig
