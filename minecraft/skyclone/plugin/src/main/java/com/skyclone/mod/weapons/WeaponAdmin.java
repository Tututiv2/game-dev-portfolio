package com.skyclone.mod.weapons;

import com.skyclone.api.Sky;
import com.skyclone.api.ability.AbilityService;
import com.skyclone.api.item.AbilityDef;
import com.skyclone.api.item.ItemDef;
import com.skyclone.api.ui.Text;
import org.bukkit.Bukkit;
import org.bukkit.command.CommandSender;
import org.bukkit.entity.Player;
import org.bukkit.inventory.ItemStack;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

/**
 * {@code /sb weapons <sub>} — every sub that targets a player takes the name, so it works over RCON.
 * Output is synchronous (RCON only shows what is printed while the command runs).
 */
final class WeaponAdmin {

    private WeaponAdmin() {}

    private static final List<String> SUBS = List.of("info", "list", "trait", "kills", "paid", "shoot", "cast", "arrows",
            "select", "math", "menu", "quiverarrow", "upgrade", "mf", "fear", "floor", "classability", "wave5");

    static void register(WeaponsModule mod) {
        Sky.commands().admin("weapons", "<info|list [filter]|trait <id>|kills <player> <n>|paid <player> <coins>|shoot <player>"
                        + "|cast <player> [n]|arrows <player>|select <player> <arrowId|auto>|math|menu <player> [arsenal|arrows]"
                        + "|quiverarrow <player>|upgrade <player> <tuners N|merge|unmerge>|mf <player> <mobId>|fear <player>"
                        + "|floor <player> <0-9>|classability <player>|wave5 <player>>",
                "Weapons module: inspect data, set item counters, fire/cast held weapons for testing",
                (s, a) -> run(mod, s, a),
                (s, a) -> a.length <= 1 ? SUBS : List.of());
    }

    private static void say(CommandSender s, String msg) { s.sendMessage(Text.c(msg)); }

    private static Player player(CommandSender s, String[] a, int i) {
        if (a.length <= i) { say(s, "&cMissing player name."); return null; }
        Player p = Bukkit.getPlayerExact(a[i]);
        if (p == null) say(s, "&cPlayer " + a[i] + " is not online.");
        return p;
    }

