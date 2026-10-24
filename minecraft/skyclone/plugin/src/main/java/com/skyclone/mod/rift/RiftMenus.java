package com.skyclone.mod.rift;

import com.skyclone.api.Sky;
import com.skyclone.api.feature.Features;
import com.skyclone.api.item.ItemDef;
import com.skyclone.api.loot.LootSource;
import com.skyclone.api.ui.Icons;
import com.skyclone.api.ui.Menu;
import com.skyclone.api.ui.Msg;
import com.skyclone.api.ui.Text;
import org.bukkit.Material;
import org.bukkit.Sound;
import org.bukkit.entity.Player;
import org.bukkit.inventory.ItemStack;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;

/**
 * Every Rift menu. Entry points: the Warden of the Arch (hub copy: entry; Cradle: overview), {@code /rift},
 * and the SkyBlock Menu's Rift link if the menus module adds one (it can run {@code /rift}).
 */
final class RiftMenus {

    private final RiftModule m;

    RiftMenus(RiftModule m) { this.m = m; }

    private static Menu frame(String title, int rows) { return new Menu(title, rows).chrome(); }

    private void backToOverview(Menu menu) { menu.set(menu.closeSlot() - 1, Icons.back("The Rift"), c -> overview(c.player())); }

    // =========================================================================================
    // entry (hub side)
    // =========================================================================================

    void entry(Player p) {
        RiftData d = m.data(p);
        if (d == null) return;
        Menu menu = frame("The Rift", 5);
        int inf = m.travel.infusions(p);
        int max = m.dials.maxInfusions.get();
        long next = m.travel.untilNextInfusion(p);
        boolean prism = m.travel.hasPrism(p);
        boolean free = m.dials.freeEntry.get();
        List<String> lore = new ArrayList<>(List.of(
                "&7Step through the arch into the Rift,", "&7a twisted mirror of this world.", "",
                "&7Inside you have your own &dRift", "&dinventory&7, your own stats and a", "&7draining &aRift Time ф&7.", ""));
        if (free) lore.add("&aEntry is free on this server right now.");
        else if (prism) lore.add("&aYour Rift Prism lets you in for free.");
        else lore.add("&7Cost: &d1 Planar Infusion &7(you have &d" + inf + "&7/" + max + ")");
        lore.add("");
        boolean can = free || prism || inf > 0;
        lore.add(can ? "&eClick to enter the Rift!" : "&cYou have no Planar Infusion.");
        menu.set(13, Icons.glow(Icons.of(Material.END_PORTAL_FRAME, "&5Enter the Rift", lore)), c -> {
            c.player().closeInventory();
            m.travel.enter(c.player(), null);
        });
        List<String> il = new ArrayList<>(List.of("&7A free Planar Infusion charges every", "&a" + Text.commas(m.dials.infusionHours.get())
                + " hours&7, up to &a" + max + " &7stored.", "", "&7Stored: &d" + inf + "&7/" + max));
        if (inf < max) il.add("&7Next in: &a" + Text.duration(next / 1000));
        menu.set(29, Icons.of(Material.AMETHYST_SHARD, "&dPlanar Infusions", il));
        int bits = m.dials.bitsCost.get();
        menu.set(31, Icons.of(Material.DIAMOND, "&bPay with Bits", "&7Enter without an infusion.", "", "&7Cost: &b" + bits + " Bits",
                "&7You have: &b" + Text.commasInt(Sky.economy().bits(p)), "", "&eClick to pay and enter!"), c -> {
            if (Sky.economy().bits(c.player()) < bits) { Msg.error(c.player(), "You need " + bits + " Bits."); return; }
            Sky.menus().confirm(c.player(), "Spend " + bits + " Bits?", List.of("&7Enter the Rift for &b" + bits + " Bits&7."),
                    () -> m.travel.enter(c.player(), RiftTravel.Pay.BITS), null);
        });
        int bottles = m.dials.bottleCost.get();
        if (Sky.items().exists("GRAND_EXP_BOTTLE")) {
            menu.set(33, Icons.of(Material.EXPERIENCE_BOTTLE, "&9Pay with Experience", "&7Pour experience into the arch.", "",
                    "&7Cost: &9" + bottles + "x Grand Experience Bottle", "&7You have: &9" + Sky.items().count(p, "GRAND_EXP_BOTTLE"), "",
                    "&eClick to pay and enter!"), c -> {
                if (Sky.items().count(c.player(), "GRAND_EXP_BOTTLE") < bottles) { Msg.error(c.player(), "You need " + bottles + " Grand Experience Bottles."); return; }
                m.travel.enter(c.player(), RiftTravel.Pay.BOTTLES);
            });
        }
        menu.set(11, Icons.of(Material.PRISMARINE_CRYSTALS, "&9Rift Prism", "&7Keep a &9Rift Prism &7in your", "&7Accessory Bag and entering is", "&7always free.", "",
                prism ? "&aYou have one!" : "&cYou do not have one."));
        menu.set(15, Icons.of(Material.CLOCK, "&aYour Rift Progress", progressLore(p, d)), c -> overview(c.player()));
        menu.open(p);
    }

