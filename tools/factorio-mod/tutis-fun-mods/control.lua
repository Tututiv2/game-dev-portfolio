-- Tuti's Fun Mods - control stage.
--
-- One mod, many features, each behind its own toggle:
--   * the cheat panel (scripts/cheats.lua + cheat_gui.lua + give.lua)
--   * the QoL layer (prototypes/qol.lua at data stage, qol_runtime.lua here)
--
-- Adaptive Biters used to live here too; it moved out into its own mod,
-- "Tuti's Cooler Biters", so the swarm work can grow independently.

local CheatGui = require("scripts.cheat_gui")
local QolRuntime = require("scripts.qol_runtime")
local Selftest = require("scripts.selftest")

local UPDATE_PERIOD = 60

local function init()
  storage.tfm = storage.tfm or {}
  storage.tfm.baseline = storage.tfm.baseline or {}
  storage.tfm.gui = storage.tfm.gui or {}
  QolRuntime.init()
end

script.on_init(function()
  init()
  -- A headless load runs no players and clicks no buttons, so this is where the
  -- automated test gets its results from.
  Selftest.run()
end)

script.on_configuration_changed(function()
  init()
end)

QolRuntime.register()

-- Single tick handler for the whole mod. Two on the same period would silently
-- replace each other.
script.on_nth_tick(UPDATE_PERIOD, function()
  QolRuntime.update()
end)

-- ------------------------------------------------------------- cheat panel --

script.on_event("tfm-toggle-cheats", function(event)
  local player = game.get_player(event.player_index)
  if player then CheatGui.toggle(player) end
end)

script.on_event(defines.events.on_lua_shortcut, function(event)
  if event.prototype_name ~= "tfm-cheats-shortcut" then return end
  local player = game.get_player(event.player_index)
  if player then CheatGui.toggle(player) end
end)

script.on_event(defines.events.on_gui_click, CheatGui.on_click)
script.on_event(defines.events.on_gui_value_changed, CheatGui.on_slider)
script.on_event(defines.events.on_gui_text_changed, CheatGui.on_text)
script.on_event(defines.events.on_gui_checked_state_changed, CheatGui.on_checkbox)

-- Escape closes the panel, like every other Factorio window.
script.on_event(defines.events.on_gui_closed, function(event)
  local player = game.get_player(event.player_index)
  if player and event.element and event.element.valid and event.element.name == "tfm-cheat-panel" then
    CheatGui.close(player)
  end
end)

script.on_event(defines.events.on_runtime_mod_setting_changed, function(event)
  if event.setting and event.setting:find("^tfm%-") then
    QolRuntime.on_settings_changed(event.setting)
  end
end)

commands.add_command("tfm", "Open Tuti's cheat panel", function(command)
  local player = command.player_index and game.get_player(command.player_index)
  if player then CheatGui.toggle(player) end
end)

commands.add_command("tfm-selftest", "Run Tuti's Fun Mods self-test", function(command)
  local failed, total = Selftest.run()
  local player = command.player_index and game.get_player(command.player_index)
  local message = failed == 0
    and { "tfm.selftest-pass", total }
    or { "tfm.selftest-fail", failed, total }
  if player then player.print(message) else game.print(message) end
end)
