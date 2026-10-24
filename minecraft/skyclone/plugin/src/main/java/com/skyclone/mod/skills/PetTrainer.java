package com.skyclone.mod.skills;

import com.skyclone.api.Sky;
import com.skyclone.api.config.Dials;
import com.skyclone.api.feature.Features;
import com.skyclone.api.item.Rarity;
import com.skyclone.api.skill.SkillType;
import com.skyclone.api.ui.Icons;
import com.skyclone.api.ui.Menu;
import com.skyclone.api.ui.Msg;
import com.skyclone.api.ui.Text;
import org.bukkit.Bukkit;
import org.bukkit.Material;
import org.bukkit.Sound;
import org.bukkit.entity.Player;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

/**
 * The Pet Trainer (wave 5): the Taming road's "training sessions" were text with nothing behind them.
 * {@code /pettrainer}, Your Skills -> Taming -> Pet Trainer, or the Hub NPC.
 *
 * <p>Hand over your SUMMONED pet for a session (Free at Taming 10 ... Turbo! at 60, see {@link TrainerTiers}):
 * pay up front, wait real time (online or not), come back and claim with the same pet summoned, and it gains
 * the session's pet XP through {@code Features.Pets.addPetXp} (so the pets module's own caps and level-up
 * messages apply). One session at a time. Cancelling refunds nothing. Unlike the reference, the pet is not taken
 * away while it trains (the pets API has no way to lock a pet); claiming needs the same type and rarity summoned.
 *
 * <p>Coins and Bits are taken with the economy's all-or-nothing calls BEFORE the session is written; a Bits
 * failure gives the coins back. Nothing here can create coins or items.
 */
final class PetTrainer {

    static final String NPC_ID = "skills_pet_trainer";
    static final String NPC_NAME = "&aTrainer Brindle";
    private static final int[] TIER_SLOTS = {19, 20, 21, 23, 24, 25};
    private static final int INFO = 4, PET = 13, SESSION = 22, CANCEL = 31, PETS = 50;

    private final SkillServiceImpl svc;
    private final SkillMenus menus;

    final Dials.Dial<Boolean> enabled;
    final Dials.Dial<Double> costScale;
    final Dials.Dial<Double> xpScale;
    final Dials.Dial<Double> timeScale;
    final Dials.Dial<Integer> turboBits;
    final Dials.Dial<Boolean> samePet;

    PetTrainer(SkillServiceImpl svc, SkillMenus menus) {
        this.svc = svc;
        this.menus = menus;
        var d = Sky.dials();
        enabled = d.flag("skills.trainer.enabled", true, "Pet Trainer sessions (Taming 10/20/30/40/50/60 unlocks)");
        costScale = d.number("skills.trainer.cost-scale", 1.0, "Multiplier on Pet Trainer coin costs (base: Light 50k ... Ultra/Turbo 2M)"); // TUNE
        xpScale = d.number("skills.trainer.xp-scale", 1.0, "Multiplier on the pet XP a training session gives (base: Free 10k ... Ultra/Turbo 6M)"); // TUNE
        timeScale = d.number("skills.trainer.time-scale", 1.0, "Multiplier on training session length (base: Free 1h ... Ultra 16h, Turbo 2h)"); // TUNE
        turboBits = d.integer("skills.trainer.turbo-bits", 500, "Bits a Turbo! session costs on top of its coins (research 05: 500)");
        samePet = d.flag("skills.trainer.same-pet", true, "Claiming needs the trained pet's type AND rarity summoned (false = any summoned pet)");
    }

    void start() {
        Bukkit.getScheduler().runTaskTimer(Sky.plugin(), this::notifyDone, 600L, 600L);
    }

    // ---- numbers ---------------------------------------------------------------------------------

    double coins(TrainerTiers.Tier t) { return Math.max(0, t.coins() * Math.max(0, costScale.get())); }

    long bits(TrainerTiers.Tier t) { return t.bits() <= 0 ? 0 : Math.max(0, turboBits.get()); }

    double xp(TrainerTiers.Tier t) { return Math.max(0, t.xp() * Math.max(0, xpScale.get())); }

    long millis(TrainerTiers.Tier t) { return TrainerTiers.durationMillis(t, timeScale.get()); }

    private static Features.Pets pets() { return Sky.has(Features.Pets.class) ? Sky.service(Features.Pets.class) : null; }

    private static String petName(String type, String rarity) {
        Rarity r = Rarity.parse(rarity);
        return (r == null ? "&f" : r.code()) + Text.titleCase(type == null ? "pet" : type.toLowerCase(Locale.ROOT).replace('_', ' '));
    }

    // ---- menu ------------------------------------------------------------------------------------

