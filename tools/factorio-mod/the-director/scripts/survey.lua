-- Turns your factory into the plain table scripts/director.lua reasons over.
-- All the Factorio API contact for reading the world lives here.
--
-- Cost matters: this runs on a timer forever, on bases that get enormous. So it
-- never walks every machine. It builds a skeleton from electric poles (a base
-- has thousands of machines but far fewer poles), takes a ring of approaches
-- around that, and asks two bounded count queries per approach.

local Survey = {}

local APPROACHES = 12      -- points probed around the base perimeter
local POLE_LIMIT = 2000    -- skeleton sample cap
local MACHINE_SAMPLE = 300 -- power-strain sample cap
local DEFENSE_RADIUS = 24  -- how far from an approach a turret still counts
local VALUE_DEPTH = 48     -- how far inward "behind this approach" reaches

local TURRETS = { "ammo-turret", "electric-turret", "fluid-turret", "artillery-turret" }
local MACHINES = { "assembling-machine", "furnace", "mining-drill", "lab" }
local GENERATORS = { "generator", "burner-generator", "reactor", "fusion-generator" }

-- Stable across base growth: keying by chunk means the Director's memory of
-- "I died at this door" survives you extending the wall by ten tiles.
local function key_of(position)
  return string.format("%d:%d", math.floor(position.x / 32), math.floor(position.y / 32))
end

local function skeleton(surface, force)
  local poles = surface.find_entities_filtered{
    force = force, type = "electric-pole", limit = POLE_LIMIT,
  }
  if #poles == 0 then return nil end

  local sum_x, sum_y = 0, 0
  for _, pole in ipairs(poles) do
    sum_x = sum_x + pole.position.x
    sum_y = sum_y + pole.position.y
  end
  local centre = { x = sum_x / #poles, y = sum_y / #poles }

  local radius = 0
  for _, pole in ipairs(poles) do
    local dx, dy = pole.position.x - centre.x, pole.position.y - centre.y
    local distance = math.sqrt(dx * dx + dy * dy)
    if distance > radius then radius = distance end
  end

  return centre, math.max(radius, 32), #poles
end

local function defense_at(surface, force, position)
  local score = 0
  for _, turret_type in ipairs(TURRETS) do
    score = score + surface.count_entities_filtered{
      force = force, type = turret_type,
      position = position, radius = DEFENSE_RADIUS,
    }
  end
  -- Walls matter, but far less than something that shoots back.
  local walls = surface.count_entities_filtered{
    force = force, type = "wall", position = position, radius = DEFENSE_RADIUS,
  }
  return score + walls * 0.05
end

-- Everything worth breaking that sits between this approach and the middle.
local function value_behind(surface, force, position, centre)
  local dx, dy = centre.x - position.x, centre.y - position.y
  local length = math.max(1, math.sqrt(dx * dx + dy * dy))
  local inward = {
    x = position.x + (dx / length) * VALUE_DEPTH,
    y = position.y + (dy / length) * VALUE_DEPTH,
  }
  return surface.count_entities_filtered{
    force = force, position = inward, radius = VALUE_DEPTH,
  }
end

local function power_strain(surface, force)
  local machines = surface.find_entities_filtered{
    force = force, type = MACHINES, limit = MACHINE_SAMPLE,
  }
  if #machines == 0 then return 0 end
  local starved = 0
  for _, machine in ipairs(machines) do
    local status = machine.status
    if status == defines.entity_status.no_power or status == defines.entity_status.low_power then
      starved = starved + 1
    end
  end
  return starved / #machines
end

-- How much of your grid is the sun? Only something that knows this has any
-- reason to care that it is night.
local function solar_dependence(surface, force)
  local solar = surface.count_entities_filtered{ force = force, type = "solar-panel" }
  local other = 0
  for _, generator_type in ipairs(GENERATORS) do
    other = other + surface.count_entities_filtered{ force = force, type = generator_type }
  end
  local total = solar + other
  if total == 0 then return 0 end
  return solar / total
end

-- Returns nil when there is no base worth studying yet.
function Survey.of(surface, force)
  local centre, radius, poles = skeleton(surface, force)
  if not centre then return nil end

  local approaches = {}
  local ring = radius + 24
  for index = 1, APPROACHES do
    local angle = (index - 1) * (2 * math.pi / APPROACHES)
    local position = {
      x = centre.x + math.cos(angle) * ring,
      y = centre.y + math.sin(angle) * ring,
    }
    approaches[#approaches + 1] = {
      key = key_of(position),
      position = position,
      defense = defense_at(surface, force, position),
      value = value_behind(surface, force, position, centre),
    }
  end

  return {
    centre = centre,
    radius = radius,
    poles = poles,
    approaches = approaches,
    power_strain = power_strain(surface, force),
    darkness = surface.darkness,
    solar_dependent = solar_dependence(surface, force),
  }
end

Survey.key_of = key_of

return Survey
