-- Unit tests for the adaptation model. There is no standalone Lua on this
-- machine, so these run inside Factorio (/dir-selftest, or automatically on a
-- headless load) and report through log(), which tools/test.ps1 then scans.
--
-- These assert the DESIGN, not just the code: if "spread your damage and they
-- never adapt" ever stops being true, check 2 fails.

local Adaptation = require("scripts.adaptation")
local Director = require("scripts.director")
local Survey = require("scripts.survey")
local Strike = require("scripts.strike")

local Selftest = {}

local ALL_TYPES = { "physical", "impact", "poison", "explosion", "fire", "laser", "acid", "electric" }

local function opts(overrides)
  local o = {}
  for k, v in pairs(Adaptation.defaults) do o[k] = v end
  for k, v in pairs(overrides or {}) do o[k] = v end
  return o
end

local function run(state, o, bias, updates)
  for _ = 1, updates do Adaptation.update(state, o, bias) end
end

-- Sustained fire: re-observe every update so the memory decay is being fed,
-- which is what actually happens in a fight.
local function sustain(state, o, per_type, updates, bias)
  for _ = 1, updates do
    for dt, amount in pairs(per_type) do Adaptation.observe(state, dt, amount) end
    Adaptation.update(state, o, bias)
  end
end

local checks = {}