    void open(Player p) {
        if (!svc.ready(p)) { Msg.error(p, "Your profile is still loading!"); return; }
        Menu m = new Menu("Pet Trainer", 6).chrome();
        m.drawer(mm -> draw(mm, p));
        m.refreshEvery(20);
        m.back("Taming Skill", () -> menus.road(p, SkillType.TAMING, SkillMenus.pageOf(svc.level(p, SkillType.TAMING))));
        m.set(PETS, Icons.of(Material.BONE, "&aYour Pets", "&7Summon the pet you want to train.", "", "&eClick to open!"),
                c -> { Features.Pets pp = pets(); if (pp != null) pp.openMenu(c.player()); });
        m.redraw();
        m.open(p);
    }

    private void draw(Menu m, Player p) {
        if (!svc.ready(p)) return;
        SkillData d = svc.data(p);
        int taming = svc.level(p, SkillType.TAMING);
        m.set(INFO, Icons.of(Material.LEAD, "&aPet Trainer",
                "&7Leave your pet with the trainer for a",
                "&7session and it comes back with pet XP.",
                "&7Higher &aTaming &7levels unlock longer,",
                "&7better-value sessions.",
                "",
                "&7Your Taming level: &a" + taming,
                "&7Coins spent on training: &6" + Text.commasInt(d.trainerSpent)));

        Features.Pets pp = pets();
        String type = pp == null ? null : pp.activeType(p);
        if (type == null) m.set(PET, Icons.of(Material.GRAY_DYE, "&cNo pet summoned", "&7Summon a pet from &aYour Pets &7to", "&7start a session."));
        else m.set(PET, Icons.of(Material.NAME_TAG, petName(type, pp.activeRarity(p)), "&7Level &a" + pp.activeLevel(p), "", "&7This pet would be trained."));

        long now = System.currentTimeMillis();
        SkillData.Training tr = d.training;
        if (tr != null) {
            for (int s : TIER_SLOTS) m.set(s, Icons.filler());
            TrainerTiers.Tier t = TrainerTiers.byId(tr.tier);
            double frac = TrainerTiers.progress(tr.start, tr.end, now);
            boolean done = now >= tr.end;
            List<String> lore = new ArrayList<>();
            lore.add("&7Pet: " + petName(tr.petType, tr.petRarity));
            lore.add("&7Session: &e" + (t == null ? tr.tier : t.name()));
            lore.add("&7Pet XP on return: &b" + Text.commasInt(tr.xp));
            lore.add("");
            lore.add(Text.bar(frac, 20) + " &e" + SkillMenus.pct(frac, 1) + "%");
            if (done) {
                lore.add("&a&lTRAINING COMPLETE!");
                lore.add("");
                lore.add("&7Summon your " + petName(tr.petType, tr.petRarity) + " &7and");
                lore.add("&eClick to claim!");
            } else {
                lore.add("&7Time left: &e" + Text.duration(TrainerTiers.remaining(tr.end, now) / 1000));
            }
            m.set(SESSION, Icons.of(done ? Material.LIME_DYE : Material.CLOCK, done ? "&aTraining complete" : "&eTraining...", lore),
                    c -> claim(c.player()));
            m.set(CANCEL, Icons.of(Material.BARRIER, "&cCancel Session", "&7Stop this session now.", "", "&cNothing is refunded.", "", "&eClick to cancel!"),
                    c -> cancel(c.player()));
        } else {
            m.set(SESSION, Icons.filler());
            m.set(CANCEL, Icons.filler());
            for (int i = 0; i < TrainerTiers.TIERS.size() && i < TIER_SLOTS.length; i++) {
                TrainerTiers.Tier t = TrainerTiers.TIERS.get(i);
                m.set(TIER_SLOTS[i], tierIcon(t, taming), c -> pick(c.player(), t));
            }
        }
    }

    private org.bukkit.inventory.ItemStack tierIcon(TrainerTiers.Tier t, int taming) {
        boolean open = taming >= t.taming();
        Material mat = open ? Material.matchMaterial(t.icon()) : Material.GRAY_DYE;
        if (mat == null || !mat.isItem()) mat = Material.BONE;
        List<String> lore = new ArrayList<>();
        lore.add("&7Pet XP: &b+" + Text.commasInt(xp(t)));
        lore.add("&7Duration: &e" + Text.duration(millis(t) / 1000));
        lore.add("");
        lore.add("&7Cost:");
        double c = coins(t);
        long b = bits(t);
        if (c <= 0 && b <= 0) lore.add("&aFree");
        if (c > 0) lore.add("&6" + Text.commasInt(c) + " Coins");
        if (b > 0) lore.add("&b" + Text.commasInt(b) + " Bits");
        lore.add("");
        if (open) lore.add("&eClick to start!");
        else lore.add("&cRequires Taming " + t.taming());
        return Icons.of(mat, (open ? "&a" : "&c") + t.name() + " Training", lore);
    }

