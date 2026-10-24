package com.skyclone.gen;

import java.util.HashMap;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Approximate colours (average texture / in-game look, foliage already tinted) for the preview
 * renderer. Covers the common building and terrain blocks explicitly and derives the rest:
 * stairs/slabs/walls/fences/doors take their material's colour, the 16 dye colours cover wool,
 * carpet, concrete, terracotta, glass, beds, banners, candles and shulker boxes. Anything still
 * unknown gets a stable hashed mid-tone so it is at least distinguishable.
 */
public final class BlockColors {

    private BlockColors() {}

    private static final Map<String, Integer> BASE = new HashMap<>();
    private static final ConcurrentHashMap<String, Integer> CACHE = new ConcurrentHashMap<>();

    private static final String[] DYES = {"white", "orange", "magenta", "light_blue", "yellow", "lime", "pink", "gray",
            "light_gray", "cyan", "purple", "blue", "brown", "green", "red", "black"};
    private static final int[] WOOL = {0xE9ECEC, 0xF07613, 0xBD44B3, 0x3AAFD9, 0xF8C527, 0x70B919, 0xED8DAC, 0x3E4447,
            0x8E8E86, 0x158991, 0x792AAC, 0x35399D, 0x724728, 0x546D1B, 0xA12722, 0x141519};
    private static final int[] CONCRETE = {0xCFD5D6, 0xE06100, 0xA9309F, 0x2389C6, 0xF0AF15, 0x5EA818, 0xD5658E, 0x36393D,
            0x7D7D73, 0x157788, 0x64209C, 0x2C2E8F, 0x603B1F, 0x495B24, 0x8E2020, 0x080A0F};
    private static final int[] TERRACOTTA = {0xD1B2A1, 0xA15325, 0x95586C, 0x716C89, 0xBA8523, 0x677534, 0xA04D4E, 0x392A23,
            0x876A61, 0x575B5B, 0x764656, 0x4A3B5B, 0x4D3323, 0x4C532A, 0x8F3D2E, 0x251610};

    private static final String[] WOODS = {"oak", "spruce", "birch", "jungle", "acacia", "dark_oak", "mangrove", "cherry",
            "bamboo", "crimson", "warped", "pale_oak"};