    private List<String> progressLore(Player p, RiftData d) {
        List<String> l = new ArrayList<>();
        l.add("&7Max Rift Time: &a" + RiftMath.clock(m.stats.sheet(p).time()) + "ф");
        l.add("&7Motes: &d" + Text.commasInt(d.motes));
        l.add("&7Riddle Souls: &d" + d.souls.size() + "&7/" + RiftCatalog.totalSouls());
        l.add("&7Timecharms: &a" + d.charms.size() + "&7/" + RiftCatalog.CHARMS.size());
        l.add("&7Visits: &f" + d.visits);
        l.add("");
        l.add("&eClick to view!");
        return l;
    }

    // =========================================================================================
    // overview
    // =========================================================================================

    void overview(Player p) {
        RiftData d = m.data(p);
        if (d == null) return;
        RiftSession s = m.session(p);
        RiftStats.Sheet sh = m.stats.sheet(p);
        Menu menu = frame("The Rift", 6);
        menu.set(4, Icons.glow(Icons.of(Material.END_PORTAL_FRAME, "&5The Rift", progressLore(p, d).subList(0, 5))));
        List<String> st = new ArrayList<>();
        st.add("&aRift Time ф &f" + RiftMath.clock(sh.time()) + (s != null ? " &7(&a" + RiftMath.clock(s.remaining) + " &7left)" : ""));
        for (Map.Entry<String, Double> e : sh.timeSources().entrySet()) st.add("  &8" + e.getKey() + ": &a+" + RiftMath.clock(e.getValue()));
        st.add("&5Rift Damage &f" + Text.commas(sh.damage()));
        st.add("&cHearts ❤ &f" + Text.commas(sh.hearts()));
        st.add("&bRift Intelligence ✎ &f" + Text.commas(sh.intelligence()));
        st.add("&fRift Speed ✦ &f+" + Text.commas(sh.speed()));
        st.add("&bMana Regen &f" + Text.commas(sh.manaRegen()) + "%");
        if (sh.cruxTier() > 0) st.add("&dCrux Fortune &f" + Text.commas(sh.cruxFortune()) + " &8(talisman tier " + sh.cruxTier() + ")");
        st.add("");
        st.add("&7Inside the Rift only these count:");
        st.add("&7your main gear stays outside.");
        menu.set(20, Icons.of(Material.CLOCK, "&aRift Stats", st));
        menu.set(21, Icons.of(Material.AMETHYST_CLUSTER, "&dMotes", "&7The Rift's own currency.", "", "&7Balance: &d" + Text.commasInt(d.motes),
                "&7Earned in total: &d" + Text.commasInt(d.lifetimeMotes), "", "&7Earn them from &dMote Orbs&7, Rift", "&7monsters, bosses, and by selling",
                "&7Rift items to the &dMote Monger&7."));
        menu.set(22, Icons.of(Material.ENDER_EYE, "&5Riddle Souls", "&7Found: &d" + d.souls.size() + "&7/" + RiftCatalog.totalSouls(), "",
                "&7Every &a" + m.dials.soulStep.get() + " &7souls: &a+" + RiftMath.clock(m.dials.soulBonus.get()) + "ф &7max Rift Time.", "", "&eClick for hints!"),
                c -> souls(c.player()));
        menu.set(23, Icons.of(Material.CLOCK, "&aTimecharms", "&7Owned: &a" + d.charms.size() + "&7/" + RiftCatalog.CHARMS.size(), "",
                "&7Each adds &a" + RiftMath.clock(m.dials.perCharm.get()) + "ф &7max Rift Time", "&7and opens new areas.", "", "&eClick to view!"),
                c -> charms(c.player()));
        menu.set(24, Icons.of(Material.FILLED_MAP, "&eAreas & Gates", "&7Which parts of the Rift will let", "&7you in, and what they ask for.", "", "&eClick to view!"),
                c -> areas(c.player()));
        menu.set(30, Icons.of(Material.BOOK, "&eQuests", questLore(d)));
        menu.set(31, Icons.of(Material.PAPER, "&7Statistics", "&7Visits: &f" + d.visits, "&7Time inside: &f" + Text.duration(d.secondsInside),
                "&7Deaths: &f" + d.deaths, "&7Hearts caught: &f" + d.heartsCaught,
                "&7Best race: &f" + (d.raceBest == 0 ? "-" : Text.commas(Math.floor(d.raceBest / 100.0) / 10.0) + "s"),
                "&7Monsters slain: &f" + d.kills.values().stream().mapToInt(Integer::intValue).sum()));
        menu.set(32, Icons.of(Material.AMETHYST_SHARD, "&dPlanar Infusions", "&7Stored: &d" + m.travel.infusions(p) + "&7/" + m.dials.maxInfusions.get(),
                m.travel.infusions(p) < m.dials.maxInfusions.get() ? "&7Next in: &a" + Text.duration(m.travel.untilNextInfusion(p) / 1000) : "&aFull."));
        if (s != null) {
            menu.set(40, Icons.of(Material.OAK_DOOR, "&cLeave the Rift", "&7Return to the hub now.", "&7Your remaining Rift Time is lost.", "", "&eClick to leave!"),
                    c -> Sky.menus().confirm(c.player(), "Leave the Rift?", List.of("&7Your &a" + RiftMath.clock(s.remaining) + "ф &7of Rift Time will be lost."),
                            () -> m.travel.eject(c.player(), "You left the Rift."), null));
        } else {
            menu.set(40, Icons.of(Material.END_PORTAL_FRAME, "&5Enter the Rift", "&7Use the Warden of the Arch by", "&7the hub's Wizard Tower.", "",
                    "&eClick to warp there!"), c -> {
                c.player().closeInventory();
                var l = m.travel.hubLanding();
                if (l != null && !m.inRift(c.player())) c.player().teleport(l);
            });
        }
        menu.set(33, Icons.of(Material.ITEM_FRAME, "&dRift Gallery", "&7Secured Timecharms: &a" + d.gallery.size() + "&7/" + RiftCatalog.CHARMS.size(),
                "&7Unsecured: &e" + d.charms.stream().filter(c -> !d.gallery.contains(c)).count(), "",
                "&7Bring your Timecharms to &dCurator", "&dIlsabet &7in the Gallery, south-east", "&7of the Cradle.", "",
                "&eClick to view!"), c -> m.gallery.menu(c.player()));
        // wave 5: the eyes and the lost cat
        menu.set(29, Icons.of(Material.ENDER_EYE, "&5The Warden's Eyes", "&7Found: &d" + d.eyes.size() + "&7/" + RiftCatalog.EYES, "",
                "&7Each eye is a fast-travel point", "&7inside the Rift.", "", "&eClick to travel!"), c -> m.cat.eyesMenu(c.player()));
        menu.set(34, Icons.of(Material.BONE, "&9Pharaoh's Soul", "&7Pieces found: &9" + d.catPieces.size() + "&7/" + RiftCatalog.CAT_PIECES, "",
                d.catStage == 0 ? "&7Weeping Maribel waits by the road" : "&7Rift Time from Pharaoh: &a+" + (int) RiftEnigma.catRiftTime(d.catPieces.size()) + "s",
                d.catStage == 0 ? "&7to the Great Wyld Tree." : "", "&eClick to view!"), c -> m.cat.menu(c.player()));
        menu.set(48, Icons.back("SkyBlock Menu"), c -> Sky.service(Features.SkyMenu.class).open(c.player()));
        menu.open(p);
    }

