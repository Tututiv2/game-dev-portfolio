-- Adaptive Biters: the Factorio-facing half. Everything that needs the game
-- state lives here; the model itself is in scripts/adaptation.lua.
--
-- Resistance is applied by healing back a fraction of damage as it lands rather
-- than by generating resistance variants of every enemy prototype. That choice
-- is what makes the feature work with modded enemies you already run (Cold
-- biters, Toxic biters, biter factions) without knowing anything about them,
-- and what lets the whole thing be toggled mid-save.

local Adaptation = require("scripts.adaptation")

local Biters = {}

local UPDATE_PERIOD = 60   -- ticks between adaptation updates (once per second)
local SAMPLE_PERIOD = 600  -- ticks between industry samples (once per 10s)

-- Entity types the swarm is made of. Player turrets are ammo-turret /
-- electric-turret / fluid-turret, so plain "turret" catches worms and nothing
-- of yours. segmented-unit is the demolisher type.
local ENEMY_TYPES = { "unit", "unit-spawner", "turret", "segmented-unit" }

-- What your factory is mostly made of nudges the swarm on its own. The weights
-- exist because raw production counts are not comparable - you make plates in
-- the millions and uranium in the thousands - so each group is scaled to bring
-- it into the same range before shares are taken. All of this is flavour and
-- all of it is tunable; set "Industry influence" to 0 to ignore it entirely.
local INDUSTRY = {
  { damage = "physical",  weight = 1,  items = { "iron-plate", "copper-plate", "steel-plate" } },
  { damage = "poison",    weight = 4,  items = { "plastic-bar", "sulfur" } },
  { damage = "fire",      weight = 8,  items = { "rocket-fuel", "solid-fuel" } },
  { damage = "explosion", weight = 40, items = { "uranium-235", "uranium-238", "uranium-fuel-cell" } },
  { damage = "laser",     weight = 6,  items = { "advanced-circuit", "processing-unit" } },
}

local function opts_from_settings()
  local s = settings.global
  return {
    cap       = s["dir-adapt-cap"].value,
    threshold = s["dir-adapt-threshold"].value,
    rate      = s["dir-adapt-rate"].value,
    memory    = s["dir-adapt-memory"].value,
    bias      = s["dir-industry-bias"].value,
    pressure  = storage.dir.pressure or 1.0,
  }
end

-- Which forces count as "the swarm". Rebuilt periodically rather than looked up
-- per damage event, and recomputed immediately when a force appears, because
-- faction mods create enemy forces at runtime.
function Biters.refresh_hostiles()
  local player_force = game.forces["player"]
  local hostile = {}
  if player_force and player_force.valid then
    for name, force in pairs(game.forces) do
      if force ~= player_force and force.is_enemy(player_force) then
        hostile[name] = true
      end
    end
  end
  storage.dir.hostile = hostile
end

-- Highest evolution across every hostile force on every surface. Used as the
-- global pressure multiplier so an early game is untouched.
local function compute_pressure()
  if not settings.global["dir-adapt-evolution-gate"].value then
    return 1.0
  end
  local peak = 0
  for name in pairs(storage.dir.hostile) do
    local force = game.forces[name]
    if force and force.valid then
      for _, surface in pairs(game.surfaces) do
        local e = force.get_evolution_factor(surface)
        if e and e > peak then peak = e end
      end
    end
  end
  return peak
end

local function produced(force, item)
  if not prototypes.item[item] then return 0 end
  local total = 0
  for _, surface in pairs(game.surfaces) do
    total = total + force.get_item_production_statistics(surface).get_input_count(item)
  end
  return total
end

-- Turn what the factory has produced into a damage-type bias table of 0..1.
local function sample_industry()
  local force = game.forces["player"]
  if not force or not force.valid then return {} end

  local weighted, total = {}, 0
  for _, group in ipairs(INDUSTRY) do
    local count = 0
    for _, item in ipairs(group.items) do
      count = count + produced(force, item)
    end
    local value = count * group.weight
    weighted[group.damage] = (weighted[group.damage] or 0) + value
    total = total + value
  end

  if total <= 0 then return {} end
  local bias = {}
  for damage, value in pairs(weighted) do
    bias[damage] = value / total
  end
  return bias
end

-- Every point of damage that lands on the swarm passes through here, so it is
-- kept to table lookups and one arithmetic op.
local function on_entity_damaged(event)
  if not storage.dir.enabled then return end

  local entity = event.entity
  if not (entity and entity.valid) then return end

  -- A lethal hit is left alone. Healing an entity at zero health would revive
  -- things you just killed.
  if event.final_health <= 0 then return end

  local hostile = storage.dir.hostile
  if not hostile[entity.force.name] then return end

  -- Biters fighting biters must not train the profile, and must not be healed
  -- either, or inter-faction wars stalemate forever.
  local attacker = event.force
  if attacker and attacker.valid and hostile[attacker.name] then return end

  local damage_type = event.damage_type.name
  local amount = event.final_damage_amount
  Adaptation.observe(storage.dir.adapt, damage_type, amount)

  local resist = storage.dir.adapt.resist[damage_type]
  if resist and resist > 0.001 then
    local healed = entity.health + amount * resist
    local max = entity.max_health
    if max and healed > max then healed = max end
    entity.health = healed
  end
end

-- Driven by control.lua rather than registering its own on_nth_tick: two
-- handlers on the same period would silently replace each other.
function Biters.update()
  storage.dir.enabled = settings.global["dir-enabled"].value
  if not storage.dir.enabled then return end

  if game.tick - (storage.dir.last_sample or -SAMPLE_PERIOD) >= SAMPLE_PERIOD then
    storage.dir.last_sample = game.tick
    Biters.refresh_hostiles()
    storage.dir.pressure = compute_pressure()
    storage.dir.bias = settings.global["dir-industry-bias"].value > 0 and sample_industry() or {}
  end

  Adaptation.update(storage.dir.adapt, opts_from_settings(), storage.dir.bias)
end

function Biters.init()
  storage.dir = storage.dir or {}
  storage.dir.adapt = storage.dir.adapt or Adaptation.new_state()
  storage.dir.bias = storage.dir.bias or {}
  storage.dir.hostile = storage.dir.hostile or {}
  storage.dir.pressure = storage.dir.pressure or 0
  storage.dir.last_sample = storage.dir.last_sample or -SAMPLE_PERIOD
  storage.dir.enabled = settings.global["dir-enabled"].value
  storage.dir.gui = storage.dir.gui or {}
  Biters.refresh_hostiles()
end

function Biters.register()
  -- The first filter carries no mode; every later one is OR'd onto it, so the
  -- event only ever fires for swarm entity types and never for your own base.
  local filters = {}
  for index, entity_type in ipairs(ENEMY_TYPES) do
    filters[index] = { filter = "type", type = entity_type }
    if index > 1 then filters[index].mode = "or" end
  end
  script.on_event(defines.events.on_entity_damaged, on_entity_damaged, filters)
  script.on_event(defines.events.on_force_created, function() Biters.refresh_hostiles() end)
  script.on_event(defines.events.on_forces_merged, function() Biters.refresh_hostiles() end)
end

Biters.UPDATE_PERIOD = UPDATE_PERIOD

return Biters
