package com.skyclone.mod.pets;

import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.skyclone.api.Sky;
import com.skyclone.api.feature.Features;
import com.skyclone.api.item.ItemData;
import com.skyclone.api.item.ItemDef;
import com.skyclone.api.item.Rarity;
import com.skyclone.api.loot.LootSource;
import com.skyclone.api.skill.SkillService;
import com.skyclone.api.skill.SkillType;
import com.skyclone.api.stat.Stat;
import com.skyclone.api.ui.Msg;
import com.skyclone.api.ui.Text;
import com.skyclone.mod.pets.PetData.OwnedPet;
import com.skyclone.mod.pets.PetDefs.HeldItem;
import com.skyclone.mod.pets.PetDefs.PetDef;
import com.skyclone.mod.pets.PetDefs.Tier;
import org.bukkit.Bukkit;
import org.bukkit.Sound;
import org.bukkit.entity.Player;
import org.bukkit.inventory.ItemStack;

import java.util.ArrayList;
import java.util.EnumMap;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

/**
 * Everything that changes a player's pets. Also the {@link Features.Pets} implementation other
 * modules see. Main thread only.
 */
public final class PetService implements Features.Pets {

    /** A pet as it currently counts: effective rarity (Tier Boost), level, stat level, data for that rarity. */
    public record View(OwnedPet owned, PetDef def, String tier, int level, int statLevel, Tier t, HeldItem held, boolean boosted) {
        public double num(String token, PetDefs.Ability a) {
            if (token == null) return 0;
            Integer idx = a == null ? null : a.tok.get(token);
            if (idx != null) return t.num(idx, statLevel);
            try { return Double.parseDouble(token); } catch (NumberFormatException e) { return 0; }
        }
        public Rarity rarity() { return Rarity.parse(tier); }
        public boolean maxed() { return level >= def.maxLevel; }
    }

    private final PetsModule mod;
    private final PetDefs defs;

    PetService(PetsModule mod, PetDefs defs) {
        this.mod = mod;
        this.defs = defs;
    }

    // ---- data access -----------------------------------------------------------------------

    /** The member's pet data, or null before ProfileReady (never touch a throwaway object). */
    public PetData data(Player p) {
        if (p == null || !Sky.profiles().isReady(p)) return null;
        return Sky.profiles().member(p, "pets", PetData.class);
    }

    public View view(OwnedPet o) {
        if (o == null) return null;
        PetDef d = defs.pet(o.type);
        if (d == null) return null;
        String tier = d.has(o.tier) ? o.tier : d.lowest();
        HeldItem h = o.heldItem == null ? null : defs.held.get(o.heldItem);
        boolean boosted = false;
        if (h != null && hasFx(h, "tier_boost")) {
            String nx = PetMath.next(tier);
            if (nx != null && d.has(nx)) { tier = nx; boosted = true; }
        }
        int level = defs.levelOf(d, tier, o.exp);
        int sl = PetMath.statLevel(d.curve, level);
        return new View(o, d, tier, level, sl, d.tiers.get(tier), h, boosted);
    }

    public View active(Player p) {
        PetData d = data(p);
        if (d == null || d.active == null) return null;
        OwnedPet o = d.find(d.active);
        if (o == null) { d.active = null; return null; }
        return view(o);
    }

    static boolean hasFx(HeldItem h, String type) {
        if (h == null) return false;
        for (JsonObject o : h.fx) if (type.equals(str(o, "t"))) return true;
        return false;
    }

    static String str(JsonObject o, String k) {
        JsonElement e = o.get(k);
        return e == null || e.isJsonNull() ? null : e.getAsString();
    }

    // ---- stats of a pet --------------------------------------------------------------------