    static {
        String table =
                // --- wood ---
                "oak_planks=A2834F,spruce_planks=735531,birch_planks=C0AF79,jungle_planks=A07351,acacia_planks=A85A32," +
                "dark_oak_planks=42291A,mangrove_planks=773632,cherry_planks=E2B2AC,bamboo_planks=C1AD50,crimson_planks=653047," +
                "warped_planks=2B6963,pale_oak_planks=E4DAD8,bamboo_mosaic=BEAA4E," +
                "oak_log=6D5533,spruce_log=3A2611,birch_log=D8D7D2,jungle_log=554419,acacia_log=676157,dark_oak_log=3C2E1A," +
                "mangrove_log=544229,cherry_log=37201F,pale_oak_log=57514D,crimson_stem=5C1A1E,warped_stem=3A3B4E,bamboo_block=7F8F2E," +
                "stripped_oak_log=B19056,stripped_spruce_log=745A33,stripped_birch_log=C4B07A,stripped_jungle_log=AB8454," +
                "stripped_acacia_log=AF5C3B,stripped_dark_oak_log=60482F,stripped_mangrove_log=773632,stripped_cherry_log=D7918F," +
                "stripped_pale_oak_log=F5EEEC,stripped_crimson_stem=89394D,stripped_warped_stem=3A9A93,stripped_bamboo_block=C2AE52," +
                "oak_leaves=4A8A2A,spruce_leaves=3D5F3D,birch_leaves=6A8F45,jungle_leaves=3E8A1E,acacia_leaves=5E8A1E," +
                "dark_oak_leaves=3E7A1E,mangrove_leaves=4F8A24,cherry_leaves=E9B1C8,azalea_leaves=5A7A2A," +
                "flowering_azalea_leaves=7A7C4A,pale_oak_leaves=878D86," +
                // --- ground ---
                "grass_block=6FA046,dirt=866043,coarse_dirt=77553B,podzol=5B3F18,mycelium=6F6369,rooted_dirt=90674C,mud=3C393D," +
                "dirt_path=947A41,farmland=6B4A2A,clay=A0A6B3,gravel=837E7E,sand=DBD3A0,red_sand=BE6621,sandstone=D8CB9B," +
                "cut_sandstone=D9CE9F,smooth_sandstone=E0D6AA,chiseled_sandstone=D8CA9B,red_sandstone=BA631D," +
                "cut_red_sandstone=BD6620,smooth_red_sandstone=B5621F,snow_block=F9FEFE,snow=F9FEFE,powder_snow=F8FDFD," +
                "ice=91B7FD,packed_ice=8DB4FA,blue_ice=74A7FD,moss_block=596E2D,moss_carpet=596E2D,pale_moss_block=6B7069," +
                "pale_moss_carpet=6B7069,suspicious_sand=D8CE9C,suspicious_gravel=827C7B," +
                // --- stone ---
                "stone=7D7D7D,cobblestone=7A7A7A,mossy_cobblestone=6E775F,stone_bricks=7A7979,mossy_stone_bricks=737969," +
                "cracked_stone_bricks=767676,chiseled_stone_bricks=777777,smooth_stone=9E9E9E,andesite=888889," +
                "polished_andesite=848786,diorite=BCBCBC,polished_diorite=C1C1C3,granite=956755,polished_granite=9A6A59," +
                "deepslate=505053,cobbled_deepslate=4D4D51,polished_deepslate=484849,deepslate_bricks=474747," +
                "cracked_deepslate_bricks=434343,deepslate_tiles=373737,cracked_deepslate_tiles=343434,chiseled_deepslate=363637," +
                "reinforced_deepslate=505653,tuff=6C6D67,tuff_bricks=62655D,polished_tuff=616863,chiseled_tuff=5A5D55," +
                "chiseled_tuff_bricks=6A6C64,calcite=DFE0DD,dripstone_block=866B5C,bricks=976253,mud_bricks=89684F," +
                "packed_mud=8E6B50,nether_bricks=2C1519,cracked_nether_bricks=281417,chiseled_nether_bricks=2F171B," +
                "red_nether_bricks=450709,netherrack=612626,basalt=505156,polished_basalt=636367,smooth_basalt=48484E," +
                "blackstone=2A2328,polished_blackstone=35303B,polished_blackstone_bricks=302A31,chiseled_polished_blackstone=35303B," +
                "cracked_polished_blackstone_bricks=2C262C,gilded_blackstone=382B26,end_stone=DCDF9E,end_stone_bricks=DAE0A2," +
                "purpur_block=A97DA9,purpur_pillar=AB80AB,prismarine=63A397,prismarine_bricks=63AB9E,dark_prismarine=335B4B," +
                "sea_lantern=ACC7BE,quartz_block=ECE6DF,smooth_quartz=ECE6DF,quartz_bricks=EAE5DE,quartz_pillar=ECE6DF," +
                "chiseled_quartz_block=E7E2DA,obsidian=0F0B19,crying_obsidian=200A3C,bedrock=555555,glowstone=FBDA74," +
                "shroomlight=F09245,magma_block=8E3F1F,soul_sand=513E32,soul_soil=4B3A2F,bone_block=E1DDC9,terracotta=985E43," +
                "resin_block=D9631B,resin_bricks=CE5A1A,chiseled_resin_bricks=C85718,cobweb=E4E9EA," +
                "copper_block=C06B4F,exposed_copper=A17E68,weathered_copper=6C9A6D,oxidized_copper=52A385,cut_copper=BF6A50," +
                "exposed_cut_copper=9A7965,weathered_cut_copper=6D9165,oxidized_cut_copper=4F9986,chiseled_copper=B8664C," +
                "copper_grate=C06B4F,copper_bulb=C27A57," +
                // --- ores & storage ---
                "coal_ore=6A6A6A,iron_ore=8E8580,gold_ore=8F8B70,diamond_ore=7A928F,emerald_ore=74917A,lapis_ore=657A9A," +
                "redstone_ore=8E6464,copper_ore=7C7E78,deepslate_coal_ore=4A4A4C,deepslate_iron_ore=6A6461,deepslate_gold_ore=73694F," +
                "deepslate_diamond_ore=536A6A,deepslate_emerald_ore=4E6B57,deepslate_lapis_ore=4F5A7A,deepslate_redstone_ore=694546," +
                "deepslate_copper_ore=5C5D59,nether_gold_ore=73372A,nether_quartz_ore=75413E,ancient_debris=5F4A43," +
                "iron_block=DCDCDC,gold_block=F6D03D,diamond_block=62EDE4,emerald_block=2ACB57,lapis_block=1F43A6," +
                "redstone_block=AF1805,coal_block=101010,netherite_block=433E41,amethyst_block=8562BE,budding_amethyst=8461BC," +
                "raw_iron_block=A6876B,raw_gold_block=DDA92E,raw_copper_block=9A6A4F,quartz_ore=75413E," +
                // --- utility ---
                "hay_block=A68B0C,bookshelf=75603C,chiseled_bookshelf=B2925F,crafting_table=7B5D3A,barrel=86643B,composter=7A5534," +
                "furnace=6E6E6E,blast_furnace=515051,smoker=6B5A47,chest=A27A38,trapped_chest=A27A38,ender_chest=2F4246," +
                "anvil=444444,chipped_anvil=444444,damaged_anvil=444444,enchanting_table=6B2C2A,brewing_stand=7A6551," +
                "cauldron=4A4A4B,water_cauldron=3F5A9A,lava_cauldron=B4520F,lectern=AA8A55,loom=917A5B,smithing_table=393A45," +
                "fletching_table=C5B37E,cartography_table=685341,grindstone=8E8E8E,stonecutter=7B7671,beacon=76DDD7,bell=C9A33B," +
                "lantern=E5A64B,soul_lantern=4FC0C5,copper_lantern=E5A64B,torch=FFD83D,wall_torch=FFD83D,soul_torch=6BE0E2," +
                "soul_wall_torch=6BE0E2,copper_torch=8FE0A0,copper_wall_torch=8FE0A0,redstone_torch=D53A2C,redstone_wall_torch=D53A2C," +
                "campfire=C8742C,soul_campfire=4FC0C5,iron_chain=3B414E,copper_chain=9A5A42,iron_bars=6E7274,glass=CFE6EC," +
                "glass_pane=BFD9E0,tinted_glass=2C2630,jack_o_lantern=D8901A,pumpkin=C67618,carved_pumpkin=C67618," +
                "melon=6F9A20,mushroom_stem=CBC4B9,red_mushroom_block=C82E2D,brown_mushroom_block=957051,nether_wart_block=7B0202," +
                "warped_wart_block=167879,sponge=C4C04A,wet_sponge=AAAB27,slime_block=6FC05B,honey_block=F6A92F," +
                "honeycomb_block=E6A12C,target=E6AA9C,tnt=C22818,note_block=584038,jukebox=573A2A,spawner=25384A," +
                "dispenser=6E6E6E,dropper=6E6E6E,observer=636363,piston=9C8462,sticky_piston=8A9A62,hopper=4C4C4C," +
                "rail=8A7B5C,powered_rail=9A7A4C,detector_rail=7A6B5C,activator_rail=7A5B4C,scaffolding=AA8448,ladder=7C6136," +
                "flower_pot=7C4536,decorated_pot=8C5A44,beehive=B59658,bee_nest=C9A23D,lodestone=777879,respawn_anchor=291C3F," +
                "ochre_froglight=F7EDBC,verdant_froglight=E6F5D9,pearlescent_froglight=F6E9EE,end_rod=E3D9C9,lightning_rod=C66F4E," +
                "daylight_detector=82745E,repeater=9E9997,comparator=A19995,lever=6E5E48,tripwire_hook=8A7756,redstone_wire=7A0000," +
                "structure_block=584A5A,conduit=A08C6D,dragon_egg=0C0910,end_portal_frame=5B7867,nether_portal=5A0AD6," +
                "end_portal=11141C,end_gateway=11141C,crafter=6E6062,vault=3C4650,trial_spawner=3C4650,heavy_core=56585F," +
                // --- liquids & water plants ---
                "water=3F76E4,bubble_column=3F76E4,lava=D4600F,kelp=4E8F2F,kelp_plant=4E8F2F,seagrass=3A8B2B," +
                "tall_seagrass=3A8B2B,lily_pad=208030,sea_pickle=6B7128,turtle_egg=E5E2C9,sniffer_egg=9D3F33," +
                // --- plants ---
                "short_grass=5E9D35,grass=5E9D35,tall_grass=5E9D35,fern=51862E,large_fern=51862E,dead_bush=7A5A2B,bush=4F8A2A," +
                "firefly_bush=4E6A2A,short_dry_grass=B4A065,tall_dry_grass=B4A065,leaf_litter=A0672C,dandelion=FED73E," +
                "poppy=E2271B,blue_orchid=2BA9F9,allium=B678E8,azure_bluet=D4DAE1,red_tulip=E23A1B,orange_tulip=F28C26," +
                "white_tulip=E5ECE2,pink_tulip=EBC5E9,oxeye_daisy=DFE3D5,cornflower=466AEB,lily_of_the_valley=F0F0F0," +
                "wither_rose=2A2C1A,torchflower=E3812C,pink_petals=F5B4D0,wildflowers=EED754,sunflower=F6CA1C,lilac=B88EBE," +
                "rose_bush=C1312A,peony=D9B8E2,pitcher_plant=6D8FB5,open_eyeblossom=E78B2C,closed_eyeblossom=7A6E7E," +
                "cactus_flower=E896B4,wheat=D0B84B,carrots=EE8A1A,potatoes=7BA83A,beetroots=9C2B2D,nether_wart=81251E," +
                "sweet_berry_bush=3C6C34,brown_mushroom=9A7559,red_mushroom=D93B37,crimson_fungus=8D2C1E,warped_fungus=4AA79A," +
                "crimson_nylium=822020,warped_nylium=2B7266,hanging_roots=9F6E54,pale_hanging_moss=7F8580,spore_blossom=D5679E," +
                "big_dripleaf=6F9133,small_dripleaf=6F9133,big_dripleaf_stem=5A7A2A,sculk=0C1E25,sculk_vein=0C1E25," +
                "sculk_sensor=0F4A57,sculk_catalyst=0F2A30,sculk_shrieker=C6CDA6,amethyst_cluster=A07ECB,pointed_dripstone=816658," +
                "cave_vines=5B7C2A,cave_vines_plant=5B7C2A,weeping_vines=7E0E06,weeping_vines_plant=7E0E06,twisting_vines=15897A," +
                "twisting_vines_plant=15897A,crimson_roots=7E0E1E,warped_roots=159085,nether_sprouts=15897A,cocoa=A4602A," +
                "bamboo=5D8D20,bamboo_sapling=5D8D20,azalea=667F2E,flowering_azalea=7A7C46,mangrove_roots=4A3B2B," +
                "muddy_mangrove_roots=443A30,chorus_plant=5F395F,chorus_flower=977697,vine=3D6E1C,glow_lichen=708575," +
                "sugar_cane=94C065,cactus=5A8A33,melon_stem=8A9A30,pumpkin_stem=8A9A30,attached_melon_stem=8A9A30," +
                "attached_pumpkin_stem=8A9A30,torchflower_crop=7A8A30,pitcher_crop=6D8FB5,frogspawn=6D5E5A," +
                "creaking_heart=5B4F48,dried_ghast=8E8A88,mangrove_propagule=6A9A40";
        for (String e : table.split(",")) {
            int eq = e.indexOf('=');
            if (eq > 0) BASE.put(e.substring(0, eq).trim(), Integer.parseInt(e.substring(eq + 1).trim(), 16));
        }
        for (int i = 0; i < DYES.length; i++) {
            String d = DYES[i];
            int w = WOOL[i];
            BASE.put(d + "_wool", w);
            BASE.put(d + "_carpet", w);
            BASE.put(d + "_bed", w);
            BASE.put(d + "_banner", w);
            BASE.put(d + "_wall_banner", w);
            BASE.put(d + "_candle", w);
            BASE.put(d + "_candle_cake", 0xE8E2D4);
            BASE.put(d + "_shulker_box", w);
            BASE.put(d + "_concrete", CONCRETE[i]);
            BASE.put(d + "_concrete_powder", mix(CONCRETE[i], 0xFFFFFF, 0.25));
            BASE.put(d + "_terracotta", TERRACOTTA[i]);
            BASE.put(d + "_glazed_terracotta", mix(CONCRETE[i], 0xFFFFFF, 0.15));
            BASE.put(d + "_stained_glass", mix(w, 0xFFFFFF, 0.3));
            BASE.put(d + "_stained_glass_pane", mix(w, 0xFFFFFF, 0.3));
        }
        BASE.put("candle", 0xE8D6A8);
        BASE.put("shulker_box", 0x8B5E8B);
        BASE.put("cake", 0xE8E2D4);
    }