    private static void run(WeaponsModule mod, CommandSender s, String[] a) {
        String sub = a.length == 0 ? "info" : a[0].toLowerCase(Locale.ROOT);
        switch (sub) {
            case "info" -> {
                say(s, "&6Weapons: &f" + mod.mainIds().size() + " &7own items, &f" + mod.fallbackIds().size()
                        + " &7fallback items registered, &f" + mod.fallbackSkipped().size() + " &7fallbacks left to their owner");
                say(s, "&7Items with behaviour: &f" + mod.traits().ownedIds().size() + "&7, ability handlers: &f"
                        + mod.abilities().registered.size() + "&7, recipes: &f" + mod.recipeIds().size()
                        + "&7, live arrows tracked: &f" + mod.bows().liveArrows());
                say(s, "&7Weapons with a Catacombs requirement: &f" + mod.dungeonCataCount() + " &7(dial weapons.catacombs-requirement = "
                        + WeaponDials.CATACOMBS_REQUIREMENT.get() + ")");
                if (!mod.traits().parseErrors().isEmpty()) say(s, "&cTrait parse errors: " + mod.traits().parseErrors());
            }
            case "list" -> {
                String f = a.length > 1 ? a[1].toUpperCase(Locale.ROOT) : "";
                List<String> rows = new ArrayList<>();
                for (String id : mod.traits().ownedIds()) {
                    if (!id.contains(f)) continue;
                    ItemDef d = Sky.items().def(id);
                    String owner = d.extra.has("owner") ? d.extra.get("owner").getAsString() : "?";
                    rows.add(id + " (" + Text.strip(d.name) + ", " + d.category + ", " + owner + ")");
                }
                rows.sort(String::compareTo);
                say(s, "&6" + rows.size() + " weapons" + (f.isEmpty() ? "" : " matching " + f) + ":");
                for (int i = 0; i < Math.min(80, rows.size()); i++) say(s, "&7- &f" + rows.get(i));
                if (rows.size() > 80) say(s, "&8... " + (rows.size() - 80) + " more (add a filter)");
            }
            case "trait" -> {
                if (a.length < 2) { say(s, "&cUsage: trait <ITEM_ID>"); return; }
                ItemDef d = Sky.items().def(a[1].toUpperCase(Locale.ROOT));
                if (d == null) { say(s, "&cUnknown item."); return; }
                say(s, "&6" + d.id + " &7ours=" + Traits.isOurs(d) + " owner=" + (d.extra.has("owner") ? d.extra.get("owner") : "?"));
                say(s, "&7w: &f" + (d.extra.has("w") ? d.extra.get("w").toString() : "{}"));
                say(s, "&7requirements: &f" + d.requirements);
                for (AbilityDef ab : d.abilities)
                    say(s, "&7ability &f" + ab.id + " &7(" + ab.trigger + ", mana " + ab.mana + ", cd " + ab.cooldown + ", dmg " + ab.damage
                            + ", scaling " + ab.scaling + ") handler=" + mod.abilities().registered.contains(ab.id));
            }
            case "kills", "paid" -> {
                Player p = player(s, a, 1);
                if (p == null) return;
                if (a.length < 3) { say(s, "&cUsage: " + sub + " <player> <number>"); return; }
                double v;
                try { v = Double.parseDouble(a[2]); } catch (NumberFormatException e) { say(s, "&cNot a number."); return; }
                ItemStack held = p.getInventory().getItemInMainHand();
                if (Traits.idOf(held) == null) { say(s, "&c" + p.getName() + " is not holding a sky item."); return; }
                String key = sub;
                Sky.items().update(held, d -> {
                    if (key.equals("kills")) d.extra.addProperty("kills", (long) v);
                    else { d.extra.remove("paid"); d.extra.addProperty("winning_bid", v); }   // the Dark Auction's key
                });
                Sky.stats().invalidate(p);
                say(s, "&aSet " + key + "=" + Text.commas(v) + " on " + p.getName() + "'s " + Traits.idOf(held));
            }
            case "shoot" -> {
                Player p = player(s, a, 1);
                if (p == null) return;
                Bows.Opts o = new Bows.Opts();
                o.ignoreCooldown = true;
                boolean ok = mod.bows().shoot(p, p.getInventory().getItemInMainHand(), o);
                say(s, ok ? "&aFired " + p.getName() + "'s held bow." : "&cCould not fire (not a bow, no arrows, or requirements).");
            }
            case "cast" -> {
                Player p = player(s, a, 1);
                if (p == null) return;
                ItemStack held = p.getInventory().getItemInMainHand();
                ItemDef d = Sky.items().def(Traits.idOf(held));
                if (d == null || d.abilities.isEmpty()) { say(s, "&cHeld item has no ability."); return; }
                int idx = 0;
                if (a.length > 2) try { idx = Integer.parseInt(a[2]); } catch (NumberFormatException ignored) { }
                AbilityDef ab = d.abilities.get(Math.max(0, Math.min(d.abilities.size() - 1, idx)));
                AbilityService.Handler h = mod.abilities().handlers.get(ab.id);
                if (h == null) { say(s, "&cNo weapons handler for " + ab.id + " (another module owns it)."); return; }
                boolean ok = h.use(new AbilityService.Use(p, held, d, ab));
                say(s, (ok ? "&aCast " : "&eDid not cast ") + ab.id + " for " + p.getName() + " &8(no mana or cooldown charged)");
            }
            case "arrows" -> {
                Player p = player(s, a, 1);
                if (p == null) return;
                ItemDef pick = Quiver.pick(p);
                say(s, "&6" + p.getName() + " &7next arrow: &f" + (pick == null ? "none" : pick.id) + " &7selected: &f"
                        + mod.menus().selectedName(p));
                for (ItemDef d : Quiver.arrowTypes()) {
                    int n = Quiver.available(p, d.id);
                    if (n > 0) say(s, "&7- " + d.id + " x" + n + (Quiver.usable(p, d) ? "" : " &c(locked)"));
                }
            }
            case "select" -> {
                Player p = player(s, a, 1);
                if (p == null) return;
                WeaponData d = WeaponsModule.data(p);
                if (d == null) { say(s, "&cProfile not ready."); return; }
                d.selectedArrow = a.length < 3 || a[2].equalsIgnoreCase("auto") ? null : a[2].toUpperCase(Locale.ROOT);
                say(s, "&aSelected arrow for " + p.getName() + ": " + (d.selectedArrow == null ? "auto" : d.selectedArrow));
            }
            case "math" -> {
                say(s, "&7Shortbow 0.5s at 0/50/100 AS: &f" + WeaponMath.shortbowCooldown(0.5, 0, 0.1) + " / "
                        + WeaponMath.shortbowCooldown(0.5, 50, 0.1) + " / " + WeaponMath.shortbowCooldown(0.5, 100, 0.1));
                say(s, "&7Gilded sword bonus at 1M/7.5M/50M: &f" + WeaponMath.midasSwordBonus(1e6) + " / "
                        + WeaponMath.midasSwordBonus(7.5e6) + " / " + WeaponMath.midasSwordBonus(5e7));
                say(s, "&7Emerald Blade at 1M/100M/2B purse: &f" + fmt(WeaponMath.emeraldBlade(1e6, 2.5, 2e9)) + " / "
                        + fmt(WeaponMath.emeraldBlade(1e8, 2.5, 2e9)) + " / " + fmt(WeaponMath.emeraldBlade(2e9, 2.5, 2e9)));
                say(s, "&7Hurricane arrows at 0/20/50/100/250 kills: &f" + WeaponMath.hurricaneArrows(0) + " "
                        + WeaponMath.hurricaneArrows(20) + " " + WeaponMath.hurricaneArrows(50) + " " + WeaponMath.hurricaneArrows(100)
                        + " " + WeaponMath.hurricaneArrows(250));
                say(s, "&7Focus Burn 1,000 mana: &f" + WeaponMath.focusBurn(1000)[0] + "% DR, +" + WeaponMath.focusBurn(1000)[1] + "% next hit");
            }
            case "menu" -> {
                Player p = player(s, a, 1);
                if (p == null) return;
                boolean arrows = a.length > 2 && a[2].equalsIgnoreCase("arrows");
                (arrows ? mod.menus().arrows(p) : mod.menus().arsenal(p)).open(p);
                say(s, "&aOpened " + (arrows ? "Arrow Selector" : "Arsenal") + " for " + p.getName());
            }
            case "quiverarrow" -> {
                Player p = player(s, a, 1);
                if (p == null) return;
                mod.quiverArrow().sync(p, true);
                ItemStack hand = p.getInventory().getItemInMainHand();
                int slot = -1;
                for (int i = 0; i < p.getInventory().getSize(); i++) if (QuiverArrow.isProxy(p.getInventory().getItem(i))) { slot = i; break; }
                ItemDef next = Quiver.pick(p);
                say(s, "&6Quiver Arrow &7for " + p.getName() + ": holding &f" + hand.getType() + " (" + Traits.idOf(hand) + ")"
                        + "&7, proxy slot &f" + (slot < 0 ? "none" : String.valueOf(slot + 1))
                        + "&7, next arrow &f" + (next == null ? "none" : next.id + " x" + Quiver.available(p, next.id))
                        + "&7, holders tracked &f" + mod.quiverArrow().holderCount()
                        + "&7, dial weapons.quiver-arrow = " + WeaponDials.QUIVER_DRAW.get());
            }
            case "upgrade" -> {
                Player p = player(s, a, 1);
                if (p == null) return;
                ItemStack held = p.getInventory().getItemInMainHand();
                String id = Traits.idOf(held);
                if (id == null || !Teleporters.TUNABLE.contains(id)) { say(s, "&c" + p.getName() + " is not holding a Voidstep/Riftwalker Blade."); return; }
                String what = a.length > 2 ? a[2].toLowerCase(Locale.ROOT) : "";
                switch (what) {
                    case "tuners" -> {
                        int n = a.length > 3 ? Math.max(0, Math.min(Teleporters.MAX_TUNERS, parseInt(a[3]))) : Teleporters.MAX_TUNERS;
                        Sky.items().update(held, d -> d.extra.addProperty("tuned_transmission", n));
                    }
                    case "merge" -> Sky.items().update(held, d -> d.extra.addProperty("ethermerge", 1));
                    case "unmerge" -> Sky.items().update(held, d -> d.extra.remove("ethermerge"));
                    default -> { say(s, "&cUsage: upgrade <player> <tuners N|merge|unmerge>"); return; }
                }
                p.getInventory().setItemInMainHand(held);
                say(s, "&a" + id + ": tuners " + Teleporters.tuners(held) + ", merged " + Teleporters.merged(held)
                        + ", blink " + (int) Teleporters.blinkRange(held) + " blocks, warp " + (int) Teleporters.etherRange(held) + " blocks");
            }
            case "mf" -> {
                Player p = player(s, a, 1);
                if (p == null) return;
                if (a.length < 3) { say(s, "&cUsage: mf <player> <mobId>"); return; }
                com.skyclone.api.mob.MobDef d = Sky.mobs().def(a[2]);
                if (d == null) { say(s, "&cUnknown mob id " + a[2]); return; }
                say(s, "&6Magic Find &7for " + p.getName() + " killing &f" + d.id + "&7 with &f" + Traits.heldId(p) + "&7: weapon bonus &b+"
                        + fmt(mod.targets().magicFind(p, d)) + "&7, core total &b" + fmt(Sky.loot().magicFind(p, d))
                        + "&7 (mythological: " + MobTypes.isDef("mythological", d) + ", myth bestiary tiers: " + mod.targets().mythTiers(p) + ")");
            }
            case "fear" -> {
                Player p = player(s, a, 1);
                if (p == null) return;
                Passives.forgetFear(p.getUniqueId());
                WeaponData wd = WeaponsModule.data(p);
                say(s, "&5Fear &7of " + p.getName() + ": &f" + Passives.fear(p) + "&7 (Primal Fears slain " + (wd == null ? "?" : wd.primalFears)
                        + ", Eerie Fearcreasing per 10: " + fmt(Passives.petValue(p, "eerie_fearcreasing", "a")) + ")");
            }
            case "floor" -> {
                Player p = player(s, a, 1);
                if (p == null) return;
                ItemStack held = p.getInventory().getItemInMainHand();
                String id = Traits.idOf(held);
                if (id == null || mod.traits().of(id).tiered == null) { say(s, "&c" + p.getName() + " is not holding a weapon with floor rolls."); return; }
                int n = a.length > 2 ? Math.max(0, Math.min(9, parseInt(a[2]))) : 0;
                Sky.items().update(held, d -> { if (n <= 0) d.extra.remove(Passives.ITEM_TIER); else d.extra.addProperty(Passives.ITEM_TIER, n); });
                p.getInventory().setItemInMainHand(held);
                say(s, "&a" + id + " now carries the " + WeaponMath.floorName(n) + " roll (item_tier " + n + ")");
            }
            case "classability" -> {
                Player p = player(s, a, 1);
                if (p == null) return;
                boolean took = DungeonWeapons.castClassAbility(p);
                say(s, "&7Class ability for " + p.getName() + " (in dungeon " + Passives.inDungeon(p) + ", class " + Passives.dungeonClass(p)
                        + "): " + (took ? "&ataken by the dungeons module" : "&cnot taken (not in a run?)"));
            }
            case "wave5" -> {
                Player p = player(s, a, 1);
                if (p == null) return;
                say(s, "&6Wave 5 &7for " + p.getName() + ": Soulstream " + SoulflowLink.balance(p) + " (module "
                        + Sky.has(com.skyclone.api.feature.Features.Soulflow.class) + ", Judgement Beam payable " + SoulflowLink.canPay(p, 1)
                        + "), myth bestiary tiers " + mod.targets().mythTiers(p) + " of " + mod.targets().mythFamilies().size()
                        + " families, Fear " + Passives.fear(p) + ", class " + Passives.dungeonClass(p) + ", whip kills " + mod.passives().whipKills(p));
            }
            default -> say(s, "&cUnknown sub. Try: " + String.join(", ", SUBS));
        }
    }

    private static int parseInt(String s) {
        try { return Integer.parseInt(s); } catch (NumberFormatException e) { return 0; }
    }

    private static String fmt(double v) { return Text.commas(Math.round(v * 10) / 10.0); }
}