    private List<String> questLore(RiftData d) {
        List<String> l = new ArrayList<>();
        l.add(mark(d.flags.contains("met_riddler")) + "Meet Riddler Nyx in the Cradle");
        l.add(mark(d.charms.contains("supreme")) + "Climb the Great Wyld Tree");
        l.add(mark(d.flags.contains("met_velka")) + "Visit Old Vel'ka on the lagoon");
        l.add(mark(d.raceBest > 0) + "Finish Racer Gustav's race");
        l.add(mark(d.flags.contains("vermin_reward")) + "Clear the Infested House");
        l.add(mark(d.charms.contains("mirror")) + "Beat the Looking-Glass Halls &7(" + d.mirror.size() + "/3)");
        l.add(mark(d.wilma >= 3) + "Help Farmer Wilma &7(" + (d.wilma == 1 ? d.eggs.size() + "/" + RiftCatalog.EGGS + " eggs" : d.wilma >= 3 ? "done" : d.wilma == 2 ? "feed" : "not started") + ")");
        l.add(mark(d.paperwork >= 6) + "Become a citizen &7(" + (d.paperwork <= 0 ? "not started" : d.paperwork <= 4 ? "desk " + d.paperwork : d.paperwork == 5 ? "see the Mayor" : "done") + ")");
        l.add(mark(d.charms.contains("living")) + "Catch the Living Heart");
        l.add(mark(d.charms.contains("globulate")) + "Defeat the Bloatmass");
        l.add(mark(d.charms.contains("vampiric")) + "Defeat the Chateau Lord");
        l.add(mark(d.charms.contains("celestial")) + "Reach the summit with seven Timecharms");
        l.add(mark(d.gallery.size() >= RiftCatalog.CHARMS.size()) + "Fill the Rift Gallery &7(" + d.gallery.size() + "/" + RiftCatalog.CHARMS.size() + ")");
        l.add(mark(d.splitStealRounds > 0) + "Play Split or Steal in the stone circle");
        l.add(mark(d.odonataCaught > 0) + "Bottle an Odonata in the Wildwood &7(" + d.odonataCaught + ")");
        l.add(mark(d.catPieces.size() >= RiftCatalog.CAT_PIECES) + "Find Pharaoh's soul pieces &7(" + d.catPieces.size() + "/" + RiftCatalog.CAT_PIECES + ")");
        l.add(mark(d.eyes.size() >= RiftCatalog.EYES) + "Find the Warden's eyes &7(" + d.eyes.size() + "/" + RiftCatalog.EYES + ")");
        l.add(mark(d.riddles.size() >= RiftEnigma.puzzleCount()) + "Solve the Riddle Soul puzzles &7(" + d.riddles.size() + "/" + RiftEnigma.puzzleCount() + ")");
        return l;
    }

