-- Feature 2: the Cheat Panel.
--
-- Why this exists in this shape: Factorio flatly refuses to let one mod write
-- another mod's settings ("Settings can only be changed by the owning player or
-- the mod that made the setting"), and startup settings are read-only at runtime
-- even for their owner. Three separate attempts confirmed it, including trying
-- to steal prototype ownership in settings-final-fixes. There is no way in.
--
-- So this does not touch settings at all. It edits the GAME STATE those settings
-- were only ever a means to produce - force bonuses, surface properties, map
-- settings. That turns out to be strictly better for what it is for:
--   * it works no matter which mods are installed, including mods with no
--     settings at all,
--   * it applies instantly with no restart,
--   * and it stacks on top of whatever the other mods already did.
--
-- The catch, stated honestly: values baked into prototypes at load - belt speed
-- being the obvious one - are not reachable this way by anybody. Those need to
-- be OUR startup settings, which is what the absorption plan is for.
--
-- Everything below is data. Adding a lever is adding a row, not writing code.

local Cheats = {}

-- Discovered once and sorted, so every client walks them in the same order.
local function ammo_categories()
  local list = {}
  local ok = pcall(function()
    for name in pairs(prototypes.ammo_category) do list[#list + 1] = name end
  end)
  if not ok or #list == 0 then
    list = { "bullet", "rocket", "shotgun-shell", "flamethrower", "laser",
             "artillery-shell", "capsule", "grenade", "landmine", "melee", "electric" }
  end
  table.sort(list)
  return list
end

-- ctx = { player, force, surface }
local LEVERS = {
  -- ---------------------------------------------------------------- player --
  { id = "inventory",      section = "player", kind = "number", min = 0,   max = 500,  step = 10,
    get = function(c) return c.force.character_inventory_slots_bonus end,
    set = function(c, v) c.force.character_inventory_slots_bonus = v end },
  { id = "reach",          section = "player", kind = "number", min = 0,   max = 200,  step = 5,
    get = function(c) return c.force.character_reach_distance_bonus end,
    set = function(c, v) c.force.character_reach_distance_bonus = v end },
  { id = "build_reach",    section = "player", kind = "number", min = 0,   max = 200,  step = 5,
    get = function(c) return c.force.character_build_distance_bonus end,
    set = function(c, v) c.force.character_build_distance_bonus = v end },
  { id = "resource_reach", section = "player", kind = "number", min = 0,   max = 200,  step = 5,
    get = function(c) return c.force.character_resource_reach_distance_bonus end,
    set = function(c, v) c.force.character_resource_reach_distance_bonus = v end },
  { id = "run_speed",      section = "player", kind = "number", min = 0,   max = 10,   step = 0.25,
    get = function(c) return c.force.character_running_speed_modifier end,
    set = function(c, v) c.force.character_running_speed_modifier = v end },
  { id = "mining_speed",   section = "player", kind = "number", min = 0,   max = 20,   step = 0.5,
    get = function(c) return c.force.manual_mining_speed_modifier end,
    set = function(c, v) c.force.manual_mining_speed_modifier = v end },
  { id = "craft_speed",    section = "player", kind = "number", min = 0,   max = 20,   step = 0.5,
    get = function(c) return c.force.manual_crafting_speed_modifier end,
    set = function(c, v) c.force.manual_crafting_speed_modifier = v end },
  { id = "health",         section = "player", kind = "number", min = 0,   max = 5000, step = 100,
    get = function(c) return c.force.character_health_bonus end,
    set = function(c, v) c.force.character_health_bonus = v end },
  { id = "trash_slots",    section = "player", kind = "number", min = 0,   max = 100,  step = 5,
    get = function(c) return c.force.character_trash_slot_count end,
    set = function(c, v) c.force.character_trash_slot_count = v end },

  -- ------------------------------------------------------------------ base --
  { id = "lab_speed",      section = "base", kind = "number", min = 0, max = 50, step = 1,
    get = function(c) return c.force.laboratory_speed_modifier end,
    set = function(c, v) c.force.laboratory_speed_modifier = v end },
  { id = "lab_prod",       section = "base", kind = "number", min = 0, max = 10, step = 0.25,
    get = function(c) return c.force.laboratory_productivity_bonus end,
    set = function(c, v) c.force.laboratory_productivity_bonus = v end },
  { id = "bot_speed",      section = "base", kind = "number", min = 0, max = 30, step = 1,
    get = function(c) return c.force.worker_robots_speed_modifier end,
    set = function(c, v) c.force.worker_robots_speed_modifier = v end },
  { id = "bot_battery",    section = "base", kind = "number", min = 0, max = 30, step = 1,
    get = function(c) return c.force.worker_robots_battery_modifier end,
    set = function(c, v) c.force.worker_robots_battery_modifier = v end },
  { id = "bot_storage",    section = "base", kind = "number", min = 0, max = 30, step = 1,
    get = function(c) return c.force.worker_robots_storage_bonus end,
    set = function(c, v) c.force.worker_robots_storage_bonus = v end },
  { id = "inserter_stack", section = "base", kind = "number", min = 0, max = 50, step = 1,
    get = function(c) return c.force.inserter_stack_size_bonus end,
    set = function(c, v) c.force.inserter_stack_size_bonus = v end },
  { id = "drill_prod",     section = "base", kind = "number", min = 0, max = 20, step = 0.5,
    get = function(c) return c.force.mining_drill_productivity_bonus end,
    set = function(c, v) c.force.mining_drill_productivity_bonus = v end },
  { id = "artillery",      section = "base", kind = "number", min = 0, max = 20, step = 0.5,
    get = function(c) return c.force.artillery_range_modifier end,
    set = function(c, v) c.force.artillery_range_modifier = v end },
  { id = "train_brakes",   section = "base", kind = "number", min = 0, max = 20, step = 0.5,
    get = function(c) return c.force.train_braking_force_bonus end,
    set = function(c, v) c.force.train_braking_force_bonus = v end },
  { id = "bot_lifetime",   section = "base", kind = "number", min = 0, max = 20, step = 0.5,
    get = function(c) return c.force.following_robots_lifetime_modifier end,
    set = function(c, v) c.force.following_robots_lifetime_modifier = v end },

  -- ---------------------------------------------------------------- combat --
  -- One slider drives every ammo category at once; per-category tuning is what
  -- the vanilla upgrade tree is for.
  { id = "ammo_damage",  section = "combat", kind = "number", min = 0, max = 20, step = 0.5,
    get = function(c) return c.force.get_ammo_damage_modifier(Cheats.first_ammo_category()) end,
    set = function(c, v)
      for _, category in ipairs(Cheats.ammo_categories()) do
        pcall(function() c.force.set_ammo_damage_modifier(category, v) end)
      end
    end },
  { id = "gun_speed",    section = "combat", kind = "number", min = 0, max = 20, step = 0.5,
    get = function(c) return c.force.get_gun_speed_modifier(Cheats.first_ammo_category()) end,
    set = function(c, v)
      for _, category in ipairs(Cheats.ammo_categories()) do
        pcall(function() c.force.set_gun_speed_modifier(category, v) end)
      end
    end },
  { id = "turret_damage", section = "combat", kind = "number", min = 0, max = 20, step = 0.5,
    get = function(c) return c.force.get_turret_attack_modifier("gun-turret") end,
    set = function(c, v)
      for _, turret in ipairs({ "gun-turret", "laser-turret", "flamethrower-turret", "artillery-turret" }) do
        pcall(function() c.force.set_turret_attack_modifier(turret, v) end)
      end
    end },

  -- ----------------------------------------------------------------- world --
  { id = "solar",        section = "world", kind = "number", min = 0, max = 50, step = 1,
    get = function(c) return c.surface.solar_power_multiplier end,
    set = function(c, v) c.surface.solar_power_multiplier = v end },
  { id = "day_length",   section = "world", kind = "number", min = 5000, max = 200000, step = 5000,
    get = function(c) return c.surface.ticks_per_day end,
    set = function(c, v) c.surface.ticks_per_day = v end },
  { id = "always_day",   section = "world", kind = "bool",
    get = function(c) return c.surface.always_day end,
    set = function(c, v) c.surface.always_day = v end },
  { id = "freeze_time",  section = "world", kind = "bool",
    get = function(c) return c.surface.freeze_daytime end,
    set = function(c, v) c.surface.freeze_daytime = v end },
  { id = "wind",         section = "world", kind = "number", min = 0, max = 2, step = 0.05,
    get = function(c) return c.surface.wind_speed end,
    set = function(c, v) c.surface.wind_speed = v end },
  { id = "game_speed",   section = "world", kind = "number", min = 0.25, max = 10, step = 0.25,
    get = function() return game.speed end,
    set = function(_, v) game.speed = v end },

  -- --------------------------------------------------------------- enemies --
  { id = "peaceful",     section = "enemies", kind = "bool",
    get = function(c) return c.surface.peaceful_mode end,
    set = function(c, v) c.surface.peaceful_mode = v end },
  { id = "evolution",    section = "enemies", kind = "number", min = 0, max = 1, step = 0.05,
    get = function(c) return game.forces["enemy"].get_evolution_factor(c.surface) end,
    set = function(c, v) game.forces["enemy"].set_evolution_factor(v, c.surface) end },
  { id = "evo_time",     section = "enemies", kind = "number", min = 0, max = 0.001, step = 0.00005,
    get = function() return game.map_settings.enemy_evolution.time_factor end,
    set = function(_, v) game.map_settings.enemy_evolution.time_factor = v end },
  { id = "evo_pollution", section = "enemies", kind = "number", min = 0, max = 0.001, step = 0.00005,
    get = function() return game.map_settings.enemy_evolution.pollution_factor end,
    set = function(_, v) game.map_settings.enemy_evolution.pollution_factor = v end },
  { id = "pollution_on", section = "enemies", kind = "bool",
    get = function() return game.map_settings.pollution.enabled end,
    set = function(_, v) game.map_settings.pollution.enabled = v end },
  { id = "pollution_spread", section = "enemies", kind = "number", min = 0, max = 1, step = 0.01,
    get = function() return game.map_settings.pollution.diffusion_ratio end,
    set = function(_, v) game.map_settings.pollution.diffusion_ratio = v end },
  { id = "expansion_on", section = "enemies", kind = "bool",
    get = function() return game.map_settings.enemy_expansion.enabled end,
    set = function(_, v) game.map_settings.enemy_expansion.enabled = v end },
}

Cheats.SECTIONS = { "player", "base", "combat", "world", "enemies" }
Cheats.LEVERS = LEVERS

local by_id = {}
for _, lever in ipairs(LEVERS) do by_id[lever.id] = lever end

function Cheats.get(id) return by_id[id] end

function Cheats.ammo_categories()
  if not storage.tfm.ammo_categories then
    storage.tfm.ammo_categories = ammo_categories()
  end
  return storage.tfm.ammo_categories
end

function Cheats.first_ammo_category()
  return Cheats.ammo_categories()[1] or "bullet"
end

function Cheats.context(player)
  return { player = player, force = player.force, surface = player.surface }
end

-- No lever reads ctx.player, so the test suite can drive all of them without a
-- player existing - which is the only reason the registry can be verified at all
-- on a headless load.
function Cheats.headless_context()
  return {
    player = nil,
    force = game.forces["player"],
    surface = game.surfaces["nauvis"] or game.surfaces[1],
  }
end

function Cheats.levers_in(section)
  local out = {}
  for _, lever in ipairs(LEVERS) do
    if lever.section == section then out[#out + 1] = lever end
  end
  return out
end

-- Reading a lever can fail if another mod removed whatever it depends on, so
-- every read is guarded and reports nil rather than taking the GUI down.
function Cheats.read(lever, ctx)
  local ok, value = pcall(lever.get, ctx)
  if not ok then return nil end
  return value
end

-- The first time a lever is touched, remember where it started so Reset can put
-- it back exactly - including back to whatever another mod had set it to.
function Cheats.write(lever, ctx, value)
  storage.tfm.baseline = storage.tfm.baseline or {}
  if storage.tfm.baseline[lever.id] == nil then
    local current = Cheats.read(lever, ctx)
    if current ~= nil then storage.tfm.baseline[lever.id] = current end
  end
  local ok, err = pcall(lever.set, ctx, value)
  return ok, err
end

function Cheats.reset(lever, ctx)
  local baseline = storage.tfm.baseline and storage.tfm.baseline[lever.id]
  if baseline == nil then return false end
  local ok = pcall(lever.set, ctx, baseline)
  if ok then storage.tfm.baseline[lever.id] = nil end
  return ok
end

function Cheats.reset_all(ctx)
  local count = 0
  for _, lever in ipairs(LEVERS) do
    if Cheats.reset(lever, ctx) then count = count + 1 end
  end
  return count
end

function Cheats.is_modified(lever)
  return storage.tfm.baseline ~= nil and storage.tfm.baseline[lever.id] ~= nil
end

return Cheats