    /** Linear blend: t = 0 -> a, t = 1 -> b. */
    public static int mix(int a, int b, double t) {
        int r = (int) Math.round(((a >> 16) & 255) * (1 - t) + ((b >> 16) & 255) * t);
        int g = (int) Math.round(((a >> 8) & 255) * (1 - t) + ((b >> 8) & 255) * t);
        int bl = (int) Math.round((a & 255) * (1 - t) + (b & 255) * t);
        return (r << 16) | (g << 8) | bl;
    }

    /** Scale brightness (f > 1 brightens). */
    public static int shade(int rgb, double f) {
        int r = (int) Math.min(255, Math.max(0, ((rgb >> 16) & 255) * f));
        int g = (int) Math.min(255, Math.max(0, ((rgb >> 8) & 255) * f));
        int b = (int) Math.min(255, Math.max(0, (rgb & 255) * f));
        return (r << 16) | (g << 8) | b;
    }

    /** RGB (0xRRGGBB) for a block string. */
    public static int rgb(String state) {
        String n = BlockState.name(state);
        Integer c = CACHE.get(n);
        if (c != null) return c;
        int v = resolve(n);
        CACHE.put(n, v);
        return v;
    }

    /** True if the block is in the explicit table or derivable (not a hashed guess). */
    public static boolean known(String state) { return resolveOrNull(BlockState.name(state)) != null; }