    private static String mark(boolean done) { return done ? "&a✔ &7" : "&c✖ &f"; }

    // =========================================================================================
    // souls, charms, areas
    // =========================================================================================

    void souls(Player p) {
        RiftData d = m.data(p);
        if (d == null) return;
        Menu menu = frame("Riddle Souls", 6);
        Map<String, Integer> found = m.world.soulsByArea(d);
        int slot = 10;
        for (Map.Entry<String, Integer> e : RiftCatalog.SOULS_PER_AREA.entrySet()) {
            RiftCatalog.Area a = RiftCatalog.area(e.getKey());
            int f = found.getOrDefault(e.getKey(), 0), t = e.getValue();
            menu.set(slot, Icons.of(f >= t ? Material.ENDER_EYE : Material.ENDER_PEARL, "&" + a.color() + a.name(),
                    "&7Souls found: " + (f >= t ? "&a" : "&d") + f + "&7/" + t, "", "&eClick for hints!"), c -> areaSouls(c.player(), e.getKey()));
            slot++;
            if (slot % 9 == 8) slot += 2;
        }
        menu.set(4, Icons.of(Material.NETHER_STAR, "&5Riddle Souls", "&7Found: &d" + d.souls.size() + "&7/" + RiftCatalog.totalSouls(), "",
                "&7Every soul is a riddle: some you just", "&7reach, some need a puzzle solved first.", "&7Free ones sparkle purple; locked ones smoke.", "",
                "&7Enigma Cloak holds: &d" + d.cloakSouls + "&7/" + RiftEnigma.CLOAK_MAX + " souls &8(Riddler Nyx", "&8stitches in every " + RiftEnigma.CLOAK_STEP + ")"));
        backToOverview(menu);
        menu.open(p);
    }

