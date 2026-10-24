-- Throwaway harness. Never ships. Lives only in the test mod directory.
-- Proves the end-to-end claim that unit tests cannot: that landing real damage
-- on a real biter over real time actually makes later damage of that type hurt
-- less. It touches no internals of tutis-fun-mods - it just shoots things.

local SURFACE = "nauvis"
local BITER = "big-biter"
local HIT = 100

local function spawn()
  local surface = game.surfaces[SURFACE]
  local position = surface.find_non_colliding_position(BITER, { 0, 0 }, 200, 1) or { 0, 0 }
  return surface.create_entity{ name = BITER, position = position, force = "enemy" }
end

-- One clean measurement: full health, one hit, report how much health it lost.
local function measure(damage_type)
  local biter = spawn()
  if not biter then return nil end
  biter.health = biter.max_health
  local before = biter.health
  biter.damage(HIT, game.forces.player, damage_type)
  local after = biter.health
  biter.destroy()
  return before - after
end

script.on_init(function()
  storage.phase = "start"
end)

script.on_nth_tick(60, function()
  if storage.phase == "start" then
    -- A fresh map has zero evolution and the gate would scale all resistance to
    -- nothing. A mod may only write its own settings, so rather than switching
    -- the gate off we drive evolution to maximum - which tests the real gated
    -- path instead of bypassing it.
    game.forces["enemy"].set_evolution_factor(1.0, game.surfaces[SURFACE])
    storage.phase = "baseline"
    return
  end

  if storage.phase == "baseline" then
    storage.fire_before = measure("fire")
    storage.physical_before = measure("physical")
    log(string.format("[TFM combat] baseline: fire hit removed %.1f hp, physical hit removed %.1f hp",
      storage.fire_before or -1, storage.physical_before or -1))
    storage.phase = "train"
    storage.trained = 0
    return
  end

  if storage.phase == "train" then
    -- Pour fire into the swarm, and nothing else, for two game minutes.
    local biter = spawn()
    if biter then
      for _ = 1, 20 do
        biter.health = biter.max_health
        biter.damage(HIT, game.forces.player, "fire")
      end
      biter.destroy()
    end
    storage.trained = storage.trained + 1
    if storage.trained >= 120 then storage.phase = "verify" end
    return
  end

  if storage.phase == "verify" then
    local fire_after = measure("fire")
    local physical_after = measure("physical")
    log(string.format("[TFM combat] after training on fire: fire hit removed %.1f hp, physical hit removed %.1f hp",
      fire_after or -1, physical_after or -1))

    local fire_ratio = (storage.fire_before or 0) > 0 and (fire_after / storage.fire_before) or -1
    local phys_ratio = (storage.physical_before or 0) > 0 and (physical_after / storage.physical_before) or -1
    log(string.format("[TFM combat] fire damage now lands at %.0f%% of baseline; physical at %.0f%%",
      fire_ratio * 100, phys_ratio * 100))

    if fire_ratio > 0 and fire_ratio < 0.75 then
      log("[TFM combat] PASS  the swarm hardened against the damage type it was fed")
    else
      log(string.format("[TFM combat] FAIL  fire ratio %.3f (expected well under 1.0)", fire_ratio))
    end

    -- Physical was never used, so it must be untouched. This is the check that
    -- catches a bug where resistance leaks across damage types.
    if phys_ratio > 0.95 then
      log("[TFM combat] PASS  an unused damage type was left alone")
    else
      log(string.format("[TFM combat] FAIL  physical ratio %.3f (expected ~1.0)", phys_ratio))
    end

    storage.phase = "done"
  end
end)
