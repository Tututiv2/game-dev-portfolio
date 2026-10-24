-- The Cheat Panel window.
--
-- Note on defensiveness: a headless Factorio has no player, so this is the one
-- module the automated tests cannot exercise. Every build is wrapped so that a
-- mistake here surfaces as a printed message rather than taking the save down,
-- and style names are applied through pcall for the same reason.

local Cheats = require("scripts.cheats")
local Give = require("scripts.give")

local CheatGui = {}

local ROOT = "tfm-cheat-panel"

local function try_style(element, key, value)
  pcall(function() element.style[key] = value end)
end

local function destroy(player)
  local existing = player.gui.screen[ROOT]
  if existing then existing.destroy() end
end

local function format_value(value)
  if type(value) == "number" then
    if value == math.floor(value) and math.abs(value) < 1e6 then
      return tostring(math.floor(value))
    end
    return string.format("%.5f", value):gsub("0+$", ""):gsub("%.$", "")
  end
  return tostring(value)
end

local function add_row(parent, lever, ctx)
  local value = Cheats.read(lever, ctx)

  local label = parent.add{ type = "label", caption = { "tfm-cheat." .. lever.id } }
  if Cheats.is_modified(lever) then
    try_style(label, "font_color", { r = 1, g = 0.85, b = 0.4 })
  end

  if value == nil then
    -- Something this lever depends on is not present in this game.
    parent.add{ type = "label", caption = { "tfm-cheat.unavailable" } }
    parent.add{ type = "empty-widget" }
    parent.add{ type = "empty-widget" }
    return
  end

  if lever.kind == "bool" then
    parent.add{ type = "checkbox", name = "tfm-cheat-check:" .. lever.id, state = value and true or false }
    parent.add{ type = "empty-widget" }
  else
    local slider = parent.add{
      type = "slider",
      name = "tfm-cheat-slider:" .. lever.id,
      minimum_value = lever.min,
      maximum_value = lever.max,
      value_step = lever.step,
      value = math.max(lever.min, math.min(lever.max, value)),
    }
    try_style(slider, "width", 180)

    local field = parent.add{
      type = "textfield",
      name = "tfm-cheat-text:" .. lever.id,
      text = format_value(value),
      numeric = true,
      allow_decimal = true,
      allow_negative = false,
    }
    try_style(field, "width", 80)
  end

  local reset = parent.add{
    type = "button",
    name = "tfm-cheat-reset:" .. lever.id,
    caption = { "tfm-cheat.reset" },
  }
  try_style(reset, "width", 60)
  try_style(reset, "height", 28)
  try_style(reset, "font", "default-small")
  reset.enabled = Cheats.is_modified(lever)
end

-- The item tab is hand-built rather than generated from the lever table: it is
-- the one tab that is not a list of numbers.
local function build_items_tab(parent, player)
  local picker_row = parent.add{ type = "flow", direction = "horizontal" }
  picker_row.add{ type = "label", caption = { "tfm-cheat.pick-item" } }

  -- Prefer the quality-aware picker; fall back if quality is absent.
  local picker
  local ok = pcall(function()
    picker = picker_row.add{ type = "choose-elem-button", name = "tfm-give-elem",
                             elem_type = "item-with-quality" }
  end)
  if not ok or not picker then
    picker_row.add{ type = "choose-elem-button", name = "tfm-give-elem", elem_type = "item" }
  end

  local amount_row = parent.add{ type = "flow", direction = "horizontal" }
  amount_row.add{ type = "label", caption = { "tfm-cheat.amount" } }
  local count = amount_row.add{ type = "textfield", name = "tfm-give-count", text = "100",
                                numeric = true, allow_decimal = false, allow_negative = false }
  try_style(count, "width", 80)
  amount_row.add{ type = "button", name = "tfm-give", caption = { "tfm-cheat.give" } }
  amount_row.add{ type = "button", name = "tfm-give-stack", caption = { "tfm-cheat.give-stack" } }
  amount_row.add{ type = "button", name = "tfm-give-fill", caption = { "tfm-cheat.give-fill" } }

  parent.add{ type = "line" }

  local recents = Give.recents(player)
  parent.add{ type = "label", caption = { "tfm-cheat.recent" } }
  if #recents == 0 then
    parent.add{ type = "label", caption = { "tfm-cheat.recent-empty" } }
  else
    local grid = parent.add{ type = "table", column_count = 12 }
    for _, entry in ipairs(recents) do
      local button = grid.add{
        type = "sprite-button",
        name = "tfm-give-recent:" .. entry.name .. "|" .. tostring(entry.quality or ""),
        sprite = "item/" .. entry.name,
        -- Quality is shown in the tooltip rather than as a sprite-button field,
        -- so this does not depend on the quality overlay being supported here.
        tooltip = { "tfm-cheat.recent-tooltip", entry.name, entry.quality or "normal" },
      }
      try_style(button, "size", 40)
    end
    parent.add{ type = "button", name = "tfm-give-clear", caption = { "tfm-cheat.recent-clear" } }
  end

  parent.add{ type = "line" }

  -- Player-scoped cheats that do not belong in the force/surface lever table.
  local extras = parent.add{ type = "flow", direction = "vertical" }
  extras.add{ type = "checkbox", name = "tfm-cheat-mode", state = player.cheat_mode and true or false,
              caption = { "tfm-cheat.free-crafting" } }
  local buttons = extras.add{ type = "flow", direction = "horizontal" }
  buttons.add{ type = "button", name = "tfm-research-all", caption = { "tfm-cheat.research-all" } }
  buttons.add{ type = "button", name = "tfm-recipes-all", caption = { "tfm-cheat.recipes-all" } }
