package com.skyclone.mod.pets;

import com.google.gson.Gson;
import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import com.skyclone.api.GameModule;
import com.skyclone.api.Sky;
import com.skyclone.api.command.SkyCommand;
import com.skyclone.api.config.Dials;
import com.skyclone.api.event.SkyEvents;
import com.skyclone.api.feature.Features;
import com.skyclone.api.item.ItemData;
import com.skyclone.api.item.ItemDef;
import com.skyclone.api.item.LoreContributor;
import com.skyclone.api.item.Recipe;
import com.skyclone.api.npc.NpcDef;
import com.skyclone.api.skill.SkillService;
import com.skyclone.api.skill.SkillType;
import com.skyclone.api.stat.Stat;
import com.skyclone.api.ui.Msg;
import com.skyclone.api.ui.Text;
import com.skyclone.api.world.gen.Layout;
import com.skyclone.mod.pets.PetData.OwnedPet;
import com.skyclone.mod.pets.PetService.View;
import org.bukkit.Bukkit;
import org.bukkit.command.CommandSender;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.Listener;
import org.bukkit.event.block.Action;
import org.bukkit.event.player.PlayerChangedWorldEvent;
import org.bukkit.event.player.PlayerInteractEvent;
import org.bukkit.event.player.PlayerQuitEvent;
import org.bukkit.inventory.EquipmentSlot;
import org.bukkit.inventory.ItemStack;

import java.io.InputStream;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

/**
 * Pets: every pet of the reference game (88 types, every rarity), the exact XP curve, stats and
 * ability numbers interpolated by level, abilities that actually work, pet items, candy, Exp Share,
 * Autopet, Pet Score, the Pet Sitter's rarity upgrades, a floating pet at your shoulder.
 * Provides {@link Features.Pets}.
 */
public final class PetsModule implements GameModule, Listener {

    static final String ID = "pets";

    private PetDefs defs;
    private PetService service;
    private PetEffects effects;
    private PetDisplay display;
    private PetMenus menus;
    private PetKat kat;
    private PetRide ride;
    private PetForms forms;
    private final Map<UUID, Long> lastAction = new HashMap<>();
    private final List<String> skippedRecipes = new ArrayList<>();
    private int registeredRecipes;

    private Dials.Dial<Double> xpScale, tamingRatio, katCoinScale, katTimeScale, supplyScale, rideMaxSpeed, speedCap, skinBitsScale;
    private Dials.Dial<Integer> shareSlots, maxPets, maxRules;
    private Dials.Dial<String> sharePerk, xpPerk, katName;
    private Dials.Dial<Boolean> showPets, passivePets, katTaming, announceShare, katRemote;

    @Override public String id() { return ID; }

    PetService service() { return service; }
    PetEffects effects() { return effects; }
    PetDisplay display() { return display; }
    PetMenus menus() { return menus; }
    PetKat kat() { return kat; }
    PetRide ride() { return ride; }
    PetForms forms() { return forms; }

    // ---- dials -----------------------------------------------------------------------------------

    double dialXpScale() { return xpScale.get(); }
    double dialTamingRatio() { return tamingRatio.get(); }
    double dialKatCoinScale() { return katCoinScale.get(); }
    double dialKatTimeScale() { return katTimeScale.get(); }
    double dialSupplyScale() { return supplyScale.get(); }
    double dialRideMaxSpeed() { return rideMaxSpeed.get(); }
    double dialSpeedCap() { return speedCap.get(); }
    int dialShareSlots() { return Math.max(0, shareSlots.get()); }
    int dialMaxPets() { return Math.max(1, maxPets.get()); }
    int dialMaxRules() { return Math.max(0, maxRules.get()); }
    String dialSharePerk() { return sharePerk.get(); }
    double dialPetXpPerk() { return Sky.service(Features.Mayors.class).perkValue(xpPerk.get()); }
    boolean dialShowPets() { return showPets.get(); }
    boolean dialPassivePets() { return passivePets.get(); }
    boolean dialKatTaming() { return katTaming.get(); }
    boolean dialAnnounceShare() { return announceShare.get(); }
    String katName() { return katName.get(); }
    /** Bits price of a skin in the Pet Sitter's shop (data price x dial, never below 1). */
    long skinPrice(PetDefs.Skin sk) { return Math.max(1, Math.round(sk.bits * Math.max(0, skinBitsScale.get()))); }

    // ---- lifecycle -------------------------------------------------------------------------------

