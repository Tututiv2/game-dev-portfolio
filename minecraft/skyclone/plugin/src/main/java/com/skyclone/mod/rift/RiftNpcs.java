package com.skyclone.mod.rift;

import com.skyclone.api.Sky;
import com.skyclone.api.loot.LootSource;
import com.skyclone.api.npc.NpcDef;
import com.skyclone.api.ui.Msg;
import org.bukkit.Location;
import org.bukkit.entity.Player;

import java.util.List;
import java.util.function.Consumer;

/**
 * Every Rift NPC and the quests they run. Names are ours (docs/names/rift.md). The Warden of the Arch is
 * registered once (in the Cradle) and copied into the hub next to the Wizard Tower's arch with
 * {@code spawnAt}, because the hub has no rift anchor of its own (hub hand-off Request 7).
 */
final class RiftNpcs {

    static final String WARDEN = "rift_warden";
    static final String[] CLERK_NAMES = {"Clerk Pomfrey", "Clerk Quine", "Clerk Rumbold", "Clerk Sable"};   // not Pell/Rook: both were taken

    private final RiftModule m;

    RiftNpcs(RiftModule m) { this.m = m; }

    private void npc(String id, String name, String anchor, String title, String entity, Consumer<Player> click) {
        NpcDef d = NpcDef.of(id, name, RiftCatalog.ISLAND, anchor).title(title);
        if (entity != null) d.entity(entity);
        Sky.npcs().register(d, click);
    }

    private void talk(Player p, String id, List<String> lines, Runnable then) { Sky.npcs().talk(p, id, lines, then); }

    void register() {
        npc(WARDEN, "&dWarden of the Arch", "npc.rift_wizard", "&5&lTHE RIFT", "EVOKER", p -> {
            if (m.session(p) != null) m.menus.overview(p); else m.menus.entry(p);
        });
        npc("rift_mote_monger", "&dMote Monger", "npc.mote_monger", "&d&lMOTES", "WANDERING_TRADER", p -> m.menus.shop(p, "mote_monger"));
        npc("rift_plaza_monger", "&dMote Monger", "npc.plaza_monger", "&d&lMOTES", "WANDERING_TRADER", p -> m.menus.shop(p, "mote_monger"));
        npc("rift_riddler", "&5Riddler Nyx", "npc.riddler", "&5&lRIDDLE SOULS", "WITCH", this::riddler);
        npc("rift_glade_peddler", "&aGlade Peddler Tarquin", "npc.glade_peddler", "&a&lSHOP", null, p -> m.menus.shop(p, "glade_peddler"));
        npc("rift_tower_sage", "&bTower Sage Elm", "npc.tower_sage", "&b&lSHOP", null, p -> m.menus.shop(p, "tower_sage"));
        npc("rift_vel_ka", "&2Old Vel'ka", "npc.vel_ka", "&e&lCLICK", "VILLAGER", this::velka);
        npc("rift_lagoon_scientist", "&3Dr. Marsh", "npc.lagoon_scientist", "&c&lBOSS", null,
                p -> m.menus.summon(p, "rift_leech_sovereign", "boss.leech", m.dials.summonLeech.get(), "Dr. Marsh",
                        List.of("&7My leeches have grown a queen.", "&7Pay for the bait and I will lure", "&7her out of the Lair.")));
        npc("rift_racer", "&eRacer Gustav", "npc.racer", "&e&lRACE", null, p -> m.menus.racer(p));
        npc("rift_quillon", "&bDr. Quillon", "npc.quillon", "&b&lLOOKING-GLASS", null, this::quillon);
        npc("rift_wilma", "&6Farmer Wilma", "npc.wilma", "&e&lQUEST", "VILLAGER", this::wilma);
        for (int i = 1; i <= RiftCatalog.CLERKS; i++) {
            final int n = i;
            npc("rift_clerk_" + i, "&f" + CLERK_NAMES[i - 1], "npc.clerk_" + i, "&7&lDESK " + i, "VILLAGER", p -> clerk(p, n));
        }
        npc("rift_barnaby", "&aMayor Barnaby", "npc.barnaby", "&a&lMAYOR", null, this::barnaby);
        npc("rift_tailor", "&dTailor Penrose", "npc.tailor", "&d&lTAILOR", null, p -> m.menus.shop(p, "tailor"));
        npc("rift_cave_warden", "&9Cave Warden Ostrel", "npc.cave_warden", "&9&lLIVING CAVE", null, this::caveWarden);
        npc("rift_arena_keeper", "&cArena Keeper Brutus", "npc.arena_keeper", "&c&lBOSS", null,
                p -> m.menus.summon(p, "rift_bloatmass", "boss.bacte", m.dials.summonBacte.get(), "Arena Keeper Brutus",
                        List.of("&7Something gelatinous sleeps", "&7under the sand. Pay the gate fee", "&7and I will wake it.")));
        npc("rift_chateau_hunter", "&4Vampire Hunter Moira", "npc.chateau_hunter", "&4&lHUNTER", null, p -> m.menus.hunter(p));
        npc("rift_starkeeper", "&fThe Starkeeper", "npc.starkeeper", "&f&lSUMMIT", "ALLAY", this::starkeeper);
        // wave 3
        npc(RiftGallery.CURATOR, "&dCurator Ilsabet", "npc.curator", "&d&lRIFT GALLERY", null, p -> m.gallery.click(p));
        npc(RiftSplitSteal.HOST, "&e" + RiftSplitSteal.HOST_NAME, "npc.split_steal", "&e&lSPLIT OR STEAL", "WANDERING_TRADER", p -> m.splitSteal.click(p));
    }

