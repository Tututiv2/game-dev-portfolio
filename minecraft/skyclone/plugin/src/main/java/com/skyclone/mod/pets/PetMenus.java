package com.skyclone.mod.pets;

import com.skyclone.api.Sky;
import com.skyclone.api.feature.Features;
import com.skyclone.api.item.ItemDef;
import com.skyclone.api.item.Rarity;
import com.skyclone.api.ui.Icons;
import com.skyclone.api.ui.Menu;
import com.skyclone.api.ui.Msg;
import com.skyclone.api.ui.Text;
import com.skyclone.api.world.IslandDef;
import com.skyclone.mod.pets.PetData.OwnedPet;
import com.skyclone.mod.pets.PetService.View;
import org.bukkit.Material;
import org.bukkit.entity.Player;
import org.bukkit.inventory.ItemStack;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.function.Consumer;

/** Every pets GUI: Pets, pet details, Exp Share, Autopet, and the Pet Sitter (upgrades, supplies, collector). */
final class PetMenus {

    private static final int[] INNER = {10, 11, 12, 13, 14, 15, 16, 19, 20, 21, 22, 23, 24, 25, 28, 29, 30, 31, 32, 33, 34, 37, 38, 39, 40, 41, 42, 43};
    private static final String[] SORTS = {"Rarity", "Level", "Skill", "Name"};

    private final PetsModule mod;
    private final PetService svc;
    /** Players who toggled "Convert Pet to an Item" in the Pets menu. */
    private final java.util.Set<java.util.UUID> convertMode = new java.util.HashSet<>();

    PetMenus(PetsModule mod, PetService svc) {
        this.mod = mod;
        this.svc = svc;
    }

    // ---- helpers ----------------------------------------------------------------------------

    ItemStack icon(View v, List<String> extra) {
        List<String> lore = new ArrayList<>(PetLore.lines(svc, v, false));
        if (!extra.isEmpty()) { lore.add(""); lore.addAll(extra); }
        ItemStack it = Icons.skull(svc.skull(v), PetLore.title(v), lore);
        return it;
    }

    private List<OwnedPet> sorted(PetData d) {
        List<OwnedPet> l = new ArrayList<>(d.pets);
        Comparator<OwnedPet> c = switch (d.sort) {
            case 1 -> Comparator.comparingInt((OwnedPet o) -> -lvl(o));
            case 2 -> Comparator.comparing((OwnedPet o) -> svc.defs().pet(o.type) == null ? "" : svc.defs().pet(o.type).skill);
            case 3 -> Comparator.comparing((OwnedPet o) -> svc.defs().pet(o.type) == null ? o.type : svc.defs().pet(o.type).name);
            default -> Comparator.comparingInt((OwnedPet o) -> -PetMath.RARITIES.indexOf(o.tier));
        };
        l.sort(c.thenComparing(o -> o.type).thenComparingDouble(o -> -o.exp));
        return l;
    }

    private int lvl(OwnedPet o) {
        View v = svc.view(o);
        return v == null ? 0 : v.level();
    }

    private void backToSkyMenu(Menu m) {
        m.set(48, Icons.back("SkyBlock Menu"), c -> Sky.service(Features.SkyMenu.class).open(c.player()));
    }

    // ---- Pets -------------------------------------------------------------------------------