local function check(name, condition, detail)
  checks[#checks + 1] = { name = name, ok = condition and true or false, detail = detail or "" }
end

function Selftest.run()
  checks = {}
  local o = opts()

  -- 1. A swarm nobody has shot at resists nothing.
  do
    local s = Adaptation.new_state()
    run(s, o, nil, 100)
    local _, peak = Adaptation.peak(s)
    check("untouched swarm stays at zero", peak == 0, string.format("peak=%.4f", peak))
  end

  -- 2. THE LOAD-BEARING ONE. Eight damage types used evenly is a 12.5% share
  --    each, under the 15% threshold, so nothing adapts however long you keep
  --    it up. This is the mechanic's promise to the player.
  do
    local s = Adaptation.new_state()
    local spread = {}
    for _, dt in ipairs(ALL_TYPES) do spread[dt] = 100 end
    sustain(s, o, spread, 600)
    local _, peak = Adaptation.peak(s)
    check("evenly spread damage never adapts", peak == 0,
      string.format("peak=%.4f after 600 updates", peak))
  end

  -- 3. One weapon forever climbs toward the cap but never past it.
  do
    local s = Adaptation.new_state()
    sustain(s, o, { fire = 100 }, 600)
    local r = Adaptation.resistance(s, "fire")
    check("single damage type approaches the cap", r > o.cap * 0.9 and r <= o.cap,
      string.format("fire=%.4f cap=%.2f", r, o.cap))
    check("resistance never exceeds the cap", r <= o.cap, string.format("fire=%.4f", r))
  end

  -- 4. Switching weapons works. Without this the mechanic is a wall instead of
  --    a rotation.
  do
    local s = Adaptation.new_state()
    sustain(s, o, { fire = 100 }, 600)
    local fire_before = Adaptation.resistance(s, "fire")
    sustain(s, o, { electric = 100 }, 600)
    local fire_after = Adaptation.resistance(s, "fire")
    local electric_after = Adaptation.resistance(s, "electric")
    check("old resistance decays after switching", fire_after < fire_before * 0.25,
      string.format("fire %.4f -> %.4f", fire_before, fire_after))
    check("new resistance grows after switching", electric_after > o.cap * 0.9,
      string.format("electric=%.4f", electric_after))
  end

  -- 5. Two types at 50/50 both clear the threshold, so splitting between only
  --    two weapons is not enough to hide.
  do
    local s = Adaptation.new_state()
    sustain(s, o, { fire = 100, physical = 100 }, 600)
    local a = Adaptation.resistance(s, "fire")
    local b = Adaptation.resistance(s, "physical")
    check("a 50/50 split still adapts to both", a > 0.1 and b > 0.1,
      string.format("fire=%.4f physical=%.4f", a, b))
    check("a 50/50 split adapts less than a single type", a < o.cap * 0.9,
      string.format("fire=%.4f vs cap %.2f", a, o.cap))
  end

  -- 6. The evolution gate scales the whole profile down.
  do
    local s = Adaptation.new_state()
    sustain(s, opts({ pressure = 0.25 }), { fire = 100 }, 600)
    local r = Adaptation.resistance(s, "fire")
    check("evolution gate scales resistance", r > 0 and r < o.cap * 0.4,
      string.format("fire=%.4f at pressure 0.25", r))
  end

  -- 7. Industry bias alone can move the swarm before a shot is fired.
  do
    local s = Adaptation.new_state()
    run(s, o, { acid = 1.0 }, 600)
    check("industry bias adapts without any damage", Adaptation.resistance(s, "acid") > 0,
      string.format("acid=%.4f", Adaptation.resistance(s, "acid")))
  end

  -- 8. Bias weight of zero means industry is genuinely ignored.
  do
    local s = Adaptation.new_state()
    run(s, opts({ bias = 0 }), { acid = 1.0 }, 600)
    check("industry influence of 0 disables the bias", Adaptation.resistance(s, "acid") == 0,
      string.format("acid=%.4f", Adaptation.resistance(s, "acid")))
  end

  -- 9. Threshold of 0 means everything you touch adapts, however little.
  do
    local s = Adaptation.new_state()
    local spread = {}
    for _, dt in ipairs(ALL_TYPES) do spread[dt] = 100 end
    sustain(s, opts({ threshold = 0 }), spread, 600)
    local _, peak = Adaptation.peak(s)
    check("threshold 0 adapts to everything", peak > 0, string.format("peak=%.4f", peak))
  end

  -- ------------------------------------------------------------- Director --
  -- These assert the DESIGN of the mind, not just the code. If "fortify the
  -- hole and it goes somewhere else" ever stops being true, check D3 fails.

  local D = Director.defaults
  local function approach(key, value, defense)
    return { key = key, value = value, defense = defense, position = { 0, 0 } }
  end

  -- D1. Softness beats value: it goes for the open door, not the big prize.
  do
    local survey = { approaches = {
      approach("rich-but-walled", 500, 20),
      approach("poor-but-open", 60, 0),
    } }
    local d = Director.assess(survey, D, Director.new_state())
    check("picks the soft approach over the rich one", d and d.target.key == "poor-but-open",
      d and d.target.key or "nil")
  end

  -- D2. With defence equal, it takes the bigger prize.
  do
    local survey = { approaches = { approach("small", 50, 2), approach("big", 400, 2) } }
    local d = Director.assess(survey, D, Director.new_state())
    check("picks the richer approach when defence is equal", d and d.target.key == "big",
      d and d.target.key or "nil")
  end

  -- D3. THE LOAD-BEARING ONE. Fortifying the approach it wants must actually
  --     send it somewhere else, or defending accomplishes nothing.
  do
    local state = Director.new_state()
    local before = Director.assess({ approaches = {
      approach("east", 300, 0), approach("west", 200, 1) } }, D, state)
    local after = Director.assess({ approaches = {
      approach("east", 300, 40), approach("west", 200, 1) } }, D, state)
    check("fortifying its target redirects it", before.target.key == "east" and after.target.key == "west",
      string.format("%s -> %s", before.target.key, after.target.key))
  end

  -- D4. It must never invent a target.
  do
    check("no approaches means no attack", Director.assess({ approaches = {} }, D, Director.new_state()) == nil, "")
    local empty = { approaches = { approach("nothing", 0, 0) } }
    check("an approach with nothing behind it is ignored",
      Director.assess(empty, D, Director.new_state()) == nil, "")
  end

  -- D5. It gets bolder as your power sags.
  do
    local base = { approaches = { approach("a", 100, 1) }, power_strain = 0 }
    local sag  = { approaches = { approach("a", 100, 1) }, power_strain = 1 }
    local u1 = Director.urgency(base, D)
    local u2 = Director.urgency(sag, D)
    check("a power sag emboldens it", u2 > u1, string.format("%.3f -> %.3f", u1, u2))
  end

  -- D6. Darkness only matters to something that knows you run on solar.
  do
    local steam = { approaches = {}, darkness = 1, solar_dependent = 0 }
    local solar = { approaches = {}, darkness = 1, solar_dependent = 1 }
    check("night only matters if you depend on solar",
      Director.urgency(steam, D) < Director.urgency(solar, D),
      string.format("steam=%.3f solar=%.3f", Director.urgency(steam, D), Director.urgency(solar, D)))
  end

  -- D7. It learns from losing. Repeatedly dying at one door makes it try another.
  do
    local state = Director.new_state()
    local survey = { approaches = { approach("east", 300, 0), approach("west", 200, 0) } }
    local first = Director.assess(survey, D, state).target.key
    for _ = 1, 4 do Director.remember(state, first, false, D) end
    local second = Director.assess(survey, D, state).target.key
    check("losing at a door makes it try another", second ~= first,
      string.format("%s -> %s", first, second))
  end

  -- D8. But it never writes a door off forever.
  do
    local state = Director.new_state()
    Director.remember(state, "east", false, D)
    local penalised = state.bias["east"]
    for _ = 1, 400 do Director.decay(state, D) end
    local recovered = state.bias["east"]
    check("a penalised approach is eventually probed again",
      penalised < 1 and (recovered == nil or recovered > penalised),
      string.format("%.3f -> %s", penalised, tostring(recovered)))
  end

  -- D9. Escalation must be monotonic, or the ladder is meaningless.
  do
    local tiers = { "normal", "uncommon", "rare", "epic", "legendary" }
    local low = Director.escalation({ threat = 0 }, D, tiers)
    local high = Director.escalation({ threat = 5 }, D, tiers)
    check("escalation grows with threat",
      high.spawn_count > low.spawn_count and high.damage_bonus > low.damage_bonus
        and high.quality_step > low.quality_step,
      string.format("count %d->%d, quality %s->%s", low.spawn_count, high.spawn_count,
        low.quality, high.quality))
    check("spawn count is capped", Director.escalation({ threat = 1e6 }, D, tiers).spawn_count == D.spawn_cap,
      tostring(Director.escalation({ threat = 1e6 }, D, tiers).spawn_count))
    check("quality clamps to what this game has",
      Director.escalation({ threat = 1e6 }, D, tiers).quality == "legendary", "")
  end

  -- D10. Ignoring it is the mistake: a landed strike feeds it far more than
  --      time alone does.
  do
    local ignored = Director.new_state()
    Director.feed(ignored, "landed", D)
    local patient = Director.new_state()
    for _ = 1, 10 do Director.feed(patient, "passive", D) end
    check("ignoring it escalates faster than waiting", ignored.threat > patient.threat,
      string.format("landed=%.3f vs 10 passive=%.3f", ignored.threat, patient.threat))
  end

  -- D11. You can push it back, but never below nothing, and never out.
  do
    local state = Director.new_state()
    Director.feed(state, "landed", D)
    local fed = state.threat
    Director.feed(state, "repelled", D)
    check("repelling a strike sets it back", state.threat < fed,
      string.format("%.3f -> %.3f", fed, state.threat))

    local hunted = Director.new_state()
    for _ = 1, 50 do Director.feed(hunted, "wounded", D) end
    check("killing its body never takes threat below zero", hunted.threat == 0,
      string.format("threat=%.3f", hunted.threat))

    Director.feed(hunted, "passive", D)
    check("it always starts climbing again", hunted.threat > 0,
      string.format("threat=%.4f", hunted.threat))
  end

  -- ---------------------------------------------------- survey and strike --
  -- Everything above is fabricated tables. This builds an ACTUAL base out of
  -- real entities, surveys it with the real code, and checks the Director looks
  -- at the side that was left open. Then it cleans every entity back up, so
  -- running on a real save leaves nothing behind.
  do
    local surface = game.surfaces["nauvis"]
    local force = game.forces["player"]
    local origin = { x = 3000, y = 3000 } -- far from anything a player owns
    local built = {}

    local function place(name, x, y)
      local position = surface.find_non_colliding_position(name, { origin.x + x, origin.y + y }, 16, 1)
      if not position then return end
      local ok, entity = pcall(function()
        return surface.create_entity{ name = name, position = position, force = force }
      end)
      if ok and entity and entity.valid then built[#built + 1] = entity end
    end

    -- Nothing can be placed on ungenerated chunks, and this spot is far from
    -- the starting area precisely so it cannot disturb a real save.
    pcall(function()
      surface.request_to_generate_chunks(origin, 6)
      surface.force_generate_chunk_requests()
    end)

    local ok_build = pcall(function()
      -- A square of poles gives the survey its skeleton.
      for x = -80, 80, 20 do
        for y = -80, 80, 20 do place("medium-electric-pole", x, y) end
      end
      -- Fortify the east side only. West is deliberately wide open.
      for y = -60, 60, 12 do
        place("gun-turret", 96, y)
        place("gun-turret", 104, y)
      end
      -- Something worth attacking in the middle.
      for x = -40, 40, 16 do
        for y = -40, 40, 16 do place("stone-furnace", x, y) end
      end
    end)

    if not ok_build or #built < 20 then
      check("test base could be built", false, "#built=" .. #built)
    else
      local survey = Survey.of(surface, force)
      check("survey finds the base", survey ~= nil and #survey.approaches > 0,
        survey and (#survey.approaches .. " approaches, " .. survey.poles .. " poles") or "nil")

      if survey then
        -- East is the fortified side (+x). Find the most and least defended.
        local most, least
        for _, a in ipairs(survey.approaches) do
          if not most or a.defense > most.defense then most = a end
          if not least or a.defense < least.defense then least = a end
        end
        check("survey sees the fortified side", most.defense > least.defense,
          string.format("max defense=%.1f min=%.1f", most.defense, least.defense))
        check("the fortified side is the east one", most.position.x > survey.centre.x,
          string.format("most-defended x=%.0f vs centre x=%.0f", most.position.x, survey.centre.x))

        local decision = Director.assess(survey, Director.defaults, Director.new_state())
        check("the Director attacks a real base's soft side",
          decision ~= nil and decision.target.defense <= least.defense + 0.001,
          decision and string.format("chose defense=%.1f (softest=%.1f)",
            decision.target.defense, least.defense) or "no decision")

        -- The roster must actually grow as it gets stronger.
        local weak = Strike.roster(0)
        local strong = Strike.roster(5)
        check("the strike roster widens with threat", #strong > #weak,
          string.format("%d unit types -> %d", #weak, #strong))
      end
    end

    for _, entity in ipairs(built) do
      if entity.valid then entity.destroy() end
    end
    check("test base cleaned up",
      surface.count_entities_filtered{ force = force,
        area = { { origin.x - 200, origin.y - 200 }, { origin.x + 200, origin.y + 200 } } } == 0, "")
  end

  local failed = 0
  for _, c in ipairs(checks) do
    if c.ok then
      log(string.format("[DIR selftest] PASS  %s  (%s)", c.name, c.detail))
    else
      failed = failed + 1
      log(string.format("[DIR selftest] FAIL  %s  (%s)", c.name, c.detail))
    end
  end
  log(string.format("[DIR selftest] %d/%d checks passed", #checks - failed, #checks))

  return failed, #checks
end

return Selftest
