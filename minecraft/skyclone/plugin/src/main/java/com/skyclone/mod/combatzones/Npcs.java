package com.skyclone.mod.combatzones;

import com.skyclone.api.Sky;
import com.skyclone.api.config.Dials;
import com.skyclone.api.loot.LootSource;
import com.skyclone.api.npc.NpcDef;
import com.skyclone.api.ui.Icons;
import com.skyclone.api.ui.Menu;
import com.skyclone.api.ui.Msg;
import com.skyclone.api.ui.Text;
import com.skyclone.mod.combatzones.gen.DenGenerator;
import com.skyclone.mod.combatzones.gen.EndGenerator;
import org.bukkit.Material;
import org.bukkit.entity.Player;

import java.util.List;

/** The people of the combat zones. Shop NPCs (Spider Tamer, Crystal Mender, Pearl Dealer, Archer) belong to "shops". */
final class Npcs {

    private Npcs() {}

    static Dials.Dial<Double> denScrollPrice, endScrollPrice;

    static void register(CombatzonesModule m) {
        denScrollPrice = Sky.dials().number("combatzones.den-scroll-price", 25000, "Coins for a Spider's Den travel scroll at Ranger Ravensby (TUNE)");
        endScrollPrice = Sky.dials().number("combatzones.end-scroll-price", 50000, "Coins for an End travel scroll at Warden Selk (TUNE)");

        Sky.npcs().register(NpcDef.of("cz_den_guide", "&fRanger Ravensby", DenGenerator.ID, "npc.den_guide").title("&e&lCLICK"),
                p -> Sky.npcs().talk(p, "cz_den_guide", List.of(
                        "&fWelcome to the Den. Keep to the road unless you like being wrapped in silk.",
                        "&fThe road climbs to the &cTop of the Nest&f, where the &4Broodmother &fwakes every so often.",
                        "&fUnder the north face is the &dSilkmother's Burrow&f. Her keepers carry the Callings that wake her."),
                        () -> guideMenu(m, p, true).open(p)));
        Sky.npcs().register(NpcDef.of("cz_granny", "&fGranny Moss", DenGenerator.ID, "npc.granny").title("&aBESTIARY"),
                p -> Sky.npcs().talk(p, "cz_granny", List.of(
                        "&fOh, a visitor! Mind the webs on the stairs, dear.",
                        "&fI keep a little book of every creature on this mountain. Have a look.",
                        "&fKill enough of one kind and you will learn where to hit it."),
                        () -> Sky.commands().run(p, "bestiary")));
        Sky.npcs().register(NpcDef.of("cz_archaeologist", "&fProfessor Dunmore", DenGenerator.ID, "npc.archaeologist"),
                p -> Sky.npcs().talk(p, "cz_archaeologist", m.relics.dunmoreLines(p), null));   // relic hunt (Relics.java)
        Sky.npcs().register(NpcDef.of("cz_quarryman", "&fQuarryman Rook", DenGenerator.ID, "npc.quarryman"),
                p -> Sky.npcs().talk(p, "cz_quarryman", List.of(
                        "&fGravel's free for the taking down in the pit. It grows back, don't ask me how.",
                        "&fOne shovelful in ten turns up &7flint&f. The skeletons down there want it too."), null));
        Sky.npcs().register(NpcDef.of("cz_end_guide", "&fWarden Selk", EndGenerator.ID, "npc.end_guide").title("&e&lCLICK"),
                p -> Sky.npcs().talk(p, "cz_end_guide", List.of(
                        "&fThe End. Nothing grows here but trouble.",
                        "&fThe &5Dragon's Nest &fis north-east. Fill its altar with eight &5Summoning Eyes &fand a dragon answers.",
                        "&fZealots in the Nest sometimes carry an eye. Bring friends: the dragon pays by how much you helped."),
                        () -> guideMenu(m, p, false).open(p)));
        Sky.npcs().register(NpcDef.of("cz_lone_adventurer", "&fWanderer Tavi", EndGenerator.ID, "npc.lone_adventurer").title("&e&lQUESTS"),
                p -> {
                    boolean first = Sky.profiles().isReady(p) && m.data(p).taviQuest == 0 && m.data(p).taviProgress == 0;
                    Sky.npcs().talk(p, "cz_lone_adventurer", first ? List.of(
                            "&fI came for the dragons and stayed for the view. The view is mostly void.",
                            "&fThis island has too many teeth. Help me thin them out and I will pay you for it.",
                            "&fAnd if you want a blade that likes &5Ender armour&f, I have a &5Void Sword &fto sell.")
                            : List.of("&fBack again? Let's see how the hunt is going."),
                            () -> m.tavi.menu(p).open(p));
                });
        Sky.npcs().register(NpcDef.of("cz_void_keeper", "&fKeeper Ixo", EndGenerator.ID, "npc.void_keeper").title("&d&lDRAGON ESSENCE"),
                p -> Sky.npcs().talk(p, "cz_void_keeper", List.of(
                        "&fThe dragon's head on that pillar is not decoration. It is the &5Draconic Altar&f.",
                        "&fBring it what the dragons left behind and it gives back their &dessence&f.",
                        "&fEssence buys what I teach: how to fight the End, and how to call its kings."),
                        () -> m.essence.keeper(p).open(p)));

        // ---- wave 5: the mob-types intro at the Den Entrance (the reference's Ike and Michael)
        Sky.npcs().register(NpcDef.of("cz_mobtype_fenn", "&fFenn", DenGenerator.ID, "npc.ike").title("&a&lMOB TYPES"),
                p -> Sky.npcs().talk(p, "cz_mobtype_fenn", List.of(
                        "&fHey! Every monster out there is some &atype &fof monster. Spiders are &4Arthropods&f.",
                        "&fSkeletons are &fSkeletal &fand &2Undead&f. Endermen are &5Ender&f. Some are two at once!",
                        "&fA weapon or a charm that names a type only works against that type. Pick your gear to match."),
                        () -> m.mobTypes.menu(p).open(p)));
        Sky.npcs().register(NpcDef.of("cz_mobtype_lark", "&fLark", DenGenerator.ID, "npc.michael"),
                p -> Sky.npcs().talk(p, "cz_mobtype_lark", List.of(
                        "&fFenn talks too much. Here's what matters: slay one of every type.",
                        "&fThe first of each kind teaches you something. You'll feel it. &8(+SkyBlock XP)",
                        "&fI keep a list. Have a look at what you're still missing."),
                        () -> m.mobTypes.menu(p).open(p)));
    }