    void openPets(Player p, int page) {
        PetData d = svc.data(p);
        if (d == null) { Msg.error(p, "Your profile is still loading."); return; }
        List<OwnedPet> list = sorted(d);
        int pages = Math.max(1, (list.size() + INNER.length - 1) / INNER.length);
        int pg = Math.max(0, Math.min(page, pages - 1));
        boolean convert = convertMode.contains(p.getUniqueId());
        Menu m = new Menu("Pets" + (pages > 1 ? " (" + (pg + 1) + "/" + pages + ")" : ""), 6).chrome();
        View active = svc.active(p);
        int score = svc.score(p);
        List<String> info = new ArrayList<>();
        info.add("&7View and manage all of your Pets.");
        info.add("");
        info.add("&7Level up your pets faster by gaining");
        info.add("&7XP in their favorite skill!");
        info.add("");
        info.add("&7Selected pet: " + (active == null ? "&cNone" : active.rarity().code() + active.def().name));
        info.add("&7Pets: &a" + d.pets.size() + "&7/&a" + mod.dialMaxPets());
        info.add("");
        info.add("&7Pet Score: &d" + score + " &7(&b+" + svc.scoreMagicFind(p) + " Magic Find&7)");
        info.add("&8Each unique pet adds its best rarity.");
        if (svc.crest(p) != null) {
            int steps = PetMath.crestSteps(d.mythKills);
            info.add("");
            info.add("&6Beastmaster Crest &7kills: &a" + Text.commas(d.mythKills) + (steps >= PetMath.CREST_MAX_STEPS ? " &8(MAX)"
                    : " &8(next growth at " + Text.commas((long) (steps + 1) * PetMath.CREST_STEP) + ")"));
        }
        m.set(4, Icons.of(Material.BONE, "&aPets", info));
        for (int i = 0; i < INNER.length; i++) {
            int idx = pg * INNER.length + i;
            if (idx >= list.size()) break;
            OwnedPet o = list.get(idx);
            View v = svc.view(o);
            if (v == null) continue;
            boolean isActive = o.uuid.equals(d.active);
            List<String> hint = new ArrayList<>();
            if (convert) hint.add("&eClick to convert to an item!");
            else {
                hint.add(isActive ? "&cClick to despawn!" : "&eClick to summon!");
                hint.add("&eRight-click for options!");
            }
            ItemStack it = icon(v, hint);
            if (isActive) Icons.glow(it);
            m.set(INNER[i], it, c -> {
                if (convertMode.contains(c.player().getUniqueId())) {
                    svc.convert(c.player(), o.uuid);
                    openPets(c.player(), pg);
                } else if (c.right()) {
                    openDetail(c.player(), o.uuid, pg);
                } else if (o.uuid.equals(d.active)) {
                    svc.despawn(c.player(), true);
                    c.player().closeInventory();
                } else {
                    svc.summon(c.player(), o.uuid);
                    c.player().closeInventory();
                }
            });
        }
        if (pg > 0) m.set(45, Icons.prevPage(pg + 1), c -> openPets(c.player(), pg - 1));
        if (pg < pages - 1) m.set(53, Icons.nextPage(pg + 1), c -> openPets(c.player(), pg + 1));
        m.set(46, Icons.of(Material.HOPPER, "&aSort", sortLore(d.sort)), c -> {
            PetData dd = svc.data(c.player());
            if (dd != null) dd.sort = (dd.sort + 1) % SORTS.length;
            openPets(c.player(), pg);
        });
        backToSkyMenu(m);
        m.set(47, Icons.of(convert ? Material.LIME_DYE : Material.GRAY_DYE, "&aConvert Pet to an Item",
                "&7Click a pet while this is on to turn", "&7it back into an item you can trade.", "",
                convert ? "&aEnabled" : "&cDisabled", "&eClick to toggle!"), c -> {
            if (!convertMode.remove(c.player().getUniqueId())) convertMode.add(c.player().getUniqueId());
            openPets(c.player(), pg);
        });
        m.set(50, Icons.of(d.hidePets ? Material.ENDER_EYE : Material.ENDER_PEARL, "&aHide Pets",
                "&7Hide every floating pet, yours and", "&7other players'.", "", d.hidePets ? "&aPets hidden" : "&cPets shown", "",
                "&8Settings also has: Show My Pet " + onOff(PetDisplay.setting(p, "pets.visible")),
                "&8and Show Other Pets " + onOff(PetDisplay.setting(p, "pets.others_visible")) + "&8.", "&eClick to toggle!"), c -> {
            PetData dd = svc.data(c.player());
            if (dd != null) dd.hidePets = !dd.hidePets;
            mod.display().viewerChanged(c.player());
            openPets(c.player(), pg);
        });
        m.set(51, Icons.of(Material.EXPERIENCE_BOTTLE, "&aExp Sharing", "&7Pets in Exp Share slots earn a part", "&7of what your summoned pet earns.", "", "&eClick to view!"),
                c -> openShare(c.player()));
        m.set(52, Icons.of(Material.COMPARATOR, "&aAutopet Rules", "&7Summon a chosen pet automatically", "&7when you arrive on an island.", "",
                "&7Rules: &a" + d.autopet.size() + "&7/&a" + mod.dialMaxRules(), "", "&eClick to view!"), c -> openAutopet(c.player()));
        m.open(p);
    }

