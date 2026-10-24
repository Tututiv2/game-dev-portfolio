data:extend({
  {
    type = "custom-input",
    name = "dir-toggle-gui",
    key_sequence = "CONTROL + SHIFT + B",
    consuming = "none",
  },
})

-- Intel is earned, not given. Before you research anything you are blind: the
-- attacks simply happen to you and you have no idea why they land where they
-- land. Each tier buys you a little more of what it already knows about you.
--
-- A plain 64x64 core icon: the technology/effect icons are 120x64 mipmap
-- strips and would need a mipmap count declared.
local function intel(level, prerequisites, ingredients, count)
  return {
    type = "technology",
    name = "dir-intel-" .. level,
    icon = "__core__/graphics/icons/tip.png",
    icon_size = 64,
    effects = { { type = "nothing", effect_description = { "dir.intel-" .. level .. "-effect" } } },
    prerequisites = prerequisites,
    unit = { count = count, ingredients = ingredients, time = 30 },
    order = "z-dir-" .. level,
  }
end

data:extend({
  intel(1, { "electronics" },
    { { "automation-science-pack", 1 } }, 100),
  intel(2, { "dir-intel-1", "logistic-science-pack" },
    { { "automation-science-pack", 1 }, { "logistic-science-pack", 1 } }, 250),
  intel(3, { "dir-intel-2", "chemical-science-pack" },
    { { "automation-science-pack", 1 }, { "logistic-science-pack", 1 }, { "chemical-science-pack", 1 } }, 500),
})