    private void areaSouls(Player p, String area) {
        RiftData d = m.data(p);
        if (d == null) return;
        RiftCatalog.Area a = RiftCatalog.area(area);
        List<RiftWorldState.Spot> list = new ArrayList<>();
        for (RiftWorldState.Spot s : m.world.souls()) if (area.equals(s.area())) list.add(s);
        Sky.menus().paged("Souls: " + a.name(), list, s -> {
            boolean got = d.souls.contains(s.name());
            RiftRiddles.Puzzle pz = m.riddles.puzzleOfSoul(s.name());
            List<String> lore = new ArrayList<>();
            if (pz != null) { lore.addAll(Text.wrap("&f" + pz.r.task(), 32)); lore.add(""); }
            lore.addAll(Text.wrap("&7" + s.hint(), 32));
            lore.add("");
            if (!got && pz != null && d.riddles.contains(pz.r.id())) lore.add("&eSolved: go and collect it!");
            lore.add(got ? "&aFound!" : "&cNot found yet.");
            String title = pz == null ? "Riddle Soul #" + s.name().substring(5) : pz.r.name();
            return Icons.of(got ? Material.ENDER_EYE : Material.GRAY_DYE, (got ? "&d" : "&7") + title, lore);
        }, pc -> { }, page -> page.set(page.closeSlot() - 1, Icons.back("Riddle Souls"), c -> souls(c.player())), 0).open(p);
    }

    void charms(Player p) {
        RiftData d = m.data(p);
        if (d == null) return;
        Menu menu = frame("Timecharms", 4);
        int[] slots = {10, 11, 12, 13, 14, 15, 16, 22};
        int i = 0;
        for (RiftCatalog.Charm c : RiftCatalog.CHARMS) {
            boolean has = d.charms.contains(c.id());
            RiftCatalog.Area a = RiftCatalog.area(c.area());
            List<String> lore = new ArrayList<>();
            lore.add("&7Where: &" + a.color() + a.name());
            lore.addAll(Text.wrap("&7" + c.how(), 30));
            lore.add("");
            lore.add("&7Reward: &a+" + RiftMath.clock(m.dials.perCharm.get()) + "ф &7max Rift Time");
            lore.add("");
            lore.add(has ? "&aObtained!" : "&cNot obtained.");
            ItemStack icon = Icons.of(has ? Material.CLOCK : Material.GRAY_DYE, (has ? "&a" : "&7") + c.name(), lore);
            menu.set(slots[i++], has ? Icons.glow(icon) : icon);
        }
        backToOverview(menu);
        menu.open(p);
    }

