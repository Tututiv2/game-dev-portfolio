--!strict
-- Signal.luau
-- A tiny, dependency-free event object so services can broadcast things like
-- "a zombie died" or "the round changed" without coupling to RemoteEvents.
-- Standard connect/fire pattern.

export type Connection = {
	Disconnect: (self: Connection) -> (),
}

local Signal = {}
Signal.__index = Signal

function Signal.new()
	return setmetatable({ _handlers = {} }, Signal)
end

function Signal:Connect(fn: (...any) -> ()): Connection
	local handlers = self._handlers
	handlers[fn] = true
	return {
		Disconnect = function()
			handlers[fn] = nil
		end,
	}
end

function Signal:Fire(...: any)
	for fn in pairs(self._handlers) do
		task.spawn(fn, ...)
	end
end

function Signal:DisconnectAll()
	self._handlers = {}
end

return Signal
