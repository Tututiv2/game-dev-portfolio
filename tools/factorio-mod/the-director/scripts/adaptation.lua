-- The adaptation model. Deliberately contains NO Factorio API calls: no game,
-- no storage, no settings, no prototypes. That is what lets scripts/selftest.lua
-- drive the real algorithm with fabricated numbers and assert on the result.
--
-- The model in one paragraph:
--   Every point of damage you land on the swarm is remembered against its damage
--   type. That memory decays every update, so the profile describes what you have
--   been doing lately rather than what you did once. Resistance is driven by a
--   type's SHARE of your damage, not its absolute amount - which is the whole
--   design. Concentrate on one weapon and its share approaches 1 and they harden
--   against it. Spread across enough types that none exceeds the threshold and
--   they never adapt to anything. The swarm is not punishing you for fighting;
--   it is punishing you for being predictable.

local Adaptation = {}

Adaptation.defaults = {
  cap        = 0.60,   -- ceiling on resistance for any single damage type
  threshold  = 0.15,   -- damage share below which a type earns no resistance
  rate       = 0.04,   -- fraction of the way to target moved per update
  memory     = 0.985,  -- exposure retained per update
  bias       = 0.25,   -- weight of industry composition versus actual damage
  pressure   = 1.0,    -- global multiplier, used for the evolution gate
}

local EPSILON = 1e-4

local function clamp(v, lo, hi)
  if v < lo then return lo end
  if v > hi then return hi end
  return v
end

function Adaptation.new_state()
  return {
    exposure = {}, -- damage type -> decaying record of damage you have dealt
    resist   = {}, -- damage type -> current resistance fraction, 0..cap
    share    = {}, -- damage type -> last computed share, kept for the GUI
  }
end

-- Called for every point of damage landed on the swarm.
function Adaptation.observe(state, damage_type, amount)
  if not damage_type or not amount or amount <= 0 then return end
  state.exposure[damage_type] = (state.exposure[damage_type] or 0) + amount
end

-- opts follows the shape of Adaptation.defaults.
-- bias is an optional table of damage type -> 0..1 describing what your factory
-- is mostly built out of, which nudges the swarm before you fire a shot.
function Adaptation.update(state, opts, bias)
  opts = opts or Adaptation.defaults

  -- Decay first, so "share" always describes recent behaviour.
  local total = 0
  for dt, value in pairs(state.exposure) do
    local decayed = value * opts.memory
    if decayed < EPSILON then decayed = 0 end
    state.exposure[dt] = decayed
    total = total + decayed
  end

  -- Resistance can be driven by damage, by industry, or both, so walk the union
  -- of the two key sets rather than just the damage the player has dealt.
  local keys = {}
  for dt in pairs(state.exposure) do keys[dt] = true end
  for dt in pairs(state.resist) do keys[dt] = true end
  if bias then
    for dt in pairs(bias) do keys[dt] = true end
  end

  for dt in pairs(keys) do
    local share = 0
    if total > 0 then
      share = (state.exposure[dt] or 0) / total
    end
    if bias and bias[dt] then
      share = share + bias[dt] * opts.bias
    end
    state.share[dt] = share

    -- Everything below the threshold reads as zero, and the remaining band is
    -- rescaled to 0..1 so a type sitting just over the line is barely resisted
    -- rather than jumping straight to a large value.
    local headroom = 1 - opts.threshold
    local over = headroom > 0 and ((share - opts.threshold) / headroom) or 0
    local target = opts.cap * clamp(over, 0, 1) * (opts.pressure or 1)

    local current = state.resist[dt] or 0
    local moved = current + (target - current) * opts.rate
    if moved < EPSILON then moved = 0 end
    state.resist[dt] = clamp(moved, 0, opts.cap)
  end
end

function Adaptation.resistance(state, damage_type)
  return state.resist[damage_type] or 0
end

-- Highest resistance currently held, for the "are they adapted at all" check.
function Adaptation.peak(state)
  local best_type, best_value = nil, 0
  for dt, value in pairs(state.resist) do
    if value > best_value then
      best_type, best_value = dt, value
    end
  end
  return best_type, best_value
end

return Adaptation
