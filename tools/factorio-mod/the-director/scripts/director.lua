-- The Director's mind. Contains NO Factorio API: no game, no storage, no
-- surfaces. scripts/survey.lua turns the world into the plain table this reads,
-- and scripts/strike.lua turns the decision back into orders. That split is
-- what lets the whole decision process be unit-tested with fabricated bases.
--
-- The rule the entire mod obeys: IT NEVER STOPS. There is no plateau and no
-- win condition. You can hurt it, push it back, even hunt down and kill what it
-- is using as a body - and it returns, stronger and different. You win battles,
-- never the war. If you wanted it to stop you would be playing vanilla.
--
-- It DOES spawn extra biters and it DOES inflate their stats. Escalation comes
-- from four real levers, all verified against the engine:
--   * quality tiers on units - a legendary medium biter has 187.5 hp against a
--     normal one's 75, and the badge is visible on the thing walking at you
--   * enemy force damage modifiers
--   * unit speed
--   * fielding tiers the evolution factor would never allow yet
--
-- Ignoring it is the mistake. Every strike that lands feeds it, so a player who
-- never fights back is the one who escalates fastest.
--
-- What it is looking for:
--   * softness  - value sitting behind an approach, divided by what defends it
--   * weakness  - a moment when your power is sagging, or night if you run solar
--   * memory    - approaches that ate its last attack look less attractive for
--                 a while, so fortifying a hole genuinely redirects it

local Director = {}

Director.defaults = {
  defense_weight = 1.0,   -- how strongly a turret discourages it
  min_value       = 1,    -- never attack an approach with nothing behind it
  base_urgency    = 0.25, -- it does not wait forever for a perfect moment
  power_weight    = 0.50, -- how much a power sag emboldens it
  night_weight    = 0.40, -- how much darkness emboldens it, if you run on solar
  patience        = 0.60, -- urgency it needs before committing to a strike
  failure_penalty = 0.50, -- appeal multiplier applied when a strike dies
  memory          = 0.98, -- how fast a penalty fades back toward neutral

  -- Escalation. Threat is the Director's power, and it only ever trends up.
  threat_passive  = 0.004, -- gained per assessment tick, just for existing
  threat_fed      = 0.35,  -- gained when a strike lands. Ignoring it costs most
  threat_repelled = 0.12,  -- lost when you kill a strike before it does damage
  threat_wounded  = 0.60,  -- lost when you kill its body on another planet
  spawn_base      = 4,     -- extra units at threat 0
  spawn_per_threat = 6,    -- extra units per point of threat
  spawn_cap       = 120,   -- never field more than this in one strike
  damage_per_threat = 0.25,-- enemy melee damage bonus per point of threat
  speed_per_threat  = 0.12,-- unit speed bonus per point of threat
  quality_per_threat = 0.8,-- threat needed per step up the quality ladder
}

local function clamp(v, lo, hi)
  if v < lo then return lo end
  if v > hi then return hi end
  return v
end

function Director.new_state()
  return {
    bias = {},        -- approach key -> appeal multiplier, 1 is neutral
    last_target = nil,
    strikes = 0,
    threat = 0,       -- its power. Trends up forever.
    landed = 0,       -- strikes that did damage
    repelled = 0,     -- strikes you killed first
  }
end

-- ------------------------------------------------------------- escalation --