    private void pick(Player p, TrainerTiers.Tier t) {
        String why = canStart(p, t);
        if (why != null) { Msg.error(p, why); return; }
        double c = coins(t);
        long b = bits(t);
        if (c <= 0 && b <= 0) { begin(p, t); return; }
        List<String> desc = new ArrayList<>();
        desc.add("&7Train your " + petName(pets().activeType(p), pets().activeRarity(p)) + "&7?");
        if (c > 0) desc.add("&7Cost: &6" + Text.commasInt(c) + " Coins");
        if (b > 0) desc.add("&7Cost: &b" + Text.commasInt(b) + " Bits");
        desc.add("&7Returns with &b+" + Text.commasInt(xp(t)) + " pet XP &7in &e" + Text.duration(millis(t) / 1000) + "&7.");
        Sky.menus().confirm(p, "Start " + t.name() + " Training?", desc, () -> { begin(p, t); open(p); }, () -> open(p));
    }

    /** Why this player cannot start that session now, or null. */
    String canStart(Player p, TrainerTiers.Tier t) {
        if (!enabled.get()) return "The Pet Trainer is not taking pets right now.";
        if (!svc.ready(p)) return "Your profile is still loading!";
        SkillData d = svc.data(p);
        if (d.training != null) return "Your pet is already training! Claim or cancel that session first.";
        if (svc.level(p, SkillType.TAMING) < t.taming()) return "You need Taming " + t.taming() + " for " + t.name() + " Training.";
        Features.Pets pp = pets();
        if (pp == null || pp.activeType(p) == null) return "Summon the pet you want to train first!";
        if (Sky.economy().purse(p) < coins(t)) return "You need " + Text.commasInt(coins(t)) + " coins in your purse.";
        if (bits(t) > 0 && Sky.economy().bits(p) < bits(t)) return "You need " + Text.commasInt(bits(t)) + " Bits.";
        return null;
    }

    /** Takes the payment, then writes the session. Re-checks everything (the confirm screen can be stale). */
    boolean begin(Player p, TrainerTiers.Tier t) {
        String why = canStart(p, t);
        if (why != null) { Msg.error(p, why); return false; }
        Features.Pets pp = pets();
        String type = pp.activeType(p), rarity = pp.activeRarity(p);
        double c = coins(t);
        long b = bits(t);
        if (c > 0 && !Sky.economy().withdraw(p, c, "skills: pet training " + t.id())) { Msg.error(p, "You cannot afford that!"); return false; }
        if (b > 0 && !Sky.economy().spendBits(p, b, "skills: pet training " + t.id())) {
            if (c > 0) Sky.economy().deposit(p, c, "skills: pet training refund (bits short)");
            Msg.error(p, "You do not have enough Bits!");
            return false;
        }
        SkillData d = svc.data(p);
        SkillData.Training tr = new SkillData.Training();
        tr.tier = t.id();
        tr.petType = type;
        tr.petRarity = rarity;
        tr.xp = xp(t);
        tr.start = System.currentTimeMillis();
        tr.end = tr.start + millis(t);
        tr.paidCoins = c;
        tr.paidBits = b;
        d.training = tr;
        d.trainerSpent += c;
        Sky.profiles().saveNow(p);
        Msg.send(p, "&aYour " + petName(type, rarity) + " &abegan &e" + t.name() + " Training&a! Come back in &e"
                + Text.duration(millis(t) / 1000) + "&a.");
        Msg.sound(p, Sound.ENTITY_WOLF_AMBIENT, 1.2f);
        return true;
    }

    void claim(Player p) {
        if (!svc.ready(p)) return;
        SkillData d = svc.data(p);
        SkillData.Training tr = d.training;
        if (tr == null) { Msg.error(p, "You have no pet in training."); return; }
        long now = System.currentTimeMillis();
        if (now < tr.end) { Msg.error(p, "Still training! " + Text.duration(TrainerTiers.remaining(tr.end, now) / 1000) + " left."); return; }
        Features.Pets pp = pets();
        String type = pp == null ? null : pp.activeType(p);
        if (type == null) { Msg.error(p, "Summon your " + Text.strip(petName(tr.petType, tr.petRarity)) + " to take it back!"); return; }
        if (samePet.get() && (!type.equalsIgnoreCase(tr.petType) || !String.valueOf(pp.activeRarity(p)).equalsIgnoreCase(String.valueOf(tr.petRarity)))) {
            Msg.error(p, "That is not the pet you left here! Summon your " + Text.strip(petName(tr.petType, tr.petRarity)) + ".");
            return;
        }
        d.training = null;               // cleared BEFORE the XP is handed over: a second click can never pay twice
        Sky.profiles().saveNow(p);
        pp.addPetXp(p, tr.xp, "pet_trainer");
        Msg.send(p, "&aYour " + petName(tr.petType, tr.petRarity) + " &areturned from training with &b+" + Text.commasInt(tr.xp) + " pet XP&a!");
        Msg.sound(p, Sound.ENTITY_PLAYER_LEVELUP, 1.6f);
    }