    @Override
    public void enable() {
        xpScale = Sky.dials().number("pets.xp-scale", 1.0, "Multiplier on every pet XP gain from skills (1 = reference rate)");
        tamingRatio = Sky.dials().number("pets.taming-xp-ratio", 0.25, "Taming XP granted per pet XP the summoned pet earns (TUNE: the reference ratio is undocumented, research 02 suggests 25%)");
        katCoinScale = Sky.dials().number("pets.kat-coin-scale", 1.0, "Multiplier on Pet Sitter upgrade coin costs");
        katTimeScale = Sky.dials().number("pets.kat-time-scale", 1.0, "Multiplier on Pet Sitter upgrade durations (0 = instant)");
        supplyScale = Sky.dials().number("pets.supply-price-scale", 1.0, "Multiplier on Pet Supplies prices for upgrade materials (TUNE prices)");
        shareSlots = Sky.dials().integer("pets.share-slots", 1, "Exp Share slots every player has (research: 1, +2 while the sharing perk is active)");
        maxPets = Sky.dials().integer("pets.max-pets", 500, "Most pets one pets menu may hold");
        maxRules = Sky.dials().integer("pets.max-autopet-rules", 10, "Most Autopet rules per player");
        sharePerk = Sky.dials().text("pets.share-perk", "diana.sharing_is_caring", "Mayor perk id that adds 2 Exp Share slots and +10% share rate");
        xpPerk = Sky.dials().text("pets.xp-perk", "diana.pet_xp_buff", "Mayor perk id whose perkValue is a % bonus to pet XP");
        katName = Sky.dials().text("pets.kat-name", "&fTabitha", "Display name of the Pet Sitter NPC");
        showPets = Sky.dials().flag("pets.show-pets", true, "Show summoned pets floating at their owner's shoulder");
        passivePets = Sky.dials().flag("pets.passive-pets", true, "Pets whose perks work unsummoned (Grandma Wolf, Spirit, Owl...) apply from the pets menu");
        katTaming = Sky.dials().flag("pets.kat-taming-required", true, "Pet Sitter upgrades need Taming 10/20/25 for Epic/Legendary/Mythic");
        announceShare = Sky.dials().flag("pets.announce-share-levelups", false, "Announce level-ups of Exp Share pets too");
        katRemote = Sky.dials().flag("pets.kat-remote", true, "Allow /petsitter from anywhere (off = visit the Pet Sitter in the Hub)");
        rideMaxSpeed = Sky.dials().number("pets.ride-max-speed", 15.0, "Fastest a ridden pet (Armadillo) may move, in blocks per second on flat ground (15 = about the fastest vanilla horse; walking is 4.3)");
        skinBitsScale = Sky.dials().number("pets.skin-bits-scale", 1.0, "Multiplier on Pet Skin prices in Bits at the Pet Sitter (TUNE: the reference sells skins for gems; here Bits are earned in game)");
        // core's own dial, shared (same key + default returns the existing one): the mount reads Speed through the same cap you walk with
        speedCap = Sky.dials().number("stats.speed-cap", 400, "Highest Speed stat that still makes you faster (genre cap 400)");

        defs = PetDefs.load(f -> Sky.plugin().getResource("data/" + ID + "/" + f));
        Sky.items().loadJson(ID, "items.json");
        service = new PetService(this, defs);
        effects = new PetEffects(this, service);
        display = new PetDisplay(this);
        menus = new PetMenus(this, service);
        kat = new PetKat(this, service);
        ride = new PetRide(this);
        forms = new PetForms(this);

        Sky.provide(Features.Pets.class, service);
        effects.register();
        display.start();
        ride.register();
        forms.register();
        Bukkit.getPluginManager().registerEvents(this, Sky.plugin());

        Sky.items().addLoreContributor("pets", new LoreContributor() {
            @Override public Section section() { return Section.AFTER_STATS; }
            @Override public List<String> lines(ItemDef def, ItemData data) { return petLore(def, data); }
        });

        // wave 5 (core request 3 done): pet items read "[Lvl 23] Bee" like the genre
        Sky.items().addNamePrefix("pets", this::namePrefix);

        Sky.npcs().register(NpcDef.of("pets_sitter", katName.get(), "hub", "npc.pet_sitter").title("&ePET SITTER"), p -> {
            if (!Sky.profiles().isReady(p)) return;
            PetData d = service.data(p);
            if (d != null && d.kat != null && System.currentTimeMillis() >= d.kat.readyAt) kat.claim(p);
            menus.openKat(p);
        });

        Sky.sidebar().tabWidget("pets", 300, this::tabLines);
        registerCommands();
        registerAdmin();
        registerSelfTests();
    }

    @Override
    public void postEnable() {
        // materials the recipes/upgrades need, only where no owning module registered them
        int fb = 0;
        for (ItemDef d : readItems("fallback_items.json")) if (!Sky.items().exists(d.id)) { Sky.items().register(d); fb++; }
        if (fb > 0) Sky.log().info("[pets] registered " + fb + " fallback materials nobody else provided");
        // recipes only when every ingredient exists (a recipe with an unknown id would fail recipes.ingredients-exist)
        JsonArray arr = readJson("recipes.json");
        Gson gson = new Gson();
        if (arr != null) for (JsonElement e : arr) {
            Recipe r = gson.fromJson(e, Recipe.class);
            String miss = missingIngredient(r);
            if (miss != null) { skippedRecipes.add(r.result + " (needs " + miss + ")"); continue; }
            Sky.recipes().register(r);
            registeredRecipes++;
        }
        if (!skippedRecipes.isEmpty()) Sky.log().info("[pets] " + skippedRecipes.size() + " pet recipes skipped (missing ingredients): /sb pets recipes");
    }

    @Override
    public void disable() {
        if (ride != null) ride.stopAll();
        if (forms != null) forms.stopAll();
        if (display != null) display.removeAll();
    }

    private String missingIngredient(Recipe r) {
        if (r.result == null || !Sky.items().exists(r.result)) return r.result;
        List<String> cells = new ArrayList<>();
        if (r.grid != null) for (String c : r.grid) if (c != null && !c.isBlank()) cells.add(c);
        if (r.inputs != null) cells.addAll(r.inputs);
        for (String c : cells) {
            if (c.startsWith("COINS:")) continue;
            String id = kat.splitCost(c)[0];
            if (!Sky.items().exists(id)) return id;
        }
        return null;
    }

    private JsonArray readJson(String file) {
        try (InputStream in = Sky.plugin().getResource("data/" + ID + "/" + file)) {
            if (in == null) return null;
            return JsonParser.parseReader(new InputStreamReader(in, StandardCharsets.UTF_8)).getAsJsonArray();
        } catch (Exception e) {
            Sky.log().warning("[pets] could not read " + file + ": " + e);
            return null;
        }
    }

    private List<ItemDef> readItems(String file) {
        List<ItemDef> out = new ArrayList<>();
        JsonArray a = readJson(file);
        if (a == null) return out;
        Gson g = new Gson();
        for (JsonElement e : a) {
            try {
                JsonObject o = e.getAsJsonObject();
                ItemDef d = ItemDef.of(o.get("id").getAsString(), o.get("name").getAsString(), o.get("material").getAsString(),
                        com.skyclone.api.item.Rarity.parse(o.get("rarity").getAsString()), com.skyclone.api.item.ItemCategory.parse(o.get("category").getAsString()));
                if (o.has("skull") && !o.get("skull").isJsonNull()) d.skull = o.get("skull").getAsString();
                if (o.has("lore")) for (JsonElement l : o.getAsJsonArray("lore")) d.lore.add(l.getAsString());
                if (o.has("tags")) for (JsonElement t : o.getAsJsonArray("tags")) d.tags.add(t.getAsString());
                out.add(d);
            } catch (RuntimeException ex) {
                Sky.log().warning("[pets] bad entry in " + file + ": " + ex);
            }
        }
        return out;
    }