    private static String onOff(boolean on) { return on ? "&aON" : "&cOFF"; }

    private List<String> sortLore(int cur) {
        List<String> l = new ArrayList<>();
        for (int i = 0; i < SORTS.length; i++) l.add((i == cur ? "&b> " : "&7  ") + SORTS[i]);
        l.add("");
        l.add("&eClick to switch!");
        return l;
    }

    // ---- one pet -----------------------------------------------------------------------------

    void openDetail(Player p, String uuid, int fromPage) {
        PetData d = svc.data(p);
        OwnedPet o = d == null ? null : d.find(uuid);
        if (o == null) { openPets(p, fromPage); return; }
        View v = svc.view(o);
        Menu m = new Menu(v.def().name, 6).chrome();
        m.set(48, Icons.back("Pets"), c -> openPets(c.player(), fromPage));
        m.set(13, icon(v, List.of()));
        boolean isActive = uuid.equals(d.active);
        m.set(29, Icons.of(isActive ? Material.REDSTONE : Material.LIME_DYE, isActive ? "&cDespawn Pet" : "&aSummon Pet",
                isActive ? "&eClick to despawn!" : "&eClick to summon!"), c -> {
            if (isActive) svc.despawn(c.player(), true); else svc.summon(c.player(), uuid);
            openDetail(c.player(), uuid, fromPage);
        });
        // held item
        if (o.heldItem != null && Sky.items().exists(o.heldItem)) {
            ItemDef h = Sky.items().def(o.heldItem);
            List<String> l = new ArrayList<>(h.lore);
            l.add("");
            l.add("&eClick to take it back!");
            m.set(31, Icons.of(Material.CHEST_MINECART, "&6Held Item: " + h.rarity.code() + h.name, l), c -> {
                svc.takeHeld(c.player(), d.find(uuid));
                openDetail(c.player(), uuid, fromPage);
            });
        } else {
            m.set(31, Icons.of(Material.MINECART, "&6Held Item: &7None", "&7Give this pet one pet item from", "&7your inventory.", "", "&eClick to choose!"),
                    c -> pickFromInventory(c.player(), "Give a Pet Item", id -> svc.defs().held.containsKey(id),
                            (pl, stack) -> { svc.giveHeldTo(pl, svc.data(pl).find(uuid), stack); openDetail(pl, uuid, fromPage); }));
        }
        m.set(33, Icons.of(Material.GOLDEN_CARROT, "&aFeed Candy", "&7Candy used: &a" + o.candyUsed + "&7/&a10", "", "&eClick to choose a candy!"),
                c -> pickFromInventory(c.player(), "Feed Candy", id -> svc.defs().candies.containsKey(id),
                        (pl, stack) -> { svc.feedTo(pl, svc.data(pl).find(uuid), stack); openDetail(pl, uuid, fromPage); }));
        boolean shared = d.expShare.contains(uuid);
        m.set(39, Icons.of(Material.EXPERIENCE_BOTTLE, shared ? "&cRemove from Exp Share" : "&aAdd to Exp Share",
                "&7Slots used: &a" + d.expShare.size() + "&7/&a" + svc.shareSlots(sharePerk()), "", "&eClick to toggle!"), c -> {
            toggleShare(c.player(), uuid);
            openDetail(c.player(), uuid, fromPage);
        });
        m.set(41, Icons.of(Material.NAME_TAG, "&aConvert to Item", "&7Turn this pet back into an item.", "", "&eClick to convert!"), c -> {
            svc.convert(c.player(), uuid);
            openPets(c.player(), fromPage);
        });
        // skin (wave 5): cosmetic only
        PetDefs.Skin worn = o.skin == null ? null : svc.defs().skins.get(o.skin);
        if (worn != null) {
            m.set(24, Icons.skull(worn.skull, "&dSkin: " + worn.name, List.of("&7This pet is wearing a skin. Skins only", "&7change how it looks.", "",
                    "&eClick to take it off!")), c -> {
                svc.removeSkin(c.player(), svc.data(c.player()).find(uuid));
                openDetail(c.player(), uuid, fromPage);
            });
        } else {
            m.set(24, Icons.of(Material.ARMOR_STAND, "&dSkin: &7None", "&7Give this pet a skin from your", "&7inventory. Skins only change its look.", "",
                    "&8Skins: Pet Sitter -> Pet Skins (Bits).", "", "&eClick to choose!"),
                    c -> pickFromInventory(c.player(), "Apply a Skin", id -> { PetDefs.Skin sk = svc.defs().skins.get(id); return sk != null && sk.fits(o.type); },
                            (pl, stack) -> { svc.applySkinTo(pl, svc.data(pl).find(uuid), stack); openDetail(pl, uuid, fromPage); }));
        }
        if (isActive && mod.forms() != null && mod.forms().canMorph(p)) {
            boolean on = mod.forms().morphed(p);
            m.set(20, Icons.of(Material.RABBIT_FOOT, on ? "&cChange Back" : "&aTake Its Form", "&7Same as right-clicking your floating", "&7pet or &e/pet morph&7.", "",
                    on ? "&eClick to change back!" : "&eClick to shapeshift!"), c -> {
                c.player().closeInventory();
                mod.forms().toggleMorph(c.player());
            });
        }
        if (isActive && mod.ride() != null && mod.ride().rideFx(p) != null) {
            boolean on = mod.ride().riding(p);
            m.set(22, Icons.of(Material.SADDLE, on ? "&cGet Off" : "&aRide Pet", "&7Same as right-clicking your floating", "&7pet or &e/pet ride&7. Sneak to get off.", "",
                    on ? "&eClick to get off!" : "&eClick to ride!"), c -> {
                c.player().closeInventory();
                mod.ride().toggle(c.player());
            });
        }
        m.open(p);
    }

