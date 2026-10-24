package com.skyclone.mod.extras;

import com.skyclone.api.Sky;
import com.skyclone.api.event.SkyEvents;
import com.skyclone.api.feature.Features;
import com.skyclone.api.item.ItemDef;
import com.skyclone.api.npc.NpcDef;
import com.skyclone.api.ui.Icons;
import com.skyclone.api.ui.Menu;
import com.skyclone.api.ui.Msg;
import com.skyclone.api.ui.Text;
import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.event.ClickEvent;
import net.kyori.adventure.text.event.HoverEvent;
import org.bukkit.Bukkit;
import org.bukkit.Material;
import org.bukkit.NamespacedKey;
import org.bukkit.Sound;
import org.bukkit.entity.Entity;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.Listener;
import org.bukkit.event.block.Action;
import org.bukkit.event.player.PlayerInteractEntityEvent;
import org.bukkit.event.player.PlayerInteractEvent;
import org.bukkit.inventory.EquipmentSlot;
import org.bukkit.inventory.ItemStack;
import org.bukkit.persistence.PersistentDataType;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Random;
import java.util.UUID;

/**
 * The phone (research 08 section 6.4, the reference's "Abiphone"; ours is the Ringphone). Contacts are added
 * by clicking an NPC while holding a phone (or from the Contacts Directory when standing next to them), each
 * with the reference contact's requirement; calling a contact opens that NPC's service from anywhere. The
 * contact limit comes from the best phone in the inventory plus Contacts Trios (+3 each, up to 25). Apps by
 * model: Contacts Directory, Do Not Disturb, Ringtones, Snake, Tic Tac Toe, Speed Dial.
 */
final class Phone implements Features.Abiphone, Listener {

    /** Playtest wave 5: an island's display name (a title-cased id leaked reference names like "Galatea"). */
    private static String islandName(String id) {
        if (id == null) return "";
        com.skyclone.api.world.IslandDef d = com.skyclone.api.Sky.worlds().island(id);
        return d != null && d.displayName != null ? com.skyclone.api.ui.Text.strip(d.displayName) : com.skyclone.api.ui.Text.titleCase(id.replace('_', ' '));
    }

    static final NamespacedKey NPC_KEY = NamespacedKey.fromString("skyclone:npc");
    static final String COMMUNITY = "extras_community";

    private final Map<UUID, Long> lastCall = new HashMap<>();
    private final Map<UUID, String> ringing = new HashMap<>();     // incoming call waiting for an answer
    private final Random rnd = new Random();

    // ---- Features.Abiphone -------------------------------------------------------------------------------

    @Override public void addContact(Player p, String contactId) {
        if (!X.ready(p)) return;
        Catalog.Contact c = X.catalog.contact(contactId);
        if (c == null) return;
        ExtrasData d = X.data(p);
        if (d.contacts.contains(c.id)) return;
        int cap = capacity(p);
        if (d.contacts.size() >= cap && cap > 0) {
            Msg.send(p, "&c" + display(c) + " &cwanted to give you their number, but your phone is full!");
            return;
        }
        if (cap == 0) {
            Msg.send(p, "&7" + display(c) + " &7left you their number. Get a phone to call them!");
        }
        added(p, c);
    }

    @Override public boolean hasContact(Player p, String contactId) {
        if (!X.ready(p)) return false;
        Catalog.Contact c = X.catalog.contact(contactId);
        return c != null && X.data(p).contacts.contains(c.id);
    }

    @Override public int contacts(Player p) {
        return X.ready(p) ? X.data(p).contacts.size() : 0;
    }

    // ---- phones ------------------------------------------------------------------------------------------

    /** The best phone the player carries (inventory, off hand included), or null. */
    static Catalog.Phone bestPhone(Player p) {
        Catalog.Phone best = null;
        for (ItemStack it : p.getInventory().getContents()) {
            if (it == null || it.getType().isAir()) continue;
            Catalog.Phone ph = X.catalog.phones.get(Sky.items().idOf(it));
            if (ph != null && (best == null || ph.contacts > best.contacts || (ph.contacts == best.contacts && ph.discs > best.discs)))
                best = ph;
        }
        return best;
    }

    static int capacity(Player p) { return Catalog.capacity(bestPhone(p), X.data(p).trios); }

