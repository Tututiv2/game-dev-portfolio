package com.skyclone.mod.fishing;

import com.google.gson.Gson;
import com.google.gson.reflect.TypeToken;
import com.skyclone.api.Sky;
import com.skyclone.api.loot.LootSource;
import com.skyclone.api.npc.NpcDef;
import com.skyclone.api.ui.Msg;
import org.bukkit.entity.Player;

import java.io.InputStream;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.function.Consumer;

/** The fishing NPCs: hub guide, the Bayou's tackle-smith / bait-seller / warden, the Crimson trophy keeper. */
final class FishNpcs {

    private FishNpcs() {}

    static final class Row { String id, name, title, island, anchor, skin, hand; }

    static final List<Row> ROWS = new ArrayList<>();

    static void register() {
        try (InputStream in = Sky.plugin().getResource("data/fishing/npcs.json")) {
            if (in != null) ROWS.addAll(new Gson().fromJson(new InputStreamReader(in, StandardCharsets.UTF_8),
                    new TypeToken<List<Row>>() {}.getType()));
        } catch (Exception e) {
            Sky.log().warning("[fishing] npcs.json: " + e);
        }
        Map<String, Consumer<Player>> click = Map.of(
                "fishing_guide", FishNpcs::guide,
                "fishing_roddy", p -> Sky.npcs().talk(p, "fishing_roddy", List.of(
                        "Every rod has room for a hook, a line and a sinker.",
                        "Hold your rod and I'll fit whatever parts you've got."), () -> RodParts.open(p)),
                "fishing_bait_shop", p -> FishMenus.shop(p, "Bait-seller Mae", Fishing.CAT.baitShop, () -> FishMenus.main(p)),
                "fishing_warden", FishNpcs::warden,
                "fishing_trophy_keeper", p -> FishMenus.trophies(p, true));
        for (Row r : ROWS) {
            NpcDef d = NpcDef.of(r.id, r.name, r.island, r.anchor).title(r.title);
            if (r.skin != null && !r.skin.isBlank()) d.skin(r.skin, null);
            d.hand = r.hand;
            Consumer<Player> c = click.get(r.id);
            Sky.npcs().register(d, p -> { if (Fishing.ready(p) && c != null) c.accept(p); });
        }
    }

    private static void guide(Player p) {
        FishData d = Fishing.data(p);
        if (FishDials.STARTER_ROD.get() && !d.starterRod && Sky.items().exists("FISHING_ROD")) {
            Sky.npcs().talk(p, "fishing_guide", List.of(
                    "New to the water? Take this rod, on the house.",
                    "Cast it in the pond, wait for the &c&l!!!&f, then reel in.",
                    "Bigger things bite as your Fishing skill grows. Good luck!"), () -> {
                // decided when the gift is handed over (not when the talk starts), so leaving mid-talk
                // never loses the rod and a second click can never get two
                if (!Fishing.ready(p)) return;
                FishData now = Fishing.data(p);
                if (now.starterRod) { FishMenus.main(p); return; }
                now.starterRod = true;
                Sky.loot().give(p, "FISHING_ROD", 1, LootSource.REWARD);
                Sky.profiles().saveNow(p);
                Msg.success(p, "You received a Fishing Rod!");
                FishMenus.main(p);
            });
            return;
        }
        FishMenus.main(p);
    }

    private static void warden(Player p) {
        // the reference's Jonesy: the Bayou guide gives the seven chapters (audit 2026-10-06 F11)
        if (FishDials.JONESY.get() && !Fishing.CAT.jonesy.isEmpty()) {
            if (Fishing.data(p).jonesyChapter == 0 && Fishing.data(p).jonesyTasks.isEmpty()) {
                Sky.npcs().talk(p, "fishing_warden", List.of(
                        "Not many new faces round here... welcome to the Bayou, sailor!",
                        "Trash, junk and everything in between - I'll show you the lot.",
                        "Work through my chapters and there's a Bayou Pearl at the end."), () -> Jonesy.open(p));
            } else Jonesy.open(p);
            return;
        }
        Sky.npcs().talk(p, "fishing_warden", List.of(
                "Welcome to the Bayou. Mind the gators.",
                "The whole bayou hides junk-eaters and worse - gators, sludge, a snake.",
                "Want frogs? Reach Fishing X and sail for the Lotus Atoll. The marsh",
                "folk fish the Moonmarsh loch, and the canyon springs have stranger things.",
                "Fish inside a glowing ring when one shows up - that's a hotspot."), () -> FishMenus.here(p));
    }
}