    /**
     * The pet's own stat lines after its held item's multipliers ("Increases the pet's Strength by
     * 80%") and any active base-stat boost ({@code extraBasePct}, e.g. festival/Bingo Chimera).
     */
    public Map<Stat, Double> petStats(View v, double extraBasePct) {
        Map<Stat, Double> out = new EnumMap<>(Stat.class);
        if (v == null || v.t() == null) return out;
        Map<String, Double> mult = new HashMap<>();
        double all = 0;
        if (v.held() != null) for (JsonObject fx : v.held().fx) {
            if (!"pet_mult".equals(str(fx, "t"))) continue;
            double pct = fx.get("v").getAsDouble();
            JsonElement st = fx.get("stats");
            if (st.isJsonPrimitive()) all += pct;
            else for (JsonElement s : st.getAsJsonArray()) mult.merge(s.getAsString(), pct, Double::sum);
        }
        for (Map.Entry<String, Double> e : v.t().s100.entrySet()) {
            Stat s = Stat.parse(e.getKey());
            if (s == null) continue;
            double base = PetMath.lerp(v.t().s1.getOrDefault(e.getKey(), 0.0), e.getValue(), v.statLevel());
            double m = 1 + (all + mult.getOrDefault(e.getKey(), 0.0) + extraBasePct) / 100.0;
            out.put(s, base * m);
        }
        return out;
    }

    /** Display-only stats this game has no Stat for (Overbloom, Potato Fortune...). */
    public Map<String, Double> extraStats(View v) {
        Map<String, Double> out = new java.util.LinkedHashMap<>();
        if (v == null || v.t() == null) return out;
        for (Map.Entry<String, Double> e : v.t().x100.entrySet()) {
            out.put(e.getKey(), PetMath.lerp(v.t().x1.getOrDefault(e.getKey(), 0.0), e.getValue(), v.statLevel()));
        }
        return out;
    }

    public double totalFor(View v, int level) { return defs.totalXpFor(v.def(), v.tier(), level); }

    public double maxXpOf(View v) { return defs.maxXp(v.def(), v.tier()); }

    public PetDefs defs() { return defs; }

    // ---- XP ----------------------------------------------------------------------------------

    /** Held-item pet XP boost for a skill, in percent. */
    double xpBoost(View v, String skill) {
        if (v == null || v.held() == null) return 0;
        double b = 0;
        for (JsonObject fx : v.held().fx) {
            if (!"xp_boost".equals(str(fx, "t"))) continue;
            String s = str(fx, "skill");
            if ("ALL".equals(s) || skill.equals(s)) b += fx.get("v").getAsDouble();
        }
        return b;
    }

    /** Beastmaster Crest ids, best first, and their Pet Exp Boost % (accessories hand-off: the XP part is the pets module's). */
    static final String[][] CRESTS = {{"BEASTMASTER_CREST_LEGENDARY", "0.5"}, {"BEASTMASTER_CREST_EPIC", "0.4"},
            {"BEASTMASTER_CREST_RARE", "0.3"}, {"BEASTMASTER_CREST_UNCOMMON", "0.2"}, {"BEASTMASTER_CREST_COMMON", "0.1"}};

    /** The best Beastmaster Crest the player counts (bag or inventory), or null. */
    String crest(Player p) {
        if (!Sky.has(Features.Accessories.class)) return null;
        try {
            Features.Accessories acc = Sky.service(Features.Accessories.class);
            for (String[] c : CRESTS) if (acc.has(p, c[0])) return c[0];
        } catch (RuntimeException ignored) { }
        return null;
    }

    /** Pet Exp Boost % from the best Beastmaster Crest the player counts (0 without accessories). */
    double crestBoost(Player p) {
        if (!Sky.has(Features.Accessories.class)) return 0;
        try {
            Features.Accessories acc = Sky.service(Features.Accessories.class);
            for (String[] c : CRESTS) if (acc.has(p, c[0])) return Double.parseDouble(c[1]);
        } catch (RuntimeException ignored) { }
        return 0;
    }