    private static int resolve(String n) {
        Integer v = resolveOrNull(n);
        if (v != null) return v;
        long h = Hash.of(n);
        int r = 90 + (int) ((h >>> 8) & 95), g = 90 + (int) ((h >>> 16) & 95), b = 90 + (int) ((h >>> 24) & 95);
        return (r << 16) | (g << 8) | b;
    }

    private static final String[] SUFFIXES = {"_wall_hanging_sign", "_hanging_sign", "_wall_sign", "_sign", "_fence_gate",
            "_pressure_plate", "_trapdoor", "_button", "_stairs", "_slab", "_fence", "_wall", "_door", "_shelf"};

    private static Integer resolveOrNull(String n) {
        Integer v = BASE.get(n);
        if (v != null) return v;
        if (n.startsWith("waxed_")) return resolveOrNull(n.substring(6));
        if (n.startsWith("infested_")) return resolveOrNull(n.substring(9));
        if (n.startsWith("potted_")) {
            Integer p = resolveOrNull(n.substring(7));
            return p != null ? mix(p, 0x7C4536, 0.45) : 0x7C4536;
        }
        for (String s : SUFFIXES) {
            if (!n.endsWith(s)) continue;
            String base = n.substring(0, n.length() - s.length());
            for (String w : WOODS) if (base.equals(w)) return BASE.get(w + "_planks");
            for (String cand : new String[]{base, base + "s", base + "_block", base + "_planks", base.replace("_tile", "_tiles"),
                    base.replace("_brick", "_bricks")}) {
                Integer b = BASE.get(cand);
                if (b != null) return b;
            }
            if (base.startsWith("polished_") || base.startsWith("smooth_") || base.startsWith("cut_")) {
                Integer b = resolveOrNull(base.substring(base.indexOf('_') + 1));
                if (b != null) return b;
            }
        }
        if (n.endsWith("_wood") || n.endsWith("_hyphae")) {
            String base = n.substring(0, n.lastIndexOf('_'));
            Integer b = BASE.get(base + "_log");
            if (b == null) b = BASE.get(base + "_stem");
            if (b != null) return b;
        }
        if (n.endsWith("_sapling") || n.endsWith("_propagule")) return 0x4F8A2A;
        if (n.endsWith("_leaves")) return 0x4A8A2A;
        if (n.endsWith("_log") || n.endsWith("_stem")) return 0x5A4428;
        if (n.endsWith("_planks")) return 0xA2834F;
        if (n.contains("coral")) return n.startsWith("dead_") ? 0x8A8480 : 0xD05A8A;
        if (n.endsWith("_ore")) return n.startsWith("deepslate") ? 0x55555A : 0x808080;
        if (n.endsWith("_head") || n.endsWith("_skull")) return 0x9A9A8A;
        if (n.endsWith("_bulb") || n.endsWith("_grate") || n.endsWith("_lantern") || n.endsWith("_chain")) {
            for (String p : new String[]{"exposed_", "weathered_", "oxidized_"}) if (n.startsWith(p)) return resolveOrNull(p + "copper");
            return BASE.get("copper_block");
        }
        return null;
    }
}
