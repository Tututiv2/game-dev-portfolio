package com.skyclone.mod.farming;

import com.google.gson.JsonPrimitive;
import com.skyclone.api.Sky;
import com.skyclone.api.item.ItemData;
import com.skyclone.api.item.ItemDef;
import com.skyclone.api.item.ItemStatSheet;
import com.skyclone.api.item.LoreContributor;
import com.skyclone.api.stat.Stat;
import com.skyclone.api.ui.Text;
import org.bukkit.Bukkit;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.Listener;
import org.bukkit.event.inventory.InventoryOpenEvent;
import org.bukkit.event.player.PlayerDropItemEvent;
import org.bukkit.event.player.PlayerItemHeldEvent;
import org.bukkit.event.player.PlayerSwapHandItemsEvent;
import org.bukkit.event.player.PlayerQuitEvent;
import org.bukkit.inventory.ItemStack;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

/**
 * Per-tool progress (Counter, tool level XP) and the lore / stat lines of every farming tool.
 *
 * <p>Writing an item's data re-renders its lore, which is too costly on every crop a fast farmer breaks, so
 * progress is buffered per player and flushed onto the stack every 2 seconds, on slot change and on quit.
 * The buffered amount is added to what the lore shows in menus, so nothing ever looks lost.
 */
final class ToolTracker implements Listener {

    private static final class Pending { String uuid; long count; double xp; }

    private final Map<UUID, Pending> pending = new HashMap<>();

    void start() {
        Sky.items().addLoreContributor("farming_tool", new Lore());
        Sky.items().addStatContributor("farming_tool", this::stats);
        Bukkit.getScheduler().runTaskTimer(Sky.plugin(), this::flushAll, 40L, 40L);
    }

    // ---- buffering ---------------------------------------------------------------------------------

    /** Count {@code items} collected with the held tool (unique tools only: they carry a uuid). */
    void add(Player p, ItemStack tool, String toolUuid, long items, double xp) {
        if (toolUuid == null) return;
        Pending pd = pending.get(p.getUniqueId());
        if (pd != null && !toolUuid.equals(pd.uuid)) { flush(p); pd = null; }
        if (pd == null) { pd = new Pending(); pd.uuid = toolUuid; pending.put(p.getUniqueId(), pd); }
        pd.count += items;
        pd.xp += xp;
    }

    /** Unflushed counter for this tool uuid (for live displays). */
    long pendingCount(Player p, String uuid) {
        Pending pd = pending.get(p.getUniqueId());
        return pd != null && uuid != null && uuid.equals(pd.uuid) ? pd.count : 0;
    }

    double pendingXp(Player p, String uuid) {
        Pending pd = pending.get(p.getUniqueId());
        return pd != null && uuid != null && uuid.equals(pd.uuid) ? pd.xp : 0;
    }

    void flushAll() {
        for (UUID u : new ArrayList<>(pending.keySet())) {
            Player p = Bukkit.getPlayer(u);
            if (p == null) { pending.remove(u); continue; }
            flush(p);
        }
    }

    void flush(Player p) {
        Pending pd = pending.remove(p.getUniqueId());
        if (pd == null || pd.count == 0 && pd.xp == 0) return;
        ItemStack stack = find(p, pd.uuid);
        if (stack == null) return;            // tool already gone (cannot happen through the listeners below)
        apply(p, pd, stack);
    }

