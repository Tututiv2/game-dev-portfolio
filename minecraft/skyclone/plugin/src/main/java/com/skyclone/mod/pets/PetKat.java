package com.skyclone.mod.pets;

import com.skyclone.api.Sky;
import com.skyclone.api.item.ItemDef;
import com.skyclone.api.item.Rarity;
import com.skyclone.api.ui.Msg;
import com.skyclone.api.ui.Text;
import com.skyclone.mod.pets.PetData.OwnedPet;
import org.bukkit.Sound;
import org.bukkit.entity.Player;

/**
 * The Pet Sitter: raise a pet one rarity for coins + items + real time (research 06 §6.5, neu-repo
 * katgrade recipes). The pet leaves the pets menu while it is being looked after, so it can never be
 * summoned, converted or traded mid-upgrade; XP is kept and the level recomputed at the new rarity.
 * Coins are taken before items and both before the pet moves - a short purse refuses with nothing taken.
 */
final class PetKat {

    private final PetsModule mod;
    private final PetService svc;

    PetKat(PetsModule mod, PetService svc) {
        this.mod = mod;
        this.svc = svc;
    }

    /** "ENCHANTED_COOKED_FISH:8" -> [id, "8"]; ids may themselves contain ':' ("INK_SACK:3"). */
    String[] splitCost(String cell) {
        if (Sky.items().exists(cell)) return new String[]{cell, "1"};
        int i = cell.lastIndexOf(':');
        if (i <= 0) return new String[]{cell, "1"};
        try {
            Integer.parseInt(cell.substring(i + 1));
            return new String[]{cell.substring(0, i), cell.substring(i + 1)};
        } catch (NumberFormatException e) {
            return new String[]{cell, "1"};
        }
    }

    boolean start(Player p, String uuid) {
        if (!mod.rateOk(p)) return false;
        PetData d = svc.data(p);
        if (d == null) return false;
        if (d.kat != null) { Msg.error(p, "I'm already looking after one of your pets!"); return false; }
        OwnedPet o = d.find(uuid);
        if (o == null) return false;
        if (uuid.equals(d.active)) { Msg.error(p, "Despawn that pet first."); return false; }
        PetDefs.KatStep st = svc.defs().katStep(o.type, o.tier);
        if (st == null) { Msg.error(p, "That pet can't be upgraded any further."); return false; }
        Integer tam = svc.defs().meta.katTaming.get(st.to);
        if (tam != null && mod.dialKatTaming() && svc.tamingLevel(p) < tam) {
            Msg.error(p, "You need Taming " + tam + " for a " + Text.titleCase(st.to) + " upgrade.");
            return false;
        }
        double coins = Math.max(0, st.coins * mod.dialKatCoinScale());
        for (String cell : st.items) {
            String[] sp = splitCost(cell);
            int need = Integer.parseInt(sp[1]);
            if (!Sky.items().exists(sp[0]) || Sky.items().count(p, sp[0]) < need) {
                ItemDef def = Sky.items().def(sp[0]);
                Msg.error(p, "You need " + need + "x " + (def == null ? sp[0] : def.name) + "!");
                return false;
            }
        }
        if (Sky.economy().purse(p) < coins) { Msg.error(p, "You don't have enough coins!"); return false; }
        if (coins > 0 && !Sky.economy().withdraw(p, coins, "pets: pet sitter upgrade " + o.type + " " + o.tier + "->" + st.to)) {
            Msg.error(p, "You don't have enough coins!");
            return false;
        }
        for (String cell : st.items) {
            String[] sp = splitCost(cell);
            Sky.items().remove(p, sp[0], Integer.parseInt(sp[1]));
        }
        d.pets.remove(o);
        d.expShare.remove(uuid);
        PetData.KatJob job = new PetData.KatJob();
        job.pet = o;
        job.toTier = st.to;
        job.startedAt = System.currentTimeMillis();
        job.readyAt = job.startedAt + (long) (Math.max(0, st.seconds * mod.dialKatTimeScale()) * 1000L);
        d.kat = job;
        Sky.profiles().saveNow(p);
        PetService.View v = svc.view(o);
        Msg.npc(p, Text.strip(mod.katName()), java.util.List.of("I'll take good care of your " + v.def().name + "!",
                "Come back in " + Text.duration((job.readyAt - job.startedAt) / 1000) + "."), null);
        svc.changed(p);
        return true;
    }

    boolean claim(Player p) {
        PetData d = svc.data(p);
        if (d == null || d.kat == null) return false;
        if (System.currentTimeMillis() < d.kat.readyAt) { Msg.error(p, "Your pet isn't ready yet."); return false; }
        OwnedPet o = d.kat.pet;
        PetDefs.PetDef def = svc.defs().pet(o.type);
        if (def != null && def.has(d.kat.toTier)) o.tier = d.kat.toTier;
        d.kat = null;
        d.pets.add(o);
        Sky.profiles().saveNow(p);
        PetService.View v = svc.view(o);
        Msg.box(p, "&6&lPET UPGRADED", java.util.List.of("&7Your " + Rarity.parse(o.tier).code() + v.def().name + " &7is now "
                + Rarity.parse(o.tier).code() + "&l" + Rarity.parse(o.tier).display() + "&7!", "&8Level " + v.level() + " at its new rarity."));
        Msg.sound(p, Sound.UI_TOAST_CHALLENGE_COMPLETE, 1.2f);
        svc.changed(p);
        return true;
    }

    void cancel(Player p) {
        PetData d = svc.data(p);
        if (d == null || d.kat == null) return;
        d.pets.add(d.kat.pet);
        d.kat = null;
        Sky.profiles().saveNow(p);
        Msg.success(p, "Your pet is back in your pets menu. The upgrade was cancelled.");
        svc.changed(p);
    }

    void skip(Player p, String itemId) {
        PetData d = svc.data(p);
        if (d == null || d.kat == null || !mod.rateOk(p)) return;
        Long secs = svc.defs().meta.skipItems.get(itemId);
        if (secs == null) return;
        if (Sky.items().remove(p, itemId, 1) < 1) { Msg.error(p, "You don't have one!"); return; }
        d.kat.readyAt = Math.max(System.currentTimeMillis(), d.kat.readyAt - secs * 1000L);
        Msg.success(p, "The Pet Sitter will be done " + Text.duration(secs) + " sooner!");
    }

    /** Admin: finish now. */
    void finish(Player p) {
        PetData d = svc.data(p);
        if (d != null && d.kat != null) d.kat.readyAt = System.currentTimeMillis();
    }
}
