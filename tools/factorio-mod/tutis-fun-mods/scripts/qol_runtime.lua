-- The QoL features that do NOT need a prototype change, and therefore apply
-- instantly with no restart: reach distance and never-drying oil wells.

local QolRuntime = {}

local OIL_PERIOD = 300 -- ticks between top-ups, matching InfiniteOil's default

local function conflicts_with(mod_name)
  return script.active_mods[mod_name] ~= nil
    and not (settings.startup["tfm-qol-override"] and settings.startup["tfm-qol-override"].value)
end

-- ---------------------------------------------------------------- reach -----

-- Applied on load and when the setting changes, never every tick, so the cheat
-- panel's reach sliders are not fought over by this.
function QolRuntime.apply_reach()
  if not settings.global["tfm-qol-reach"].value then return end
  if conflicts_with("far-reach") then return end

  local bonus = settings.global["tfm-qol-reach-bonus"].value
  for _, force in pairs(game.forces) do
    force.character_reach_distance_bonus = bonus
    force.character_build_distance_bonus = bonus
    force.character_resource_reach_distance_bonus = bonus
    force.character_item_drop_distance_bonus = bonus
  end
end

-- ------------------------------------------------------------------ oil -----

-- Rather than sweeping the map for resources, this tracks the drills and asks
-- each one what it is mining. A pumpjack knows its own well.
local function is_drill(entity)
  return entity and entity.valid and entity.type == "mining-drill"
end

function QolRuntime.register_drill(entity)
  if not is_drill(entity) then return end
  storage.tfm.drills = storage.tfm.drills or {}
  storage.tfm.drills[entity.unit_number] = entity
end

function QolRuntime.rescan_drills()
  storage.tfm.drills = {}
  for _, surface in pairs(game.surfaces) do
    for _, drill in pairs(surface.find_entities_filtered{ type = "mining-drill" }) do
      storage.tfm.drills[drill.unit_number] = drill
    end
  end
end

function QolRuntime.refill_oil()
  if not settings.global["tfm-qol-oil"].value then return end
  if conflicts_with("InfiniteOil") then return end

  local yield = settings.global["tfm-qol-oil-yield"].value
  local drills = storage.tfm.drills or {}

  for unit_number, drill in pairs(drills) do
    if not (drill and drill.valid) then
      drills[unit_number] = nil
    else
      local target = drill.mining_target
      -- initial_amount is only set on infinite resources, which is exactly the
      -- set that should never run dry.
      if target and target.valid and target.initial_amount and target.amount < yield then
        target.amount = yield
      end
    end
  end
end

-- ----------------------------------------------------------------- wiring ---

function QolRuntime.init()
  storage.tfm.drills = storage.tfm.drills or {}
  QolRuntime.rescan_drills()
  QolRuntime.apply_reach()
end

function QolRuntime.on_settings_changed(setting)
  if setting == "tfm-qol-reach" or setting == "tfm-qol-reach-bonus" then
    QolRuntime.apply_reach()
  end
end

-- Called from the mod's single 60-tick handler.
function QolRuntime.update()
  storage.tfm.oil_timer = (storage.tfm.oil_timer or 0) + 60
  if storage.tfm.oil_timer >= OIL_PERIOD then
    storage.tfm.oil_timer = 0
    QolRuntime.refill_oil()
  end
end

function QolRuntime.register()
  local function built(event)
    QolRuntime.register_drill(event.entity)
  end
  script.on_event(defines.events.on_built_entity, built, { { filter = "type", type = "mining-drill" } })
  script.on_event(defines.events.on_robot_built_entity, built, { { filter = "type", type = "mining-drill" } })
  script.on_event(defines.events.script_raised_built, built, { { filter = "type", type = "mining-drill" } })
  script.on_event(defines.events.script_raised_revive, built, { { filter = "type", type = "mining-drill" } })
end

return QolRuntime
