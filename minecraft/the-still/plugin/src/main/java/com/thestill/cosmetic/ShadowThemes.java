package com.thestill.cosmetic;

import com.thestill.role.Perks;
import org.bukkit.Material;

import java.util.List;

/**
 * Shadow Prison's theme library, ported as DATA.
 *
 * <p>All 141 of them, with their real palettes. The colours are not guesses: they come
 * from that plugin's {@code particleStops()}, which is a byte-exact server-side mirror
 * of the palettes its resource-pack fragment shader uses, and which exists precisely so
 * something other than the GPU can know what a theme looks like.
 *
 * <p><b>What could not come across, and why.</b> Over there a theme is a
 * {@code shaderIndex} encoded into per-character RGB markers that a custom
 * {@code rendertype_text.vsh} decodes — so a MOTION theme physically displaces glyphs
 * and an EFFECT theme relights them in screen space. Both live entirely in shaders in
 * the resource pack. The Still has no pack, so those two axes are rendered here as what
 * survives without one: the palette, animated server-side. Motion and effect themes
 * therefore read as their colours in movement rather than as their geometry. That is a
 * real difference and it is written down rather than hidden.
 *
 * <p><b>Index 34 is a permanent gap</b> and it is preserved. {@code OBSIDIAN_FLOW} was
 * removed for sitting too close to the VOID rank's colours, and the source's rule is
 * that its index is never reused — theme order is a wire protocol there. It costs
 * nothing to honour and it keeps the two libraries comparable.
 *
 * <p><b>APPEND ONLY.</b> A player's choice is stored by id. Reordering or renaming one
 * repoints somebody's saved theme at a different theme, silently.
 */
public final class ShadowThemes {

    private ShadowThemes() {
    }

    /** Which axis a theme sits on. See the class note on what MOTION and EFFECT lose. */
    public enum Kind { COLOUR, MOTION, EFFECT }