    static String phoneName(Catalog.Phone ph) { return ph == null ? "&7No phone" : X.itemName(ph.id); }

    /** The first registered NPC of a contact, or null when none of its NPCs exists on this server. */
    static String npcOf(Catalog.Contact c) {
        for (String id : c.npcs) if (Sky.npcs().def(id) != null) return id;
        return null;
    }

    static boolean available(Catalog.Contact c) { return npcOf(c) != null; }

    static String display(Catalog.Contact c) {
        String n = X.npcName(npcOf(c));
        return n == null ? "&f" + Text.titleCase(c.id) : n;
    }

    // ---- adding contacts ---------------------------------------------------------------------------------

    /** Human list of what a contact asks for. */
    static List<String> describe(Catalog.Req r) {
        List<String> out = new ArrayList<>();
        if (r == null || r.none()) { out.add("&7No requirement."); return out; }
        if (r.contact != null) {
            Catalog.Contact o = X.catalog.contacts.get(r.contact);
            out.add("&7Have " + (o == null ? r.contact : display(o)) + " &7as a contact");
        }
        if (r.key != null) {
            String t;
            try { t = Sky.requirements().describe(r.key); } catch (RuntimeException e) { t = r.key; }
            out.add("&7" + t);
        }
        if (r.set != null) out.add("&7Wear the full " + Text.titleCase(r.set) + " set");
        if (r.items != null) r.items.forEach((id, n) -> out.add("&7" + (r.keep ? "Show " : "") + "&a" + Text.commas(n) + "x " + X.itemName(id)));
        if (r.coins > 0) out.add("&6" + Text.commas(r.coins) + " coins");
        if (r.essence != null) r.essence.forEach((t, n) -> out.add("&d" + Text.commas(n) + " " + Text.titleCase(t) + " Essence"));
        return out;
    }

    /** Check, pay and add. Tells the player what is missing. */
    void tryAdd(Player p, Catalog.Contact c) {
        if (!X.ready(p)) return;
        ExtrasData d = X.data(p);
        String name = display(c);
        if (d.contacts.contains(c.id)) { Msg.send(p, name + " &eis already in your contacts."); return; }
        int cap = capacity(p);
        if (cap <= 0) { Msg.error(p, "You need a phone to save contacts."); return; }
        if (d.contacts.size() >= cap) { Msg.error(p, "Your phone is full! (" + d.contacts.size() + "/" + cap + " contacts)"); return; }
        Catalog.Req r = c.req;
        List<String> missing = new ArrayList<>();
        if (r.contact != null && !d.contacts.contains(r.contact)) missing.add("another contact first");
        if (r.key != null) {
            boolean ok;
            try { ok = Sky.requirements().check(p, r.key); } catch (RuntimeException e) { ok = false; }
            if (!ok) missing.add("a requirement");
        }
        if (r.set != null) {
            String set = Sky.items().fullSet(p);
            if (set == null || !set.equalsIgnoreCase(r.set)) missing.add("a full armour set");
        }
        if (r.items != null) for (Map.Entry<String, Integer> e : r.items.entrySet())
            if (Sky.items().count(p, e.getKey()) < e.getValue()) missing.add("items");
        if (r.coins > 0 && Sky.economy().purse(p) < r.coins) missing.add("coins");
        Features.Essence ess = Sky.service(Features.Essence.class);
        if (r.essence != null) for (Map.Entry<String, Long> e : r.essence.entrySet())
            if (ess.essence(p, e.getKey()) < e.getValue()) missing.add("essence");
        if (!missing.isEmpty()) {
            List<String> lines = new ArrayList<>();
            lines.add("&7To get " + name + "&7's number:");
            for (String s : describe(r)) lines.add("&8 • " + s);
            Msg.box(p, "&c&lNOT YET", lines);
            return;
        }
        // pay: coins first (atomic), then essence, then items (counted this same tick)
        if (r.coins > 0 && !Sky.economy().withdraw(p, r.coins, "extras: phone contact " + c.id)) { Msg.error(p, "You cannot afford that."); return; }
        if (r.essence != null && !r.essence.isEmpty()) {
            for (Map.Entry<String, Long> e : r.essence.entrySet()) {
                if (!ess.takeEssence(p, e.getKey(), e.getValue())) {
                    if (r.coins > 0) Sky.economy().deposit(p, r.coins, "extras: phone contact refund " + c.id);
                    Msg.error(p, "You do not have enough essence.");
                    return;
                }
            }
        }
        if (!r.keep && r.items != null) r.items.forEach((id, n) -> Sky.items().remove(p, id, n));
        added(p, c);
    }

