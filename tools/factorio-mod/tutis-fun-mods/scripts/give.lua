-- Item giving for the cheat panel.
--
-- The picker is a vanilla choose-elem-button of type "item-with-quality", which
-- means the game's own item browser and search do the work - 340 item
-- prototypes (more with mods) in one element, instead of a grid of hundreds of
-- buttons that would have to be rebuilt on every keystroke.

local Give = {}

local MAX_RECENT = 12

-- "quality-unknown" is an engine placeholder, never something to hand out.
local function usable_qualities()
  local list = {}
  local ok = pcall(function()
    for name in pairs(prototypes.quality) do
      if name ~= "quality-unknown" then list[#list + 1] = name end
    end
  end)
  if not ok then return { "normal" } end
  table.sort(list)
  return list
end

function Give.qualities()
  if not storage.tfm.qualities then
    storage.tfm.qualities = usable_qualities()
  end
  return storage.tfm.qualities
end

function Give.stack_size(name)
  local proto = prototypes.item[name]
  if not proto then return 1 end
  local ok, size = pcall(function() return proto.stack_size end)
  return (ok and size) or 1
end

-- Returns how many actually landed. Anything that does not fit is simply not
-- inserted - insert() never spills onto the ground.
function Give.give(player, name, quality, count)
  if not prototypes.item[name] then return 0, "no such item" end
  count = math.max(1, math.floor(count or 1))

  local stack = { name = name, count = count }
  if quality then stack.quality = quality end

  local ok, inserted = pcall(function() return player.insert(stack) end)
  if not ok then
    -- Quality may not exist in this game; retry without it rather than fail.
    local ok2, inserted2 = pcall(function() return player.insert({ name = name, count = count }) end)
    if not ok2 then return 0, tostring(inserted2) end
    inserted = inserted2
  end

  if inserted and inserted > 0 then
    Give.remember(player, name, quality)
  end
  return inserted or 0
end

function Give.remember(player, name, quality)
  storage.tfm.recent = storage.tfm.recent or {}
  local list = storage.tfm.recent[player.index] or {}

  for index, entry in ipairs(list) do
    if entry.name == name and entry.quality == quality then
      table.remove(list, index)
      break
    end
  end
  table.insert(list, 1, { name = name, quality = quality })
  while #list > MAX_RECENT do table.remove(list) end

  storage.tfm.recent[player.index] = list
end

function Give.recents(player)
  storage.tfm.recent = storage.tfm.recent or {}
  return storage.tfm.recent[player.index] or {}
end

function Give.clear_recents(player)
  storage.tfm.recent = storage.tfm.recent or {}
  storage.tfm.recent[player.index] = nil
end

function Give.research_all(player)
  local ok, err = pcall(function() player.force.research_all_technologies() end)
  return ok, err
end

function Give.recipes_all(player)
  local ok, err = pcall(function() player.force.enable_all_recipes() end)
  return ok, err
end

return Give