    // ---- item lore ---------------------------------------------------------------------------------

    private List<String> petLore(ItemDef def, ItemData data) {
        if (def == null || def.extra == null || !def.extra.has("petType")) return List.of();
        PetDefs.PetDef pd = defs.pet(def.extra.get("petType").getAsString());
        if (pd == null) return List.of();
        OwnedPet o = new OwnedPet();
        o.uuid = data == null || data.uuid == null ? "item" : data.uuid;
        o.type = pd.id;
        o.tier = def.extra.has("petTier") ? def.extra.get("petTier").getAsString() : pd.lowest();
        JsonObject info = data != null && data.extra != null && data.extra.has("petInfo") ? data.extra.getAsJsonObject("petInfo") : null;
        if (info != null) {
            if (info.has("exp")) o.exp = info.get("exp").getAsDouble();
            if (info.has("heldItem") && !info.get("heldItem").isJsonNull()) o.heldItem = info.get("heldItem").getAsString();
            if (info.has("candyUsed")) o.candyUsed = info.get("candyUsed").getAsInt();
            PetService.readCounters(info, o);
        }
        View v = service.view(o);
        if (v == null || v.t() == null) return List.of();
        List<String> lines = new ArrayList<>(PetLore.lines(service, v, false));   // the level is in the name now (namePrefix)
        lines.add("");
        lines.add("&8Can be upgraded by the Pet Sitter in the Hub!");
        return lines;
    }

    /** "&7[Lvl 23] " for a pet item, from the exp it carries (Tier Boost counted). Pure: runs on every render. */
    String namePrefix(ItemDef def, ItemData data) {
        if (def == null || def.extra == null || !def.extra.has("petType")) return null;
        try {
            PetDefs.PetDef pd = defs.pet(def.extra.get("petType").getAsString());
            if (pd == null) return null;
            OwnedPet o = new OwnedPet();
            o.uuid = "name";
            o.type = pd.id;
            o.tier = def.extra.has("petTier") ? def.extra.get("petTier").getAsString() : pd.lowest();
            JsonObject info = data != null && data.extra != null && data.extra.has("petInfo") ? data.extra.getAsJsonObject("petInfo") : null;
            if (info != null && info.has("exp")) o.exp = Math.max(0, info.get("exp").getAsDouble());
            if (info != null && info.has("heldItem") && !info.get("heldItem").isJsonNull()) o.heldItem = info.get("heldItem").getAsString();
            View v = service.view(o);
            return v == null ? null : "&7[Lvl " + v.level() + "] ";
        } catch (RuntimeException e) {
            return null;
        }
    }

    // ---- listeners -------------------------------------------------------------------------------

    boolean rateOk(Player p) {
        long now = System.currentTimeMillis();
        Long last = lastAction.get(p.getUniqueId());
        if (last != null && now - last < 200) return false;
        lastAction.put(p.getUniqueId(), now);
        return true;
    }

    @EventHandler(priority = EventPriority.NORMAL)
    public void onInteract(PlayerInteractEvent e) {
        if (e.getHand() != EquipmentSlot.HAND) return;
        if (e.getAction() != Action.RIGHT_CLICK_AIR && e.getAction() != Action.RIGHT_CLICK_BLOCK) return;
        Player p = e.getPlayer();
        ItemStack hand = p.getInventory().getItemInMainHand();
        String id = Sky.items().idOf(hand);
        if (id == null) return;
        ItemDef def = Sky.items().def(id);
        boolean pet = defs.parseItemId(id) != null || (def != null && def.extra != null && def.extra.has("petType"));
        boolean held = defs.held.containsKey(id);
        boolean candy = defs.candies.containsKey(id);
        boolean skin = defs.skins.containsKey(id);
        if (!pet && !held && !candy && !skin) return;
        e.setCancelled(true);
        if (!Sky.profiles().isReady(p) || !rateOk(p)) return;
        if (pet) service.learn(p, hand);
        else if (held) service.giveHeld(p, hand);
        else if (skin) service.applySkin(p, hand);
        else service.feed(p, hand);
    }

    @EventHandler
    public void onReady(SkyEvents.ProfileReady e) {
        Player p = e.player;
        PetData d = service.data(p);
        if (d == null) return;
        if (d.active != null && d.find(d.active) == null) d.active = null;
        applyTamingCap(p);
        forms.sanitize(p);
        effects.invalidate(p);
        display.refresh(p);
        updateScore(p);
        display.viewerChanged(p);
        if (d.kat != null && System.currentTimeMillis() >= d.kat.readyAt)
            Msg.send(p, "&e[NPC] " + katName.get() + "&f: Your pet's upgrade is done! Come and collect it.");
    }

    @EventHandler
    public void onUnload(SkyEvents.ProfileUnload e) { cleanup(e.player); }

    @EventHandler
    public void onQuit(PlayerQuitEvent e) { cleanup(e.getPlayer()); }

    private void cleanup(Player p) {
        ride.stop(p, false);
        forms.forget(p);
        display.forget(p.getUniqueId());
        effects.forget(p);
        menus.forget(p);
        lastAction.remove(p.getUniqueId());
    }

    @EventHandler
    public void onWorld(PlayerChangedWorldEvent e) {
        Bukkit.getScheduler().runTaskLater(Sky.plugin(), () -> display.refresh(e.getPlayer()), 5L);
    }