    /** The hub copy of the Warden, by the Wizard Tower's arch. */
    void placeHubCopy() {
        // hub generator v7 builds a live Rift arch with its own anchor for the Warden; the offset dial still wins
        // when someone has changed it from its default.
        Location own = Sky.worlds().anchor("hub", "npc.rift_warden");
        if (own != null && "-17,0,8".equals(m.dials.hubNpcOffset.get().replace(" ", ""))) {
            Sky.npcs().spawnAt(WARDEN, "hub", own.clone());
            return;
        }
        Location base = Sky.worlds().anchor("hub", "warp.wizard_tower");
        if (base == null) return;
        double[] off = {-17, 0, 8};
        try {
            String[] parts = m.dials.hubNpcOffset.get().split(",");
            for (int i = 0; i < 3 && i < parts.length; i++) off[i] = Double.parseDouble(parts[i].trim());
        } catch (RuntimeException ignored) { }
        Location at = base.clone().add(off[0], off[1], off[2]);
        at.setYaw(90f);
        Sky.npcs().spawnAt(WARDEN, "hub", at);
    }

    // =========================================================================================
    // dialogue and quests
    // =========================================================================================

    private void riddler(Player p) {
        RiftData d = m.data(p);
        if (d == null) return;
        if (d.flags.add("met_riddler")) {
            talk(p, "rift_riddler", List.of(
                    "Ah, a new face. The Rift is full of lost souls, did you know?",
                    "Riddle Souls. " + RiftCatalog.totalSouls() + " of them, tucked away where nobody looks.",
                    "Walk right into one to set it free. Every " + m.dials.soulStep.get() + " you free, the Rift lets you stay longer.",
                    "There is one right beside me. Go on, try it."), () -> m.menus.souls(p));
            m.award(p, "quest:riddler", 3, "Met the Riddler");
        } else if (!m.cat.cloakVisit(p)) m.menus.souls(p);
    }

    private void velka(Player p) {
        RiftData d = m.data(p);
        if (d == null) return;
        if (d.flags.add("met_velka")) {
            talk(p, "rift_vel_ka", List.of(
                    "Visitors... it has been a while. Mind the leeches, they drink time as well as blood.",
                    "The big one lives in the Lair to the south-west. Dr. Marsh can lure her out, for a price.",
                    "Take these. Melons heal your Hearts in here. Nothing else does as quickly."), () -> {
                if (Sky.items().exists("HEALING_MELON")) Sky.loot().give(p, "HEALING_MELON", 3, LootSource.REWARD);
            });
            m.award(p, "quest:velka", 3, "Old Vel'ka");
        } else if (!m.riddles.velkaQuiz(p)) {
            talk(p, "rift_vel_ka", List.of("Five souls hide around this lagoon. You already have mine. Rude, really."), null);
        }
    }

