package com.skyclone.mod.combatzones;

import com.skyclone.api.Sky;
import com.skyclone.api.combat.DamageContext;
import com.skyclone.api.event.SkyEvents;
import com.skyclone.api.feature.Features;
import com.skyclone.api.item.ItemData;
import com.skyclone.api.item.ItemDef;
import com.skyclone.api.item.ItemStatSheet;
import com.skyclone.api.loot.LootSource;
import com.skyclone.api.mob.MobDef;
import com.skyclone.api.stat.Stat;
import com.skyclone.api.stat.StatMap;
import com.skyclone.api.ui.Icons;
import com.skyclone.api.ui.Menu;
import com.skyclone.api.ui.Msg;
import com.skyclone.api.ui.Text;
import com.skyclone.mod.combatzones.gen.EndGenerator;
import org.bukkit.Bukkit;
import org.bukkit.Location;
import org.bukkit.Material;
import org.bukkit.Particle;
import org.bukkit.Sound;
import org.bukkit.block.Block;
import org.bukkit.entity.LivingEntity;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.Listener;
import org.bukkit.event.block.Action;
import org.bukkit.event.player.PlayerInteractEvent;
import org.bukkit.inventory.EquipmentSlot;
import org.bukkit.inventory.ItemStack;
import org.bukkit.inventory.PlayerInventory;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Random;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Keeper Ixo on the Void Ledge (the reference's Tyzzo on the Void Slate, research 03 sec 14.5): the
 * <b>Draconic Altar</b> (sacrifice dragon loot for Dragon Essence, with a chance of Ritual Residue) and the
 * <b>Dragon Essence shop</b> (nine permanent perks), plus every perk's effect. Numbers: {@link DraconicMath}.
 *
 * <p>Essence lives in the reforges module's essence pouch ({@code Features.Essence}, type {@code DRAGON}).
 * Earned essence is given as {@code ESSENCE_DRAGON} items through {@code Sky.loot().give}, which the pouch
 * absorbs; perks are paid from the pouch first and from essence items in the inventory second.
 */
final class DragonEssence implements Listener {

    static final String ESSENCE = "DRAGON", ESSENCE_ITEM = "ESSENCE_DRAGON", RESIDUE = "RITUAL_RESIDUE";
    static final Set<String> DRAGON_REFORGES = Set.of("fabled", "renowned", "spiked");

    private final CombatzonesModule m;
    private final Random rng = new Random();
    private final Map<String, Set<String>> typeCache = new ConcurrentHashMap<>();
    private final Map<java.util.UUID, Long> clickGate = new HashMap<>();

    DragonEssence(CombatzonesModule m) {
        this.m = m;
        // ---- perk effects
        Sky.combat().addModifier("combatzones_dragon_perks", 400, this::modify);
        Sky.stats().addProvider("combatzones_dragon_perks", this::stats);
        Sky.abilities().addCostModifier("combatzones_dragon_infusion", (use, cost) -> {
            if (use.ability() == null || !"weapons_dragon_rage".equals(use.ability().id)) return cost;
            double pct = value(use.player(), "edrag_cd");
            return pct <= 0 ? cost : cost * Math.max(0, 1 - pct / 100.0);
        });
    }

    // ============================================================================================ levels + values

    int level(Player p, String perk) {
        if (p == null || !Sky.profiles().isReady(p)) return 0;
        return m.data(p).dragonPerks.getOrDefault(perk, 0);
    }

    /** The perk's current effect for this player (0 when not bought or perks are switched off). */
    double value(Player p, String perk) {
        if (!m.dials.perksEnabled.get()) return 0;
        int lvl = level(p, perk);
        if (lvl <= 0) return 0;
        var d = m.dials.perkPerLevel.get(perk);
        DraconicMath.Perk pk = DraconicMath.perk(perk);
        return (d == null ? (pk == null ? 0 : pk.perLevel()) : d.get()) * lvl;
    }

    Set<String> typesOf(MobDef d) {
        if (d == null || d.id == null) return Set.of();
        return typeCache.computeIfAbsent(d.id, k -> {
            List<String> stated = new ArrayList<>();
            if (d.extra != null) for (String key : new String[]{"mobTypes", "types"})
                if (d.extra.has(key) && d.extra.get(key).isJsonArray())
                    d.extra.getAsJsonArray(key).forEach(e -> { if (e.isJsonPrimitive()) stated.add(e.getAsString()); });
            return MobTypeCatalog.of(d.entity, d.id, d.slayer, d.has("sea_creature"), stated);
        });
    }

    private MobDef defOf(LivingEntity le) {
        if (le == null) return null;
        try { return Sky.mobs().isSkyMob(le) ? Sky.mobs().defOf(le) : null; } catch (RuntimeException e) { return null; }
    }

    // ============================================================================================ perk effects

    /** Heavy Hand (+flat vs Ender) and Unbridled Rage (+% vs an enraged dragon). Outgoing player hits only. */
    private void modify(DamageContext ctx) {
        Player p = ctx.playerAttacker();
        if (p == null || ctx.victim == null || ctx.cancelled) return;
        MobDef d = defOf(ctx.victim);
        if (d == null) return;
        if (typesOf(d).contains("ender") && ctx.kind != DamageContext.Kind.MAGIC) {
            double flat = value(p, "flat_damage_vs_ender");
            if (flat > 0) ctx.flatBonus += flat;
        }
        if (d.id.startsWith("end_dragon_")) {
            double rage = value(p, "unbridled_rage");
            if (rage > 0) {
                double max = Sky.mobs().maxHealth(ctx.victim);
                if (max > 0 && Sky.mobs().health(ctx.victim) < max * 0.5) ctx.additive += rage;
            }
        }
    }

    /** Ender Training (Combat Wisdom on The End) and Twin Fang (dragon reforges stronger). */
    private void stats(Player p, StatMap into) {
        if (!Sky.profiles().isReady(p) || !m.dials.perksEnabled.get()) return;
        double wis = value(p, "combat_wisdom_in_end");
        if (wis > 0 && EndGenerator.ID.equals(Sky.worlds().islandOf(p.getWorld()))) into.add(Stat.COMBAT_WISDOM, wis);
        double twin = value(p, "dragon_reforges_buff");
        if (twin <= 0) return;
        PlayerInventory inv = p.getInventory();
        List<ItemStack> worn = new ArrayList<>();
        for (ItemStack a : inv.getArmorContents()) if (a != null) worn.add(a);
        ItemStack hand = inv.getItemInMainHand();
        for (ItemStack it : worn) addReforgeShare(it, twin, into, false);
        if (hand != null && !hand.getType().isAir()) addReforgeShare(hand, twin, into, true);
    }

    private void addReforgeShare(ItemStack it, double pct, StatMap into, boolean hand) {
        try {
            String id = Sky.items().idOf(it);
            if (id == null) return;
            ItemData data = Sky.items().data(it);
            if (data == null || data.reforge == null || !DRAGON_REFORGES.contains(data.reforge.toLowerCase())) return;
            if (hand && !"fabled".equalsIgnoreCase(data.reforge)) return;          // armour reforges count when worn
            ItemStatSheet sheet = Sky.items().sheet(it);
            for (Stat s : sheet.view().keySet()) {
                double part = sheet.part(s, ItemStatSheet.Source.REFORGE);
                if (part != 0) into.add(s, part * pct / 100.0);
            }
        } catch (RuntimeException ignored) { }
    }

    /** Recharge: an Ender kill refills a share of your mana. */
    @EventHandler
    public void onKill(SkyEvents.MobKill e) {
        if (e.killer == null || e.def == null || !Sky.profiles().isReady(e.killer)) return;
        if (!typesOf(e.def).contains("ender")) return;
        double pct = value(e.killer, "mana_after_ender_kill");
        if (pct > 0) Sky.vitals().addMana(e.killer, Sky.vitals().maxMana(e.killer) * pct / 100.0);
    }

    /** Nestfury: Ferocity against the dragon - each point is a 1% chance of an extra strike 0.2 s later. */
    @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true)
    public void onHit(SkyEvents.Damage e) {
        DamageContext ctx = e.ctx;
        if (ctx.cancelled || ctx.kind != DamageContext.Kind.MELEE || ctx.victim == null) return;
        Player p = ctx.playerAttacker();
        if (p == null) return;
        MobDef d = defOf(ctx.victim);
        if (d == null || !d.id.startsWith("end_dragon_")) return;
        double fero = value(p, "fero_vs_dragons");
        if (fero <= 0) return;
        int strikes = (int) (fero / 100) + (rng.nextDouble() < (fero % 100) / 100.0 ? 1 : 0);
        LivingEntity victim = ctx.victim;
        ItemStack weapon = ctx.weapon;
        for (int i = 1; i <= strikes; i++) {
            Bukkit.getScheduler().runTaskLater(Sky.plugin(), () -> {
                if (!victim.isValid() || !p.isOnline() || Sky.mobs().health(victim) <= 0) return;
                Sky.combat().playerAttack(p, victim, weapon, DamageContext.Kind.FEROCITY);
            }, 4L * i);
        }
    }

    /** Zealot's Fortune (+ the Enderman pet's Zealot Madness) as a multiplier on the Special Zealot chance. */
    double specialZealotMultiplier(Player near) {
        if (near == null) return 1;
        double pct = value(near, "inc_zealots_odds");
        try { pct += Sky.service(Features.Pets.class).abilityValue(near, "enderman_zealot_madness", "a"); } catch (RuntimeException ignored) { }
        return 1 + Math.max(0, pct) / 100.0;
    }

    /** Eyes in the altar placed by players holding Wyrm Caller (online at summon time). */
    int wyrmCallerEyes(Map<java.util.UUID, Integer> eyesBy) {
        int n = 0;
        for (Map.Entry<java.util.UUID, Integer> en : eyesBy.entrySet()) {
            Player p = Bukkit.getPlayer(en.getKey());
            if (p != null && value(p, "increased_sup_chances") > 0) n += en.getValue();
        }
        return n;
    }

    double wyrmCallerPct() {
        var d = m.dials.perkPerLevel.get("increased_sup_chances");
        return d == null ? 25 : d.get();
    }

    // ============================================================================================ essence

    long balance(Player p) {
        long pouch = 0;
        try { pouch = Sky.service(Features.Essence.class).essence(p, ESSENCE); } catch (RuntimeException ignored) { }
        return pouch + Sky.items().count(p, ESSENCE_ITEM);
    }

    /** Pays {@code n} essence: the pouch first, else essence items. All or nothing. */
    boolean spend(Player p, long n) {
        if (n <= 0) return true;
        Features.Essence ess = Sky.service(Features.Essence.class);
        try { if (ess.takeEssence(p, ESSENCE, n)) return true; } catch (RuntimeException ignored) { }
        if (Sky.items().count(p, ESSENCE_ITEM) >= n) { Sky.items().remove(p, ESSENCE_ITEM, (int) Math.min(Integer.MAX_VALUE, n)); return true; }
        return false;
    }

    /** Earned essence: as items through the loot pipeline (the reforges pouch absorbs them), else straight into the pouch. */
    void give(Player p, long n, String why) {
        if (n <= 0) return;
        if (Sky.items().exists(ESSENCE_ITEM)) {
            long left = n;
            while (left > 0) { int c = (int) Math.min(left, 100_000); Sky.loot().give(p, ESSENCE_ITEM, c, LootSource.REWARD); left -= c; }
        } else Sky.service(Features.Essence.class).addEssence(p, ESSENCE, n);
    }

    // ============================================================================================ menus

    Menu keeper(Player p) {
        Menu menu = new Menu("Keeper Ixo", 4).chrome();
        menu.set(11, Icons.of(Material.END_PORTAL_FRAME, "&5Draconic Altar",
                "&7Offer dragon loot to the altar on the", "&7Void Ledge and it gives back", "&dDragon Essence&7. Sometimes it leaves", "&5Ritual Residue &7behind as well.", "",
                "&eClick to sacrifice!"), c -> altar(c.player()).open(c.player()));
        menu.set(13, Icons.of(Material.DRAGON_BREATH, "&dDragon Essence",
                "&7You have: &d" + Text.commasInt(balance(p)), "",
                "&7Earned from:", " &8- &7dragon kills &8(by loot quality)", " &8- &7the Draconic Altar", " &8- &7lost adventurers in the Catacombs"));
        menu.set(15, Icons.of(Material.DRAGON_HEAD, "&dDragon Essence Shop",
                "&7Spend Dragon Essence on permanent", "&7perks against the End and its dragons.", "",
                "&7Perks bought: &a" + perksBought(p) + "&7/&a" + totalTiers(), "", "&eClick to browse!"), c -> shop(c.player()).open(c.player()));
        menu.back("Combat Zones", () -> m.menus.main(p));
        return menu;
    }

    private int perksBought(Player p) { int n = 0; for (int v : m.data(p).dragonPerks.values()) n += v; return n; }

    private static int totalTiers() { int n = 0; for (DraconicMath.Perk pk : DraconicMath.PERKS) n += pk.max(); return n; }

    Menu shop(Player p) {
        Menu menu = new Menu("Dragon Essence Shop", 6).chrome();
        menu.set(4, Icons.of(Material.DRAGON_BREATH, "&dDragon Essence", "&7You have: &d" + Text.commasInt(balance(p)),
                "", "&7Every perk tier also grants", "&bSkyBlock XP&7."));
        int[] slots = {19, 20, 21, 22, 23, 24, 25, 30, 32};
        for (int i = 0; i < DraconicMath.PERKS.size() && i < slots.length; i++) {
            DraconicMath.Perk pk = DraconicMath.PERKS.get(i);
            int lvl = level(p, pk.id());
            List<String> lore = new ArrayList<>();
            lore.add("&7Level: &a" + lvl + "&7/&a" + pk.max());
            lore.add("");
            if (lvl > 0) { lore.add("&7Current:"); lore.addAll(DraconicMath.effectLines(withDial(pk), lvl)); lore.add(""); }
            if (lvl < pk.max()) {
                lore.add("&7Next level:");
                lore.addAll(DraconicMath.effectLines(withDial(pk), lvl + 1));
                lore.add("");
                lore.add("&7Cost: &d" + Text.commasInt(pk.costs()[lvl]) + " Dragon Essence");
                lore.add("&7Reward: &b+" + DraconicMath.sbXp(lvl + 1, pk.max()) + " SkyBlock XP");
                lore.add("");
                lore.add(balance(p) >= pk.costs()[lvl] ? "&eClick to unlock!" : "&cNot enough Dragon Essence!");
            } else lore.add("&a&lMAXED");
            Material icon = Material.matchMaterial(pk.icon());
            ItemStack it = Icons.of(icon == null ? Material.PAPER : icon, (lvl >= pk.max() ? "&a" : lvl > 0 ? "&e" : "&c") + pk.name()
                    + (lvl > 0 ? " " + Text.roman(lvl) : ""), lore);
            menu.set(slots[i], it, c -> buy(c.player(), pk.id()));
        }
        menu.back("Keeper Ixo", () -> keeper(p));
        return menu;
    }

    private DraconicMath.Perk withDial(DraconicMath.Perk pk) {
        var d = m.dials.perkPerLevel.get(pk.id());
        return d == null ? pk : new DraconicMath.Perk(pk.id(), pk.name(), pk.icon(), pk.costs(), d.get(), pk.effect());
    }

    void buy(Player p, String perkId) {
        DraconicMath.Perk pk = DraconicMath.perk(perkId);
        if (pk == null || !Sky.profiles().isReady(p)) return;
        CzData d = m.data(p);
        int lvl = d.dragonPerks.getOrDefault(pk.id(), 0);
        if (lvl >= pk.max()) { Msg.error(p, "That perk is already maxed."); return; }
        int cost = pk.costs()[lvl];
        if (!spend(p, cost)) { Msg.error(p, "You need &d" + Text.commasInt(cost) + " Dragon Essence&c!"); Msg.sound(p, Sound.ENTITY_ENDERMAN_TELEPORT, 0.5f); return; }
        d.dragonPerks.put(pk.id(), lvl + 1);
        Sky.service(Features.SkyLevel.class).award(p, "essence:dragon:" + pk.id() + ":" + (lvl + 1), DraconicMath.sbXp(lvl + 1, pk.max()),
                "Dragon Essence Shop: " + pk.name() + " " + Text.roman(lvl + 1));
        Sky.stats().invalidate(p);
        Sky.profiles().saveNow(p);
        Msg.success(p, "Unlocked &d" + pk.name() + " " + Text.roman(lvl + 1) + "&a!");
        Msg.sound(p, Sound.ENTITY_ENDER_DRAGON_FLAP, 1.4f);
        shop(p).open(p);
    }

    // ---- the altar

    /** What the player could sacrifice right now: one entry per stack, in inventory order. */
    private record Offer(int slot, String id, int amount, String uuid) {}

    private List<Offer> offers(Player p) {
        List<Offer> out = new ArrayList<>();
        ItemStack[] inv = p.getInventory().getStorageContents();
        for (int i = 0; i < inv.length; i++) {
            ItemStack it = inv[i];
            if (it == null || it.getType().isAir()) continue;
            String id = Sky.items().idOf(it);
            if (id == null || !DraconicMath.SACRIFICE.containsKey(id)) continue;
            ItemData data = Sky.items().data(it);
            out.add(new Offer(i, id, it.getAmount(), data == null ? null : data.uuid));
        }
        return out;
    }

    Menu altar(Player p) {
        Menu menu = new Menu("Draconic Altar", 6).chrome();
        CzData d = m.data(p);
        double sac = sacrificerPct(p);
        menu.set(4, Icons.of(Material.END_PORTAL_FRAME, "&5Draconic Altar",
                "&7Click dragon loot from your inventory", "&7below to give it to the altar.", "",
                "&7Left-click: &aone &8| &7Right-click: &athe stack", "",
                "&7Bonus loot chance: &a" + Text.commas(m.dials.altarBonusPerEssence.get() * 100 * 100) + "% &7per &d100 essence",
                "&7(max &a" + Text.commas(m.dials.altarBonusCap.get() * 100) + "%&7)" + (sac > 0 ? " &8| &aSacrificer +" + DraconicMath.fmt(sac) + "%" : ""), "",
                "&7Sacrificed: &e" + d.altarSacrifices + " &8| &7Essence: &d" + Text.commas(d.altarEssence) + " &8| &7Bonuses: &5" + d.altarBonuses));
        List<Offer> offers = offers(p);
        int slot = 10;
        for (Offer o : offers) {
            if (slot > 43) break;
            ItemStack shown = p.getInventory().getItem(o.slot());
            if (shown == null) continue;
            long each = DraconicMath.essenceFor(o.id(), 1, m.dials.altarScale.get());
            List<String> extra = new ArrayList<>();
            extra.add("&7Worth: &d" + Text.commasInt(each) + " Dragon Essence" + (o.amount() > 1 ? " &7each" : ""));
            if (o.amount() > 1) extra.add("&7Whole stack: &d" + Text.commasInt(each * o.amount()));
            extra.add("");
            extra.add(o.uuid() != null ? "&eClick to sacrifice!" : "&eLeft-click: one &8| &eRight-click: stack");
            menu.set(slot, Icons.withLore(shown.clone(), extra), c -> sacrifice(c.player(), o, c.right() || c.shift()));
            slot = ZoneMenus.next(slot);
        }
        if (offers.isEmpty()) menu.set(22, Icons.of(Material.BARRIER, "&cNothing to offer",
                "&7The altar takes dragon fragments,", "&7dragon armour, Wyrmbreath Blades,", "&7dragon claws, horns and scales,", "&7and Ender Dragon pets."));
        menu.set(50, Icons.of(Material.BOOK, "&aWhat the altar takes", accepted()));
        menu.back("Keeper Ixo", () -> keeper(p));
        return menu;
    }

    private List<String> accepted() {
        List<String> l = new ArrayList<>();
        l.add("&7Dragon fragments: &d" + DraconicMath.essenceFor("OLD_FRAGMENT", 1, m.dials.altarScale.get()) + " &8(Superior &d"
                + DraconicMath.essenceFor("SUPERIOR_FRAGMENT", 1, m.dials.altarScale.get()) + "&8)");
        l.add("&7Dragon armour: &d" + DraconicMath.essenceFor("OLD_DRAGON_BOOTS", 1, m.dials.altarScale.get()) + "-"
                + DraconicMath.essenceFor("OLD_DRAGON_CHESTPLATE", 1, m.dials.altarScale.get()) + " &8(Superior x3)");
        Map<String, String> named = new LinkedHashMap<>();
        named.put("ASPECT_OF_THE_DRAGON", "Wyrmbreath Blade");
        named.put("DRAGON_SCALE", "Dragon Scale");
        named.put("DRAGON_CLAW", "Dragon Claw");
        named.put("DRAGON_HORN", "Dragon Horn");
        named.put("ENDER_DRAGON;3", "Ender Dragon pet (Epic)");
        named.put("ENDER_DRAGON;4", "Ender Dragon pet (Legendary)");
        named.forEach((id, n) -> l.add("&7" + (Sky.items().exists(id) ? Text.strip(Sky.items().def(id).name) : n) + ": &d"
                + Text.commasInt(DraconicMath.essenceFor(id, 1, m.dials.altarScale.get()))));
        return l;
    }

    double sacrificerPct(Player p) {
        try { return Math.max(0, Sky.service(Features.Pets.class).abilityValue(p, "endermite_sacrificer", "a")); }
        catch (RuntimeException e) { return 0; }
    }

    private void sacrifice(Player p, Offer o, boolean stack) {
        if (!Sky.profiles().isReady(p)) return;
        long now = System.currentTimeMillis();
        Long last = clickGate.get(p.getUniqueId());
        if (last != null && now - last < 150) return;                 // rate limit: one sacrifice per 3 ticks
        clickGate.put(p.getUniqueId(), now);
        ItemStack cur = p.getInventory().getItem(o.slot());
        String id = cur == null ? null : Sky.items().idOf(cur);
        if (id == null || !id.equals(o.id())) { altar(p).open(p); return; }           // the inventory changed: re-read it
        ItemDef def = Sky.items().def(id);
        String name = def == null ? id : def.rarity.code() + Text.strip(def.name);
        if (o.uuid() != null) {
            ItemData data = Sky.items().data(cur);
            if (data == null || !o.uuid().equals(data.uuid)) { altar(p).open(p); return; }
            long ess = DraconicMath.essenceFor(id, 1, m.dials.altarScale.get());
            Sky.menus().confirm(p, "Sacrifice?", List.of("&7Give " + name + " &7to the altar", "&7for &d" + Text.commasInt(ess) + " Dragon Essence&7?", "", "&cThis cannot be undone."), () -> {
                ItemStack again = p.getInventory().getItem(o.slot());
                ItemData d2 = again == null ? null : Sky.items().data(again);
                if (d2 == null || !o.uuid().equals(d2.uuid)) { Msg.error(p, "That item is no longer where it was."); return; }
                p.getInventory().setItem(o.slot(), null);
                pay(p, name, 1, ess);
                altar(p).open(p);
            }, () -> altar(p).open(p));
            return;
        }
        int n = stack ? cur.getAmount() : 1;
        if (n <= 0) return;
        long ess = DraconicMath.essenceFor(id, n, m.dials.altarScale.get());
        if (ess <= 0) return;
        cur.setAmount(cur.getAmount() - n);
        p.getInventory().setItem(o.slot(), cur.getAmount() <= 0 ? null : cur);
        pay(p, name, n, ess);
        altar(p).open(p);
    }

    private void pay(Player p, String name, int n, long ess) {
        CzData d = m.data(p);
        give(p, ess, "altar");
        d.altarEssence += ess;
        d.altarSacrifices += n;
        Msg.send(p, "&c&lSACRIFICE! &eYou turned " + (n > 1 ? "&a" + n + "x " : "") + name + " &einto &d" + Text.commasInt(ess) + " Dragon Essence&e!");
        double chance = DraconicMath.bonusChance(ess, m.dials.altarBonusPerEssence.get(), m.dials.altarBonusCap.get(), sacrificerPct(p));
        if (rng.nextDouble() < chance && Sky.items().exists(RESIDUE)) {
            Sky.loot().give(p, RESIDUE, 1, LootSource.REWARD);
            d.altarBonuses++;
            Msg.send(p, "&c&lBONUS LOOT! &eYou also received &5Ritual Residue &efrom your sacrifice!");
            Msg.sound(p, Sound.ENTITY_ENDER_DRAGON_GROWL, 1.6f);
        } else Msg.sound(p, Sound.BLOCK_END_PORTAL_FRAME_FILL, 0.8f);
        Location at = altarLocation();
        if (at != null && at.getWorld() == p.getWorld()) at.getWorld().spawnParticle(Particle.DRAGON_BREATH, at.clone().add(0.5, 2.5, 0.5), 30, 0.6, 0.6, 0.6, 0.02);
        Sky.profiles().saveNow(p);
    }

    // ---- the physical altar on the Void Ledge (layout data "altar.draconic", EndGenerator v5)

    Location altarLocation() {
        var lay = Sky.worlds().layout(EndGenerator.ID);
        var w = Sky.worlds().world(EndGenerator.ID);
        if (lay == null || w == null) return null;
        String s = lay.data().get("altar.draconic");
        if (s == null) return null;
        String[] a = s.split(",");
        return new Location(w, Integer.parseInt(a[0]), Integer.parseInt(a[1]), Integer.parseInt(a[2]));
    }

    @EventHandler(priority = EventPriority.NORMAL)
    public void onAltarClick(PlayerInteractEvent e) {
        if (e.getHand() != EquipmentSlot.HAND || e.getAction() != Action.RIGHT_CLICK_BLOCK || e.getClickedBlock() == null) return;
        Block b = e.getClickedBlock();
        if (!EndGenerator.ID.equals(Sky.worlds().islandOf(b.getWorld()))) return;
        Location at = altarLocation();
        if (at == null || at.getWorld() != b.getWorld()) return;
        if (Math.abs(b.getX() - at.getBlockX()) > 2 || Math.abs(b.getZ() - at.getBlockZ()) > 2) return;
        if (b.getY() < at.getBlockY() || b.getY() > at.getBlockY() + 3) return;
        e.setCancelled(true);
        Sky.abilities().consumeClick(e.getPlayer());
        if (Sky.profiles().isReady(e.getPlayer())) altar(e.getPlayer()).open(e.getPlayer());
    }
}