    void areas(Player p) {
        RiftData d = m.data(p);
        if (d == null) return;
        RiftSession s = m.session(p);
        Menu menu = frame("Rift Areas", 5);
        int slot = 10;
        for (RiftCatalog.Area a : RiftCatalog.AREAS) {
            List<String> lore = new ArrayList<>(Text.wrap("&7" + a.blurb(), 30));
            lore.add("");
            if (!a.gated()) lore.add("&aAlways open.");
            else {
                lore.add("&7To enter you need:");
                for (String c : a.gateCharms()) {
                    RiftCatalog.Charm ch = RiftCatalog.charm(c);
                    lore.add((d.charms.contains(c) ? " &a✔ " : " &c✖ ") + "&7the " + (ch == null ? c : ch.name()));
                }
                if (a.gateSeconds() > 0) {
                    boolean ok = s != null && s.remaining >= a.gateSeconds();
                    lore.add((ok ? " &a✔ " : " &c✖ ") + "&a" + RiftMath.clock(a.gateSeconds()) + "ф &7of Rift Time left");
                    if (m.stats.sheet(p).time() < a.gateSeconds()) lore.add("   &8(your max is " + RiftMath.clock(m.stats.sheet(p).time()) + ")");
                }
                if (s != null && s.gates.contains(a.id())) lore.add("&aOpen for this visit.");
            }
            if (a.drain() == 0) lore.add("&bRift Time stands still here.");
            else if (a.drain() < 1) lore.add("&eRift Time drains at half speed here.");
            menu.set(slot, Icons.of(Material.FILLED_MAP, "&" + a.color() + a.name(), lore));
            slot++;
            if (slot % 9 == 8) slot += 2;
        }
        backToOverview(menu);
        menu.open(p);
    }

    // =========================================================================================
    // shops
    // =========================================================================================

    void shop(Player p, String shopId) {
        RiftData d = m.data(p);
        if (d == null) return;
        RiftEconomy.Shop sh = m.economy.shops.get(shopId);
        String title = sh == null ? "Shop" : sh.name();
        List<RiftEconomy.Offer> offers = m.economy.available(shopId);
        Menu menu = frame(title, 6);
        int slot = 10;
        for (RiftEconomy.Offer o : offers) {
            ItemDef def = Sky.items().def(o.item());
            ItemStack shown = Sky.items().create(o.item(), 1);
            List<String> extra = new ArrayList<>();
            extra.add("");
            extra.add("&7Cost: &d" + Text.commasInt(o.motes()) + " Motes");
            boolean ok = RiftRequirements.has(p, o.requirement());
            if (!ok) extra.add("&cRequires " + Sky.requirements().describe(o.requirement()));
            extra.add(ok ? (d.motes >= o.motes() ? "&eClick to buy!" : "&cYou cannot afford this.") : "&cLocked");
            menu.set(slot, Icons.withLore(shown, extra), c -> buy(c.player(), shopId, o));
            slot++;
            if (slot % 9 == 8) slot += 2;
            if (slot > 43) break;
            if (def == null) continue;
        }
        menu.set(4, Icons.of(Material.AMETHYST_CLUSTER, "&dYour Motes", "&7Balance: &d" + Text.commasInt(d.motes), "",
                "&7Click Rift items in your inventory", "&7to sell them for Motes."));
        menu.set(49, Icons.close(), c -> c.player().closeInventory());
        menu.set(50, Icons.of(Material.HOPPER, "&aSell Rift Items", "&7Sells every item in your inventory", "&7the Mote Monger buys (not gear).", "",
                "&7Worth: &d" + Text.commasInt(sellAllValue(p)) + " Motes", "", "&eClick to sell!"), c -> { sellAll(c.player()); shop(c.player(), shopId); });
        backToOverview(menu);
        menu.onPlayerInventoryClick((click, stack) -> {
            if (stack == null || stack.getType().isAir()) return;
            sellStack(click.player(), click.slot(), stack);
            shop(click.player(), shopId);
        });
        menu.open(p);
    }