    private void quillon(Player p) {
        RiftData d = m.data(p);
        if (d == null) return;
        if (d.charms.contains("mirror")) {
            talk(p, "rift_quillon", List.of(".lufrednow - ylno sdrawkcab ti yas I. Wonderful, I mean. You beat all my rooms."), null);
            return;
        }
        talk(p, "rift_quillon", List.of(
                "Down the stairs in my house are the Looking-Glass Halls. Rift Time stands still down there.",
                "Three rooms: one upside down, one with a floor that lies, and one that wants you to dance.",
                "Beat all three and the glass gives you its Timecharm. You have beaten " + d.mirror.size() + "/3."), null);
    }

    private void wilma(Player p) {
        RiftData d = m.data(p);
        if (d == null) return;
        int ber = m.dials.eggBerberis.get(), aga = m.dials.eggAgaricus.get();
        switch (d.wilma) {
            case 0 -> {
                d.wilma = 1;
                talk(p, "rift_wilma", List.of(
                        "Oh thank goodness, a helper! My hens have laid their eggs all over the farm again.",
                        "There are " + RiftCatalog.EGGS + " of them. Look for the little green sparkles.",
                        "And while you're out there, I need " + ber + " Wilted Berberis and " + aga + " Agaricus Caps for the hens' feed."), null);
                m.award(p, "quest:wilma_start", 3, "Farmer Wilma");
            }
            case 1 -> talk(p, "rift_wilma", List.of("Found any eggs? You have " + d.eggs.size() + " of " + RiftCatalog.EGGS + "."), null);
            case 2 -> {
                int hb = Sky.items().count(p, "WILTED_BERBERIS"), ha = Sky.items().count(p, "AGARICUS_CAP");
                if (hb < ber || ha < aga) {
                    talk(p, "rift_wilma", List.of("All the eggs, lovely! Now the feed: " + ber + " Wilted Berberis (you have " + hb + ") and "
                            + aga + " Agaricus Caps (you have " + ha + ")."), null);
                    return;
                }
                Sky.items().remove(p, "WILTED_BERBERIS", ber);
                Sky.items().remove(p, "AGARICUS_CAP", aga);
                d.wilma = 3;
                talk(p, "rift_wilma", List.of("You did it all! The hens are clucking with joy.",
                        "Take these leggings - I sewed them from their best feathers. And this... it hatched from the oddest egg."), () -> {
                    if (Sky.items().exists("CHICKEN_LEGGS")) Sky.loot().give(p, "CHICKEN_LEGGS", 1, LootSource.REWARD);
                    // wave 5: the reference ties the blowgun riddles (balloons, buttons) to finishing this quest
                    if (Sky.items().exists("BERBERIS_BLOWGUN")) Sky.loot().give(p, "BERBERIS_BLOWGUN", 1, LootSource.REWARD);
                    m.puzzles.grantCharm(p, "chicken");
                });
            }
            default -> talk(p, "rift_wilma", List.of("The hens are happy. Come back any time."), null);
        }
    }

    private void clerk(Player p, int n) {
        RiftData d = m.data(p);
        if (d == null) return;
        String id = "rift_clerk_" + n, name = CLERK_NAMES[n - 1];
        if (d.paperwork >= 6) { talk(p, id, List.of("Your citizenship is filed. Next!"), null); return; }
        if (d.paperwork == 0) d.paperwork = 1;
        if (d.paperwork == 5) { talk(p, id, List.of("All four stamps. Take it to Mayor Barnaby, in the town hall."), null); return; }
        if (d.paperwork != n) {
            String next = CLERK_NAMES[Math.max(0, Math.min(3, d.paperwork - 1))];
            talk(p, id, List.of("Wrong desk. You need " + next + " first. Please queue again."), null);
            return;
        }
        int cost = m.dials.paperworkSeconds.get();
        RiftSession s = m.session(p);
        if (s != null && s.remaining <= cost + m.dials.warnAt.get()) {
            talk(p, id, List.of("We close in... oh, you don't have the time. Come back on another visit."), null);
            return;
        }
        m.travel.addTime(p, -cost);
        d.paperwork = n + 1;
        String[] lines = {
                "Form 27-B, application for Rift citizenship. Fill it in. Now see " + CLERK_NAMES[1] + " for the stamp.",
                "Stamped. In triplicate. " + CLERK_NAMES[2] + " will want your signature.",
                "Signed, witnessed, and witnessed again. " + CLERK_NAMES[3] + " does the filing.",
                "Filed! Take the receipt to Mayor Barnaby in the town hall."};
        talk(p, id, List.of(lines[n - 1], "&7(-" + cost + "sф of Rift Time. Bureaucracy takes time.)"), null);
        Sky.profiles().saveNow(p);
    }