    void cancel(Player p) {
        if (!svc.ready(p) || svc.data(p).training == null) return;
        Sky.menus().confirm(p, "Cancel Training?", List.of("&7The session ends now and your pet", "&7gains nothing.", "", "&cNothing is refunded."),
                () -> {
                    if (svc.ready(p)) { svc.data(p).training = null; Sky.profiles().saveNow(p); Msg.send(p, "&eTraining cancelled."); }
                    open(p);
                }, () -> open(p));
    }

    /** Every 30 s: one message when a session finishes while you are online. */
    private void notifyDone() {
        long now = System.currentTimeMillis();
        for (Player p : Bukkit.getOnlinePlayers()) {
            if (!svc.ready(p)) continue;
            SkillData.Training tr = svc.data(p).training;
            if (tr == null || tr.notified || now < tr.end) continue;
            tr.notified = true;
            Msg.send(p, "&aYour " + petName(tr.petType, tr.petRarity) + " &afinished training! &7Claim it at the Pet Trainer (&e/pettrainer&7).");
            Msg.sound(p, Sound.ENTITY_EXPERIENCE_ORB_PICKUP, 1.2f);
        }
    }

    // ---- NPC -------------------------------------------------------------------------------------

    void npcClick(Player p) {
        if (!svc.ready(p)) return;
        SkillData d = svc.data(p);
        if (!d.metTrainer) {
            d.metTrainer = true;
            Sky.npcs().talk(p, NPC_ID, List.of(
                    "Every pet can learn more than it shows. Leave yours with me for a while.",
                    "The better you are with animals, the longer I can work with them.",
                    "Bring the same pet back to collect it. Off you go!"), () -> open(p));
            return;
        }
        open(p);
    }

    // ---- admin -----------------------------------------------------------------------------------

    /** "/sb skill <player> trainer [info|finish|clear]" */
    String admin(Player p, String sub) {
        if (!svc.ready(p)) return "&cprofile not ready";
        SkillData d = svc.data(p);
        SkillData.Training tr = d.training;
        switch (sub == null ? "info" : sub.toLowerCase(Locale.ROOT)) {
            case "finish" -> {
                if (tr == null) return "&cNo session.";
                tr.end = System.currentTimeMillis();
                return "&aSession finished; " + p.getName() + " can claim it now.";
            }
            case "clear" -> { d.training = null; return "&aSession cleared (no refund)."; }
            default -> {
                if (tr == null) return "&7" + p.getName() + " has no pet in training. Spent: " + Text.commasInt(d.trainerSpent);
                return "&7" + p.getName() + ": " + tr.tier + " " + tr.petRarity + " " + tr.petType + " +" + Text.commasInt(tr.xp)
                        + " XP, " + Text.duration(TrainerTiers.remaining(tr.end, System.currentTimeMillis()) / 1000) + " left";
            }
        }
    }

    // ---- self-test -------------------------------------------------------------------------------

    String validate() {
        List<TrainerTiers.Tier> ts = TrainerTiers.TIERS;
        if (ts.size() != 6) return ts.size() + " session types (want 6: Taming 10-60)";
        for (int i = 0; i < ts.size(); i++) {
            TrainerTiers.Tier t = ts.get(i);
            if (t.taming() != 10 * (i + 1)) return t.id() + " gated at Taming " + t.taming();
            if (!(xp(t) > 0) || millis(t) <= 0) return t.id() + " gives no XP or takes no time";
            if (i > 0 && t.xp() < ts.get(i - 1).xp()) return t.id() + " gives less XP than the tier below";
            if (i > 1 && t.bits() == 0 && t.coinsPerXp() > ts.get(i - 1).coinsPerXp() + 1e-9) return t.id() + " is worse value than the tier below";
        }
        if (bits(ts.get(5)) <= 0) return "Turbo costs no Bits";
        // the Taming road advertises exactly these sessions
        int lines = 0;
        for (int L = 1; L <= svc.table.maxLevel(SkillType.TAMING); L++)
            for (SkillTable.Unlock u : svc.table.level(SkillType.TAMING, L).unlocks)
                if (u.name != null && u.name.contains("Pet Trainer")) lines++;
        if (lines != ts.size()) return "Taming road names " + lines + " training sessions, the trainer has " + ts.size();
        return null;
    }
}