    private void buy(Player p, String shopId, RiftEconomy.Offer o) {
        RiftData d = m.data(p);
        if (d == null) return;
        if (!RiftRequirements.has(p, o.requirement())) { Sky.requirements().checkOrTell(p, o.requirement()); return; }
        if (d.motes < o.motes()) { Msg.error(p, "You need " + Text.commasInt(o.motes()) + " Motes."); return; }
        Runnable doIt = () -> {
            RiftData dd = m.data(p);
            if (dd == null || dd.motes < o.motes()) { Msg.error(p, "You cannot afford that."); return; }
            if (!m.takeMotes(p, o.motes())) return;
            Sky.loot().give(p, o.item(), 1, LootSource.NPC_SHOP);
            ItemDef def = Sky.items().def(o.item());
            Msg.success(p, "You bought " + (def == null ? o.item() : def.rarity.code() + def.name) + " &afor &d" + Text.commasInt(o.motes()) + " Motes&a!");
            Sky.profiles().saveNow(p);
            shop(p, shopId);
        };
        if (o.motes() >= 1000) Sky.menus().confirm(p, "Buy for " + Text.commasInt(o.motes()) + " Motes?", List.of("&7Spend &d" + Text.commasInt(o.motes()) + " Motes&7."), doIt, () -> shop(p, shopId));
        else doIt.run();
    }

    /** Value of everything sellable in the main inventory (unique gear excluded). */
    private long sellAllValue(Player p) {
        long v = 0;
        for (ItemStack it : p.getInventory().getStorageContents()) {
            String id = it == null ? null : Sky.items().idOf(it);
            if (id == null || unique(id)) continue;
            v += (long) m.economy.sellValue(id) * it.getAmount();
        }
        return v;
    }

    private static boolean unique(String id) { ItemDef d = Sky.items().def(id); return d != null && d.isUnique(); }

    private void sellAll(Player p) {
        long total = 0;
        ItemStack[] st = p.getInventory().getStorageContents();
        for (int i = 0; i < st.length; i++) {
            ItemStack it = st[i];
            String id = it == null ? null : Sky.items().idOf(it);
            if (id == null || unique(id)) continue;
            int each = m.economy.sellValue(id);
            if (each <= 0) continue;
            total += (long) each * it.getAmount();
            p.getInventory().setItem(i, null);
        }
        if (total <= 0) { Msg.error(p, "You have nothing the Mote Monger wants."); return; }
        m.addMotes(p, total, "sold");
        Msg.success(p, "Sold for &d" + Text.commasInt(total) + " Motes&a.");
        Msg.sound(p, Sound.ENTITY_VILLAGER_YES, 1.2f);
        Sky.profiles().saveNow(p);
    }

    private void sellStack(Player p, int rawSlot, ItemStack clicked) {
        String id = Sky.items().idOf(clicked);
        int each = id == null ? 0 : m.economy.sellValue(id);
        if (each <= 0) { Msg.error(p, "The Mote Monger does not want that."); return; }
        // find the real stack in the inventory again (the click is handled a tick later)
        ItemStack[] st = p.getInventory().getStorageContents();
        for (int i = 0; i < st.length; i++) {
            ItemStack it = st[i];
            if (it == null || !it.isSimilar(clicked) || it.getAmount() != clicked.getAmount()) continue;
            long total = (long) each * it.getAmount();
            p.getInventory().setItem(i, null);
            m.addMotes(p, total, "sold");
            Msg.success(p, "Sold for &d" + Text.commasInt(total) + " Motes&a.");
            Sky.profiles().saveNow(p);
            return;
        }
    }

