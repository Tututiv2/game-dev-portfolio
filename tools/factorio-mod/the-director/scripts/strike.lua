-- Turning a decision into an actual attack.
--
-- It recruits whatever is already nearby FIRST and only spawns the shortfall,
-- so early strikes are mostly the map's own biters being pointed at you, and it
-- is late-game threat that starts conjuring reinforcements out of the dark.
--
-- Stat inflation is applied here, and all of it is real engine behaviour that
-- was probed before it was written:
--   * quality on the spawned unit  (legendary medium biter: 187.5 hp vs 75)
--   * unit.speed
--   * the enemy force's melee damage modifier
-- Resistance to your favourite damage type is applied elsewhere, in biters.lua.

local Strike = {}

local RECRUIT_RADIUS = 72
local STAGING_OFFSET = 40
local MAX_GROUP = 200

-- Ascending ladder. Anything missing from this game is skipped, so it degrades
-- rather than breaking when a mod removes or renames a tier.
local LADDER = {
  { name = "small-biter",     threat = 0.0 },
  { name = "small-spitter",   threat = 0.3 },
  { name = "medium-biter",    threat = 0.8 },
  { name = "medium-spitter",  threat = 1.1 },
  { name = "big-biter",       threat = 1.8 },
  { name = "big-spitter",     threat = 2.2 },
  { name = "behemoth-biter",  threat = 3.2 },
  { name = "behemoth-spitter", threat = 3.6 },
}

-- What it is allowed to field right now: everything unlocked by threat, so a
-- late strike is a mixed horde rather than a single uniform tier.
function Strike.roster(threat)
  local available = {}
  for _, entry in ipairs(LADDER) do
    if threat >= entry.threat and prototypes.entity[entry.name] then
      available[#available + 1] = entry.name
    end
  end
  if #available == 0 then
    available = { prototypes.entity["small-biter"] and "small-biter" or nil }
  end
  return available
end

local function staging_position(surface, target, centre)
  local dx, dy = target.position.x - centre.x, target.position.y - centre.y
  local length = math.max(1, math.sqrt(dx * dx + dy * dy))
  local wanted = {
    x = target.position.x + (dx / length) * STAGING_OFFSET,
    y = target.position.y + (dy / length) * STAGING_OFFSET,
  }
  return surface.find_non_colliding_position("small-biter", wanted, 64, 2) or wanted
end

-- Point the biters that are already out there at the chosen door.
local function recruit(surface, hostile_forces, staging)
  local found = {}
  for force_name in pairs(hostile_forces) do
    local units = surface.find_entities_filtered{
      force = force_name, type = "unit",
      position = staging, radius = RECRUIT_RADIUS,
    }
    for _, unit in ipairs(units) do found[#found + 1] = unit end
    if #found >= MAX_GROUP then break end
  end
  return found
end

local function spawn(surface, staging, roster, count, quality)
  local made = {}
  for index = 1, count do
    local name = roster[((index - 1) % #roster) + 1]
    local wanted = {
      x = staging.x + math.random(-12, 12),
      y = staging.y + math.random(-12, 12),
    }
    local position = surface.find_non_colliding_position(name, wanted, 24, 1)
    if position then
      local unit
      local ok = pcall(function()
        unit = surface.create_entity{
          name = name, position = position, force = "enemy", quality = quality,
        }
      end)
      -- Quality may not exist in this game; fall back rather than field nothing.
      if not ok or not unit then
        pcall(function()
          unit = surface.create_entity{ name = name, position = position, force = "enemy" }
        end)
      end
      if unit and unit.valid then made[#made + 1] = unit end
    end
  end
  return made
end

-- Returns a record of what was launched, or nil if it could not muster anything.
function Strike.launch(surface, target, centre, escalation, hostile_forces)
  local staging = staging_position(surface, target, centre)

  local units = recruit(surface, hostile_forces, staging)
  local shortfall = escalation.spawn_count - #units
  if shortfall > 0 then
    local roster = Strike.roster(escalation.threat)
    local spawned = spawn(surface, staging, roster, shortfall, escalation.quality)
    for _, unit in ipairs(spawned) do units[#units + 1] = unit end
  end

  if #units == 0 then return nil end
  if #units > MAX_GROUP then
    for index = #units, MAX_GROUP + 1, -1 do units[index] = nil end
  end

  -- Speed is a per-unit property, so it is applied to the actual attackers.
  if escalation.speed_bonus > 0 then
    for _, unit in ipairs(units) do
      if unit.valid then
        pcall(function() unit.speed = unit.speed * (1 + escalation.speed_bonus) end)
      end
    end
  end

  local group
  local ok = pcall(function()
    group = surface.create_unit_group{ position = staging, force = "enemy" }
    for _, unit in ipairs(units) do
      if unit.valid then group.add_member(unit) end
    end
    group.set_command{
      type = defines.command.attack_area,
      destination = target.position,
      radius = 32,
      distraction = defines.distraction.by_enemy,
    }
    group.start_moving()
  end)
  if not ok or not group or not group.valid then return nil end

  return {
    key = target.key,
    position = { x = target.position.x, y = target.position.y },
    size = #units,
    quality = escalation.quality,
    threat = escalation.threat,
  }
end

-- Global damage inflation. Applied to the enemy force rather than per unit,
-- because that is the only lever the engine actually offers for it.
function Strike.apply_damage_bonus(hostile_forces, bonus)
  for force_name in pairs(hostile_forces) do
    local force = game.forces[force_name]
    if force and force.valid then
      for _, category in ipairs({ "melee", "biological" }) do
        pcall(function() force.set_ammo_damage_modifier(category, bonus) end)
      end
    end
  end
end

-- How the Director learns whether a door is worth using again: did you lose
-- anything, or did the attack simply die on your turrets?
function Strike.assess_outcome(surface, force, record)
  local after = surface.count_entities_filtered{
    force = force, position = record.position, radius = 64,
  }
  if record.before == nil then return "repelled" end
  return (after < record.before) and "landed" or "repelled"
end

function Strike.count_defenders(surface, force, position)
  return surface.count_entities_filtered{ force = force, position = position, radius = 64 }
end

return Strike