end

local function build(player)
  -- Rebuilding is how this window updates, so keep the open tab across rebuilds
  -- or every click would throw you back to the first tab.
  local tab_index
  local previous = player.gui.screen[ROOT]
  if previous then
    local pane = CheatGui.find(previous, "tfm-cheat-tabs")
    if pane and pane.valid then tab_index = pane.selected_tab_index end
  end

  destroy(player)
  local ctx = Cheats.context(player)

  local frame = player.gui.screen.add{ type = "frame", name = ROOT, direction = "vertical" }
  frame.auto_center = true

  -- Title bar with a drag handle and a close button.
  local titlebar = frame.add{ type = "flow", direction = "horizontal" }
  titlebar.drag_target = frame
  titlebar.add{ type = "label", caption = { "tfm-cheat.title" }, style = "frame_title" }
  local filler = titlebar.add{ type = "empty-widget", style = "draggable_space_header" }
  filler.drag_target = frame
  try_style(filler, "horizontally_stretchable", true)
  try_style(filler, "height", 24)
  try_style(filler, "minimal_width", 24)
  titlebar.add{ type = "sprite-button", name = "tfm-cheat-close", sprite = "utility/close",
                style = "frame_action_button" }

  frame.add{ type = "label", caption = { "tfm-cheat.subtitle", player.surface.name } }

  local tabs = frame.add{ type = "tabbed-pane", name = "tfm-cheat-tabs" }

  for _, section in ipairs(Cheats.SECTIONS) do
    local tab = tabs.add{ type = "tab", caption = { "tfm-cheat-section." .. section } }
    local content = tabs.add{ type = "frame", direction = "vertical", style = "inside_shallow_frame_with_padding" }
    local grid = content.add{ type = "table", column_count = 4 }
    try_style(grid, "horizontal_spacing", 12)
    try_style(grid, "vertical_spacing", 6)

    for _, lever in ipairs(Cheats.levers_in(section)) do
      add_row(grid, lever, ctx)
    end

    tabs.add_tab(tab, content)
  end

  local items_tab = tabs.add{ type = "tab", caption = { "tfm-cheat-section.items" } }
  local items_content = tabs.add{ type = "frame", direction = "vertical",
                                  style = "inside_shallow_frame_with_padding" }
  build_items_tab(items_content, player)
  tabs.add_tab(items_tab, items_content)

  local footer = frame.add{ type = "flow", direction = "horizontal" }
  footer.add{ type = "button", name = "tfm-cheat-reset-all", caption = { "tfm-cheat.reset-all" },
              style = "red_button" }
  footer.add{ type = "label", caption = { "tfm-cheat.footer" } }

  if tab_index then
    pcall(function() tabs.selected_tab_index = tab_index end)
  end

  -- Registers the window with the game so Escape closes it like any other.
  player.opened = frame

  return frame
end

function CheatGui.is_open(player)
  return player.gui.screen[ROOT] ~= nil
end

function CheatGui.build(player)
  local ok, err = pcall(build, player)
  if not ok then
    destroy(player)
    player.print("[Tuti's Fun Mods] cheat panel failed to open: " .. tostring(err))
  end
  return ok
end

function CheatGui.toggle(player)
  if CheatGui.is_open(player) then
    destroy(player)
  else
    CheatGui.build(player)
  end
end

function CheatGui.close(player)
  destroy(player)
end

-- Only rebuild what changed: rebuilding the whole window on every slider drag
-- would fight the mouse and lose the open tab.
local function refresh_row(player, lever)
  local ctx = Cheats.context(player)
  local value = Cheats.read(lever, ctx)
  local root = player.gui.screen[ROOT]
  if not root or value == nil then return end

  local field = CheatGui.find(root, "tfm-cheat-text:" .. lever.id)
  if field and field.valid then field.text = format_value(value) end
  local slider = CheatGui.find(root, "tfm-cheat-slider:" .. lever.id)
  if slider and slider.valid then
    slider.slider_value = math.max(lever.min, math.min(lever.max, value))
  end
  local reset = CheatGui.find(root, "tfm-cheat-reset:" .. lever.id)
  if reset and reset.valid then reset.enabled = Cheats.is_modified(lever) end
end

function CheatGui.find(element, name)
  if not (element and element.valid) then return nil end
  if element.name == name then return element end
  for _, child in pairs(element.children or {}) do
    local found = CheatGui.find(child, name)
    if found then return found end
  end
  return nil
end

local function lever_from_element(element, prefix)
  if not (element and element.valid and element.name) then return nil end
  local id = element.name:match("^" .. prefix .. "(.+)$")
  if not id then return nil end
  return Cheats.get(id)