    boolean sharePerk() { return Sky.service(Features.Mayors.class).perkActive(mod.dialSharePerk()); }

    void toggleShare(Player p, String uuid) {
        PetData d = svc.data(p);
        if (d == null) return;
        if (d.expShare.remove(uuid)) { Msg.success(p, "Removed from Exp Share."); return; }
        if (uuid.equals(d.active)) { Msg.error(p, "Your summoned pet already earns full XP."); return; }
        if (d.expShare.size() >= svc.shareSlots(sharePerk())) { Msg.error(p, "Your Exp Share slots are full!"); return; }
        d.expShare.add(uuid);
        Msg.success(p, "Added to Exp Share.");
    }

    /** Lists matching stacks from the player's inventory; click one to use it. */
    void pickFromInventory(Player p, String title, java.util.function.Predicate<String> filter, java.util.function.BiConsumer<Player, ItemStack> use) {
        List<Integer> slots = new ArrayList<>();
        ItemStack[] inv = p.getInventory().getStorageContents();
        for (int i = 0; i < inv.length; i++) {
            String id = Sky.items().idOf(inv[i]);
            if (id != null && filter.test(id)) slots.add(i);
        }
        if (slots.isEmpty()) { Msg.error(p, "You don't have anything for that in your inventory."); return; }
        Sky.menus().paged(title, slots, s -> {
            ItemStack it = p.getInventory().getItem(s);
            return it == null ? Icons.filler() : Icons.withLore(it, List.of("", "&eClick to use!"));
        }, pc -> {
            Player pl = pc.click().player();
            ItemStack it = pl.getInventory().getItem(pc.entry());
            String id = Sky.items().idOf(it);
            if (id == null || !filter.test(id)) { Msg.error(pl, "That item moved."); pl.closeInventory(); return; }
            use.accept(pl, it);
        }, null, 0).open(p);
    }

    // ---- Exp Share ------------------------------------------------------------------------------