    private void barnaby(Player p) {
        RiftData d = m.data(p);
        if (d == null) return;
        if (d.paperwork == 5) {
            d.paperwork = 6;
            talk(p, "rift_barnaby", List.of("A receipt! In order! Do you know how rare that is?",
                    "Congratulations, citizen. Here, the town's own Timecharm. Don't lose it, we have no form for that."),
                    () -> m.puzzles.grantCharm(p, "citizen"));
        } else if (d.paperwork >= 6) {
            talk(p, "rift_barnaby", List.of("Citizen! Always a pleasure."), null);
        } else {
            if (d.paperwork == 0) d.paperwork = 1;
            talk(p, "rift_barnaby", List.of("You want to be a citizen of the Rift? Wonderful!",
                    "Just see the four clerks along the east side of the Plaza. In order. Desk 1 first."), null);
        }
    }

    private void caveWarden(Player p) {
        RiftData d = m.data(p);
        if (d == null) return;
        if (!d.flags.contains("met_warden")) {
            d.flags.add("met_warden");
            talk(p, "rift_cave_warden", List.of("The cave is alive. Its heart wanders.",
                    "Hit it and it flees. Hit it " + m.dials.heartHitsNeeded.get() + " times before it gets away from you for good.",
                    "The blue glass in the walls is Living Metal. I trade in pickaxes for those who mine it."),
                    () -> m.menus.shop(p, "cave_warden"));
        } else m.menus.shop(p, "cave_warden");
    }

    private void starkeeper(Player p) {
        RiftData d = m.data(p);
        if (d == null) return;
        if (!d.charms.contains("celestial")) {
            if (m.puzzles.tryCelestial(p)) {
                talk(p, "rift_starkeeper", List.of("Seven Timecharms... at last. Here is the eighth. The Rift is yours, traveller."), null);
                return;
            }
            talk(p, "rift_starkeeper", List.of("You climbed all this way. Bring me the other seven Timecharms and the last is yours.",
                    "You have " + d.charms.size() + " so far."), () -> m.menus.shop(p, "starkeeper"));
            return;
        }
        m.menus.shop(p, "starkeeper");
    }

    void verminKilled(Player p) {
        RiftData d = m.data(p);
        if (d == null) return;
        d.vermin++;
        int need = m.dials.verminNeeded.get();
        if (d.vermin == need) Msg.success(p, "The Infested House is clear! Tell Racer Gustav.");
        else if (d.vermin < need && d.vermin % 5 == 0) Sky.vitals().flash(p, "&eVermin: &a" + d.vermin + "/" + need, 30);
    }

    void verminReward(Player p) {
        RiftData d = m.data(p);
        if (d == null) return;
        if (d.vermin < m.dials.verminNeeded.get()) {
            talk(p, "rift_racer", List.of("My house is crawling with vermin! Clear out " + m.dials.verminNeeded.get() + " of them, would you? ("
                    + d.vermin + " so far)"), null);
            return;
        }
        if (!d.flags.add("vermin_reward")) { talk(p, "rift_racer", List.of("Not a squeak since you cleaned up. Thanks again!"), null); return; }
        talk(p, "rift_racer", List.of("Not a single squeak! Here, these gloves kept my hands clean. Yours now."), () -> {
            if (Sky.items().exists("DISINFESTOR_GLOVES")) Sky.loot().give(p, "DISINFESTOR_GLOVES", 1, LootSource.REWARD);
            m.addMotes(p, 200, "Vermin cleared");
            m.riddles.verminDone(p);
        });
        m.award(p, "quest:vermin", 3, "Infested House");
    }
}