end

-- The picker returns a PrototypeWithQuality table for "item-with-quality" and a
-- plain string for the plain "item" fallback, so handle both.
local function selected_item(player)
  local root = player.gui.screen[ROOT]
  local picker = root and CheatGui.find(root, "tfm-give-elem")
  if not (picker and picker.valid) then return nil end
  local value = picker.elem_value
  if value == nil then return nil end
  if type(value) == "table" then return value.name, value.quality end
  return value, nil
end

local function selected_count(player)
  local root = player.gui.screen[ROOT]
  local field = root and CheatGui.find(root, "tfm-give-count")
  local number = field and field.valid and tonumber(field.text)
  return math.max(1, math.floor(number or 1))
end

local function do_give(player, name, quality, count)
  local had = #Give.recents(player)
  local inserted = Give.give(player, name, quality, count)
  if inserted == 0 then
    player.print({ "tfm-cheat.give-none", name })
  else
    player.print({ "tfm-cheat.give-done", inserted, name })
  end
  -- Only rebuild when the recents strip actually changed shape.
  if #Give.recents(player) ~= had then CheatGui.build(player) end
end

function CheatGui.on_click(event)
  local element = event.element
  if not (element and element.valid) then return end
  local player = game.get_player(event.player_index)
  if not player then return end

  if element.name == "tfm-cheat-close" then
    destroy(player)
    return
  end

  -- ---------------------------------------------------------------- giving --
  if element.name == "tfm-give" or element.name == "tfm-give-stack" or element.name == "tfm-give-fill" then
    local name, quality = selected_item(player)
    if not name then
      player.print({ "tfm-cheat.no-item-picked" })
      return
    end
    local count
    if element.name == "tfm-give" then
      count = selected_count(player)
    elseif element.name == "tfm-give-stack" then
      count = Give.stack_size(name)
    else
      -- Far more than fits; insert() caps it and never spills the remainder.
      count = Give.stack_size(name) * 100
    end
    do_give(player, name, quality, count)
    return
  end

  if element.name == "tfm-give-clear" then
    Give.clear_recents(player)
    CheatGui.build(player)
    return
  end

  local recent = element.name:match("^tfm%-give%-recent:(.+)$")
  if recent then
    local name, quality = recent:match("^(.-)|(.*)$")
    if quality == "" then quality = nil end
    if name then do_give(player, name, quality, selected_count(player)) end
    return
  end

  if element.name == "tfm-research-all" then
    local ok = Give.research_all(player)
    player.print(ok and { "tfm-cheat.research-done" } or { "tfm-cheat.action-failed" })
    return
  end

  if element.name == "tfm-recipes-all" then
    local ok = Give.recipes_all(player)
    player.print(ok and { "tfm-cheat.recipes-done" } or { "tfm-cheat.action-failed" })
    return
  end

  if element.name == "tfm-cheat-reset-all" then
    local count = Cheats.reset_all(Cheats.context(player))
    player.print({ "tfm-cheat.reset-all-done", count })
    CheatGui.build(player)
    return
  end

  local lever = lever_from_element(element, "tfm%-cheat%-reset:")
  if lever then
    Cheats.reset(lever, Cheats.context(player))
    CheatGui.build(player)
  end
end

function CheatGui.on_slider(event)
  local lever = lever_from_element(event.element, "tfm%-cheat%-slider:")
  if not lever then return end
  local player = game.get_player(event.player_index)
  if not player then return end
  Cheats.write(lever, Cheats.context(player), event.element.slider_value)
  refresh_row(player, lever)
end

function CheatGui.on_text(event)
  local lever = lever_from_element(event.element, "tfm%-cheat%-text:")
  if not lever then return end
  local value = tonumber(event.element.text)
  if value == nil then return end -- mid-typing, ignore
  local player = game.get_player(event.player_index)
  if not player then return end
  value = math.max(lever.min, math.min(lever.max, value))
  Cheats.write(lever, Cheats.context(player), value)

  local root = player.gui.screen[ROOT]
  local slider = root and CheatGui.find(root, "tfm-cheat-slider:" .. lever.id)
  if slider and slider.valid then slider.slider_value = value end
  local reset = root and CheatGui.find(root, "tfm-cheat-reset:" .. lever.id)
  if reset and reset.valid then reset.enabled = Cheats.is_modified(lever) end
end

function CheatGui.on_checkbox(event)
  local element = event.element
  if not (element and element.valid) then return end
  local player = game.get_player(event.player_index)
  if not player then return end

  -- Free instant hand crafting is per-player, so it sits outside the
  -- force/surface lever table.
  if element.name == "tfm-cheat-mode" then
    player.cheat_mode = element.state
    return
  end

  local lever = lever_from_element(element, "tfm%-cheat%-check:")
  if not lever then return end
  Cheats.write(lever, Cheats.context(player), event.element.state)
  local root = player.gui.screen[ROOT]
  local reset = root and CheatGui.find(root, "tfm-cheat-reset:" .. lever.id)
  if reset and reset.valid then reset.enabled = Cheats.is_modified(lever) end
end

return CheatGui
