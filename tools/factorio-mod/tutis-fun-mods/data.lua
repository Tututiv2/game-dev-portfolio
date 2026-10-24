-- Prototype stage. Adaptive Biters needs exactly one prototype: a keybind for
-- the readout panel. Everything else it does is runtime-only, which is why the
-- whole feature can be toggled without restarting Factorio.

data:extend({
  {
    type = "custom-input",
    name = "tfm-toggle-cheats",
    key_sequence = "CONTROL + SHIFT + M",
    consuming = "none",
  },
})

-- A shortcut on the toolbar too, so the panel is reachable without knowing a
-- keybind exists.
data:extend({
  {
    type = "shortcut",
    name = "tfm-cheats-shortcut",
    action = "lua",
    associated_control_input = "tfm-toggle-cheats",
    toggleable = true,
    -- A plain 64x64 core icon. The technology/effect icons are mipmap strips
    -- (120x64) and would need a mipmap count declared to render correctly.
    icon = "__core__/graphics/icons/tip.png",
    icon_size = 64,
    small_icon = "__core__/graphics/icons/tip.png",
    small_icon_size = 64,
  },
})
