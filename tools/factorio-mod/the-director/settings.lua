-- Adaptive Biters touches no prototypes, only runtime behaviour, so every
-- setting here is runtime-global: safe to change mid-save with no restart.

data:extend({
  { type = "bool-setting",   name = "dir-enabled", setting_type = "runtime-global",
    default_value = true, order = "a-a" },
  { type = "double-setting", name = "dir-adapt-cap", setting_type = "runtime-global",
    default_value = 0.60, minimum_value = 0.0, maximum_value = 0.95, order = "a-b" },
  { type = "double-setting", name = "dir-adapt-threshold", setting_type = "runtime-global",
    default_value = 0.15, minimum_value = 0.0, maximum_value = 0.90, order = "a-c" },
  { type = "double-setting", name = "dir-adapt-rate", setting_type = "runtime-global",
    default_value = 0.04, minimum_value = 0.001, maximum_value = 1.0, order = "a-d" },
  { type = "double-setting", name = "dir-adapt-memory", setting_type = "runtime-global",
    default_value = 0.985, minimum_value = 0.50, maximum_value = 0.9999, order = "a-e" },
  { type = "bool-setting",   name = "dir-adapt-evolution-gate", setting_type = "runtime-global",
    default_value = true, order = "a-f" },
  { type = "double-setting", name = "dir-industry-bias", setting_type = "runtime-global",
    default_value = 0.25, minimum_value = 0.0, maximum_value = 1.0, order = "a-g" },
  { type = "bool-setting",   name = "dir-adapt-gui", setting_type = "runtime-per-user",
    default_value = true, order = "b-a" },

  -- --------------------------------------------------------- the Director --
  { type = "bool-setting",   name = "dir-director", setting_type = "runtime-global",
    default_value = true, order = "c-a" },
  { type = "int-setting",    name = "dir-assess-period", setting_type = "runtime-global",
    default_value = 240, minimum_value = 30, maximum_value = 3600, order = "c-b" },
  { type = "double-setting", name = "dir-threat-scale", setting_type = "runtime-global",
    default_value = 1.0, minimum_value = 0.1, maximum_value = 10.0, order = "c-c" },
  { type = "bool-setting",   name = "dir-notifications", setting_type = "runtime-global",
    default_value = true, order = "c-d" },
})