    public static final List<ChatTheme> ALL = List.of(
            t("prismatic", "Prismatic", 0, Kind.COLOUR, Material.NETHER_STAR, null, null, null, true, Perks.CHAT_COLOUR),
            t("void_pulse", "Void Pulse", 1, Kind.COLOUR, Material.CRYING_OBSIDIAN, 0x8C00FF, 0x1A0033, 0x8C00FF, false, Perks.TAG_ANIMATED),
            t("bloodmoon", "Bloodmoon", 19, Kind.COLOUR, Material.REDSTONE, 0x0D0000, 0x730305, 0xFF1A14, false, Perks.TAG_ANIMATED),
            t("inferno", "Inferno", 2, Kind.COLOUR, Material.BLAZE_POWDER, 0x6B0104, 0xFF2E03, 0xFFC70F, false, Perks.CHAT_COLOUR),
            t("frozen_reach", "Frozen Reach", 3, Kind.COLOUR, Material.BLUE_ICE, 0x041457, 0x14B8FF, 0xE5FAFF, false, Perks.CHAT_COLOUR),
            t("starfall", "Starfall", 4, Kind.COLOUR, Material.AMETHYST_SHARD, 0x5C08CC, 0xFF29B3, 0x1447FF, false, Perks.CHAT_COLOUR),
            t("soulfire", "Soulfire", 5, Kind.COLOUR, Material.SOUL_LANTERN, 0x001A24, 0x00D6E5, 0xC2FFFF, false, Perks.CHAT_COLOUR),
            t("neon_district", "Neon District", 6, Kind.COLOUR, Material.GLOW_INK_SAC, 0x00FFFF, 0xFF00D1, 0x5700FF, false, Perks.CHAT_COLOUR),
            t("overlord", "Overlord", 7, Kind.COLOUR, Material.GOLD_INGOT, 0x940401, 0xFF3803, 0xFFC70D, false, Perks.CHAT_COLOUR),
            t("admin_override", "Admin Override", 8, Kind.COLOUR, Material.COMMAND_BLOCK, 0x660004, 0xFF050A, 0xFFF0F0, false, null),
            t("console", "Console", 9, Kind.COLOUR, Material.REDSTONE_TORCH, 0x000600, 0x00FF2E, 0xCCFFD6, false, null),
            t("lockdown", "Lockdown", 10, Kind.COLOUR, Material.IRON_BARS, 0x020000, 0xFF0303, 0xFF8C00, false, null),
            t("crown_authority", "Crown Authority", 11, Kind.COLOUR, Material.GOLDEN_HELMET, 0xFFB805, 0xFFFADB, 0x7505D1, false, null),
            t("sunset_boulevard", "Sunset Boulevard", 12, Kind.COLOUR, Material.ORANGE_TULIP, 0x3D0A5C, 0xFF2673, 0xFF730D, false, Perks.CHAT_COLOUR),
            t("ocean_current", "Ocean Current", 13, Kind.COLOUR, Material.HEART_OF_THE_SEA, 0x001759, 0x009ED9, 0x8CFFD9, false, Perks.CHAT_COLOUR),
            t("toxic", "Toxic", 14, Kind.COLOUR, Material.SLIME_BALL, 0x0F1705, 0x59FF0D, 0xD9FF1A, false, Perks.CHAT_COLOUR),
            t("cotton_candy", "Cotton Candy", 15, Kind.COLOUR, Material.PINK_WOOL, 0xFF9ECC, 0xFFF5FA, 0x85C7FF, false, Perks.CHAT_COLOUR),
            t("golden_hour", "Golden Hour", 16, Kind.COLOUR, Material.HONEYCOMB, 0xD97308, 0xFFC738, 0xFFF2BF, false, Perks.CHAT_COLOUR),
            t("emerald_vault", "Emerald Vault", 17, Kind.COLOUR, Material.EMERALD, 0x033814, 0x0DD959, 0xA6FFCC, false, Perks.CHAT_COLOUR),
            t("bubblegum", "Bubblegum", 18, Kind.COLOUR, Material.PINK_DYE, 0xFF1A8C, 0xBF8CFF, 0x73BFFF, false, Perks.CHAT_COLOUR),
            t("aurora", "Aurora", 20, Kind.COLOUR, Material.PRISMARINE_CRYSTALS, 0x0DE673, 0x00A6BF, 0x8C33F2, false, Perks.CHAT_COLOUR),
            t("firefly", "Firefly", 21, Kind.COLOUR, Material.GLOWSTONE_DUST, 0x0D240A, 0xFFE64D, 0x33661A, false, Perks.CHAT_COLOUR),
            t("deep_space", "Deep Space", 22, Kind.COLOUR, Material.ENDER_EYE, 0x03030F, 0x331A8C, 0xEBF2FF, false, Perks.CHAT_COLOUR),
            t("cherry_blossom", "Cherry Blossom", 23, Kind.COLOUR, Material.PINK_PETALS, 0xFF8CB3, 0xFFF7F7, 0xF24D73, false, Perks.CHAT_COLOUR),
            t("vaporwave", "Vaporwave", 24, Kind.COLOUR, Material.PURPLE_DYE, 0xFF33A6, 0x1AE6F2, 0xA68CF2, false, Perks.CHAT_COLOUR),
            t("lava_lamp", "Lava Lamp", 25, Kind.COLOUR, Material.MAGMA_CREAM, 0xFF5905, 0xD9140D, 0x590D8C, false, Perks.CHAT_COLOUR),
            t("mermaid", "Mermaid", 26, Kind.COLOUR, Material.NAUTILUS_SHELL, 0x008C8C, 0x26D98C, 0xFFCCD9, false, Perks.CHAT_COLOUR),
            t("phantom", "Phantom", 27, Kind.COLOUR, Material.PHANTOM_MEMBRANE, 0xF2F7FF, 0x8CB3F2, 0x404859, false, Perks.CHAT_COLOUR),
            t("witchs_brew", "Witch's Brew", 28, Kind.COLOUR, Material.BREWING_STAND, 0x731AA6, 0x33BF26, 0x0A050F, false, Perks.CHAT_COLOUR),
            t("solar_flare", "Solar Flare", 29, Kind.COLOUR, Material.SUNFLOWER, 0xFFFAE6, 0xFF8C0D, 0xE61A05, false, Perks.CHAT_COLOUR),
            t("royalty", "Royalty", 30, Kind.COLOUR, Material.GOLDEN_APPLE, 0x590DA6, 0x1A33D9, 0xFFCC26, false, Perks.CHAT_COLOUR),
            t("dragonfire", "Dragonfire", 31, Kind.COLOUR, Material.DRAGON_BREATH, 0x8C1AD9, 0xFF59BF, 0x14001A, false, Perks.CHAT_COLOUR),
            t("crimson_court", "Crimson Court", 32, Kind.COLOUR, Material.NETHER_WART, 0x40054D, 0x730D26, 0x590308, false, Perks.CHAT_COLOUR),
            t("midnight_rose", "Midnight Rose", 33, Kind.COLOUR, Material.WITHER_ROSE, 0x1A0324, 0xD94073, 0x660514, false, Perks.CHAT_COLOUR),
            t("jungle_idol", "Jungle Idol", 35, Kind.COLOUR, Material.BAMBOO, 0x084014, 0xF2BF33, 0x407326, false, Perks.CHAT_COLOUR),
            t("arctic_night", "Arctic Night", 36, Kind.COLOUR, Material.ICE, 0x080F40, 0xD9F2FF, 0x59D9A6, false, Perks.CHAT_COLOUR),
            t("emberfall", "Emberfall", 37, Kind.COLOUR, Material.CHARCOAL, 0x4D4745, 0xFF7314, 0x1A1412, false, Perks.CHAT_COLOUR),
            t("gilded_onyx", "Gilded Onyx", 38, Kind.COLOUR, Material.GILDED_BLACKSTONE, 0x0A0808, 0xFFCC40, 0x141010, false, Perks.CHAT_COLOUR),
            t("nebula_bloom", "Nebula Bloom", 39, Kind.COLOUR, Material.AMETHYST_CLUSTER, 0xD91ABF, 0x1A1A73, 0xFF4DA6, false, Perks.CHAT_COLOUR),
            t("serpents_eye", "Serpent's Eye", 40, Kind.COLOUR, Material.VINE, 0x40D91A, 0x1A7366, 0x080F08, false, Perks.CHAT_COLOUR),
            t("stormfront", "Stormfront", 41, Kind.COLOUR, Material.LIGHTNING_ROD, 0x59666F, 0xFAFAFF, 0x26408C, false, Perks.CHAT_COLOUR),
            t("sandstorm", "Sandstorm", 42, Kind.COLOUR, Material.SAND, 0xD9A441, 0xF2DFAF, 0xFFFBF0, false, Perks.CHAT_COLOUR),
            t("rose_gold", "Rose Gold", 43, Kind.COLOUR, Material.COPPER_INGOT, 0xE0956F, 0xF7C6C7, 0xFFE9C4, false, Perks.CHAT_COLOUR),
            t("electric_blue", "Electric Blue", 44, Kind.COLOUR, Material.LAPIS_LAZULI, 0x061A66, 0x1FD6FF, 0xF0FBFF, false, Perks.CHAT_COLOUR),
            t("peppermint", "Peppermint", 45, Kind.COLOUR, Material.WHITE_CANDLE, 0xE01A26, 0xFFFFFF, 0x1FA85C, false, Perks.CHAT_COLOUR),
            t("coral_reef", "Coral Reef", 46, Kind.COLOUR, Material.BRAIN_CORAL, 0xFF6F91, 0xFF9F45, 0x2FD3C4, false, Perks.CHAT_COLOUR),
            t("iron_forge", "Iron Forge", 47, Kind.COLOUR, Material.ANVIL, 0x4A5560, 0xFFF3D6, 0xFF7A1A, false, Perks.CHAT_COLOUR),
            t("twilight_meadow", "Twilight Meadow", 48, Kind.COLOUR, Material.LILAC, 0xB48CD9, 0x7FA678, 0x2E3F6E, false, Perks.CHAT_COLOUR),
            t("ultraviolet", "Ultraviolet", 49, Kind.COLOUR, Material.PURPLE_STAINED_GLASS, 0x1A0A3D, 0x9B2BFF, 0xF2E9FF, false, Perks.CHAT_COLOUR),
            t("monarch", "Monarch", 50, Kind.COLOUR, Material.ORANGE_DYE, 0xFF7518, 0x140D08, 0xFFE9C2, false, Perks.CHAT_COLOUR),
            t("jade_temple", "Jade Temple", 51, Kind.COLOUR, Material.MOSSY_STONE_BRICKS, 0x1FA67A, 0xE6C34D, 0x0A3D33, false, Perks.CHAT_COLOUR),
            t("rust_belt", "Rust Belt", 52, Kind.COLOUR, Material.RAW_IRON, 0xB35A1F, 0x6B3A1A, 0x40474D, false, Perks.CHAT_COLOUR),
            t("blacklight", "Blacklight", 53, Kind.COLOUR, Material.GLOW_LICHEN, 0x39FF14, 0xFF00E1, 0x07030D, false, Perks.CHAT_COLOUR),
            t("sea_glass", "Sea Glass", 54, Kind.COLOUR, Material.CYAN_STAINED_GLASS, 0x8FD9C9, 0xE6D7B0, 0xFAFFFC, false, Perks.CHAT_COLOUR),
            t("plasma", "Plasma", 55, Kind.COLOUR, Material.END_CRYSTAL, 0xFFFFFF, 0xA326FF, 0x0A1466, false, Perks.CHAT_COLOUR),
            t("autumn_fall", "Autumn Fall", 56, Kind.COLOUR, Material.PUMPKIN, 0xB32218, 0xE07B18, 0x8C6218, false, Perks.CHAT_COLOUR),
            t("cosmic_latte", "Cosmic Latte", 57, Kind.COLOUR, Material.COCOA_BEANS, 0xFFF3DB, 0xC9A47A, 0xE8A9A0, false, Perks.CHAT_COLOUR),
            t("noir", "Noir", 58, Kind.COLOUR, Material.BLACK_CONCRETE, 0x050505, 0x6E6E6E, 0xFFFFFF, false, Perks.CHAT_COLOUR),
            t("carnival", "Carnival", 59, Kind.COLOUR, Material.FIREWORK_ROCKET, 0xE01B1B, 0xFFD31A, 0x1A6BE0, false, Perks.CHAT_COLOUR),
            t("glacier", "Glacier", 60, Kind.COLOUR, Material.PACKED_ICE, 0x2E6BA8, 0xBFE9FF, 0xF2FBFF, false, Perks.CHAT_COLOUR),
            t("magma_vein", "Magma Vein", 61, Kind.COLOUR, Material.MAGMA_BLOCK, 0x120705, 0xFF4A0D, 0xFFB03A, false, Perks.CHAT_COLOUR),
            t("orchid", "Orchid", 62, Kind.COLOUR, Material.PINK_TULIP, 0x7B2CBF, 0xFF8FD6, 0x3C0A57, false, Perks.CHAT_COLOUR),
            t("gunmetal", "Gunmetal", 63, Kind.COLOUR, Material.NETHERITE_INGOT, 0x2B3038, 0x6E7A88, 0xE6ECF2, false, Perks.CHAT_COLOUR),
            t("absinthe", "Absinthe", 64, Kind.COLOUR, Material.SLIME_BLOCK, 0x1F3D0C, 0x7FE02B, 0xE8FF8A, false, Perks.CHAT_COLOUR),
            t("sunken_temple", "Sunken Temple", 65, Kind.COLOUR, Material.PRISMARINE_BRICKS, 0x0A3A3A, 0x2E8B72, 0xC9A227, false, Perks.CHAT_COLOUR),
            t("ember_moth", "Ember Moth", 66, Kind.COLOUR, Material.FIREWORK_STAR, 0x3A3630, 0xC96A1E, 0xFFD9A0, false, Perks.CHAT_COLOUR),
            t("stained_glass", "Stained Glass", 67, Kind.COLOUR, Material.PURPLE_STAINED_GLASS, 0xC1121F, 0x1E3A8A, 0xE8B923, false, Perks.CHAT_COLOUR),
            t("venom", "Venom", 68, Kind.COLOUR, Material.SPIDER_EYE, 0x0B0B0F, 0x39FF14, 0x8A2BE2, false, Perks.CHAT_COLOUR),
            t("last_light", "Last Light", 69, Kind.COLOUR, Material.TORCH, 0x101C3A, 0xFF7A2F, 0xFFD9A6, false, Perks.CHAT_COLOUR),
            t("tidal", "Tidal", 70, Kind.MOTION, Material.PRISMARINE_SHARD, 0x0A3D62, 0x3FC1C9, 0xE8FFFF, false, Perks.CHAT_ANIMATED),
            t("fault_line", "Fault Line", 71, Kind.MOTION, Material.CRACKED_STONE_BRICKS, 0x3A2E2E, 0xB33A3A, 0xFFD1D1, false, Perks.CHAT_ANIMATED),
            t("kindling", "Kindling", 72, Kind.MOTION, Material.BLAZE_POWDER, 0x2B1400, 0xFF8C1A, 0xFFE0A3, false, Perks.CHAT_ANIMATED),
            t("wisp", "Wisp", 73, Kind.MOTION, Material.PHANTOM_MEMBRANE, 0x1B2A4A, 0x8FB8DE, 0xF0F7FF, false, Perks.CHAT_ANIMATED),
            t("corrupted", "Corrupted", 74, Kind.MOTION, Material.SCULK_SHRIEKER, 0x0B0B0B, 0x00FF9C, 0xFF00A8, false, Perks.CHAT_ANIMATED),
            t("living", "Living", 75, Kind.MOTION, Material.HEART_OF_THE_SEA, 0x4A0E1F, 0xE0245E, 0xFFC2D1, false, Perks.CHAT_ANIMATED),
            t("malware", "Malware", 76, Kind.COLOUR, Material.SCULK_CATALYST, 0x0B0B0B, 0x00FF9C, 0xFF00A8, false, Perks.CHAT_COLOUR),
            t("chrome", "Chrome", 77, Kind.EFFECT, Material.IRON_BLOCK, 0x2E3440, 0xB8C4CF, 0xFFFFFF, false, Perks.CHAT_ANIMATED),
            t("hologram", "Hologram", 78, Kind.EFFECT, Material.TINTED_GLASS, 0x0A2A3A, 0x35D6FF, 0xCFF6FF, false, Perks.CHAT_ANIMATED),
            t("ember", "Ember", 79, Kind.EFFECT, Material.CAMPFIRE, 0x2A0A00, 0xFF6B1A, 0xFFD98A, false, Perks.CHAT_ANIMATED),
            t("broadcast", "Broadcast", 80, Kind.EFFECT, Material.REDSTONE_TORCH, 0x2A0000, 0xFF1A1A, 0xFFE0E0, false, Perks.CHAT_ANIMATED),
            t("orbit", "Orbit", 81, Kind.MOTION, Material.END_CRYSTAL, 0x1A1035, 0x7B5CFF, 0xE0D6FF, false, Perks.CHAT_ANIMATED),
            t("cascade", "Cascade", 82, Kind.MOTION, Material.AMETHYST_CLUSTER, 0x0E2A38, 0x27C4D9, 0xDFFBFF, false, Perks.CHAT_ANIMATED),
            t("ripple", "Ripple", 83, Kind.MOTION, Material.LILY_PAD, 0x06303A, 0x1FA9A0, 0xCFF7F0, false, Perks.CHAT_ANIMATED),
            t("pendulum", "Pendulum", 84, Kind.MOTION, Material.LANTERN, 0x2B2118, 0xC9963F, 0xFFE8C2, false, Perks.CHAT_ANIMATED),
            t("vertigo", "Vertigo", 85, Kind.MOTION, Material.ENDER_PEARL, 0x1B0B2E, 0x9B4DFF, 0xE9D9FF, false, Perks.CHAT_ANIMATED),
            t("neon", "Neon", 86, Kind.EFFECT, Material.GLOWSTONE, 0x14001E, 0xFF2BD1, 0xFFD9F6, false, Perks.CHAT_ANIMATED),
            t("static", "Static", 87, Kind.EFFECT, Material.GRAY_CONCRETE_POWDER, 0x101010, 0x9A9A9A, 0xF2F2F2, false, Perks.CHAT_ANIMATED),
            t("caustics", "Caustics", 88, Kind.EFFECT, Material.PRISMARINE, 0x03303F, 0x2FB6C4, 0xDFFFFB, false, Perks.CHAT_ANIMATED),
            t("glint", "Glint", 89, Kind.EFFECT, Material.NETHER_STAR, 0x1A1A22, 0xBFC7D6, 0xFFFFFF, false, Perks.CHAT_ANIMATED),
            t("reactor", "Reactor", 90, Kind.EFFECT, Material.SEA_LANTERN, 0x0A2A0A, 0x5CFF3D, 0xEFFFD9, false, Perks.CHAT_ANIMATED),
            t("heartbeat", "Heartbeat", 91, Kind.MOTION, Material.REDSTONE_BLOCK, 0x1A0208, 0xD6142F, 0xFF6F76, false, Perks.CHAT_ANIMATED),
            t("serpent", "Serpent", 92, Kind.MOTION, Material.TURTLE_SCUTE, 0x081E10, 0x51C43C, 0xD3FF7E, false, Perks.CHAT_ANIMATED),
            t("magnetic", "Magnetic", 93, Kind.MOTION, Material.LODESTONE, 0x16284F, 0x5D8CDA, 0xF25A4F, false, Perks.CHAT_ANIMATED),
            t("spring", "Spring", 94, Kind.MOTION, Material.PISTON, 0x282E3C, 0x9CCADA, 0xFFD987, false, Perks.CHAT_ANIMATED),
            t("stagger", "Stagger", 95, Kind.MOTION, Material.HONEY_BOTTLE, 0x24190B, 0xE6962D, 0xFFE5A3, false, Perks.CHAT_ANIMATED),
            t("stomp", "Stomp", 96, Kind.MOTION, Material.NETHERITE_BLOCK, 0x2E2924, 0x9C8B76, 0xE6D9C7, false, Perks.CHAT_ANIMATED),
            t("haunted", "Haunted", 97, Kind.MOTION, Material.SOUL_CAMPFIRE, 0x0B1317, 0x66BDBA, 0xDBF9F2, false, Perks.CHAT_ANIMATED),
            t("helix", "Helix", 98, Kind.MOTION, Material.TWISTING_VINES, 0x5328AF, 0x1DC4BF, 0xECE6FF, false, Perks.CHAT_ANIMATED),
            t("shockwave", "Shockwave", 99, Kind.MOTION, Material.ECHO_SHARD, 0x050E33, 0x4B82F2, 0xFFFFFF, false, Perks.CHAT_ANIMATED),
            t("zero_g", "Zero G", 100, Kind.MOTION, Material.ELYTRA, 0x0B081C, 0x675DBA, 0xD8E5FF, false, Perks.CHAT_ANIMATED),
            t("flutter", "Flutter", 101, Kind.MOTION, Material.FEATHER, 0x342616, 0xDDBD68, 0xFFF8E1, false, Perks.CHAT_ANIMATED),
            t("whirlwind", "Whirlwind", 102, Kind.MOTION, Material.WIND_CHARGE, 0x21272E, 0x7A92A5, 0xF6FAFF, false, Perks.CHAT_ANIMATED),
            t("carousel", "Carousel", 103, Kind.MOTION, Material.MINECART, 0xA31B28, 0xFFEDCE, 0xE9AF3E, false, Perks.CHAT_ANIMATED),
            t("marionette", "Marionette", 104, Kind.MOTION, Material.STRING, 0x3D2313, 0xB47A4A, 0xF2E7D0, false, Perks.CHAT_ANIMATED),
            t("accordion", "Accordion", 105, Kind.MOTION, Material.NOTE_BLOCK, 0x161113, 0xB32D3D, 0xF9E1C8, false, Perks.CHAT_ANIMATED),
            t("buzz", "Buzz", 106, Kind.MOTION, Material.REPEATER, 0x141308, 0xF0E628, 0xFFFFC7, false, Perks.CHAT_ANIMATED),
            t("scramble", "Scramble", 107, Kind.MOTION, Material.SUSPICIOUS_GRAVEL, 0x101619, 0xF29C2F, 0x4FDBDB, false, Perks.CHAT_ANIMATED),
            t("burst", "Burst", 108, Kind.MOTION, Material.TNT, 0xFFFFF2, 0xFF9C28, 0xA31B16, false, Perks.CHAT_ANIMATED),
            t("drain", "Drain", 109, Kind.MOTION, Material.CAULDRON, 0x051B29, 0x2D8CB0, 0xC7EFF6, false, Perks.CHAT_ANIMATED),
            t("metronome", "Metronome", 110, Kind.MOTION, Material.CLOCK, 0x0E0D0B, 0xCEA444, 0xFFF2D0, false, Perks.CHAT_ANIMATED),
            t("swarm", "Swarm", 111, Kind.MOTION, Material.BEE_NEST, 0x211605, 0xE8A41D, 0xFFE892, false, Perks.CHAT_ANIMATED),
            t("typewriter", "Typewriter", 112, Kind.MOTION, Material.WRITABLE_BOOK, 0x141312, 0x8B8780, 0xFAF8F2, false, Perks.CHAT_ANIMATED),
            t("pulseline", "Pulseline", 113, Kind.MOTION, Material.OBSERVER, 0x02130B, 0x2EF279, 0xD6FFE5, false, Perks.CHAT_ANIMATED),
            t("jelly", "Jelly", 114, Kind.MOTION, Material.HONEY_BLOCK, 0x49852D, 0xA3E666, 0xEEFFD4, false, Perks.CHAT_ANIMATED),
            t("snap", "Snap", 115, Kind.MOTION, Material.TRIPWIRE_HOOK, 0x0B0B0D, 0xD8DAE0, 0xFFFFFF, false, Perks.CHAT_ANIMATED),
            t("undertow", "Undertow", 116, Kind.MOTION, Material.KELP, 0x031D21, 0x1D7A76, 0xAFE7D9, false, Perks.CHAT_ANIMATED),
            t("clockwork", "Clockwork", 117, Kind.MOTION, Material.COMPASS, 0x2E271D, 0xBF9243, 0xE5E0D4, false, Perks.CHAT_ANIMATED),
            t("gravity", "Gravity", 118, Kind.MOTION, Material.DEEPSLATE, 0x191B20, 0x7A828E, 0xD8D0BD, false, Perks.CHAT_ANIMATED),
            t("waveform", "Waveform", 119, Kind.MOTION, Material.JUKEBOX, 0x050E13, 0x35CEE5, 0xD4FCFF, false, Perks.CHAT_ANIMATED),
            t("shiver", "Shiver", 120, Kind.MOTION, Material.POWDER_SNOW_BUCKET, 0x1E2D3D, 0x92BADA, 0xF2FCFF, false, Perks.CHAT_ANIMATED),
            t("riptide", "Riptide", 121, Kind.MOTION, Material.TUBE_CORAL, 0x042832, 0x16969C, 0xBFF2EC, false, Perks.CHAT_ANIMATED),
            t("stutter", "Stutter", 122, Kind.MOTION, Material.SCULK_SENSOR, 0x211D23, 0xCD32A5, 0xECE9EE, false, Perks.CHAT_ANIMATED),
            t("slingshot", "Slingshot", 123, Kind.MOTION, Material.BOW, 0x372116, 0xD96025, 0xFFE1AD, false, Perks.CHAT_ANIMATED),
            t("rumble", "Rumble", 124, Kind.MOTION, Material.BASALT, 0x241C16, 0x7E6045, 0xD9C9B1, false, Perks.CHAT_ANIMATED),
            t("moth", "Moth", 125, Kind.MOTION, Material.BROWN_MUSHROOM, 0x2F2B28, 0xA39884, 0xFFF2BD, false, Perks.CHAT_ANIMATED),
            t("pendulum_wave", "Pendulum Wave", 126, Kind.MOTION, Material.CHAIN_COMMAND_BLOCK, 0x13161C, 0xD9B351, 0xFBF2DA, false, Perks.CHAT_ANIMATED),
            t("zipper", "Zipper", 127, Kind.MOTION, Material.IRON_TRAPDOOR, 0x1C1E21, 0x9DA5AF, 0xF2F6FA, false, Perks.CHAT_ANIMATED),
            t("chatter", "Chatter", 128, Kind.MOTION, Material.BONE, 0x253444, 0x7EADCF, 0xF8FCFF, false, Perks.CHAT_ANIMATED),
            t("warp", "Warp", 129, Kind.MOTION, Material.ENDER_CHEST, 0x280E44, 0x8C44DB, 0xF2E8FF, false, Perks.CHAT_ANIMATED),
            t("coil", "Coil", 130, Kind.MOTION, Material.WEEPING_VINES, 0x7A3E21, 0xE58A4A, 0x66C4AF, false, Perks.CHAT_ANIMATED),
            t("void_rift", "Void Rift", 131, Kind.MOTION, Material.DRAGON_EGG, 0x8C00FF, 0x1A0033, 0x8C00FF, false, Perks.TAG_ANIMATED),
            t("tether", "Tether", 132, Kind.MOTION, Material.LEAD, 0x241C0E, 0xAD8C4F, 0xE8DCC0, false, Perks.CHAT_ANIMATED),
            t("datastream", "Datastream", 133, Kind.MOTION, Material.REPEATING_COMMAND_BLOCK, 0x02120A, 0x21D66B, 0xC4FFDC, false, Perks.CHAT_ANIMATED),
            t("quake", "Quake", 134, Kind.MOTION, Material.CRACKED_DEEPSLATE_TILES, 0x1F1A17, 0x8A6F55, 0xE0CDB4, false, Perks.CHAT_ANIMATED),
            t("piston_wave", "Piston Wave", 135, Kind.MOTION, Material.STICKY_PISTON, 0x2B2018, 0xB5813F, 0xEBD5AE, false, Perks.CHAT_ANIMATED),
            t("twitch", "Twitch", 136, Kind.MOTION, Material.RABBIT_FOOT, 0x1A1520, 0xC466E0, 0xF4E4FA, false, Perks.CHAT_ANIMATED),
            t("thread", "Thread", 137, Kind.MOTION, Material.COBWEB, 0x161A1F, 0x9FB4C9, 0xF0F6FB, false, Perks.CHAT_ANIMATED),
            t("lull", "Lull", 138, Kind.MOTION, Material.LIGHT_BLUE_STAINED_GLASS, 0x0D1B2A, 0x4A7FA5, 0xCFE4F2, false, Perks.CHAT_ANIMATED),
            t("fracture", "Fracture", 139, Kind.MOTION, Material.CRACKED_NETHER_BRICKS, 0x1C1B1A, 0x777170, 0xD6D0CB, false, Perks.CHAT_ANIMATED),
            t("uprise", "Uprise", 140, Kind.MOTION, Material.SHULKER_SHELL, 0x101F1B, 0x35A88A, 0xCFF2E6, false, Perks.CHAT_ANIMATED),
            t("shunt", "Shunt", 141, Kind.MOTION, Material.HOPPER, 0x201A22, 0x8C6BA8, 0xE2D6EE, false, Perks.CHAT_ANIMATED)
    );

    private static ChatTheme t(String id, String name, int index, Kind kind, Material icon,
                               Integer a, Integer b, Integer c, boolean rainbow, String unlock) {
        return new ChatTheme(id, name, ChatTheme.Source.SHADOW,
                kind == Kind.COLOUR ? ChatTheme.Motion.COLOUR : ChatTheme.Motion.MOTION,
                icon, a, b, c, rainbow, index, unlock);
    }
}