    /** Writes a buffer onto {@code stack} (the stack with the buffer's uuid). */
    private void apply(Player p, Pending pd, ItemStack stack) {
        final long add = pd.count;
        final double addXp = pd.xp;
        ItemDef def = Sky.items().def(Sky.items().idOf(stack));
        ToolSpec spec = def == null ? null : ToolSpec.of(def.id, def.extra);
        int before = spec != null && spec.leveling ? level(spec, Sky.items().data(stack).extra, ToolSpec.xpOf(Sky.items().data(stack).extra)) : 0;
        Sky.items().update(stack, d -> {
            d.extra.add(ToolSpec.K_COUNTER, new JsonPrimitive(ToolSpec.counterOf(d.extra) + add));
            d.extra.add(ToolSpec.K_XP, new JsonPrimitive(spec != null && spec.leveling && FarmingModule.M != null
                    ? FortuneMath.cappedXp(ToolSpec.xpOf(d.extra), addXp, cap(spec, d.extra), FarmingModule.M.tables.toolLevelXp)
                    : ToolSpec.xpOf(d.extra) + addXp));
            if (spec != null) writeCropFortune(p, spec, d.extra);
        });
        if (spec != null && spec.leveling) {
            int after = level(spec, Sky.items().data(stack).extra, ToolSpec.xpOf(Sky.items().data(stack).extra));
            if (after > before) {
                com.skyclone.api.ui.Msg.send(p, "&6&lTOOL LEVEL UP! " + Sky.items().def(def.id).name + " &7is now level &a" + after
                        + "&7. &6+" + (after - before) * FarmingModule.M.dials.levelFortune.get() + "☘ Crop Fortune");
                com.skyclone.api.ui.Msg.sound(p, org.bukkit.Sound.ENTITY_PLAYER_LEVELUP, 1.4f);
            }
        }
    }

    /**
     * The Garden's hand-shake (docs/handoff/garden.md, request 3b): the Garden adds {@code extra.crop_fortune} from the
     * tool's ItemDef (its tier fortune, written by the generator) AND from its ItemData. The instance part written here
     * is everything that grows with use: tool level, Logarithmic Counter and Collection Analysis (the player's own
     * collection at the time of the last flush). Keys are the Garden's crop keys ({@link Crop#milestoneKey}).
     */
    static void writeCropFortune(Player p, ToolSpec spec, com.google.gson.JsonObject extra) {
        if (spec.crops.isEmpty()) return;
        FarmingModule m = FarmingModule.M;
        if (m == null) return;
        double lvl = spec.leveling ? FortuneMath.levelFortune(level(spec, extra, ToolSpec.xpOf(extra)), m.dials.levelFortune.get()) : 0;
        double cnt = spec.counter ? FortuneMath.counterFortune(ToolSpec.counterOf(extra), m.dials.counterPerDigit.get()) : 0;
        com.google.gson.JsonObject cf = new com.google.gson.JsonObject();
        for (Crop c : spec.crops) {
            double an = 0;
            if (spec.analysis && p != null)
                an = FortuneMath.analysisFortune(Sky.service(com.skyclone.api.feature.Features.Collections.class).count(p, c.collectionId),
                        m.dials.analysisPerDigit.get());
            cf.addProperty(c.milestoneKey, lvl + cnt + an + playerBonus(m, p, c));
        }
        extra.add("crop_fortune", cf);
    }

    /**
     * Crop fortune that belongs to the PLAYER rather than the tool - Harvesta's Personal Bests, per-crop accessories
     * (Cropling / Squash / Fermenta) and the pet's crop stat. The Garden computes its own fortune from the Farming
     * Fortune stat plus the held tool's {@code crop_fortune} and has no per-player hook, so (dial
     * {@code farming.garden-player-bonuses}) these ride on the tool the player is using, refreshed on every flush -
     * the same way Collection Analysis already reads the player's collection. Wave 5.
     */
    static double playerBonus(FarmingModule m, Player p, Crop c) {
        if (p == null || m == null || !m.dials.gardenPlayerBonuses.get() || !Sky.profiles().isReady(p)) return 0;
        double v = 0;
        FarmData d = m.data(p);
        if (d.pbUnlocked)
            v += FortuneMath.personalBestFortune(d.personalBest.getOrDefault(c.name(), 0L), m.tables.pbStep(c), m.dials.pbMaxFortune.get());
        // the garden adds accessories' per-crop otherStats itself since wave 5 - only carry them if told to
        if (m.perks != null) v += m.perks.gardenCropBonus(p, c, m.dials.gardenAccessoryBonuses.get());
        return v;
    }