    private void added(Player p, Catalog.Contact c) {
        ExtrasData d = X.data(p);
        if (d.contacts.contains(c.id)) return;
        d.contacts.add(c.id);
        String name = display(c);
        Msg.send(p, "&a✆ " + name + " &ahas been added to your contacts! &7(" + d.contacts.size() + "/" + Math.max(capacity(p), d.contacts.size()) + ")");
        p.playSound(p.getLocation(), Sound.BLOCK_NOTE_BLOCK_CHIME, 1f, 1.6f);
        X.sbxp(p, "abiphone:" + c.id, XDials.phoneContactXp.get(), "Phone contact");
        Sky.profiles().saveNow(p);
    }

    @EventHandler(priority = EventPriority.HIGH)
    public void onNpcClick(PlayerInteractEntityEvent e) {
        if (e.getHand() != EquipmentSlot.HAND || !XDials.phoneAddOnClick.get()) return;
        Player p = e.getPlayer();
        ItemStack hand = p.getInventory().getItemInMainHand();
        if (hand.getType().isAir() || !X.catalog.phones.containsKey(Sky.items().idOf(hand))) return;
        Entity ent = e.getRightClicked();
        String npc = ent.getPersistentDataContainer().get(NPC_KEY, PersistentDataType.STRING);
        if (npc == null || npc.isEmpty()) return;
        Catalog.Contact c = X.catalog.contact(npc);
        if (c == null) return;
        tryAdd(p, c);
    }

    // ---- consumables ---------------------------------------------------------------------------------------

    @EventHandler(priority = EventPriority.LOW)
    public void onUse(PlayerInteractEvent e) {
        if (e.getHand() != EquipmentSlot.HAND) return;
        if (e.getAction() != Action.RIGHT_CLICK_AIR && e.getAction() != Action.RIGHT_CLICK_BLOCK) return;
        ItemStack hand = e.getItem();
        if (hand == null || !"TRIO_CONTACTS_ADDON".equals(Sky.items().idOf(hand))) return;
        e.setCancelled(true);
        Player p = e.getPlayer();
        if (!X.ready(p)) return;
        ExtrasData d = X.data(p);
        if (d.trios >= 25) { Msg.error(p, "Your phone plan already has the most contact slots it can hold."); return; }
        if (hand.getAmount() > 1) hand.setAmount(hand.getAmount() - 1); else p.getInventory().setItemInMainHand(null);
        d.trios++;
        Msg.send(p, "&a✆ Your phone plan gained &a+3 &acontact slots! &7(" + d.trios + "/25 Trios used)");
        p.playSound(p.getLocation(), Sound.ENTITY_PLAYER_LEVELUP, 1f, 1.5f);
        Sky.profiles().saveNow(p);
    }

    // ---- calling -------------------------------------------------------------------------------------------

    void call(Player p, Catalog.Contact c, boolean answering) {
        if (!X.ready(p)) return;
        if (!X.data(p).contacts.contains(c.id)) { Msg.error(p, "That person is not in your contacts."); return; }
        if (bestPhone(p) == null) { Msg.error(p, "You need a phone to make calls."); return; }
        String npc = npcOf(c);
        if (npc == null) { Msg.error(p, "That number is not in service."); return; }
        long now = System.currentTimeMillis();
        Long last = lastCall.get(p.getUniqueId());
        if (!answering && last != null && now - last < 3000) { Msg.error(p, "You are already on a call!"); return; }
        lastCall.put(p.getUniqueId(), now);
        String name = display(c);
        p.closeInventory();
        long delay = 0;
        if (!answering) {
            Msg.send(p, "&e✆ Calling " + name + "&e...");
            Ringtones.play(p, X.data(p).ringtone, 6);
            delay = 40;
        }
        Bukkit.getScheduler().runTaskLater(Sky.plugin(), () -> {
            if (!p.isOnline()) return;
            String line = c.lines.isEmpty() ? "Hello?" : c.lines.get(rnd.nextInt(c.lines.size()));
            line = Catalog.fillLine(line, id -> {
                String n = X.npcName(id);
                return n == null || n.equals(id) ? null : com.skyclone.api.ui.Text.strip(n);
            });
            Msg.send(p, "&e✆ " + name + "&f: " + line);
            Bukkit.getScheduler().runTaskLater(Sky.plugin(), () -> { if (p.isOnline()) service(p, c, name); }, 20L);
        }, delay);
    }