    @EventHandler
    public void onIsland(SkyEvents.IslandChange e) {
        Player p = e.player;
        if (e.to == null) return;
        PetData d = service.data(p);
        if (d == null) return;
        for (PetData.AutoRule r : d.autopet) {
            if (!e.to.equals(r.island) || r.petUuid.equals(d.active) || d.find(r.petUuid) == null) continue;
            String id = r.petUuid;
            Bukkit.getScheduler().runTaskLater(Sky.plugin(), () -> { if (p.isOnline()) service.summon(p, id); }, 10L);
            break;
        }
    }

    // ---- score, collector ------------------------------------------------------------------------

    void updateScore(Player p) {
        PetData d = service.data(p);
        if (d == null) return;
        int s = service.score(p);
        int xp = PetMath.scoreAwardXp(d.bestScore, s, 3);
        if (xp > 0) {
            // ONE award per rise, keyed by the score reached: learning a pet worth 2-6 points used to print
            // "+3 SkyBlock XP (Pet Score)" once per point. Keys never overlap because bestScore only rises
            // (older per-point keys pets.score.1..n stay valid: the next rise starts above them).
            Features.SkyLevel lvl = Sky.service(Features.SkyLevel.class);
            if (lvl != null) lvl.award(p, "pets.score." + s, xp, "Pet Score " + s);
            d.bestScore = s;
        }
    }

    int collectorCap(Player p) {
        PetData d = service.data(p);
        return 50 + (d == null ? 0 : Math.min(10, d.collector.size()));
    }

    void applyTamingCap(Player p) {
        SkillService sk = Sky.service(SkillService.class);
        int want = collectorCap(p);
        if (Sky.has(SkillService.class) && want > sk.cap(p, SkillType.TAMING)) sk.setCap(p, SkillType.TAMING, want);
    }

    void donate(Player p, String uuid, String type) {
        PetData d = service.data(p);
        if (d == null || !rateOk(p)) return;
        OwnedPet o = d.find(uuid);
        if (o == null || uuid.equals(d.active) || d.collector.contains(type)) return;
        d.pets.remove(o);
        d.expShare.remove(uuid);
        d.autopet.removeIf(r -> uuid.equals(r.petUuid));
        d.collector.add(type);
        Sky.profiles().saveNow(p);
        applyTamingCap(p);
        Msg.box(p, "&a&lTAMING CAP RAISED", List.of("&7Your Taming cap is now &a" + collectorCap(p) + "&7."));
        service.changed(p);
    }

    // ---- tab -------------------------------------------------------------------------------------

    private List<String> tabLines(Player p) {
        if (!PetDisplay.setting(p, "tab.pet")) return List.of();   // Settings -> "Pet" tab widget off
        View v = service.active(p);
        if (v == null) return List.of("&e&lPet:", " &7None summoned");
        List<String> l = new ArrayList<>();
        l.add("&e&lPet:");
        l.add(" " + PetLore.title(v));
        if (v.maxed()) l.add(" &bMAX LEVEL");
        else {
            double into = v.owned().exp - service.totalFor(v, v.level());
            double need = service.totalFor(v, v.level() + 1) - service.totalFor(v, v.level());
            l.add(" &e" + PetMath.fmt(Math.floor(into / Math.max(1, need) * 1000) / 10.0) + "% &7to Lvl " + (v.level() + 1));
        }
        return l;
    }

    // ---- commands --------------------------------------------------------------------------------

    private void registerCommands() {
        Sky.commands().register(SkyCommand.of("pets", "Open your pets menu (/pet ride: ride a Rock or Armadillo; /pet morph: take your Rat's form)").alias("pet").category("Pets").playerOnly()
                .run((s, a) -> {
                    if (a.length > 0 && a[0].equalsIgnoreCase("ride")) ride.toggle((Player) s);
                    else if (a.length > 0 && (a[0].equalsIgnoreCase("morph") || a[0].equalsIgnoreCase("form"))) forms.toggleMorph((Player) s);
                    else menus.openPets((Player) s, 0);
                }));
        Sky.commands().register(SkyCommand.of("petsitter", "Open the Pet Sitter (rarity upgrades, pet supplies, collector)")
                .alias("kat").category("Pets").playerOnly().run((s, a) -> {
                    Player p = (Player) s;
                    if (!katRemote.get() && !nearSitter(p)) { Msg.error(p, "Visit the Pet Sitter in the Hub."); return; }
                    PetData d = service.data(p);
                    if (d != null && d.kat != null && System.currentTimeMillis() >= d.kat.readyAt) kat.claim(p);
                    menus.openKat(p);
                }));
        Sky.commands().register(SkyCommand.of("autopet", "Set which pet is summoned on each island").category("Pets").playerOnly()
                .run((s, a) -> menus.openAutopet((Player) s)));
        Sky.commands().register(SkyCommand.of("expshare", "Choose the pets that share your summoned pet's XP").category("Pets").playerOnly()
                .run((s, a) -> menus.openShare((Player) s)));
    }

    private boolean nearSitter(Player p) {
        org.bukkit.Location l = Sky.worlds().anchor("hub", "npc.pet_sitter");
        return l != null && l.getWorld() == p.getWorld() && l.distanceSquared(p.getLocation()) < 15 * 15;
    }

    // ---- admin: /sb pets ... -------------------------------------------------------------------------

    private static void out(CommandSender s, String msg) { s.sendMessage(Text.c(msg)); }

    private Player target(CommandSender s, String name) {
        Player p = Bukkit.getPlayerExact(name);
        if (p == null) out(s, "&cNo online player " + name);
        else if (!Sky.profiles().isReady(p)) { out(s, "&c" + name + "'s profile is not ready"); return null; }
        return p;
    }

    private static final String USAGE = "give <p> <TYPE> [RARITY] [level] [ITEM] | item <p> <TYPE> [RARITY] [level] | xp <p> <amount> | level <p> <lvl> | "
            + "skillxp <p> <SKILL> <amount> | summon <p> <TYPE|#> | despawn <p> | list <p> | held <p> <ITEM|none> | kat <p> status|finish|cancel | "
            + "score <p> | stats <p> | info <TYPE> [RARITY] [level] | recipes | clear <p> | ride <p> | "
            + "skin <p> <SKIN_ID|none> | morph <p> | cheese <p> | tunnel <p>";