    void openShare(Player p) {
        PetData d = svc.data(p);
        if (d == null) return;
        int slots = svc.shareSlots(sharePerk());
        int taming = svc.tamingLevel(p);
        Menu m = new Menu("Exp Sharing", 6).chrome();
        m.set(48, Icons.back("Pets"), c -> openPets(c.player(), 0));
        m.set(4, Icons.of(Material.EXPERIENCE_BOTTLE, "&aExp Sharing",
                "&7Pets in these slots receive a share of", "&7every bit of pet XP your summoned", "&7pet earns.", "",
                "&7Base rate: &a" + PetMath.fmt(PetMath.shareRate(taming, false, sharePerk(), 0)) + "% &8(Taming " + taming + ")",
                "&7With an Exp Share item: &a" + PetMath.fmt(PetMath.shareRate(taming, true, sharePerk(), 0)) + "%",
                "&8Maximum 47%."));
        int[] pos = {11, 13, 15};
        for (int i = 0; i < Math.max(slots, 3); i++) {
            int slot = i < pos.length ? pos[i] : 19 + i;
            if (i >= slots) {
                m.set(slot, Icons.of(Material.GRAY_STAINED_GLASS_PANE, "&cLocked Slot", "&7Opens while the pet-sharing mayor", "&7perk is active."));
                continue;
            }
            String id = i < d.expShare.size() ? d.expShare.get(i) : null;
            OwnedPet o = d.find(id);
            if (o == null) {
                m.set(slot, Icons.of(Material.LIGHT_GRAY_STAINED_GLASS_PANE, "&aEmpty Slot", "&eClick to choose a pet!"), c -> pickPet(c.player(), "Choose a Pet",
                        op -> !op.uuid.equals(svc.data(c.player()).active) && !svc.data(c.player()).expShare.contains(op.uuid),
                        (pl, op) -> { toggleShare(pl, op.uuid); openShare(pl); }));
            } else {
                View v = svc.view(o);
                m.set(slot, icon(v, List.of("&eClick to remove!")), c -> { toggleShare(c.player(), o.uuid); openShare(c.player()); });
            }
        }
        m.open(p);
    }

    void pickPet(Player p, String title, java.util.function.Predicate<OwnedPet> filter, java.util.function.BiConsumer<Player, OwnedPet> then) {
        PetData d = svc.data(p);
        if (d == null) return;
        List<OwnedPet> list = new ArrayList<>();
        for (OwnedPet o : sorted(d)) if (filter.test(o)) list.add(o);
        if (list.isEmpty()) { Msg.error(p, "You have no pet that fits."); return; }
        Sky.menus().paged(title, list, o -> icon(svc.view(o), List.of("&eClick to select!")),
                pc -> then.accept(pc.click().player(), pc.entry()), null, 0).open(p);
    }

    // ---- Autopet ---------------------------------------------------------------------------------

    void openAutopet(Player p) {
        PetData d = svc.data(p);
        if (d == null) return;
        Menu m = new Menu("Autopet Rules", 6).chrome();
        m.set(48, Icons.back("Pets"), c -> openPets(c.player(), 0));
        m.set(4, Icons.of(Material.COMPARATOR, "&aAutopet Rules", "&7When you arrive on an island, the", "&7pet chosen for it is summoned for you.",
                "", "&7Right-click a rule to delete it."));
        int[] slots = {19, 20, 21, 22, 23, 24, 25, 28, 29, 30};
        for (int i = 0; i < slots.length; i++) {
            if (i >= d.autopet.size()) break;
            PetData.AutoRule r = d.autopet.get(i);
            OwnedPet o = d.find(r.petUuid);
            View v = svc.view(o);
            IslandDef isl = Sky.worlds().island(r.island);
            String where = isl == null ? r.island : isl.displayName;
            ItemStack it = v == null ? Icons.of(Material.BARRIER, "&cMissing pet") : Icons.skull(v.def().skull, "&a" + where + " &7-> " + PetLore.title(v),
                    List.of("&7Arriving on &a" + where, "&7summons " + PetLore.title(v), "", "&cRight-click to delete!"));
            int idx = i;
            m.set(slots[i], it, c -> {
                if (!c.right()) return;
                PetData dd = svc.data(c.player());
                if (dd != null && idx < dd.autopet.size()) dd.autopet.remove(idx);
                openAutopet(c.player());
            });
        }
        if (d.autopet.size() < mod.dialMaxRules()) {
            m.set(40, Icons.of(Material.LIME_DYE, "&aCreate Rule", "&7Pick a pet, then an island.", "", "&eClick to create!"),
                    c -> pickPet(c.player(), "Choose the Pet", o -> true, (pl, o) -> pickIsland(pl, o.uuid)));
        }
        m.open(p);
    }