    private void service(Player p, Catalog.Contact c, String name) {
        if (c.command == null) return;
        if (COMMUNITY.equals(c.command)) { Community.open(p); return; }
        boolean ok;
        try { ok = Sky.commands().exists(c.command) && Sky.commands().run(p, c.command, c.args.toArray(new String[0])); }
        catch (RuntimeException ex) { ok = false; }
        if (!ok) Msg.send(p, "&e✆ " + name + "&f: Sorry, I can't help you with that right now.");
    }

    void speedDial(Player p) {
        if (!X.ready(p)) return;
        ExtrasData d = X.data(p);
        Catalog.Phone ph = bestPhone(p);
        if (ph == null || !ph.has("speed_dial")) { Msg.error(p, "Your phone has no Speed Dial."); return; }
        Catalog.Contact c = X.catalog.contacts.get(d.speedDial);
        if (c == null || !d.contacts.contains(c.id)) { Msg.error(p, "Set a Speed Dial contact in your phone first."); return; }
        call(p, c, false);
    }

    // ---- incoming calls ----------------------------------------------------------------------------------

    /** Once a minute: each player with contacts has a 1-in-N chance of a call (N = dial minutes). */
    void incomingTick() {
        int n = XDials.phoneIncomingMinutes.get();
        if (n <= 0) return;
        for (Player p : Bukkit.getOnlinePlayers()) {
            if (!X.ready(p) || ringing.containsKey(p.getUniqueId())) continue;
            if (rnd.nextInt(n) != 0) continue;
            ExtrasData d = X.data(p);
            if (d.contacts.isEmpty() || bestPhone(p) == null) continue;
            Catalog.Contact c = X.catalog.contacts.get(d.contacts.get(rnd.nextInt(d.contacts.size())));
            if (c == null || !available(c)) continue;
            ring(p, c);
        }
    }

    void ring(Player p, Catalog.Contact c) {
        ExtrasData d = X.data(p);
        Catalog.Phone ph = bestPhone(p);
        String name = display(c);
        if (d.dnd && ph != null && ph.has("dnd")) {
            missed(d, c.id);
            return;
        }
        UUID id = p.getUniqueId();
        ringing.put(id, c.id);
        Ringtones.play(p, d.ringtone, 8);
        Component msg = Text.c("&e✆ " + name + " &eis calling you! ")
                .append(Text.c("&a&l[ANSWER]").clickEvent(ClickEvent.runCommand("/phone answer"))
                        .hoverEvent(HoverEvent.showText(Text.c("&eClick to answer!"))));
        p.sendMessage(msg);
        Bukkit.getScheduler().runTaskLater(Sky.plugin(), () -> {
            String still = ringing.get(id);
            if (still != null && still.equals(c.id)) {
                ringing.remove(id);
                if (p.isOnline() && X.ready(p)) {
                    missed(X.data(p), c.id);
                    Msg.send(p, "&7✆ Missed call from " + name + "&7.");
                }
            }
        }, 20L * 20);
    }

    private static void missed(ExtrasData d, String cid) {
        d.missed.add(0, cid);
        while (d.missed.size() > 10) d.missed.remove(d.missed.size() - 1);
    }

    void answer(Player p) {
        String cid = ringing.remove(p.getUniqueId());
        if (cid == null) { Msg.error(p, "Nobody is calling you."); return; }
        Catalog.Contact c = X.catalog.contacts.get(cid);
        if (c == null) return;
        if (!c.tip.isEmpty()) Msg.send(p, "&e✆ " + display(c) + "&f: Just checking in! &7(" + c.tip + ")");
        call(p, c, true);
    }

    void clear(UUID id) {
        ringing.remove(id);
        lastCall.remove(id);
    }