    private void registerAdmin() {
        Sky.commands().admin("pets", USAGE, "Pets: grant, level, test abilities, Pet Sitter timers", this::admin, (s, a) -> {
            if (a.length <= 1) return List.of("give", "item", "xp", "level", "skillxp", "summon", "despawn", "list", "held", "kat", "score", "stats", "info", "recipes", "clear", "ride", "skin", "morph", "cheese", "tunnel");
            if (a.length == 3 && (a[0].equals("give") || a[0].equals("item") || a[0].equals("summon"))) return new ArrayList<>(defs.pets.keySet());
            if (a.length == 2 && a[0].equals("info")) return new ArrayList<>(defs.pets.keySet());
            if (a.length == 3 && a[0].equals("skin")) return new ArrayList<>(defs.skins.keySet());
            if (a.length == 4 && (a[0].equals("give") || a[0].equals("item"))) return PetMath.RARITIES;
            return List.of();
        });
    }

    private void admin(CommandSender s, String[] a) {
        if (a.length == 0) { out(s, "&e/sb pets " + USAGE); return; }
        try {
            switch (a[0].toLowerCase()) {
                case "give", "item" -> {
                    Player p = target(s, a[1]);
                    if (p == null) return;
                    PetDefs.PetDef d = defs.pet(a[2].toUpperCase());
                    if (d == null) { out(s, "&cUnknown pet type " + a[2]); return; }
                    String tier = a.length > 3 ? a[3].toUpperCase() : d.rarities.get(d.rarities.size() - 1);
                    if (!d.has(tier)) { out(s, "&c" + d.id + " has no " + tier + " (has " + d.rarities + ")"); return; }
                    int level = a.length > 4 ? Integer.parseInt(a[4]) : 1;
                    double xp = defs.totalXpFor(d, tier, Math.max(1, Math.min(d.maxLevel, level)));
                    String held = a.length > 5 ? a[5].toUpperCase() : null;
                    OwnedPet o = service.grant(p, d.id, tier, xp, held);
                    if (a[0].equalsIgnoreCase("item")) {
                        service.convert(p, o.uuid);
                        out(s, "&aGave " + p.getName() + " a " + tier + " " + d.id + " item at level " + level);
                    } else {
                        out(s, "&aAdded " + tier + " " + d.id + " lvl " + level + " to " + p.getName() + "'s pets menu");
                    }
                }
                case "xp" -> {
                    Player p = target(s, a[1]);
                    if (p == null) return;
                    View v = service.active(p);
                    if (v == null) { out(s, "&cNo summoned pet"); return; }
                    service.gain(p, v.owned(), Double.parseDouble(a[2]), true);
                    View n = service.active(p);
                    out(s, "&a" + v.def().id + " now level " + n.level() + " (" + Text.commasInt(n.owned().exp) + " xp)");
                }
                case "level" -> {
                    Player p = target(s, a[1]);
                    if (p == null) return;
                    View v = service.active(p);
                    if (v == null) { out(s, "&cNo summoned pet"); return; }
                    int lvl = Math.max(1, Math.min(v.def().maxLevel, Integer.parseInt(a[2])));
                    v.owned().exp = defs.totalXpFor(v.def(), v.tier(), lvl);
                    service.changed(p);
                    out(s, "&a" + v.def().id + " set to level " + lvl + " (" + Text.commasInt(v.owned().exp) + " xp)");
                }
                case "skillxp" -> {
                    Player p = target(s, a[1]);
                    if (p == null) return;
                    SkillType sk = SkillType.parse(a[2]);
                    if (sk == null) { out(s, "&cUnknown skill"); return; }
                    View before = service.active(p);
                    double was = before == null ? 0 : before.owned().exp;
                    service.onSkillXp(p, sk, Double.parseDouble(a[3]));
                    View after = service.active(p);
                    out(s, after == null ? "&cNo summoned pet" : "&aPet gained " + PetMath.fmt(after.owned().exp - was) + " xp from " + a[3] + " " + sk.name() + " XP");
                }
                case "summon" -> {
                    Player p = target(s, a[1]);
                    if (p == null) return;
                    PetData d = service.data(p);
                    OwnedPet pick = null;
                    if (a[2].startsWith("#")) {
                        int i = Integer.parseInt(a[2].substring(1));
                        if (i >= 0 && i < d.pets.size()) pick = d.pets.get(i);
                    } else for (OwnedPet o : d.pets) if (o.type.equalsIgnoreCase(a[2]) && (pick == null || o.exp > pick.exp)) pick = o;
                    if (pick == null) { out(s, "&cNo such pet in their menu"); return; }
                    service.summon(p, pick.uuid);
                    out(s, "&aSummoned " + pick.type);
                }
                case "despawn" -> { Player p = target(s, a[1]); if (p != null) { service.despawn(p, true); out(s, "&aDespawned"); } }
                case "ride" -> {
                    Player p = target(s, a[1]);
                    if (p == null) return;
                    ride.toggle(p);
                    out(s, ride.riding(p) ? "&a" + p.getName() + " is riding their pet" : "&e" + p.getName() + " is not riding (see their chat for why)");
                }
                case "skin" -> {
                    Player p = target(s, a[1]);
                    if (p == null) return;
                    View v = service.active(p);
                    if (v == null) { out(s, "&cNo summoned pet"); return; }
                    String id = a[2].toUpperCase();
                    if (id.equals("NONE")) { v.owned().skin = null; service.changed(p); out(s, "&aSkin removed (not refunded)"); return; }
                    PetDefs.Skin sk = defs.skins.get(id);
                    if (sk == null) { out(s, "&cUnknown skin " + id); return; }
                    if (!sk.fits(v.def().id)) { out(s, "&c" + id + " fits " + sk.pets + ", not " + v.def().id); return; }
                    v.owned().skin = id;
                    service.changed(p);
                    out(s, "&a" + p.getName() + "'s " + v.def().id + " now wears " + sk.name);
                }
                case "morph" -> {
                    Player p = target(s, a[1]);
                    if (p == null) return;
                    forms.toggleMorph(p);
                    out(s, forms.morphed(p) ? "&a" + p.getName() + " took their pet's form" : "&e" + p.getName() + " is not morphed (see their chat for why)");
                }
                case "cheese" -> {
                    Player p = target(s, a[1]);
                    if (p == null) return;
                    out(s, forms.forceCheese(p) ? "&aCheese placed near " + p.getName() : "&cNo cheese: summon a Rat and stand near solid ground");
                }
                case "tunnel" -> {
                    Player p = target(s, a[1]);
                    if (p == null) return;
                    out(s, "&e" + forms.tunnelStatus(p));
                }
                case "list" -> {
                    Player p = target(s, a[1]);
                    if (p == null) return;
                    PetData d = service.data(p);
                    out(s, "&e" + p.getName() + " has " + d.pets.size() + " pets, score " + service.score(p) + ", share " + d.expShare.size()
                            + (d.kat != null ? ", sitter: " + d.kat.pet.type + "->" + d.kat.toTier : ""));
                    for (int i = 0; i < d.pets.size(); i++) {
                        View v = service.view(d.pets.get(i));
                        if (v == null) continue;
                        out(s, " &7#" + i + " " + (v.owned().uuid.equals(d.active) ? "&a*" : " ") + PetLore.title(v) + " &8" + v.tier()
                                + (v.owned().heldItem != null ? " &7[" + v.owned().heldItem + "]" : ""));
                    }
                }
                case "held" -> {
                    Player p = target(s, a[1]);
                    if (p == null) return;
                    View v = service.active(p);
                    if (v == null) { out(s, "&cNo summoned pet"); return; }
                    String id = a[2].equalsIgnoreCase("none") ? null : a[2].toUpperCase();
                    if (id != null && !defs.held.containsKey(id)) { out(s, "&cNot a pet item: " + id); return; }
                    v.owned().heldItem = id;
                    service.changed(p);
                    out(s, "&aHeld item set to " + id);
                }
                case "kat" -> {
                    Player p = target(s, a[1]);
                    if (p == null) return;
                    PetData d = service.data(p);
                    String sub = a.length > 2 ? a[2] : "status";
                    switch (sub) {
                        case "finish" -> { kat.finish(p); kat.claim(p); out(s, "&aPet Sitter job finished and claimed"); }
                        case "cancel" -> { kat.cancel(p); out(s, "&aCancelled"); }
                        default -> out(s, d.kat == null ? "&7No pet with the sitter" : "&e" + d.kat.pet.type + " " + d.kat.pet.tier + " -> " + d.kat.toTier
                                + ", ready in " + Text.duration(Math.max(0, d.kat.readyAt - System.currentTimeMillis()) / 1000));
                    }
                }
                case "score" -> {
                    Player p = target(s, a[1]);
                    if (p != null) out(s, "&ePet Score " + service.score(p) + " -> +" + service.scoreMagicFind(p) + " Magic Find");
                }
                case "stats" -> {
                    Player p = target(s, a[1]);
                    if (p == null) return;
                    effects.invalidate(p);
                    List<PetEffects.Src> src = effects.sources(p);
                    out(s, "&e" + src.size() + " active pet effects:");
                    for (PetEffects.Src x : src) out(s, " &7" + (x.a() == null ? "item" : x.a().name) + " &8" + x.fx() + (effects.when(p, x.fx()) ? " &a(on)" : " &c(off)"));
                    for (Stat st : new Stat[]{Stat.STRENGTH, Stat.CRIT_DAMAGE, Stat.DEFENSE, Stat.HEALTH, Stat.INTELLIGENCE, Stat.MAGIC_FIND, Stat.SPEED}) {
                        Double v = Sky.stats().breakdown(p, st).get("pets");
                        out(s, " &7" + st.display + " from pets: &a" + (v == null ? 0 : PetMath.fmt(v)));
                    }
                }
                case "info" -> {
                    PetDefs.PetDef d = defs.pet(a[1].toUpperCase());
                    if (d == null) { out(s, "&cUnknown pet type"); return; }
                    String tier = a.length > 2 ? a[2].toUpperCase() : d.rarities.get(d.rarities.size() - 1);
                    if (!d.has(tier)) { out(s, "&c" + d.id + " rarities: " + d.rarities); return; }
                    int lvl = a.length > 3 ? Integer.parseInt(a[3]) : 100;
                    OwnedPet o = new OwnedPet();
                    o.uuid = "info"; o.type = d.id; o.tier = tier; o.exp = defs.totalXpFor(d, tier, Math.min(d.maxLevel, lvl));
                    View v = service.view(o);
                    out(s, "&e" + d.id + " " + tier + " lvl " + v.level() + " (stat level " + v.statLevel() + ", skill " + d.skill + ", max " + d.maxLevel
                            + ", xp " + Text.commasInt(o.exp) + ", to max " + Text.commasInt(defs.maxXp(d, tier)) + ")");
                    for (String line : PetLore.lines(service, v, false)) out(s, " " + line);
                }
                case "recipes" -> {
                    out(s, "&e" + registeredRecipes + " pet recipes registered, " + skippedRecipes.size() + " skipped:");
                    for (String r : skippedRecipes) out(s, " &7" + r);
                }
                case "clear" -> {
                    Player p = target(s, a[1]);
                    if (p == null) return;
                    PetData d = service.data(p);
                    d.pets.clear(); d.active = null; d.expShare.clear(); d.autopet.clear(); d.kat = null;
                    service.changed(p);
                    out(s, "&aCleared " + p.getName() + "'s pets");
                }
                default -> out(s, "&e/sb pets " + USAGE);
            }
        } catch (ArrayIndexOutOfBoundsException | NumberFormatException ex) {
            out(s, "&cUsage: /sb pets " + USAGE);
        }
    }