    // =========================================================================================
    // bosses and quest menus
    // =========================================================================================

    void summon(Player p, String mob, String anchor, long cost, String who, List<String> pitch) {
        RiftData d = m.data(p);
        if (d == null) return;
        var def = Sky.mobs().def(mob);
        String name = def == null ? mob : def.name;
        Menu menu = frame(who, 3);
        boolean alive = !Sky.mobs().live(mob).isEmpty();
        List<String> lore = new ArrayList<>(pitch);
        lore.add("");
        if (def != null) lore.add("&7Health: &a" + Text.commasInt(def.health) + " &7Rift HP");
        lore.add("&7Cost: &d" + Text.commasInt(cost) + " Motes");
        lore.add("");
        lore.add(alive ? "&cIt is already awake!" : d.motes >= cost ? "&eClick to summon!" : "&cYou cannot afford this.");
        menu.set(13, Icons.of(Material.SPAWNER, "&c" + name, lore), c -> {
            c.player().closeInventory();
            m.combat.summon(c.player(), mob, anchor, cost);
        });
        menu.open(p);
    }

    void racer(Player p) {
        RiftData d = m.data(p);
        if (d == null) return;
        Menu menu = frame("Racer Gustav", 3);
        menu.set(11, Icons.of(Material.LEATHER_BOOTS, "&eThe West Village Race", "&7Touch all &a" + RiftCatalog.RACE_CHECKPOINTS + " &7green banners", "&7around the village in order, then",
                "&7come back here.", "", "&7Time limit: &a" + m.dials.raceSeconds.get() + "s",
                "&7Best: &a" + (d.raceBest == 0 ? "-" : Text.commas(Math.floor(d.raceBest / 100.0) / 10.0) + "s"), "&7First finish: &9Racer's Sneakers", "",
                "&eClick to start!"), c -> { c.player().closeInventory(); m.puzzles.startRace(c.player()); });
        menu.set(15, Icons.of(Material.SILVERFISH_SPAWN_EGG, "&eThe Infested House", "&7Vermin cleared: &a" + Math.min(d.vermin, m.dials.verminNeeded.get())
                + "&7/" + m.dials.verminNeeded.get(), "", d.flags.contains("vermin_reward") ? "&aDone!" : "&eClick to report!"),
                c -> { c.player().closeInventory(); m.npcs.verminReward(c.player()); });
        menu.open(p);
    }

    void hunter(Player p) {
        Menu menu = frame("Vampire Hunter Moira", 3);
        menu.set(11, Icons.of(Material.EMERALD, "&aHunter's Wares", "&7Silver, melons and scarves for", "&7those who hunt the Bloodfiends.", "", "&eClick to browse!"),
                c -> shop(c.player(), "chateau_hunter"));
        menu.set(13, Icons.of(Material.WITHER_SKELETON_SKULL, "&4The Chateau Lord", "&7Lure the master of Grimhollow", "&7out of the oubliette.", "",
                "&7Cost: &d" + Text.commasInt(m.dials.summonLord.get()) + " Motes", "", "&eClick to continue!"),
                c -> summon(c.player(), "rift_chateau_lord", "boss.chateau", m.dials.summonLord.get(), "Vampire Hunter Moira",
                        List.of("&7His thralls drain the land.", "&7Kill him and the Chateau gives", "&7up its Timecharm.")));
        menu.set(15, Icons.of(Material.REDSTONE, "&cVampire Slayer", "&7The Duskborne Bloodfiend hunts", "&7in here. Start a quest from the", "&7Slayer menu.", "",
                "&eClick to open!"), c -> Sky.commands().run(c.player(), "slayer", "vampire"));
        menu.open(p);
    }
}
