-- Every feature in this mod is a toggle.
--
-- The split that matters: anything editing a PROTOTYPE must be a startup
-- setting and needs a game restart to change. Anything that only touches game
-- state can be runtime-global and applies instantly. That is an engine rule,
-- not a choice.

data:extend({
  -- ------------------------------------------------------------------ QoL --
  --
  -- Defaults match the mods already installed, so switching the originals off
  -- and these on should change nothing noticeable.

  { type = "bool-setting",   name = "tfm-qol-override", setting_type = "startup",
    default_value = false, order = "c-a" },

  { type = "bool-setting",   name = "tfm-qol-belts", setting_type = "startup",
    default_value = true,  order = "c-b" },
  { type = "double-setting", name = "tfm-qol-belt-speed", setting_type = "startup",
    default_value = 2.0, minimum_value = 0.1, maximum_value = 20.0, order = "c-c" },

  { type = "bool-setting",   name = "tfm-qol-solar", setting_type = "startup",
    default_value = true,  order = "c-d" },
  { type = "double-setting", name = "tfm-qol-solar-mult", setting_type = "startup",
    default_value = 4.0, minimum_value = 0.1, maximum_value = 100.0, order = "c-e" },
  { type = "double-setting", name = "tfm-qol-accumulator-mult", setting_type = "startup",
    default_value = 4.0, minimum_value = 0.1, maximum_value = 100.0, order = "c-f" },

  { type = "bool-setting",   name = "tfm-qol-stacks", setting_type = "startup",
    default_value = true,  order = "c-g" },
  { type = "int-setting",    name = "tfm-qol-stack-mult", setting_type = "startup",
    default_value = 10, minimum_value = 1, maximum_value = 1000, order = "c-h" },

  { type = "bool-setting",   name = "tfm-qol-pavement", setting_type = "startup",
    default_value = true,  order = "c-i" },

  { type = "bool-setting",   name = "tfm-qol-reach", setting_type = "runtime-global",
    default_value = true,  order = "d-a" },
  { type = "int-setting",    name = "tfm-qol-reach-bonus", setting_type = "runtime-global",
    default_value = 150, minimum_value = 0, maximum_value = 10000, order = "d-b" },

  { type = "bool-setting",   name = "tfm-qol-oil", setting_type = "runtime-global",
    default_value = true,  order = "d-c" },
  { type = "int-setting",    name = "tfm-qol-oil-yield", setting_type = "runtime-global",
    default_value = 100, minimum_value = 1, maximum_value = 1000000, order = "d-d" },
})