    private void pickIsland(Player p, String petUuid) {
        List<IslandDef> islands = new ArrayList<>(Sky.worlds().islands());
        Sky.menus().paged("Choose the Island", islands, i -> Icons.of(Material.GRASS_BLOCK, "&a" + i.displayName, "&eClick to select!"), pc -> {
            Player pl = pc.click().player();
            PetData d = svc.data(pl);
            if (d == null) return;
            d.autopet.removeIf(r -> r.island.equals(pc.entry().id));
            PetData.AutoRule r = new PetData.AutoRule();
            r.island = pc.entry().id;
            r.petUuid = petUuid;
            d.autopet.add(r);
            Msg.success(pl, "Autopet rule created.");
            openAutopet(pl);
        }, null, 0).open(p);
    }

    // ---- Pet Sitter -------------------------------------------------------------------------------

    void openKat(Player p) {
        PetData d = svc.data(p);
        if (d == null) { Msg.error(p, "Your profile is still loading."); return; }
        Menu m = new Menu("Pet Sitter", 6).chrome();
        m.set(4, Icons.of(Material.ORANGE_DYE, mod.katName(), "&7I can raise a pet one rarity at a time.", "&7It costs coins, sometimes items, and",
                "&7I need some time with your pet.", "", "&7Your pet keeps all of its XP."));
        PetData.KatJob job = d.kat;
        if (job == null) {
            m.set(22, Icons.of(Material.BONE, "&aUpgrade a Pet", "&7Choose a pet from your pets menu to", "&7leave with me.", "", "&eClick to choose!"),
                    c -> pickPet(c.player(), "Choose a Pet to Upgrade",
                            o -> svc.defs().katStep(o.type, o.tier) != null && !o.uuid.equals(svc.data(c.player()).active),
                            (pl, o) -> confirmKat(pl, o.uuid)));
        } else {
            View v = svc.view(job.pet);
            long left = Math.max(0, job.readyAt - System.currentTimeMillis());
            List<String> l = new ArrayList<>(List.of("&7Upgrading to " + Rarity.parse(job.toTier).code() + Text.titleCase(job.toTier), ""));
            if (left == 0) l.add("&aReady! &eClick to collect!");
            else { l.add("&7Time left: &e" + Text.duration(left / 1000)); l.add(""); l.add("&8Right-click to take it back early"); l.add("&8(no refund)."); }
            m.set(22, v == null ? Icons.of(Material.BARRIER, "&cUnknown pet") : Icons.skull(svc.skull(v), PetLore.title(v), l), c -> {
                if (left == 0) mod.kat().claim(c.player());
                else if (c.right()) Sky.menus().confirm(c.player(), "Take your pet back?", List.of("&7The upgrade is cancelled and", "&cnothing is refunded."),
                        () -> mod.kat().cancel(c.player()), null);
                openKat(c.player());
            });
            if (left > 0) {
                int slot = 30;
                for (var e : svc.defs().meta.skipItems.entrySet()) {
                    String id = e.getKey();
                    if (!Sky.items().exists(id)) continue;
                    ItemDef def = Sky.items().def(id);
                    int have = Sky.items().count(p, id);
                    m.set(slot, Icons.withLore(Sky.items().create(id, 1), List.of("", "&7You have: &a" + have, have > 0 ? "&eClick to use one!" : "&cYou have none.")), c -> {
                        mod.kat().skip(c.player(), id);
                        openKat(c.player());
                    });
                    slot += 2;
                }
            }
        }
        m.set(40, Icons.of(Material.ARMOR_STAND, "&dPet Skins", "&7Change how a pet looks. Skins are", "&7cosmetic: they never change stats.", "",
                "&7Paid in &bBits&7, which you earn in game.", "&7Your Bits: &b" + Text.commas(Sky.economy().bits(p)), "", "&eClick to browse!"),
                c -> openSkins(c.player()));
        m.set(38, Icons.of(Material.CHEST, "&aPet Supplies", "&7Pet items and the materials I need", "&7for certain upgrades.", "", "&eClick to browse!"),
                c -> openSupplies(c.player()));
        int cap = mod.collectorCap(p);
        m.set(42, Icons.of(Material.WRITABLE_BOOK, "&aPet Collector", "&7Bring me specific pets to keep and", "&7I will raise your Taming cap.", "",
                "&7Taming cap: &a" + cap + "&7/&a60", "", "&eClick to view!"), c -> openCollector(c.player()));
        m.open(p);
    }