    /** Skill XP was gained: feed the summoned pet and the Exp Share pets (research 06 §6.3). */
    public void onSkillXp(Player p, SkillType skill, double amount) {
        if (amount <= 0 || skill == null || skill == SkillType.TAMING) return;
        PetData d = data(p);
        if (d == null) return;
        View v = active(p);
        if (v == null) return;
        int taming = tamingLevel(p);
        double perk = mod.dialPetXpPerk();
        double xp = PetMath.petXp(amount, skill.name(), v.def().skill, taming, xpBoost(v, skill.name()) + perk + crestBoost(p), v.def().xpMult)
                * mod.dialXpScale();
        if ("KUUDRA".equals(v.def().id) && !"crimson_isle".equals(island(p)) && !"kuudra".equals(island(p))) xp = 0;
        if (xp <= 0) return;
        gain(p, v.owned(), xp, true);
        double taming2 = xp * mod.dialTamingRatio();
        if (taming2 > 0) Sky.service(SkillService.class).addXp(p, SkillType.TAMING, taming2, "pets:" + v.def().id);
        shareXp(p, d, v, xp, taming);
    }

    private void shareXp(Player p, PetData d, View active, double xp, int taming) {
        boolean perk = Sky.service(Features.Mayors.class).perkActive(mod.dialSharePerk());
        int slots = shareSlots(perk);
        int n = 0;
        for (String id : new ArrayList<>(d.expShare)) {
            if (n++ >= slots) break;
            if (id.equals(active.owned().uuid)) continue;
            OwnedPet o = d.find(id);
            if (o == null) { d.expShare.remove(id); continue; }
            View sv = view(o);
            if (sv == null) continue;
            double rate = PetMath.shareRate(taming, hasFx(sv.held(), "share"), perk, 0);
            if (rate > 0) gain(p, o, xp * rate / 100.0, false);
        }
    }

    public int shareSlots(boolean perk) { return mod.dialShareSlots() + (perk ? 2 : 0); }

    /** Add raw pet XP to one owned pet, clamp at max level, announce level-ups. */
    public void gain(Player p, OwnedPet o, double xp, boolean announce) {
        if (o == null || xp <= 0 || Double.isNaN(xp) || Double.isInfinite(xp)) return;
        View before = view(o);
        if (before == null) return;
        double cap = defs.maxXp(before.def(), before.tier());
        if (o.exp >= cap) return;
        o.exp = Math.min(cap, o.exp + xp);
        View after = view(o);
        if (after.level() > before.level()) {
            if (announce || mod.dialAnnounceShare()) {
                Msg.send(p, "&aYour " + after.rarity().code() + after.def().name + " &aleveled up to level &9" + after.level() + "&a!");
                Msg.sound(p, Sound.ENTITY_PLAYER_LEVELUP, 1.4f);
            }
            if (o.uuid.equals(activeUuid(p))) {
                mod.display().refresh(p);
                Sky.stats().invalidate(p);
            }
            mod.effects().invalidate(p);
        }
    }

    private String activeUuid(Player p) {
        PetData d = data(p);
        return d == null ? null : d.active;
    }

    int tamingLevel(Player p) {
        try { return Sky.service(SkillService.class).level(p, SkillType.TAMING); } catch (RuntimeException e) { return 0; }
    }

    static String island(Player p) {
        try { return Sky.worlds().islandOf(p.getLocation()); } catch (RuntimeException e) { return null; }
    }

    // ---- summon --------------------------------------------------------------------------------

    public boolean summon(Player p, String uuid) {
        PetData d = data(p);
        if (d == null) return false;
        OwnedPet o = d.find(uuid);
        if (o == null) return false;
        View v = view(o);
        if (v == null) return false;
        d.active = o.uuid;
        Msg.send(p, "&aYou summoned your " + v.rarity().code() + v.def().name + "&a!");
        Msg.sound(p, Sound.ENTITY_CHICKEN_EGG, 1.4f);
        changed(p);
        return true;
    }

