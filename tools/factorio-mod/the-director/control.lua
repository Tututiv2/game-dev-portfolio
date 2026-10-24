-- Tuti's Cooler Biters - control stage.
--
-- Was Feature 1 of Tuti's Fun Mods; moved out into its own mod so the swarm
-- work can grow without dragging the cheat panel and QoL layer along with it.

local Biters = require("scripts.biters")
local Gui = require("scripts.gui")
local Mind = require("scripts.mind")
local Selftest = require("scripts.selftest")

script.on_init(function()
  Biters.init()
  Mind.init()
  Selftest.run()
end)

script.on_configuration_changed(function()
  Biters.init()
  Mind.init()
end)

Biters.register()

-- One tick handler for the whole mod. Two on the same period would silently
-- replace each other.
script.on_nth_tick(Biters.UPDATE_PERIOD, function()
  Biters.update()
  Mind.update()
  Gui.refresh_all()
end)

script.on_event(defines.events.on_player_created, function(event)
  local player = game.get_player(event.player_index)
  if player then Gui.on_player_created(player) end
end)

script.on_event("dir-toggle-gui", function(event)
  local player = game.get_player(event.player_index)
  if player then Gui.toggle(player) end
end)

script.on_event(defines.events.on_runtime_mod_setting_changed, function(event)
  if event.setting and event.setting:find("^dir%-") then
    storage.dir.enabled = settings.global["dir-enabled"].value
    Gui.refresh_all()
  end
end)

commands.add_command("dir-selftest", "Run the adaptation self-test", function(command)
  local failed, total = Selftest.run()
  local player = command.player_index and game.get_player(command.player_index)
  local message = failed == 0 and { "dir.selftest-pass", total } or { "dir.selftest-fail", failed, total }
  if player then player.print(message) else game.print(message) end
end)

commands.add_command("dir-profile", "Print the swarm's current resistance profile", function(command)
  local player = command.player_index and game.get_player(command.player_index)
  local lines = { string.format("Adaptation pressure: %.0f%%", (storage.dir.pressure or 0) * 100) }
  for damage_type, resist in pairs(storage.dir.adapt.resist) do
    lines[#lines + 1] = string.format("  %-10s resist %5.1f%%   usage %5.1f%%",
      damage_type, resist * 100, (storage.dir.adapt.share[damage_type] or 0) * 100)
  end
  local text = table.concat(lines, "\n")
  if player then player.print(text) else game.print(text) end
  log(text)
end)