    private void confirmKat(Player p, String uuid) {
        PetData d = svc.data(p);
        OwnedPet o = d == null ? null : d.find(uuid);
        if (o == null) return;
        PetDefs.KatStep st = svc.defs().katStep(o.type, o.tier);
        if (st == null) { Msg.error(p, "That pet can't be upgraded any further."); return; }
        View v = svc.view(o);
        List<String> lore = new ArrayList<>();
        lore.add("&7" + PetLore.title(v) + " &7-> " + Rarity.parse(st.to).code() + Text.titleCase(st.to));
        lore.add("");
        lore.add("&7Cost:");
        if (st.coins > 0) lore.add(" &6" + Text.commas(st.coins * mod.dialKatCoinScale()) + " Coins");
        for (String it : st.items) {
            String[] sp = mod.kat().splitCost(it);
            ItemDef def = Sky.items().def(sp[0]);
            lore.add(" " + (def == null ? "&c" + sp[0] : def.rarity.code() + def.name) + " &8x" + sp[1]);
        }
        lore.add("");
        lore.add("&7Duration: &e" + Text.duration((long) (st.seconds * mod.dialKatTimeScale())));
        Integer tam = svc.defs().meta.katTaming.get(st.to);
        if (tam != null) lore.add("&7Requires &aTaming " + tam);
        Sky.menus().confirm(p, "Upgrade " + v.def().name + "?", lore, () -> { mod.kat().start(p, uuid); openKat(p); }, () -> openKat(p));
    }

    void openSupplies(Player p) {
        List<Object[]> entries = new ArrayList<>();
        for (PetDefs.ShopEntry e : svc.defs().meta.shop) if (Sky.items().exists(e.id)) entries.add(new Object[]{e.id, e.coins});
        for (PetDefs.Supply e : svc.defs().meta.supplies) if (Sky.items().exists(e.id)) entries.add(new Object[]{e.id, (long) Math.round(e.coins * mod.dialSupplyScale())});
        Sky.menus().paged("Pet Supplies", entries, e -> {
            ItemStack it = Sky.items().create((String) e[0], 1);
            return Icons.withLore(it, List.of("", "&7Cost", "&6" + Text.commas((Long) e[1]) + " Coins", "", "&eClick to buy!"));
        }, pc -> {
            Player pl = pc.click().player();
            String id = (String) pc.entry()[0];
            long price = (Long) pc.entry()[1];
            if (!mod.rateOk(pl)) return;
            if (pl.getInventory().firstEmpty() < 0) { Msg.error(pl, "Your inventory is full!"); return; }
            if (!Sky.economy().withdraw(pl, price, "pets: supplies buy " + id)) { Msg.error(pl, "You don't have enough coins!"); return; }
            Sky.loot().give(pl, id, 1, com.skyclone.api.loot.LootSource.NPC_SHOP);
            ItemDef def = Sky.items().def(id);
            Msg.success(pl, "You bought " + def.rarity.code() + def.name + "&a!");
        }, page -> page.set(48, Icons.back("Pet Sitter"), c -> openKat(c.player())), 0).open(p);
    }