    public void despawn(Player p, boolean tell) {
        PetData d = data(p);
        if (d == null || d.active == null) return;
        View v = active(p);
        d.active = null;
        if (tell && v != null) Msg.send(p, "&aYou despawned your " + v.rarity().code() + v.def().name + "&a!");
        changed(p);
    }

    void changed(Player p) {
        if (mod.ride() != null) mod.ride().check(p);
        if (mod.forms() != null) mod.forms().check(p);
        mod.effects().invalidate(p);
        mod.display().refresh(p);
        Sky.stats().invalidate(p);
        mod.updateScore(p);
    }

    // ---- items <-> menu ------------------------------------------------------------------------

    /** Read a pet item into an OwnedPet (without removing it). Null when not a pet item. */
    public OwnedPet fromItem(ItemStack stack) {
        String id = Sky.items().idOf(stack);
        String[] parsed = defs.parseItemId(id);
        ItemDef def = id == null ? null : Sky.items().def(id);
        if (parsed == null && def != null && def.extra != null && def.extra.has("petType")) {
            parsed = new String[]{def.extra.get("petType").getAsString(), def.extra.get("petTier").getAsString()};
        }
        if (parsed == null) return null;
        ItemData data = Sky.items().data(stack);
        OwnedPet o = new OwnedPet();
        o.type = parsed[0];
        o.tier = parsed[1];
        o.uuid = data.uuid != null ? data.uuid : UUID.randomUUID().toString();
        JsonObject info = data.extra != null && data.extra.has("petInfo") ? data.extra.getAsJsonObject("petInfo") : null;
        if (info != null) {
            if (info.has("exp")) o.exp = Math.max(0, info.get("exp").getAsDouble());
            if (info.has("heldItem") && !info.get("heldItem").isJsonNull()) o.heldItem = info.get("heldItem").getAsString();
            if (info.has("candyUsed")) o.candyUsed = Math.max(0, Math.min(10, info.get("candyUsed").getAsInt()));
            readCounters(info, o);
            if (info.has("skin") && !info.get("skin").isJsonNull()) o.skin = info.get("skin").getAsString();
        }
        if (o.heldItem != null && !defs.held.containsKey(o.heldItem)) o.heldItem = null;
        if (o.skin != null && (defs.skins.get(o.skin) == null || !defs.skins.get(o.skin).fits(o.type))) o.skin = null;
        o.obtained = System.currentTimeMillis();
        return o;
    }

    /** Per-pet counters carried on a pet item (Ember Ward kills...). Non-negative ints only. */
    static void readCounters(JsonObject info, OwnedPet o) {
        if (info == null || !info.has("counters") || !info.get("counters").isJsonObject()) return;
        for (Map.Entry<String, JsonElement> e : info.getAsJsonObject("counters").entrySet()) {
            try {
                int n = e.getValue().getAsInt();
                if (n > 0) {
                    if (o.counters == null) o.counters = new java.util.LinkedHashMap<>();
                    o.counters.put(e.getKey(), n);
                }
            } catch (RuntimeException ignored) { }
        }
    }

    /** Right-click a pet item: move it into the Pets menu. */
    public boolean learn(Player p, ItemStack hand) {
        PetData d = data(p);
        if (d == null) return false;
        OwnedPet o = fromItem(hand);
        if (o == null) return false;
        if (d.find(o.uuid) != null) {
            Msg.error(p, "You already have this exact pet in your pets menu.");
            return true;
        }
        if (d.pets.size() >= mod.dialMaxPets()) {
            Msg.error(p, "Your pets menu is full! (" + mod.dialMaxPets() + " pets)");
            return true;
        }
        hand.setAmount(hand.getAmount() - 1);
        d.pets.add(o);
        View v = view(o);
        Msg.success(p, "Successfully added " + v.rarity().code() + v.def().name + " &ato your pet menu!");
        Sky.profiles().saveNow(p);
        changed(p);
        return true;
    }