    // ---- self-tests ----------------------------------------------------------------------------------

    private void registerSelfTests() {
        Sky.commands().selfTest("pets.data-loaded", () -> {
            if (defs.pets.size() < 88) return "only " + defs.pets.size() + " pet types loaded (expected 88)";
            for (PetDefs.PetDef d : defs.pets.values()) {
                if (d.rarities.isEmpty()) return d.id + " has no rarities";
                for (String r : d.rarities) {
                    PetDefs.Tier t = d.tiers.get(r);
                    if (t == null) return d.id + " has no data for " + r;
                    if (t.n1.length != t.n100.length) return d.id + " " + r + " number arrays differ in length";
                }
                if (d.skull == null) return d.id + " has no head texture";
            }
            if (defs.kat.size() < 200) return "only " + defs.kat.size() + " Pet Sitter steps (expected 217)";
            return null;
        });
        Sky.commands().selfTest("pets.xp-curve", () -> {
            PetDefs.PetDef bee = defs.pet("BEE"), gd = defs.pet("GOLDEN_DRAGON"), bingo = defs.pet("BINGO");
            if (bee == null || gd == null || bingo == null) return "BEE / GOLDEN_DRAGON / BINGO missing";
            String e = expect("Common 100", defs.totalXpFor(bee, "COMMON", 100), 5_624_785);
            if (e == null) e = expect("Uncommon 100", defs.totalXpFor(bee, "UNCOMMON", 100), 8_644_220);
            if (e == null) e = expect("Rare 100", defs.totalXpFor(bee, "RARE", 100), 12_626_665);
            if (e == null) e = expect("Epic 100", defs.totalXpFor(bee, "EPIC", 100), 18_608_500);
            if (e == null) e = expect("Legendary 100", defs.totalXpFor(bee, "LEGENDARY", 100), 25_353_230);
            if (e == null) e = expect("Legendary 2", defs.totalXpFor(bee, "LEGENDARY", 2), 660);
            if (e == null) e = expect("Golden Dragon 200", defs.totalXpFor(gd, "LEGENDARY", 200), 214_023_230);
            if (e == null) e = expect("Bingo Legendary 100 (offset 0)", defs.totalXpFor(bingo, "LEGENDARY", 100), 5_624_785);
            if (e == null && defs.levelOf(bee, "LEGENDARY", 25_353_230) != 100) e = "25,353,230 XP is not level 100";
            if (e == null && defs.levelOf(gd, "LEGENDARY", 1e12) != 200) e = "Golden Dragon does not cap at 200";
            return e;
        });
        Sky.commands().selfTest("pets.stats-formula", () -> {
            PetDefs.PetDef bee = defs.pet("BEE");
            PetDefs.Tier t = bee.tiers.get("LEGENDARY");
            String e = expect("Bee L1 strength", PetMath.lerp(t.s1.get("STRENGTH"), t.s100.get("STRENGTH"), 1), 5.25);
            if (e == null) e = expect("Bee L100 strength", PetMath.lerp(t.s1.get("STRENGTH"), t.s100.get("STRENGTH"), 100), 30);
            if (e == null) e = expect("Bee L100 hive int per player", t.num(0, 100), 10);
            if (e == null) e = expect("Golden Dragon stat level at 150", PetMath.statLevel(defs.pet("GOLDEN_DRAGON").curve, 150), 50);
            if (e == null) e = expect("Golden Dragon stat level at 80", PetMath.statLevel(defs.pet("GOLDEN_DRAGON").curve, 80), 1);
            if (e == null) e = expect("pet XP, matching skill", PetMath.petXp(100, "MINING", "MINING", 0, 0, 1), 100);
            if (e == null) e = expect("pet XP, other skill", PetMath.petXp(300, "FARMING", "MINING", 0, 0, 1), 100);
            if (e == null) e = expect("pet XP, fishing", PetMath.petXp(100, "FISHING", "FISHING", 0, 0, 1), 150);
            if (e == null) e = expect("pet XP, taming 60", PetMath.petXp(100, "MINING", "MINING", 60, 0, 1), 160);
            if (e == null) e = expect("share rate cap", PetMath.shareRate(60, true, true, 50), 47);
            if (e == null) e = expect("Pet Score 100 MF", PetMath.scoreMagicFind(100, defs.meta.scoreTable), 5);
            return e;
        });
        Sky.commands().selfTest("pets.items-exist", () -> {
            List<String> miss = new ArrayList<>();
            for (PetDefs.PetDef d : defs.pets.values()) for (String r : d.rarities) if (!Sky.items().exists(d.itemId(r))) miss.add(d.itemId(r));
            for (String h : defs.held.keySet()) if (!Sky.items().exists(h)) miss.add(h);
            for (String c : defs.candies.keySet()) if (!Sky.items().exists(c)) miss.add(c);
            for (PetDefs.KatStep st : defs.kat.values()) for (String c : st.items) {
                String id = kat.splitCost(c)[0];
                if (!Sky.items().exists(id) && !miss.contains(id)) miss.add(id);
            }
            return miss.isEmpty() ? null : miss.size() + " missing: " + String.join(", ", miss.subList(0, Math.min(15, miss.size())));
        });
        Sky.commands().selfTest("pets.ability-numbers", () -> {
            for (PetDefs.PetDef d : defs.pets.values()) for (String r : d.rarities) {
                PetDefs.Tier t = d.tiers.get(r);
                for (PetDefs.Ability ab : t.abilities) {
                    for (Map.Entry<String, Integer> tk : ab.tok.entrySet())
                        if (tk.getValue() >= t.n100.length) return d.id + " " + r + " " + ab.name + " token " + tk.getKey() + " has no number";
                    OwnedPet o = new OwnedPet();
                    o.uuid = "t"; o.type = d.id; o.tier = r;
                    View v = service.view(o);
                    for (String line : ab.lore) if (PetLore.fill(line, v, ab).contains("{")) return d.id + " " + r + " " + ab.name + " left a placeholder";
                }
            }
            return null;
        });
        Sky.commands().selfTest("pets.lore-renders", () -> {
            for (PetDefs.PetDef d : defs.pets.values()) {
                String id = d.itemId(d.rarities.get(d.rarities.size() - 1));
                if (!Sky.items().exists(id)) return id + " not registered";
                ItemStack it = Sky.items().create(id, 1);
                OwnedPet o = service.fromItem(it);
                if (o == null) return id + " is not read back as a pet";
                if (!o.type.equals(d.id)) return id + " read back as " + o.type;
                ItemStack back = service.toItemStack(o);
                if (!id.equals(Sky.items().idOf(back))) return id + " did not survive menu -> item";
            }
            return null;
        });
        Sky.commands().selfTest("pets.kat-table", () -> {
            for (PetDefs.KatStep st : defs.kat.values()) {
                PetDefs.PetDef d = defs.pet(st.pet);
                if (d == null) return "sitter step for unknown pet " + st.pet;
                if (!d.has(st.from) || !d.has(st.to)) return st.pet + " " + st.from + "->" + st.to + " is not a rarity it has";
                if (PetMath.RARITIES.indexOf(st.to) != PetMath.RARITIES.indexOf(st.from) + 1) return st.pet + " skips a rarity";
            }
            PetDefs.KatStep ed = defs.katStep("ENDER_DRAGON", "EPIC");
            if (ed == null) return "Ender Dragon Epic->Legendary missing";
            if (ed.coins != 400_000_000L) return "Ender Dragon upgrade costs " + ed.coins + " (research: 400,000,000)";
            return null;
        });
        Sky.commands().selfTest("pets.ride-data", () -> {
            for (String[] want : new String[][]{{"ROCK", "sit"}, {"ARMADILLO", "mount"}}) {
                PetDefs.PetDef d = defs.pet(want[0]);
                if (d == null) return want[0] + " missing";
                boolean ok = false;
                for (PetDefs.Ability ab : d.tiers.get(d.rarities.get(d.rarities.size() - 1)).abilities)
                    for (JsonObject fx : ab.fx) if ("ride".equals(PetService.str(fx, "t")) && want[1].equals(PetService.str(fx, "mode"))) ok = true;
                if (!ok) return want[0] + " has no ride/" + want[1] + " effect";
            }
            // wave4-loops: a Lv1 Armadillo at ~350 Speed ran 21.7 b/s. Lv1 = your walking pace, under the live cap.
            double lv1 = PetMath.mountAttr(350, 1, 1.6, dialSpeedCap(), dialRideMaxSpeed()) * PetMath.BLOCKS_PER_ATTR;
            if (lv1 > dialRideMaxSpeed() + 1e-6 || lv1 > 3.5 * 4.317 + 1e-6) return "Lv1 mount at 350 Speed moves " + PetMath.fmt(lv1) + " b/s";
            return null;
        });
        Sky.commands().selfTest("pets.skins", () -> {
            if (defs.skins.size() < 300) return "only " + defs.skins.size() + " pet skins loaded (expected 359)";
            for (PetDefs.Skin sk : defs.skins.values()) {
                if (sk.pets == null || sk.pets.isEmpty()) return sk.id + " fits no pet";
                for (String t : sk.pets) if (defs.pet(t) == null) return sk.id + " fits unknown pet " + t;
                if (sk.skull == null || sk.skull.isBlank()) return sk.id + " has no texture";
                if (sk.bits <= 0) return sk.id + " has no Bits price";
                if (!Sky.items().exists(sk.id)) return sk.id + " is not a registered item";
            }
            return null;
        });
        Sky.commands().selfTest("pets.name-prefix", () -> {
            ItemStack it = Sky.items().create("BEE;4", 1);
            String pre = namePrefix(Sky.items().def("BEE;4"), Sky.items().data(it));
            if (!"&7[Lvl 1] ".equals(pre)) return "a fresh Legendary Bee item reads '" + pre + "', expected '&7[Lvl 1] '";
            OwnedPet o = new OwnedPet();
            o.uuid = "t"; o.type = "BEE"; o.tier = "LEGENDARY"; o.exp = defs.totalXpFor(defs.pet("BEE"), "LEGENDARY", 100);
            ItemStack lvl100 = service.toItemStack(o);
            String p100 = namePrefix(Sky.items().def("BEE;4"), Sky.items().data(lvl100));
            return "&7[Lvl 100] ".equals(p100) ? null : "a level 100 Bee item reads '" + p100 + "'";
        });
        Sky.commands().selfTest("pets.forms-data", () -> {
            String[][] want = {{"RAT", "morph"}, {"RAT", "cheese"}, {"RAT", "rat_blessing"}, {"ROCK", "sail"}, {"ARMADILLO", "tunnel"}};
            for (String[] w : want) {
                PetDefs.PetDef d = defs.pet(w[0]);
                if (d == null) return w[0] + " missing";
                boolean ok = false;
                for (PetDefs.Ability ab : d.tiers.get(d.rarities.get(d.rarities.size() - 1)).abilities)
                    for (JsonObject fx : ab.fx) if (w[1].equals(PetService.str(fx, "t"))) ok = true;
                if (!ok) return w[0] + " has no " + w[1] + " effect";
            }
            return null;
        });
        Sky.commands().selfTest("pets.npc-anchor", () -> {
            Layout l;
            try { l = Sky.worlds().layout("hub"); } catch (RuntimeException ex) { return "hub layout unavailable: " + ex.getMessage(); }
            if (l == null) return "hub layout not built yet (Pet Sitter needs hub anchor npc.pet_sitter)";
            return l.anchors().containsKey("npc.pet_sitter") ? null : "hub has no anchor npc.pet_sitter";
        });
    }

    private static String expect(String what, double got, double want) {
        return Math.abs(got - want) <= Math.max(1e-6, Math.abs(want) * 1e-9) ? null : what + ": got " + PetMath.fmt(got) + ", expected " + PetMath.fmt(want);
    }
}