    /** Pet Skins shop (wave 5): every skin, the ones for pets you own first. Bits only (earned), never real money. */
    void openSkins(Player p) {
        PetData d = svc.data(p);
        if (d == null) return;
        java.util.Set<String> owned = new java.util.HashSet<>();
        for (OwnedPet o : d.pets) owned.add(o.type);
        List<PetDefs.Skin> list = new ArrayList<>();
        for (PetDefs.Skin sk : svc.defs().skins.values()) if (Sky.items().exists(sk.id)) list.add(sk);
        list.sort(Comparator.comparing((PetDefs.Skin sk) -> sk.pets.stream().noneMatch(owned::contains))
                .thenComparing(sk -> sk.pets.isEmpty() ? "" : sk.pets.get(0)).thenComparing(sk -> sk.name));
        Sky.menus().paged("Pet Skins", list, sk -> {
            List<String> l = new ArrayList<>();
            l.add("&7For: &a" + String.join(", ", sk.pets.stream().map(t -> svc.defs().pet(t) == null ? t : svc.defs().pet(t).name).toList()));
            l.add(sk.pets.stream().anyMatch(owned::contains) ? "&aYou own this pet!" : "&8You don't own this pet yet.");
            l.add("");
            l.add("&7Cost");
            l.add("&b" + Text.commas(mod.skinPrice(sk)) + " Bits");
            l.add("");
            l.add("&eClick to buy!");
            return Icons.skull(sk.skull, Rarity.parse(sk.rarity).code() + sk.name, l);
        }, pc -> {
            Player pl = pc.click().player();
            PetDefs.Skin sk = pc.entry();
            if (!mod.rateOk(pl)) return;
            if (pl.getInventory().firstEmpty() < 0) { Msg.error(pl, "Your inventory is full!"); return; }
            long price = mod.skinPrice(sk);
            if (!Sky.economy().spendBits(pl, price, "pets: skin " + sk.id)) { Msg.error(pl, "You don't have enough Bits!"); return; }
            Sky.loot().give(pl, sk.id, 1, com.skyclone.api.loot.LootSource.NPC_SHOP);
            Msg.success(pl, "You bought " + Rarity.parse(sk.rarity).code() + sk.name + "&a! &7Right-click it with the pet summoned.");
        }, page -> page.set(48, Icons.back("Pet Sitter"), c -> openKat(c.player())), 0).open(p);
    }

    void openCollector(Player p) {
        PetData d = svc.data(p);
        if (d == null) return;
        Menu m = new Menu("Pet Collector", 6).chrome();
        m.set(48, Icons.back("Pet Sitter"), c -> openKat(c.player()));
        m.set(4, Icons.of(Material.WRITABLE_BOOK, "&aPet Collector", "&7Each pet below that you give me raises", "&7your Taming cap by &a1&7.",
                "&cThe pet is given away for good.", "", "&7Taming cap: &a" + mod.collectorCap(p) + "&7/&a60"));
        int[] slots = {10, 11, 12, 13, 14, 15, 16, 21, 22, 23};
        List<String[]> req = svc.defs().meta.collector;
        for (int i = 0; i < req.size() && i < slots.length; i++) {
            String type = req.get(i)[0], tier = req.get(i)[1];
            PetDefs.PetDef def = svc.defs().pet(type);
            if (def == null) continue;
            boolean done = d.collector.contains(type);
            OwnedPet match = null;
            for (OwnedPet o : d.pets) if (o.type.equals(type) && o.tier.equals(tier) && !o.uuid.equals(d.active)) { match = o; break; }
            List<String> l = new ArrayList<>(List.of("&7Wanted: " + Rarity.parse(tier).code() + Text.titleCase(tier) + " " + def.name, ""));
            if (done) l.add("&aDelivered!");
            else if (match != null) l.add("&eClick to give it away!");
            else l.add("&cYou don't have one (not summoned).");
            ItemStack it = Icons.skull(def.skull, (done ? "&a" : "&e") + def.name, l);
            if (done) Icons.glow(it);
            OwnedPet give = match;
            m.set(slots[i], it, c -> {
                if (done || give == null) return;
                Sky.menus().confirm(c.player(), "Give away " + def.name + "?", List.of("&7The pet leaves your pets menu", "&7for good. &a+1 Taming cap."),
                        () -> { mod.donate(c.player(), give.uuid, type); openCollector(c.player()); }, () -> openCollector(c.player()));
            });
        }
        m.open(p);
    }

    void forget(Player p) { convertMode.remove(p.getUniqueId()); }

    @SuppressWarnings("unused")
    private static void noop(Consumer<Player> c) { }
}