    @EventHandler
    public void onUnload(SkyEvents.ProfileUnload e) { clear(e.player.getUniqueId()); }

    // ---- menus ---------------------------------------------------------------------------------------------

    void open(Player p) {
        if (!X.ready(p)) { Msg.error(p, "Your profile is still loading."); return; }
        Catalog.Phone ph = bestPhone(p);
        if (ph == null) {
            Msg.error(p, "You do not have a phone! Buy one from a phone vendor.");
            return;
        }
        home(p, ph).open(p);
    }

    Menu home(Player p, Catalog.Phone ph) {
        ExtrasData d = X.data(p);
        int cap = Catalog.capacity(ph, d.trios);
        Menu m = new Menu(Text.strip(Text.section(phoneName(ph))), 6).chrome();
        ItemDef def = Sky.items().def(ph.id);
        m.set(4, Icons.of(def != null ? Material.matchMaterial(def.material) == null ? Material.PAPER : Material.matchMaterial(def.material) : Material.PAPER,
                phoneName(ph), "&7Contacts: &a" + d.contacts.size() + "&7/&a" + cap,
                "&7Contacts Trios used: &a" + d.trios + "&7/&a25", "&7Ringtone slots: &a" + ph.discs));
        m.set(20, Icons.of(Material.PLAYER_HEAD, "&aContacts", "&7Call anyone in your contacts.", "", "&7Saved: &a" + d.contacts.size() + "&7/&a" + cap, "", "&eClick to open!"),
                c -> contacts(c.player(), 0).open(c.player()));
        m.set(21, app(ph, "directory", Material.BOOK, "&aContacts Directory", "&7Everyone who might give you", "&7their number, and what they want."),
                c -> { if (ph.has("directory")) directory(c.player(), 0).open(c.player()); });
        m.set(22, app(ph, "dnd", d.dnd ? Material.RED_DYE : Material.LIME_DYE, "&aDo Not Disturb: " + (d.dnd ? "&cON" : "&aOFF"),
                "&7Silence incoming calls. Missed calls", "&7are kept in your call log."), c -> {
                    if (!ph.has("dnd")) return;
                    ExtrasData dd = X.data(c.player());
                    dd.dnd = !dd.dnd;
                    home(c.player(), ph).open(c.player());
                });
        m.set(23, app(ph, "ringtones", Material.JUKEBOX, "&aRingtones", "&7Load songs you learned on the harp", "&7as ringtones."),
                c -> { if (ph.discs > 0) Ringtones.menu(c.player(), ph).open(c.player()); });
        m.set(24, app(ph, "snake", Material.SLIME_BALL, "&aSnake", "&7The classic. Best: &a" + d.snakeBest),
                c -> { if (ph.has("snake")) Apps.snake(c.player()); });
        m.set(30, app(ph, "tictactoe", Material.OAK_SIGN, "&aTic Tac Toe", "&7Beat your phone. Wins: &a" + d.tttWins + "&7/&a" + d.tttGames),
                c -> { if (ph.has("tictactoe")) Apps.tictactoe(c.player()); });
        Catalog.Contact sd = X.catalog.contacts.get(d.speedDial);
        m.set(31, app(ph, "speed_dial", Material.CLOCK, "&aSpeed Dial",
                "&7Left-click your phone to call:", sd == null ? "&cNobody set" : display(sd), "", "&7Right-click a contact in Contacts", "&7to set it."),
                c -> { if (ph.has("speed_dial")) speedDial(c.player()); });
        List<String> log = new ArrayList<>();
        if (d.missed.isEmpty()) log.add("&7No missed calls.");
        for (String cid : d.missed) {
            Catalog.Contact mc = X.catalog.contacts.get(cid);
            if (mc != null) log.add("&8 • " + display(mc));
        }
        log.add("");
        log.add("&eClick to clear!");
        m.set(32, Icons.of(Material.PAPER, "&aCall Log", log), c -> {
            X.data(c.player()).missed.clear();
            home(c.player(), ph).open(c.player());
        });
        X.backToSkyMenu(m);
        return m;
    }

