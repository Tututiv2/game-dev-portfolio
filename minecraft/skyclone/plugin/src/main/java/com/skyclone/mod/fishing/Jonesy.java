package com.skyclone.mod.fishing;

import com.skyclone.api.Sky;
import com.skyclone.api.feature.Features;
import com.skyclone.api.item.ItemData;
import com.skyclone.api.loot.LootSource;
import com.skyclone.api.ui.Icons;
import com.skyclone.api.ui.Menu;
import com.skyclone.api.ui.Msg;
import com.skyclone.api.ui.Text;
import com.skyclone.mod.fishing.FishingCatalog.Chapter;
import com.skyclone.mod.fishing.FishingCatalog.Task;
import com.skyclone.mod.fishing.FishingCatalog.TreasureEntry;
import org.bukkit.Material;
import org.bukkit.Sound;
import org.bukkit.entity.Player;
import org.bukkit.inventory.ItemStack;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

/**
 * The Backwater Bayou's chapters (audit 2026-10-06 F11; the reference's Jonesy, our Warden Fen). Seven chapters of
 * tasks; every task pays its chapter's Fishing XP the first time the guide sees it done, and a finished chapter is
 * claimed for junk, Treasure Bait and SkyBlock XP. Chapter VII pays the Pearl of the Swamp: +1 Treasure Chance for
 * good ({@link MobyDuck#stats}). The chapters are data ({@code fishing.json -> jonesy}); see the generator for
 * what each task kind checks.
 */
final class Jonesy {

    private Jonesy() {}

    static final String[] NUMERALS = {"I", "II", "III", "IV", "V", "VI", "VII", "VIII", "IX", "X"};

    static void open(Player p) {
        List<Chapter> chapters = Fishing.CAT.jonesy;
        FishData d = Fishing.data(p);
        int cur = FishMath.currentChapter(d.jonesyChapter, chapters.size());
        if (cur < chapters.size()) payTasks(p, cur);
        Menu m = new Menu("Bayou Chapters", 6).chrome();
        List<String> head = new ArrayList<>();
        head.add("&7Work through the Bayou one chapter at");
        head.add("&7a time. Every task pays &3Fishing XP&7;");
        head.add("&7finish a chapter to claim its reward.");
        head.add("");
        head.add("&7Chapters done: &a" + Math.min(d.jonesyChapter, chapters.size()) + "&7/&a" + chapters.size());
        head.add(d.swampPearl ? "&a✔ &dBayou Pearl &7(+" + FishMath.oneDecimal(FishDials.PEARL_TREASURE.get()) + " Treasure Chance)"
                : "&8The last chapter pays the &dBayou Pearl&8.");
        m.set(4, Icons.of(Material.LILY_PAD, "&aBackwater Bayou", head));
        for (int i = 0; i < chapters.size() && i < 7; i++) {
            Chapter c = chapters.get(i);
            boolean done = i < d.jonesyChapter, now = i == cur;
            List<String> lore = new ArrayList<>();
            lore.add("&7Fishing XP per task: &3" + Text.commasInt(c.xp));
            lore.add("&7Tasks: &f" + c.tasks.size());
            lore.add("");
            lore.add("&7Reward:");
            lore.addAll(rewardLines(c));
            lore.add("");
            lore.add(done ? "&aCOMPLETE" : now ? "&eIn progress" : "&cLocked");
            Material mat = done ? Material.LIME_DYE : now ? Material.YELLOW_DYE : Material.GRAY_DYE;
            m.set(10 + i, Icons.of(mat, (done ? "&a" : now ? "&e" : "&7") + "Chapter " + numeral(i), lore));
        }
        if (cur < chapters.size()) {
            Chapter c = chapters.get(cur);
            boolean all = true;
            for (int t = 0; t < c.tasks.size() && t < 7; t++) {
                Task task = c.tasks.get(t);
                boolean ok = done(p, task);
                all &= ok;
                List<String> lore = new ArrayList<>(Text.wrap("&7" + hint(task), 32));
                lore.add("");
                lore.add(ok ? "&a✔ Done &8(+" + Text.commasInt(c.xp) + " Fishing XP paid)" : "&c✖ Not yet");
                m.set(28 + t, Icons.of(ok ? Material.LIME_STAINED_GLASS_PANE : Material.RED_STAINED_GLASS_PANE,
                        (ok ? "&a" : "&c") + task.label, lore));
            }
            boolean ready = all;
            List<String> claim = new ArrayList<>(rewardLines(c));
            claim.add("");
            claim.add(ready ? "&eClick to claim Chapter " + numeral(cur) + "!" : "&cFinish every task first.");
            m.set(40, Icons.of(ready ? Material.CHEST : Material.BARRIER, (ready ? "&a" : "&c") + "Claim Chapter " + numeral(cur), claim),
                    click -> { if (ready) claim(click.player(), cur); });
        }
        m.open(p);
    }

    static String numeral(int i) { return i < NUMERALS.length ? NUMERALS[i] : String.valueOf(i + 1); }

    static List<String> rewardLines(Chapter c) {
        List<String> out = new ArrayList<>();
        for (TreasureEntry e : c.rewards) out.add("&8+ &f" + e.min + "x " + Fishing.name(e.item));
        if (c.sbXp > 0) out.add("&8+ &b" + c.sbXp + " SkyBlock XP");
        if (c.pearl) out.add("&8+ &dBayou Pearl &7(+1 Treasure Chance, for good)");
        return out;
    }