    /** Build the pet item for an owned pet (does not touch data). */
    public ItemStack toItemStack(OwnedPet o) {
        View v = view(o);
        PetDef d = defs.pet(o.type);
        String tier = d.has(o.tier) ? o.tier : d.lowest();
        ItemStack it = Sky.items().create(d.itemId(tier), 1);
        Sky.items().update(it, data -> {
            JsonObject info = new JsonObject();
            info.addProperty("type", o.type);
            info.addProperty("tier", tier);
            info.addProperty("exp", o.exp);
            if (o.heldItem != null) info.addProperty("heldItem", o.heldItem);
            info.addProperty("candyUsed", o.candyUsed);
            if (o.skin != null) info.addProperty("skin", o.skin);
            if (o.counters != null && !o.counters.isEmpty()) {
                JsonObject c = new JsonObject();
                o.counters.forEach(c::addProperty);
                info.add("counters", c);
            }
            data.extra.add("petInfo", info);
        });
        return it;
    }

    /** Pets menu -> inventory. */
    public boolean convert(Player p, String uuid) {
        PetData d = data(p);
        if (d == null) return false;
        OwnedPet o = d.find(uuid);
        if (o == null) return false;
        if (p.getInventory().firstEmpty() < 0) {
            Msg.error(p, "Your inventory is full!");
            return false;
        }
        ItemStack it = toItemStack(o);
        if (uuid.equals(d.active)) d.active = null;
        d.pets.remove(o);
        d.expShare.remove(uuid);
        d.autopet.removeIf(r -> uuid.equals(r.petUuid));
        p.getInventory().addItem(it);
        View v = view(o);
        Msg.success(p, "Your " + v.rarity().code() + v.def().name + " &awas converted into an item!");
        Sky.profiles().saveNow(p);
        changed(p);
        return true;
    }

    /** Admin/other modules: put a pet straight into the menu. */
    public OwnedPet grant(Player p, String type, String tier, double exp, String held) {
        PetData d = data(p);
        PetDef def = defs.pet(type);
        if (d == null || def == null) return null;
        OwnedPet o = new OwnedPet();
        o.uuid = UUID.randomUUID().toString();
        o.type = def.id;
        o.tier = def.has(tier) ? tier : def.lowest();
        o.exp = Math.max(0, Math.min(exp, defs.maxXp(def, o.tier)));
        o.heldItem = held != null && defs.held.containsKey(held) ? held : null;
        o.obtained = System.currentTimeMillis();
        d.pets.add(o);
        changed(p);
        return o;
    }

    // ---- skins (wave 5) ----------------------------------------------------------------------------

    /** The head texture to draw for this pet: its applied skin when it has one that fits, else the pet's own. */
    public String skull(View v) {
        if (v == null) return null;
        String s = v.owned().skin;
        PetDefs.Skin sk = s == null ? null : defs.skins.get(s);
        return sk != null && sk.skull != null && sk.fits(v.def().id) ? sk.skull : v.def().skull;
    }

    /** Right-click a skin item: apply it to the summoned pet (the old skin comes back as an item). */
    public boolean applySkin(Player p, ItemStack hand) {
        String id = Sky.items().idOf(hand);
        PetDefs.Skin sk = id == null ? null : defs.skins.get(id);
        if (sk == null) return false;
        View v = active(p);
        if (v == null) { Msg.error(p, "You need to summon a pet first!"); return true; }
        return applySkinTo(p, v.owned(), hand);
    }