-- Everything the Director is allowed to field at its current power. The ladder
-- is deliberately readable: the player should be able to look at what walked
-- out of the fog and know roughly how badly they have been ignoring it.
--
-- quality_tiers is the list available in THIS game, ascending, so a save with
-- the quality mod stack reaches further up than a vanilla one.
function Director.escalation(state, opts, quality_tiers)
  opts = opts or Director.defaults
  local threat = math.max(0, state.threat or 0)

  local count = math.floor(opts.spawn_base + threat * opts.spawn_per_threat)
  count = math.min(count, opts.spawn_cap)

  local tiers = quality_tiers or { "normal" }
  local step = math.floor(threat / (opts.quality_per_threat or 0.8)) + 1
  local quality = tiers[math.min(step, #tiers)] or tiers[#tiers] or "normal"

  return {
    threat       = threat,
    spawn_count  = count,
    quality      = quality,
    quality_step = math.min(step, #tiers),
    damage_bonus = threat * opts.damage_per_threat,
    speed_bonus  = threat * opts.speed_per_threat,
  }
end

-- Threat only ever trends up. Repelling a strike buys time; it does not win.
function Director.feed(state, outcome, opts)
  opts = opts or Director.defaults
  state.threat = state.threat or 0

  if outcome == "landed" then
    state.threat = state.threat + opts.threat_fed
    state.landed = (state.landed or 0) + 1
  elseif outcome == "repelled" then
    state.threat = state.threat - opts.threat_repelled
    state.repelled = (state.repelled or 0) + 1
  elseif outcome == "wounded" then
    -- You found its body and killed it. The biggest setback available, and
    -- still not a win: threat floors at zero and starts climbing again.
    state.threat = state.threat - opts.threat_wounded
  elseif outcome == "passive" then
    state.threat = state.threat + opts.threat_passive
  end

  if state.threat < 0 then state.threat = 0 end
  return state.threat
end

-- How inviting an approach looks. Zero means "do not bother".
function Director.softness(approach, opts, state)
  if not approach then return 0 end
  local value = approach.value or 0
  if value < (opts.min_value or 1) then return 0 end

  local defense = approach.defense or 0
  local bias = 1
  if state and state.bias and approach.key and state.bias[approach.key] then
    bias = state.bias[approach.key]
  end

  return (value / (1 + defense * opts.defense_weight)) * bias
end

-- How bold it is feeling right now, 0..1.
function Director.urgency(survey, opts)
  local power = (survey.power_strain or 0) * opts.power_weight
  -- Darkness only matters to something that knows you depend on the sun.
  local night = (survey.darkness or 0) * (survey.solar_dependent or 0) * opts.night_weight
  return clamp((opts.base_urgency or 0) + power + night, 0, 1), power, night
end

-- Returns nil when there is genuinely nothing worth attacking. It must never
-- invent a target just to stay busy.
function Director.assess(survey, opts, state)
  opts = opts or Director.defaults

  local best, best_score
  for _, approach in ipairs(survey.approaches or {}) do
    local score = Director.softness(approach, opts, state)
    if score > 0 and (best_score == nil or score > best_score) then
      best, best_score = approach, score
    end
  end
  if not best then return nil end

  local urgency, power, night = Director.urgency(survey, opts)

  -- Name the dominant reason, so the player can be told what it noticed.
  local reason = "opportunity"
  if (best.defense or 0) == 0 then reason = "undefended" end
  if power >= night and power > 0.05 then reason = "power" end
  if night > power and night > 0.05 then reason = "night" end

  return {
    target  = best,
    score   = best_score,
    urgency = urgency,
    reason  = reason,
    commit  = urgency >= (opts.patience or 0.6),
  }
end

-- Called after a strike resolves. A failed attack makes that approach less
-- attractive for a while, which is what makes fortifying a hole actually work.
function Director.remember(state, key, success, opts)
  opts = opts or Director.defaults
  if not key then return end
  state.bias = state.bias or {}
  local current = state.bias[key] or 1
  if success then
    -- It worked; no reason to avoid this door.
    state.bias[key] = math.min(1, current / (opts.failure_penalty or 0.5))
  else
    state.bias[key] = current * (opts.failure_penalty or 0.5)
  end
  state.strikes = (state.strikes or 0) + 1
end

-- Penalties fade, so a door you fortified once is probed again eventually.
-- Without this it would permanently write off every approach it ever lost at.
function Director.decay(state, opts)
  opts = opts or Director.defaults
  if not state.bias then return end
  for key, value in pairs(state.bias) do
    local moved = value + (1 - value) * (1 - (opts.memory or 0.98))
    if math.abs(moved - 1) < 1e-3 then
      state.bias[key] = nil
    else
      state.bias[key] = moved
    end
  end
end

return Director