    /** Pay the chapter's Fishing XP for every task done since the last visit (once per task). */
    static void payTasks(Player p, int chapter) {
        Chapter c = Fishing.CAT.jonesy.get(chapter);
        FishData d = Fishing.data(p);
        for (int t = 0; t < c.tasks.size(); t++) {
            String key = chapter + ":" + t;
            if (d.jonesyTasks.contains(key) || !done(p, c.tasks.get(t))) continue;
            d.jonesyTasks.add(key);
            Fishing.addXp(p, c.xp, "jonesy:" + key);
            Msg.send(p, "&a✔ &f" + c.tasks.get(t).label + " &8- &3+" + Text.commasInt(c.xp) + " Fishing XP");
        }
    }

    static void claim(Player p, int chapter) {
        FishData d = Fishing.data(p);
        if (d.jonesyChapter != chapter || chapter >= Fishing.CAT.jonesy.size()) return;
        Chapter c = Fishing.CAT.jonesy.get(chapter);
        for (Task t : c.tasks) if (!done(p, t)) { open(p); return; }
        payTasks(p, chapter);
        d.jonesyChapter = chapter + 1;
        d.jonesyTasks.clear();
        for (TreasureEntry e : c.rewards) if (Sky.items().exists(e.item)) Sky.loot().give(p, e.item, e.min, LootSource.REWARD);
        if (c.pearl) {
            d.swampPearl = true;
            Sky.stats().invalidate(p);
        }
        if (c.sbXp > 0 && Sky.has(Features.SkyLevel.class)) {
            try { Sky.service(Features.SkyLevel.class).award(p, "fishing.jonesy." + chapter, c.sbXp, "Bayou Chapter " + numeral(chapter)); }
            catch (RuntimeException ignored) { }
        }
        Sky.profiles().saveNow(p);
        p.playSound(p.getLocation(), Sound.UI_TOAST_CHALLENGE_COMPLETE, 0.8f, 1.2f);
        List<String> lines = new ArrayList<>(rewardLines(c));
        Msg.box(p, "&a&lCHAPTER " + numeral(chapter) + " COMPLETE", lines);
        open(p);
    }

    // ---- tasks ----------------------------------------------------------------------------------

    static boolean done(Player p, Task t) {
        FishData d = Fishing.data(p);
        String a = t.arg == null ? "" : t.arg;
        return switch (t.kind) {
            case "talk" -> true;                        // the guide explains it as you read the chapter
            case "collection" -> Sky.requirements().check(p, "collection:" + a);
            case "fished" -> d.fished(a) > 0;
            case "caught" -> d.caught(a) > 0;
            case "hotspot" -> caughtFromPools(d, "water_hotspot", "lava_hotspot");
            case "own" -> owns(p, d, a.split("\\|"));
            case "part" -> rodPart(p, a);
            case "pet" -> pet(p, a);
            default -> false;
        };
    }

    static boolean caughtFromPools(FishData d, String... pools) {
        for (String id : pools) {
            FishingCatalog.Pool pool = Fishing.CAT.pool(id);
            if (pool == null) continue;
            for (FishingCatalog.PoolCreature c : pool.creatures) if (d.caught(c.mob) > 0) return true;
        }
        return false;
    }

    static boolean owns(Player p, FishData d, String[] ids) {
        java.util.Set<String> carried = Casting.carried(p);
        for (String id : ids) {
            if (id.isBlank()) continue;
            if (carried.contains(id) || Baits.count(d, id) > 0) return true;
            for (ItemStack it : p.getInventory().getArmorContents()) if (it != null && id.equals(Sky.items().idOf(it))) return true;
            try { if (Sky.service(Features.Accessories.class).has(p, id)) return true; } catch (RuntimeException ignored) { }
        }
        return false;
    }

    static boolean rodPart(Player p, String part) {
        List<ItemStack> rods = new ArrayList<>();
        for (ItemStack it : p.getInventory().getContents()) if (it != null) rods.add(it);
        for (ItemStack it : rods) {
            String id = Sky.items().idOf(it);
            if (id == null || !Fishing.CAT.rods.containsKey(id)) continue;
            ItemData data = Sky.items().data(it);
            for (String slot : new String[]{"hook", "line", "sinker"}) {
                String have = Fishing.part(data, slot);
                if (have != null && ("ANY".equals(part) || have.equals(part))) return true;
            }
        }
        return false;
    }

    static boolean pet(Player p, String arg) {
        String[] a = arg.split(":");
        try {
            Features.Pets pets = Sky.service(Features.Pets.class);
            return a[0].equalsIgnoreCase(pets.activeType(p)) && (a.length < 2 || FishMath.rarityAtLeast(pets.activeRarity(p), a[1]));
        } catch (RuntimeException e) { return false; }
    }

    static String hint(Task t) {
        String a = t.arg == null ? "" : t.arg;
        return switch (t.kind) {
            case "talk" -> "junk".equals(a)
                    ? "With a Junk Sinker on your rod, every treasure you catch here is junk: Rusty Coins, Busted Belt Buckles, Old Leather Boots. The Scrapper trades for them."
                    : "A glowing ring on the water is a Hotspot. Fish inside it for a buff and the chance of hotspot sea creatures.";
            case "collection" -> "Collect Lily Pads (they drop from every Bayou sea creature).";
            case "fished" -> "Fish one up with a Junk Sinker on your rod.";
            case "caught" -> "Hook one in the Bayou.";
            case "hotspot" -> "Hook a sea creature inside a Fishing Hotspot.";
            case "own" -> "Have one in your inventory, armour, accessory bag or fishing bag when you talk to me.";
            case "part" -> "ANY".equals(a) ? "Fit any hook, line or sinker to a rod at the Tackle-smith." : "Fit it to a rod you carry (Tackle-smith).";
            case "pet" -> "Have that pet out when you talk to me.";
            default -> t.kind.toLowerCase(Locale.ROOT);
        };
    }
}