    public boolean applySkinTo(Player p, OwnedPet o, ItemStack hand) {
        String id = Sky.items().idOf(hand);
        PetDefs.Skin sk = id == null ? null : defs.skins.get(id);
        if (sk == null || o == null) return false;
        if (!sk.fits(o.type)) {
            List<String> names = new ArrayList<>();
            for (String t : sk.pets) { PetDef d = defs.pet(t); names.add(d == null ? t : d.name); }
            Msg.error(p, "This skin can only be applied to " + String.join(", ", names) + " pets!");
            return true;
        }
        if (id.equals(o.skin)) { Msg.error(p, "Your pet is already wearing that skin!"); return true; }
        String old = o.skin;
        hand.setAmount(hand.getAmount() - 1);
        o.skin = id;
        if (old != null && Sky.items().exists(old)) Sky.loot().give(p, old, 1, LootSource.OTHER);
        View v = view(o);
        Msg.success(p, "Your " + v.rarity().code() + v.def().name + " &ais now wearing &d" + sk.name + "&a!");
        Msg.sound(p, Sound.BLOCK_AMETHYST_BLOCK_CHIME, 1.4f);
        Sky.profiles().saveNow(p);
        changed(p);
        return true;
    }

    /** Take the skin off: it comes back as its item. */
    public void removeSkin(Player p, OwnedPet o) {
        if (o == null || o.skin == null) return;
        String old = o.skin;
        o.skin = null;
        if (Sky.items().exists(old)) Sky.loot().give(p, old, 1, LootSource.OTHER);
        Msg.success(p, "You took the skin off your pet.");
        Sky.profiles().saveNow(p);
        changed(p);
    }

    // ---- held items and candy -------------------------------------------------------------------

    /** Give the summoned pet the pet item in hand; the old one comes back. */
    public boolean giveHeld(Player p, ItemStack hand) {
        String id = Sky.items().idOf(hand);
        HeldItem h = id == null ? null : defs.held.get(id);
        if (h == null) return false;
        View v = active(p);
        if (v == null) {
            Msg.error(p, "You need to summon a pet first!");
            return true;
        }
        return giveHeldTo(p, v.owned(), hand);
    }

    public boolean giveHeldTo(Player p, OwnedPet o, ItemStack hand) {
        String id = Sky.items().idOf(hand);
        HeldItem h = id == null ? null : defs.held.get(id);
        if (h == null || o == null) return false;
        for (JsonObject fx : h.fx) {
            if ("only".equals(str(fx, "t")) && !o.type.equals(str(fx, "pet"))) {
                PetDef only = defs.pet(str(fx, "pet"));
                Msg.error(p, "This item can only be given to a " + (only == null ? str(fx, "pet") : only.name) + " pet!");
                return true;
            }
        }
        if (id.equals(o.heldItem)) {
            Msg.error(p, "Your pet is already holding that item!");
            return true;
        }
        String old = o.heldItem;
        hand.setAmount(hand.getAmount() - 1);
        o.heldItem = id;
        if (old != null && Sky.items().exists(old)) Sky.loot().give(p, old, 1, LootSource.OTHER);
        View v = view(o);
        ItemDef idef = Sky.items().def(id);
        Msg.success(p, "Your " + v.rarity().code() + v.def().name + " &ais now holding " + idef.rarity.code() + idef.name + "&a.");
        Sky.profiles().saveNow(p);
        changed(p);
        return true;
    }

    public void takeHeld(Player p, OwnedPet o) {
        if (o == null || o.heldItem == null) return;
        String old = o.heldItem;
        o.heldItem = null;
        if (Sky.items().exists(old)) Sky.loot().give(p, old, 1, LootSource.OTHER);
        Msg.success(p, "You took the pet item back.");
        Sky.profiles().saveNow(p);
        changed(p);
    }

    /** Feed carrot candy / pet cake to the summoned pet. */
    public boolean feed(Player p, ItemStack hand) {
        String id = Sky.items().idOf(hand);
        PetDefs.Candy c = id == null ? null : defs.candies.get(id);
        if (c == null) return false;
        View v = active(p);
        if (v == null) {
            Msg.error(p, "You need to summon a pet first!");
            return true;
        }
        return feedTo(p, v.owned(), hand);
    }

