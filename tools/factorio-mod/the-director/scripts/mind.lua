-- The loop that ties the Director's mind to the world: survey, decide, strike,
-- then find out what happened and learn from it.
--
-- The learning loop is the whole point. A strike that lands feeds it. A strike
-- your turrets eat sets it back and makes that door less attractive for a
-- while. Neither ever stops it.

local Director = require("scripts.director")
local Survey = require("scripts.survey")
local Strike = require("scripts.strike")

local Mind = {}

local RESOLVE_AFTER = 60 * 60 -- one minute for a strike to play out

local function opts()
  local scale = settings.global["dir-threat-scale"].value
  local o = {}
  for key, value in pairs(Director.defaults) do o[key] = value end
  -- The difficulty dial only touches how fast it grows and how much it fields,
  -- never how cleverly it chooses. You cannot dial away the intelligence.
  o.threat_passive = o.threat_passive * scale
  o.threat_fed = o.threat_fed * scale
  o.spawn_per_threat = o.spawn_per_threat * scale
  return o
end

local function quality_tiers()
  if storage.dir.quality_tiers then return storage.dir.quality_tiers end
  local tiers = {}
  local ok = pcall(function()
    local list = {}
    for name, proto in pairs(prototypes.quality) do
      if name ~= "quality-unknown" then
        list[#list + 1] = { name = name, level = proto.level or 0 }
      end
    end
    table.sort(list, function(a, b)
      if a.level == b.level then return a.name < b.name end
      return a.level < b.level
    end)
    for _, entry in ipairs(list) do tiers[#tiers + 1] = entry.name end
  end)
  if not ok or #tiers == 0 then tiers = { "normal" } end
  storage.dir.quality_tiers = tiers
  return tiers
end

local function intel_level(force)
  local level = 0
  for tier = 1, 3 do
    local tech = force.technologies["dir-intel-" .. tier]
    if tech and tech.researched then level = tier end
  end
  return level
end

function Mind.intel_level(force) return intel_level(force) end

local function announce(force, message, needed_level)
  if not settings.global["dir-notifications"].value then return end
  if intel_level(force) < needed_level then return end
  for _, player in pairs(game.players) do
    if player.valid and player.force == force then player.print(message) end
  end
end

-- Resolve a strike that has had time to play out.
local function resolve(surface, force, o)
  local record = storage.dir.pending
  if not record or game.tick < record.deadline then return end
  storage.dir.pending = nil

  local outcome = Strike.assess_outcome(surface, force, record)
  Director.feed(storage.dir.director, outcome, o)
  Director.remember(storage.dir.director, record.key, outcome == "landed", o)

  storage.dir.last_outcome = outcome
  if outcome == "landed" then
    announce(force, { "dir.it-landed" }, 1)
  else
    announce(force, { "dir.it-was-repelled" }, 1)
  end
end

function Mind.update()
  if not settings.global["dir-director"].value then return end

  local force = game.forces["player"]
  if not (force and force.valid) then return end
  local surface = game.surfaces["nauvis"]
  if not (surface and surface.valid) then return end

  local o = opts()
  resolve(surface, force, o)

  local period = settings.global["dir-assess-period"].value * 60
  if game.tick - (storage.dir.last_assess or -period) < period then return end
  storage.dir.last_assess = game.tick

  Director.feed(storage.dir.director, "passive", o)
  Director.decay(storage.dir.director, o)

  local survey = Survey.of(surface, force)
  storage.dir.survey = survey
  if not survey then return end          -- no base yet; nothing to study
  if storage.dir.pending then return end -- one strike at a time

  local decision = Director.assess(survey, o, storage.dir.director)
  storage.dir.decision = decision
  if not decision then return end

  -- Tier 2 intel means you are told what it is looking at before it moves.
  if decision.commit then
    local escalation = Director.escalation(storage.dir.director, o, quality_tiers())
    Strike.apply_damage_bonus(storage.dir.hostile or {}, escalation.damage_bonus)

    local record = Strike.launch(surface, decision.target, survey.centre,
      escalation, storage.dir.hostile or { enemy = true })
    if record then
      record.before = Strike.count_defenders(surface, force, record.position)
      record.deadline = game.tick + RESOLVE_AFTER
      storage.dir.pending = record
      storage.dir.last_strike = record

      announce(force, { "dir.incoming", record.size, tostring(record.quality) }, 1)
      announce(force, { "dir.incoming-where",
        string.format("%d, %d", record.position.x, record.position.y),
        { "dir.reason-" .. decision.reason } }, 2)
    end
  end
end

function Mind.init()
  storage.dir = storage.dir or {}
  storage.dir.director = storage.dir.director or Director.new_state()
  storage.dir.last_assess = storage.dir.last_assess or nil
end

Mind.quality_tiers = quality_tiers
Mind.opts = opts

return Mind