    private static ItemStack app(Catalog.Phone ph, String feature, Material mat, String name, String... lore) {
        boolean has = "ringtones".equals(feature) ? ph.discs > 0 : ph.has(feature);
        List<String> l = new ArrayList<>(List.of(lore));
        l.add("");
        l.add(has ? "&eClick to open!" : "&cLocked &7- needs a better phone");
        return Icons.of(has ? mat : Material.GRAY_DYE, has ? name : "&c" + Text.strip(Text.section(name)), l);
    }

    Menu contacts(Player p, int page) {
        ExtrasData d = X.data(p);
        List<Catalog.Contact> list = new ArrayList<>();
        for (String cid : d.contacts) {
            Catalog.Contact c = X.catalog.contacts.get(cid);
            if (c != null) list.add(c);
        }
        return Sky.menus().paged("Contacts", list, c -> {
            List<String> l = new ArrayList<>();
            if (!c.tip.isEmpty()) l.add("&7" + c.tip);
            NpcDef nd = Sky.npcs().def(npcOf(c) == null ? "" : npcOf(c));
            if (nd != null && nd.island != null) l.add("&8" + islandName(nd.island));
            l.add("");
            if (!available(c)) l.add("&cNumber not in service.");
            else {
                l.add("&eLeft-click to call!");
                Catalog.Phone ph = bestPhone(p);
                if (ph != null && ph.has("speed_dial")) l.add(d.speedDial.equals(c.id) ? "&aSpeed Dial" : "&bRight-click to set Speed Dial!");
                l.add("&cShift-click to delete.");
            }
            return Icons.of(available(c) ? Material.PLAYER_HEAD : Material.SKELETON_SKULL, display(c), l);
        }, pc -> {
            Player pl = pc.click().player();
            Catalog.Contact c = pc.entry();
            if (pc.click().shift()) {
                Sky.menus().confirm(pl, "Delete contact?", List.of("&7Remove " + display(c) + "&7?", "&7You can add them again later."), () -> {
                    ExtrasData dd = X.data(pl);
                    dd.contacts.remove(c.id);
                    if (dd.speedDial.equals(c.id)) dd.speedDial = "";
                    contacts(pl, 0).open(pl);
                }, null);
            } else if (pc.click().right()) {
                Catalog.Phone ph = bestPhone(pl);
                if (ph != null && ph.has("speed_dial")) {
                    X.data(pl).speedDial = c.id;
                    Msg.success(pl, "Speed Dial set to " + Text.strip(Text.section(display(c))) + ".");
                    contacts(pl, pc.page()).open(pl);
                }
            } else {
                call(pl, c, false);
            }
        }, menu -> menu.set(48, Icons.back("Phone"), c -> open(c.player())), page);
    }

    Menu directory(Player p, int page) {
        ExtrasData d = X.data(p);
        List<Catalog.Contact> list = new ArrayList<>();
        for (Catalog.Contact c : X.catalog.contacts.values()) if (available(c)) list.add(c);
        return Sky.menus().paged("Contacts Directory", list, c -> {
            List<String> l = new ArrayList<>();
            if (!c.tip.isEmpty()) l.add("&7" + c.tip);
            NpcDef nd = Sky.npcs().def(npcOf(c));
            if (nd != null && nd.island != null) l.add("&8Found on: " + islandName(nd.island));
            l.add("");
            l.add("&7To get their number:");
            for (String s : describe(c.req)) l.add("&8 • " + s);
            l.add("");
            boolean have = d.contacts.contains(c.id);
            l.add(have ? "&aIn your contacts" : "&eStand next to them and click to add!");
            return Icons.of(have ? Material.LIME_DYE : Material.GRAY_DYE, display(c), l);
        }, pc -> {
            Player pl = pc.click().player();
            Catalog.Contact c = pc.entry();
            if (X.data(pl).contacts.contains(c.id)) return;
            Entity ent = Sky.npcs().entity(npcOf(c));
            if (ent == null || !ent.getWorld().equals(pl.getWorld()) || ent.getLocation().distanceSquared(pl.getLocation()) > 36) {
                Msg.error(pl, "You need to be standing next to them!");
                return;
            }
            pl.closeInventory();
            tryAdd(pl, c);
        }, menu -> menu.set(48, Icons.back("Phone"), c -> open(c.player())), page);
    }

    static String lower(String s) { return s == null ? "" : s.toLowerCase(Locale.ROOT); }
}