    public boolean feedTo(Player p, OwnedPet o, ItemStack hand) {
        String id = Sky.items().idOf(hand);
        PetDefs.Candy c = id == null ? null : defs.candies.get(id);
        View v = view(o);
        if (c == null || v == null) return false;
        if (v.maxed()) {
            Msg.error(p, "This pet is already at its max level!");
            return true;
        }
        if (c.counts && o.candyUsed >= 10) {
            Msg.error(p, "This pet has already eaten 10 candies and can't eat any more!");
            return true;
        }
        hand.setAmount(hand.getAmount() - 1);
        if (c.counts) o.candyUsed++;
        Msg.success(p, "Your " + v.rarity().code() + v.def().name + " &agained &e" + Text.commas(c.xp) + " &apet XP!");
        gain(p, o, c.xp, true);
        mod.display().refresh(p);
        return true;
    }

    // ---- Pet Score -------------------------------------------------------------------------------

    public int score(Player p) {
        PetData d = data(p);
        if (d == null) return 0;
        Map<String, Integer> best = new HashMap<>();
        for (OwnedPet o : d.pets) best.merge(o.type, PetMath.scoreOf(o.tier), Math::max);
        if (d.kat != null && d.kat.pet != null) best.merge(d.kat.pet.type, PetMath.scoreOf(d.kat.pet.tier), Math::max);
        int s = 0;
        for (int v : best.values()) s += v;
        return s;
    }

    public int scoreMagicFind(Player p) { return PetMath.scoreMagicFind(score(p), defs.meta.scoreTable); }

    // ---- Features.Pets -------------------------------------------------------------------------

    @Override public String activeType(Player p) {
        View v = active(p);
        return v == null ? null : v.def().id;
    }

    @Override public int activeLevel(Player p) {
        View v = active(p);
        return v == null ? 0 : v.level();
    }

    @Override public String activeRarity(Player p) {
        View v = active(p);
        return v == null ? null : v.tier();
    }

    @Override public void addPetXp(Player p, double xp, String skillSource) {
        View v = active(p);
        if (v == null || xp <= 0) return;
        gain(p, v.owned(), xp, true);
        PetData d = data(p);
        if (d != null) shareXp(p, d, v, xp, tamingLevel(p));
    }

    @Override public void openMenu(Player p) { mod.menus().openPets(p, 0); }

    /**
     * Value of one ability number for the player's summoned pet (0 when the pet lacks it). Ready for
     * other modules once the integrator adds it to Features.Pets (see hand-off "Requests"): e.g.
     * {@code abilityValue(p, "mithril_golem_the_smell_of_powder", "a")} = the % Mithril Powder bonus.
     */
    public double abilityValue(Player p, String abilityKey, String token) {
        View v = active(p);
        if (v == null) return 0;
        // "held:<ITEM_ID>" = 1 while the summoned pet holds that item (garden asks for GREEN_BANDANA and applies
        // the effect itself; the held_items.json fx for such items is 'info' so nothing counts twice).
        if (abilityKey != null && abilityKey.startsWith(HELD_PREFIX)) return heldFlag(v.owned().heldItem, abilityKey);
        if (v.t() == null) return 0;
        for (PetDefs.Ability a : v.t().abilities) if (a.key.equals(abilityKey)) return v.num(token, a);
        return 0;
    }

    static final String HELD_PREFIX = "held:";

    /** 1 when {@code heldItem} is the item named by a {@code "held:<ITEM_ID>"} key, else 0 (pure, unit-tested). */
    static double heldFlag(String heldItem, String abilityKey) {
        if (heldItem == null || abilityKey == null || !abilityKey.startsWith(HELD_PREFIX)) return 0;
        return heldItem.equalsIgnoreCase(abilityKey.substring(HELD_PREFIX.length()).trim()) ? 1 : 0;
    }

    public void runLater(Runnable r) { Bukkit.getScheduler().runTask(Sky.plugin(), r); }
}