    /** The stack in the player's inventory with this uuid, or null. */
    static ItemStack find(Player p, String uuid) {
        if (uuid == null) return null;
        ItemStack held = p.getInventory().getItemInMainHand();
        if (uuid.equals(uuidOf(held))) return held;
        for (ItemStack s : p.getInventory().getContents()) if (s != null && uuid.equals(uuidOf(s))) return s;
        return null;
    }

    static String uuidOf(ItemStack s) {
        if (s == null || s.getType().isAir() || Sky.items().idOf(s) == null) return null;
        return Sky.items().data(s).uuid;
    }

    @EventHandler(priority = EventPriority.MONITOR)
    public void onSlot(PlayerItemHeldEvent e) { flush(e.getPlayer()); }

    @EventHandler(priority = EventPriority.LOWEST)
    public void onQuit(PlayerQuitEvent e) { flush(e.getPlayer()); }

    // The buffer is written onto the stack it belongs to, so it must land before that stack can leave the inventory:
    // opening any menu (crafting the Mk upgrade, storage, trade, auction), dropping, or swapping hands.
    @EventHandler(priority = EventPriority.LOWEST)
    public void onOpen(InventoryOpenEvent e) { if (e.getPlayer() instanceof Player p) flush(p); }

    @EventHandler(priority = EventPriority.LOWEST)
    public void onDrop(PlayerDropItemEvent e) {
        Player p = e.getPlayer();
        Pending pd = pending.get(p.getUniqueId());
        if (pd == null) return;
        ItemStack dropped = e.getItemDrop().getItemStack();
        if (pd.uuid.equals(uuidOf(dropped))) {          // the tool itself is leaving: write onto the dropped stack
            pending.remove(p.getUniqueId());
            if (pd.count != 0 || pd.xp != 0) apply(p, pd, dropped);
            e.getItemDrop().setItemStack(dropped);
        } else flush(p);
    }

    @EventHandler(priority = EventPriority.LOWEST)
    public void onSwap(PlayerSwapHandItemsEvent e) { flush(e.getPlayer()); }

    // ---- stats & lore -----------------------------------------------------------------------------

    private void stats(ItemDef def, ItemData data, com.skyclone.api.item.Rarity rarity, ItemStatSheet sheet) {
        ToolSpec s = ToolSpec.of(def.id, def.extra);
        if (s == null) return;
        if (s.wisdom > 0) sheet.add(Stat.FARMING_WISDOM, s.wisdom, ItemStatSheet.Source.BASE);
        int alm = ToolSpec.almanacOf(data.extra);
        if (alm > 0) sheet.add(Stat.FARMING_FORTUNE, alm, ItemStatSheet.Source.OTHER);
    }

    private static final class Lore implements LoreContributor {
        @Override public Section section() { return Section.AFTER_STATS; }

