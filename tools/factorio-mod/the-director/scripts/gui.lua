-- The readout. Without this the feature is invisible: a player whose bullets
-- quietly stopped working just thinks the game got harder for no reason. The
-- panel is the mechanic - it tells you what they have learned so you know what
-- to switch to.

local Adaptation = require("scripts.adaptation")
local Director = require("scripts.director")
local Mind = require("scripts.mind")

local Gui = {}

local ROOT = "dir-panel"

local function destroy(player)
  local existing = player.gui.left[ROOT]
  if existing then existing.destroy() end
end

local function bar_colour(value, cap)
  -- Green while they are still soft, red as they close on the ceiling.
  local t = cap > 0 and math.min(value / cap, 1) or 0
  return { r = 0.25 + 0.65 * t, g = 0.75 - 0.55 * t, b = 0.25 }
end

function Gui.build(player)
  destroy(player)

  local frame = player.gui.left.add{ type = "frame", name = ROOT, direction = "vertical" }
  frame.caption = { "dir.panel-title" }

  if not settings.global["dir-enabled"].value then
    frame.add{ type = "label", caption = { "dir.disabled" } }
    return
  end

  -- What it knows about you is only readable once you have researched the
  -- listening gear. Before that the panel tells you nothing, which is the point.
  local intel = Mind.intel_level(player.force)
  if intel < 3 then
    frame.add{ type = "label", caption = { "dir.no-intel" } }
    return
  end

  local director = storage.dir.director
  if director then
    local threat = director.threat or 0
    local label = frame.add{ type = "label", caption = { "dir.threat", string.format("%.2f", threat) } }
    pcall(function()
      -- Redder the worse it has got, so a glance is enough.
      local heat = math.min(threat / 5, 1)
      label.style.font_color = { r = 0.6 + 0.4 * heat, g = 0.9 - 0.6 * heat, b = 0.4 }
    end)

    local escalation = Director.escalation(director, Mind.opts(), Mind.quality_tiers())
    frame.add{ type = "label",
      caption = { "dir.next-wave", escalation.spawn_count, tostring(escalation.quality) } }
  end

  local pressure = storage.dir.pressure or 0
  frame.add{ type = "label", caption = { "dir.pressure", string.format("%.0f", pressure * 100) } }

  local state = storage.dir.adapt
  local cap = settings.global["dir-adapt-cap"].value

  -- Show anything they have learned, plus anything you are currently leaning on
  -- hard enough to matter, so you can see a resistance coming before it lands.
  local rows = {}
  for damage_type, resist in pairs(state.resist) do
    if resist > 0.005 then
      rows[#rows + 1] = { damage = damage_type, resist = resist, share = state.share[damage_type] or 0 }
    end
  end
  for damage_type, share in pairs(state.share) do
    if share > 0.01 and not state.resist[damage_type] then
      rows[#rows + 1] = { damage = damage_type, resist = 0, share = share }
    end
  end

  if #rows == 0 then
    local _, peak = Adaptation.peak(state)
    local any_damage = next(state.exposure) ~= nil
    frame.add{ type = "label", caption = (any_damage and peak == 0) and { "dir.diversified" } or { "dir.dormant" } }
    return
  end

  table.sort(rows, function(a, b)
    if a.resist == b.resist then return a.damage < b.damage end
    return a.resist > b.resist
  end)

  local table_element = frame.add{ type = "table", name = "dir-rows", column_count = 3 }
  table_element.add{ type = "label", caption = { "dir.col-damage" } }
  table_element.add{ type = "label", caption = { "dir.col-resist" } }
  table_element.add{ type = "label", caption = { "dir.col-share" } }

  for _, row in ipairs(rows) do
    table_element.add{ type = "label", caption = { "damage-type-name." .. row.damage } }

    local bar = table_element.add{ type = "progressbar", value = cap > 0 and math.min(row.resist / cap, 1) or 0 }
    bar.style.width = 120
    bar.style.color = bar_colour(row.resist, cap)

    table_element.add{
      type = "label",
      caption = string.format("%.0f%%  (using %.0f%%)", row.resist * 100, row.share * 100),
    }
  end
end

function Gui.is_open(player)
  return player.gui.left[ROOT] ~= nil
end

function Gui.toggle(player)
  if Gui.is_open(player) then
    destroy(player)
    storage.dir.gui[player.index] = false
  else
    Gui.build(player)
    storage.dir.gui[player.index] = true
  end
end

-- Called on the same cadence as the model update, so the numbers on screen are
-- the numbers being used.
function Gui.refresh_all()
  for _, player in pairs(game.players) do
    if player.valid and Gui.is_open(player) then
      Gui.build(player)
    end
  end
end

function Gui.on_player_created(player)
  if settings.get_player_settings(player)["dir-adapt-gui"].value then
    Gui.build(player)
    storage.dir.gui[player.index] = true
  end
end

return Gui
