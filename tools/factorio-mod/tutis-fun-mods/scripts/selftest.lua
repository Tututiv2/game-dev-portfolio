-- Verification for the cheat panel, the item giver and the QoL layer.
-- Runs inside Factorio (/tfm-selftest, or automatically on a headless load) and
-- reports through log(), which tools/test.ps1 then scans.
--
-- The adaptation model's tests moved out with it into Tuti's Cooler Biters.

-- Required at file scope on purpose: Factorio only allows require() while
-- control.lua is being parsed, never from inside an event handler.
local Cheats = require("scripts.cheats")
local Give = require("scripts.give")

local Selftest = {}

local checks = {}

local function check(name, condition, detail)
  checks[#checks + 1] = { name = name, ok = condition and true or false, detail = detail or "" }
end

function Selftest.run()
  checks = {}

  -- 1. Every cheat lever must actually work: read it, change it, see the change
  --    land, then put it back. This catches a typo in the registry, an API that
  --    moved, or a lever another mod broke. It needs real game state, which a
  --    headless load has even with no player in it.
  do
    local ctx = Cheats.headless_context()
    local broken = {}

    for _, lever in ipairs(Cheats.LEVERS) do
      local before = Cheats.read(lever, ctx)
      if before == nil then
        broken[#broken + 1] = lever.id .. "(unreadable)"
      else
        local probe_value
        if lever.kind == "bool" then
          probe_value = not before
        else
          probe_value = (before == lever.max) and lever.min or lever.max
        end

        local ok = Cheats.write(lever, ctx, probe_value)
        local after = Cheats.read(lever, ctx)
        if not ok then
          broken[#broken + 1] = lever.id .. "(write failed)"
        elseif after == before then
          broken[#broken + 1] = lever.id .. "(no effect)"
        end
        Cheats.reset(lever, ctx)
      end
    end

    check("all cheat levers read, write and reset", #broken == 0,
      #broken == 0 and (#Cheats.LEVERS .. " levers verified") or table.concat(broken, ", "))
  end

  -- 2. The item giver's lookups. Actually inserting needs a player, which a
  --    headless load does not have, so this covers everything up to the insert.
  do
    local qualities = Give.qualities()
    local has_normal, has_placeholder = false, false
    for _, name in ipairs(qualities) do
      if name == "normal" then has_normal = true end
      if name == "quality-unknown" then has_placeholder = true end
    end
    check("quality list is usable", has_normal and not has_placeholder, table.concat(qualities, ","))

    check("stack size lookup works", Give.stack_size("iron-plate") > 1,
      "iron-plate=" .. tostring(Give.stack_size("iron-plate")))
    check("unknown item degrades safely", Give.stack_size("definitely-not-an-item") == 1, "")

    local item_count = 0
    for _ in pairs(prototypes.item) do item_count = item_count + 1 end
    check("items are enumerable for the picker", item_count > 100, item_count .. " items")
  end

  -- 3. QoL prototype tweaks. The interesting part is the stand-down rule: run
  --    alone the belts must be faster, but with more-belts installed they must
  --    be untouched. Both halves are asserted, and which half applies is
  --    derived from what is actually loaded.
  do
    local override = settings.startup["tfm-qol-override"].value
    local belts_on = settings.startup["tfm-qol-belts"].value
      and (override or not script.active_mods["more-belts"])

    local ok_speed, belt_speed = pcall(function()
      return prototypes.entity["transport-belt"].belt_speed
    end)
    local multiplier = settings.startup["tfm-qol-belt-speed"].value
    local VANILLA_BELT = 0.03125

    if not ok_speed then
      check("belt speed readable", false, "could not read transport-belt")
    elseif belts_on then
      local expected = VANILLA_BELT * multiplier
      check("faster belts applied", math.abs(belt_speed - expected) < 1e-6,
        string.format("speed=%.5f expected=%.5f (x%.2f)", belt_speed, expected, multiplier))
    else
      check("faster belts stood down for more-belts", math.abs(belt_speed - VANILLA_BELT) < 1e-6,
        string.format("speed=%.5f, untouched vanilla=%.5f", belt_speed, VANILLA_BELT))
    end

    local stack = prototypes.item["iron-plate"] and prototypes.item["iron-plate"].stack_size
    local concrete = prototypes.tile["concrete"] and prototypes.tile["concrete"].walking_speed_modifier
    log(string.format("[TFM selftest] QoL state: iron-plate stack=%s, concrete walk=%s, BigBags=%s, FasterPavements=%s",
      tostring(stack), tostring(concrete),
      tostring(script.active_mods["BigBags"] ~= nil), tostring(script.active_mods["FasterPavements"] ~= nil)))
  end

  local failed = 0
  for _, c in ipairs(checks) do
    if c.ok then
      log(string.format("[TFM selftest] PASS  %s  (%s)", c.name, c.detail))
    else
      failed = failed + 1
      log(string.format("[TFM selftest] FAIL  %s  (%s)", c.name, c.detail))
    end
  end
  log(string.format("[TFM selftest] %d/%d checks passed", #checks - failed, #checks))

  return failed, #checks
end

return Selftest