        @Override public List<String> lines(ItemDef def, ItemData data) {
            ToolSpec s = ToolSpec.of(def.id, def.extra);
            List<String> out = new ArrayList<>();
            if (s == null) return out;
            FarmingModule m = FarmingModule.M;
            int levelF = 0, level = 1;
            double[] prog = null;
            if (s.leveling && m != null) {
                double xp = ToolSpec.xpOf(data.extra);
                level = level(s, data.extra, xp);
                prog = FortuneMath.toolProgress(xp, m.tables.toolLevelXp, cap(s, data.extra));
                levelF = FortuneMath.levelFortune(level, m.dials.levelFortune.get());
            }
            for (Crop c : s.crops)
                out.add("&7" + c.display + " Fortune: &6+" + (s.cropFortune + levelF) + "☘");
            if (s.counter) {
                long cnt = ToolSpec.counterOf(data.extra);
                int per = m == null ? 16 : m.dials.counterPerDigit.get();
                out.add("");
                out.add("&7Counter: &e" + Text.commasInt(cnt) + " " + (s.crops.isEmpty() ? "crops" : s.crops.get(0).display));
                out.add("&6Logarithmic Counter");
                out.add("&7Gain &6+" + per + "☘ " + cropWord(s) + " Fortune &7for");
                out.add("&7every digit on the counter.");
                out.add("&8(+" + FortuneMath.counterFortune(cnt, per) + " currently)");
            }
            if (s.analysis) {
                int per = m == null ? 8 : m.dials.analysisPerDigit.get();
                out.add("");
                out.add("&6Collection Analysis");
                out.add("&7Gain &6+" + per + "☘ " + cropWord(s) + " Fortune &7for");
                out.add("&7every digit of your " + cropWord(s) + " collection.");
            }
            if (s.kind.equals("fungi")) {
                out.add("");
                String mode = ToolSpec.fungiOf(data.extra);
                out.add("&7Mode: " + (mode.equals("brown") ? "&6Brown Mushrooms" : "&cRed Mushrooms"));
                out.add("&8Right-click to switch.");
            }
            if (s.leveling && prog != null) {
                out.add("");
                if (prog[1] > 0) {
                    out.add("&7Level &a" + level + " &8➜ &a" + (level + 1) + "   &8(" + Text.shortNum(prog[0]) + "/" + Text.shortNum(prog[1]) + ")");
                    out.add(Text.bar(prog[0] / prog[1], 25));
                } else {
                    out.add("&7Level &a" + level + " &6&lMAXED");
                    if (m != null && level < m.tables.maxToolLevel())
                        out.add(s.mk >= 3 ? "&8Overclocker 3000s raise the cap." : "&8Upgrade to the next Mk to level further.");
                }
                int oc = ToolSpec.overclockOf(data.extra);
                if (oc > 0) out.add("&7Overclockers: &a" + oc + "&7/&a" + (m == null ? 10 : m.dials.maxOverclocks.get()));
            }
            int alm = ToolSpec.almanacOf(data.extra);
            if (alm > 0) out.add("&7Farmer's Almanac: &a" + alm + "&7/&a" + (m == null ? 5 : m.dials.maxAlmanac.get()));
            return out;
        }

        private static String cropWord(ToolSpec s) { return s.crops.isEmpty() ? "Crop" : s.crops.get(0).display; }
    }

    /** Snapshot of tool numbers for menus (includes the unflushed buffer). */
    record View(ToolSpec spec, long counter, double xp, int level, int almanac, String uuid, int cap, int overclocks) {}

    /** The tool's level cap from its Mk and Overclockers (dials farming.tool-cap-mk1/2/3, farming.overclocker-max). */
    static int cap(ToolSpec s, com.google.gson.JsonObject extra) {
        FarmingModule m = FarmingModule.M;
        if (m == null) return 50;
        return FortuneMath.levelCap(s == null ? 0 : s.mk, ToolSpec.overclockOf(extra), m.dials.capMk1.get(), m.dials.capMk2.get(),
                m.dials.capMk3.get(), m.dials.maxOverclocks.get(), m.tables.maxToolLevel());
    }

    /** The tool's level from {@code xp}, held at its Mk cap. */
    static int level(ToolSpec s, com.google.gson.JsonObject extra, double xp) {
        FarmingModule m = FarmingModule.M;
        if (m == null) return 1;
        return FortuneMath.toolLevel(xp, m.tables.toolLevelXp, cap(s, extra));
    }

    View view(Player p, ItemStack stack) {
        if (stack == null) return null;
        String id = Sky.items().idOf(stack);
        ItemDef def = id == null ? null : Sky.items().def(id);
        ToolSpec s = def == null ? null : ToolSpec.of(def.id, def.extra);
        if (s == null) return null;
        ItemData d = Sky.items().data(stack);
        long cnt = ToolSpec.counterOf(d.extra) + pendingCount(p, d.uuid);
        double xp = ToolSpec.xpOf(d.extra) + pendingXp(p, d.uuid);
        return new View(s, cnt, xp, level(s, d.extra, xp), ToolSpec.almanacOf(d.extra), d.uuid, cap(s, d.extra), ToolSpec.overclockOf(d.extra));
    }

    /** Pending counters per player (admin view). */
    Map<UUID, Long> debug() {
        Map<UUID, Long> m = new HashMap<>();
        pending.forEach((k, v) -> m.put(k, v.count));
        return m;
    }
}