    static Menu guideMenu(CombatzonesModule m, Player p, boolean den) {
        Menu menu = new Menu(den ? "Ranger Ravensby" : "Warden Selk", 4).chrome();
        menu.set(10, Icons.of(Material.IRON_SWORD, "&aCombat Zones", "&7Bosses, islands, your stats.", "", "&eClick to open!"),
                c -> m.menus.main(c.player()).open(c.player()));
        int[] slots = {12, 14};
        String[][] sel = den ? new String[][]{ZoneMenus.SCROLLS[0], ZoneMenus.SCROLLS[1]} : new String[][]{ZoneMenus.SCROLLS[2], ZoneMenus.SCROLLS[3]};
        double price = den ? denScrollPrice.get() : endScrollPrice.get();
        for (int i = 0; i < 2; i++) {
            String[] s = sel[i];
            menu.set(slots[i], ZoneMenus.iconOf(s[1], List.of("&7Cost: &6" + Text.commas(price) + " coins", "", "&eClick to buy!")),
                    c -> buy(c.player(), s[1], price, () -> guideMenu(m, c.player(), den).open(c.player())));
        }
        menu.set(16, den
                        ? Icons.of(Material.STRING, "&dThe Silkmother", "&eClick for details!")
                        : Icons.of(Material.DRAGON_HEAD, "&5Dragons", "&eClick for details!"),
                c -> (den ? m.menus.silkmother(c.player()) : m.menus.dragon(c.player())).open(c.player()));
        return menu;
    }

    static void buy(Player p, String item, double price, Runnable reopen) {
        Sky.menus().confirm(p, "Buy scroll?", List.of("&7Pay &6" + Text.commas(price) + " coins", "&7for one travel scroll."), () -> {
            if (!Sky.items().exists(item)) { Msg.error(p, "That scroll is not available."); return; }
            if (!Sky.economy().withdraw(p, price, "combatzones: buy " + item)) { Msg.error(p, "You don't have enough coins!"); return; }
            Sky.loot().give(p, item, 1, LootSource.NPC_SHOP);
            Msg.success(p, "Bought a travel scroll. Right-click it to unlock the destination.");
            reopen.run();
        }, null);
    }
}
