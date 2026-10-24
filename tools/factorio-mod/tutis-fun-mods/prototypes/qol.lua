-- QoL prototype tweaks. Runs in data-final-fixes so it lands on top of whatever
-- every other mod did, rather than being silently overwritten by them.
--
-- The conflict rule: if the mod a tweak replaces is still enabled, the tweak
-- stands down. Otherwise enabling this mod before disabling the old ones would
-- multiply the effect twice - 100x stack sizes instead of 10x. Set "Apply even
-- if the original mod is installed" to override.

local util = require("util")

local Qol = {}

local function enabled(name)
  return settings.startup[name] and settings.startup[name].value
end

local override = enabled("tfm-qol-override")

-- Returns true if the tweak should run.
local function should_apply(setting, conflicting_mods)
  if not enabled(setting) then return false end
  if override then return true end
  for _, mod_name in ipairs(conflicting_mods) do
    if mods[mod_name] then
      log(string.format("[TFM QoL] %s stood down: %s is enabled", setting, mod_name))
      return false
    end
  end
  return true
end

-- --------------------------------------------------------------- belts -----

local BELT_TYPES = { "transport-belt", "underground-belt", "splitter", "loader", "loader-1x1", "linked-belt" }

function Qol.belts()
  if not should_apply("tfm-qol-belts", { "more-belts", "deadlock-beltboxes-loaders" }) then return 0 end
  local multiplier = settings.startup["tfm-qol-belt-speed"].value
  local count = 0
  for _, belt_type in ipairs(BELT_TYPES) do
    for _, proto in pairs(data.raw[belt_type] or {}) do
      if proto.speed then
        proto.speed = proto.speed * multiplier
        count = count + 1
      end
    end
  end
  log(string.format("[TFM QoL] belts: %d prototypes x%.2f", count, multiplier))
  return count
end

-- --------------------------------------------------------------- power -----

function Qol.solar()
  if not should_apply("tfm-qol-solar", { "Advanced-Electric-Revamped-v16" }) then return 0 end
  local solar_mult = settings.startup["tfm-qol-solar-mult"].value
  local accu_mult = settings.startup["tfm-qol-accumulator-mult"].value
  local count = 0

  for _, proto in pairs(data.raw["solar-panel"] or {}) do
    if proto.production then
      local watts = util.parse_energy(proto.production)
      proto.production = tostring(watts * solar_mult) .. "W"
      count = count + 1
    end
  end

  for _, proto in pairs(data.raw["accumulator"] or {}) do
    local source = proto.energy_source
    if source and source.buffer_capacity then
      local joules = util.parse_energy(source.buffer_capacity)
      source.buffer_capacity = tostring(joules * accu_mult) .. "J"
      -- Flow limits have to scale too, or a bigger battery still trickles.
      if source.input_flow_limit then
        source.input_flow_limit = tostring(util.parse_energy(source.input_flow_limit) * accu_mult) .. "W"
      end
      if source.output_flow_limit then
        source.output_flow_limit = tostring(util.parse_energy(source.output_flow_limit) * accu_mult) .. "W"
      end
      count = count + 1
    end
  end

  log(string.format("[TFM QoL] power: %d prototypes (solar x%.2f, accumulator x%.2f)",
    count, solar_mult, accu_mult))
  return count
end

-- -------------------------------------------------------------- stacks -----

-- Items that are deliberately unique (armour, blueprints, one-off tools) sit at
-- stack_size 1. Multiplying those would turn single items into stacks and is
-- never what anyone means by "bigger stacks", so they are left alone.
local MAX_STACK = 65535

function Qol.stacks()
  if not should_apply("tfm-qol-stacks", { "BigBags" }) then return 0 end
  local multiplier = settings.startup["tfm-qol-stack-mult"].value
  local count = 0

  for _, bucket in pairs(data.raw) do
    for _, proto in pairs(bucket) do
      if type(proto) == "table" and proto.stack_size and type(proto.stack_size) == "number"
         and proto.stack_size > 1 then
        proto.stack_size = math.min(MAX_STACK, math.floor(proto.stack_size * multiplier))
        count = count + 1
      end
    end
  end

  log(string.format("[TFM QoL] stacks: %d items x%d", count, multiplier))
  return count
end

-- ------------------------------------------------------------ pavement -----

-- Values taken from FasterPavements so switching it off changes nothing.
local PAVEMENT = {
  ["stone-path"] = 1.8,
  ["concrete"] = 2.1,
  ["hazard-concrete-left"] = 2.1,
  ["hazard-concrete-right"] = 2.1,
  ["refined-concrete"] = 2.4,
  ["refined-hazard-concrete-left"] = 2.4,
  ["refined-hazard-concrete-right"] = 2.4,
}

local REFINED_COLOURS = { "acid", "black", "blue", "brown", "cyan", "green",
                          "orange", "pink", "purple", "red", "yellow" }

function Qol.pavement()
  if not should_apply("tfm-qol-pavement", { "FasterPavements" }) then return 0 end

  local targets = {}
  for name, speed in pairs(PAVEMENT) do targets[name] = speed end
  for _, colour in ipairs(REFINED_COLOURS) do
    targets[colour .. "-refined-concrete"] = 2.4
  end

  local count = 0
  for name, speed in pairs(targets) do
    local tile = data.raw["tile"][name]
    if tile then
      tile.walking_speed_modifier = speed
      count = count + 1
    end
  end

  log(string.format("[TFM QoL] pavement: %d tiles", count))
  return count
end

function Qol.apply_all()
  return {
    belts = Qol.belts(),
    power = Qol.solar(),
    stacks = Qol.stacks(),
    pavement = Qol.pavement(),
  }
end

return Qol
