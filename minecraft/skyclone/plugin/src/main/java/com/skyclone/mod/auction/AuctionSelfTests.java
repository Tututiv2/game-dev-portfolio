package com.skyclone.mod.auction;

import com.skyclone.api.Sky;
import com.skyclone.api.item.ItemCategory;
import com.skyclone.api.item.ItemDef;
import com.skyclone.api.world.gen.Layout;
import org.bukkit.inventory.ItemStack;

import java.util.ArrayList;
import java.util.List;

/** Live checks for {@code /sb selftest auction}. Each returns null on pass, else what is wrong. */
final class AuctionSelfTests {

    private AuctionSelfTests() {}

    static void register(AuctionHouse house, NpcMarket npc, VaultAuction vault) {
        // 8. The Vault Auction: its main-track items exist, the Special pool can fill its lots (volume perk included),
        //    and the ranked-bidding rules give the expected minimums.
        Sky.commands().selfTest("auction.vault", () -> {
            List<VaultRules.Spec> specs = VaultRules.parseItems(house.dials.vaultItems.get());
            if (specs.isEmpty()) return "auction.vault.items parses to nothing: " + house.dials.vaultItems.get();
            List<String> missing = new ArrayList<>();
            java.util.Set<String> ids = new java.util.HashSet<>();
            for (VaultRules.Spec s : specs) {
                ids.add(s.itemId());
                if (!Sky.items().exists(s.itemId())) missing.add(s.itemId());
            }
            if (!missing.isEmpty()) return "vault items not registered by any module: " + missing;
            int pool = vault.specialPool(ids).size();
            int want = house.dials.vaultSpecialCount.get() + 2;
            if (pool < want) return "Special pool has " + pool + " items, needs " + want + " (with the volume perk)";
            List<VaultRules.Entry> two = List.of(new VaultRules.Entry("a", 1_000_000, 1), new VaultRules.Entry("b", 2_000_000, 2));
            if (VaultRules.minNewBid(500_000, 3, two, 0.025) != 500_000) return "a lot with a free copy should take the minimum";
            if (VaultRules.minNewBid(500_000, 2, two, 0.025) != 1_025_000) return "a full lot should need the lowest bid +2.5%";
            if (VaultAuction.currentYear() <= 0) return "calendar year is " + VaultAuction.currentYear();
            if (VaultRules.ironmanStock(3, 0.5) != 2) return "Ironman Vault stock of a 3-copy lot should be 2 (50%, rounded up)";
            if (house.dials.vaultEnabled.get() && house.dials.vaultIronman.get()) {
                int y = VaultAuction.currentYear();
                for (VaultAuction.Lot l : vault.current()) {
                    if (!VaultAuction.IRONMAN.equals(l.track) || l.year != y) continue;
                    if (!ids.contains(l.itemId)) return "Ironman lot " + l.id + " holds " + l.itemId + ", not a main-track item";
                }
            }
            return null;
        });

        // 11. (wave 5) Ironman Vault rules: each Ironman lot mirrors an open classic lot of the year at half stock
        //     (main AND Special), Ironman sees only its own lots, Stranded/Bingo are kept out, Ironman gets in even
        //     when the cosmetic-only main AH is switched off.
        Sky.commands().selfTest("auction.vault-ironman", () -> {
            if (!VaultRules.visible(VaultRules.IRONMAN_SPECIAL, true) || VaultRules.visible(VaultRules.SPECIAL, true))
                return "an Ironman viewer must see Ironman lots only";
            if (VaultRules.visible(VaultRules.IRONMAN, false) || !VaultRules.visible(VaultRules.MAIN, false))
                return "a classic viewer must never see Ironman lots";
            if (VaultRules.modeRefusal("ironman", false, true) != null) return "Ironman is refused the Vault";
            if (VaultRules.modeRefusal("bingo", false, true) == null || VaultRules.modeRefusal("stranded", false, true) == null)
                return "Bingo/Stranded reach the Vault (research: excluded)";
            if (!house.dials.vaultEnabled.get() || !house.dials.vaultIronman.get()) return null;
            int y = VaultAuction.currentYear();
            double frac = house.dials.vaultIronmanStock.get();
            java.util.Map<String, VaultAuction.Lot> classic = new java.util.HashMap<>();
            for (VaultAuction.Lot l : vault.current()) {
                if (l.year == y && !VaultRules.ironmanTrack(l.track)) classic.put(l.track + "|" + l.itemId, l);
            }
            List<String> bad = new ArrayList<>();
            for (VaultAuction.Lot l : vault.current()) {
                if (l.year != y || !VaultRules.ironmanTrack(l.track)) continue;
                String src = VaultAuction.IRONMAN.equals(l.track) ? VaultAuction.MAIN : VaultAuction.SPECIAL;
                VaultAuction.Lot c = classic.get(src + "|" + l.itemId);
                if (c == null) { bad.add(l.id + " (" + l.itemId + ") has no classic " + src + " lot"); continue; }
                if (l.stock != VaultRules.ironmanStock(c.stock, frac)) bad.add(l.id + " stock " + l.stock + " vs classic " + c.stock);
                if (l.minBid != c.minBid) bad.add(l.id + " minimum differs from its classic lot");
            }
            return bad.isEmpty() ? null : String.join("; ", bad);
        });

        // 12. (wave 5) The Vault gates are the research's Museum Milestones 10/20 whenever a module resolves them,
        //     and fall back to donation counts (never to "locked for everyone") when none does.
        Sky.commands().selfTest("auction.vault-gates", () -> {
            if (!"museum:60".equals(VaultRules.gate("museum_milestone:10", "museum:60", x -> false))) return "fallback gate not used";
            if (!"museum_milestone:10".equals(VaultRules.gate("museum_milestone:10", "museum:60", x -> true))) return "dial gate not used";
            boolean known = VaultAuction.knownPrefix("museum_milestone");
            String main = vault.requirement(false), special = vault.requirement(true);
            if (known && !main.equals(house.dials.vaultRequirement.get())) return "main gate fell back although museum_milestone is resolvable";
            for (String g : List.of(main, special)) {
                if (g == null || g.isBlank()) continue;
                String prefix = g.split("[:&]")[0].trim();
                if (!VaultAuction.knownPrefix(prefix)) return "Vault gate '" + g + "' uses an unknown prefix (everyone locked out)";
            }
            return null;
        });

        // 13. (wave 5) The Regalia Room: the item is real, its requirement key resolves, the ledger maths holds.
        Sky.commands().selfTest("auction.regalia", () -> {
            String item = house.dials.regaliaItem.get();
            if (item != null && !item.isBlank() && !Sky.items().exists(item.trim())) return "auction.regalia.item " + item + " is not a registered item";
            if (!Sky.requirements().known("regalia")) return "requirement prefix 'regalia' is not registered";
            if (Sky.requirements().describe("regalia").isBlank()) return "requirement 'regalia' has no description";
            VaultRules.Result r = VaultRules.result(3, List.of(5_000_000d, 7_000_000d));
            if (r.sold() != 2 || r.unsold() != 1 || r.lowest() != 5_000_000 || r.highest() != 7_000_000) return "ledger summary wrong: " + r;
            return null;
        });

        // 14. (wave 5) Co-op Soulbound items (museum_soulbound / coop_soulbound / soulbound on the instance) are refused.
        Sky.commands().selfTest("auction.soulbound-refused", () -> {
            String id = "ASPECT_OF_THE_END";
            if (!Sky.items().exists(id)) return null;
            org.bukkit.inventory.ItemStack it = Sky.items().create(id, 1);
            if (AuctionHouse.auctionable(it) != null) return "a plain " + id + " is refused: " + AuctionHouse.auctionable(it);
            Sky.items().update(it, d -> d.extra.addProperty("museum_soulbound", "selftest-profile"));
            if (AuctionHouse.auctionable(it) == null) return "a museum-soulbound " + id + " can be auctioned";
            return null;
        });

        // 10. No consignment-seller persona shares a name with a world NPC. A shopper reading "[Agent] Hollis Crane"
        //     in a lore line next to Town Crier Hollis reasonably assumes it is the same person (wave-3 playtest);
        //     the seller pool once collided with ten NPCs. Compared word by word, case-insensitive, colour codes off.
        Sky.commands().selfTest("auction.agent-names-unique", () -> {
            java.util.Map<String, String> npcWords = new java.util.HashMap<>();
            for (com.skyclone.api.npc.NpcDef d : Sky.npcs().all()) {
                if (d == null || d.name == null) continue;
                String plain = d.name.replaceAll("[&§][0-9a-fk-orA-FK-OR]", "");
                for (String w : plain.split("[^\\p{L}]+")) {
                    if (w.length() >= 3) npcWords.putIfAbsent(w.toLowerCase(java.util.Locale.ROOT), d.id + " (" + plain.trim() + ")");
                }
            }
            List<String> clashes = new ArrayList<>();
            for (String seller : npc.sellerNames()) {
                for (String w : seller.split("[^\\p{L}]+")) {
                    String hit = npcWords.get(w.toLowerCase(java.util.Locale.ROOT));
                    if (hit != null) clashes.add("'" + seller + "' vs NPC " + hit);
                }
            }
            return clashes.isEmpty() ? null : "Agent seller names shared with world NPCs (edit tools/gen/auction/gen_auction_data.py AGENTS): " + clashes;
        });

        // 9. The claim-tax perk id is one the mayors module knows (a typo silently disables QUAD TAXES).
        Sky.commands().selfTest("auction.mayor-perks", () -> {
            if (!Sky.has(com.skyclone.api.feature.Features.Mayors.class)) return null;   // no mayors module: nothing to match
            String perk = house.dials.quadTaxPerk.get();
            if (perk == null || perk.isBlank()) return null;
            if (!perk.contains(".")) return "auction.quad-tax-perk '" + perk + "' is not a mayor perk id (expected e.g. derpy.quad_taxes)";
            return null;
        });

        // 1. The fee/tax formulas give research 05 section 3's numbers.
        Sky.commands().selfTest("auction.fees-match-research", () -> {
            AuctionRules.Settings s = new AuctionRules.Settings();
            List<String> bad = new ArrayList<>();
            check(bad, "1h fee", AuctionRules.durationFee(s, 60), 20);
            check(bad, "6h fee", AuctionRules.durationFee(s, 360), 45);
            check(bad, "12h fee", AuctionRules.durationFee(s, 720), 100);
            check(bad, "24h fee", AuctionRules.durationFee(s, 1440), 350);
            check(bad, "48h fee", AuctionRules.durationFee(s, 2880), 1200);
            check(bad, "14d fee", AuctionRules.durationFee(s, 336 * 60), 55200);
            check(bad, "BIN 5M", AuctionRules.listingFee(s, true, 5_000_000), 50_000);
            check(bad, "BIN 50M", AuctionRules.listingFee(s, true, 50_000_000), 1_000_000);
            check(bad, "BIN 200M", AuctionRules.listingFee(s, true, 200_000_000), 5_000_000);
            check(bad, "auction 1M", AuctionRules.listingFee(s, false, 1_000_000), 50_000);
            check(bad, "max listing BIN fee", AuctionRules.listingFee(s, true, 50_000_000_000d), 1_250_000_000);
            check(bad, "max listing auction fee", AuctionRules.listingFee(s, false, 50_000_000_000d), 2_500_000_000d);
            return bad.isEmpty() ? null : String.join(", ", bad);
        });

        // 2. Claim tax, bid increments, anti-snipe, listing limits.
        Sky.commands().selfTest("auction.tax-bids-limits", () -> {
            AuctionRules.Settings s = new AuctionRules.Settings();
            List<String> bad = new ArrayList<>();
            check(bad, "claim 500k", AuctionRules.claimPayout(s, 500_000), 500_000);
            check(bad, "claim 1.005M", AuctionRules.claimPayout(s, 1_005_000), 1_000_000);
            check(bad, "claim 2M", AuctionRules.claimPayout(s, 2_000_000), 1_980_000);
            s.claimTaxMultiplier = 4;
            check(bad, "quad-tax 10M", AuctionRules.claimPayout(s, 10_000_000), 9_600_000);
            s.claimTaxMultiplier = 1;
            check(bad, "first bid", AuctionRules.minNextBid(s, 500, 0), 500);
            check(bad, "next bid over 1000", AuctionRules.minNextBid(s, 500, 1000), 1025);
            check(bad, "anti-snipe", AuctionRules.endAfterBid(s, 1_000_000L + 30_000, 1_000_000L), 1_000_000L + 120_000);
            check(bad, "no snipe extension", AuctionRules.endAfterBid(s, 1_000_000L + 600_000, 1_000_000L), 1_000_000L + 600_000);
            check(bad, "solo listings", AuctionRules.maxListings(s, 1), 14);
            check(bad, "3-member listings", AuctionRules.maxListings(s, 3), 20);
            check(bad, "8-member listings", AuctionRules.maxListings(s, 8), 35);
            check(bad, "COMMON value cap", AuctionRules.commonValueCap(s, "COMMON", 10, 64), 3200);
            // the list cap must leave the seller the value cap after every fee (playtest: 5-coin cap vs 20-coin fee)
            for (boolean b : new boolean[]{true, false}) for (int h : AuctionRules.PRESET_HOURS) {
                double cap = AuctionRules.commonCap(s, "COMMON", 1, 1, b, h * 60L);
                if (AuctionRules.netIfSold(s, b, cap, h * 60L) < 5) bad.add("COMMON cap " + (b ? "BIN " : "auction ") + h + "h nets under 5x");
            }
            return bad.isEmpty() ? null : String.join(", ", bad);
        });

        // 3. The hub builder made the three NPC anchors.
        Sky.commands().selfTest("auction.anchors", () -> {
            Layout l = Sky.worlds().layout("hub");
            if (l == null) return "hub layout is missing";
            List<String> missing = new ArrayList<>();
            for (String a : List.of("npc.auction_master", "npc.auction_agent_1", "npc.auction_agent_2")) {
                if (l.anchor(a) == null) missing.add(a);
            }
            return missing.isEmpty() ? null : "hub anchors missing: " + missing;
        });

        // 4. The generated price data loaded and points at real items; every Agent candidate can be created.
        Sky.commands().selfTest("auction.price-data", () -> {
            PriceGuide g = house.guide;
            if (g.refs().size() < 400) return "prices.json has only " + g.refs().size() + " entries (expected 445)";
            if (g.bazaarRefCount() < 1000) return "bazaar_ref.json has only " + g.bazaarRefCount() + " products";
            int exist = 0;
            for (String id : g.refs().keySet()) if (Sky.items().exists(id)) exist++;
            if (exist < 25) return "only " + exist + " of " + g.refs().size() + " reference ids are registered items";
            npc.invalidate();
            List<NpcMarket.Candidate> cands = npc.candidates();
            if (cands.size() < 20) return "only " + cands.size() + " items the Agents can stock (need 20+)";
            int bands = 0;
            int[] perBand = new int[NpcMarket.BAND_TOP.length];
            for (NpcMarket.Candidate c : cands) perBand[c.band()]++;
            for (int n : perBand) if (n > 0) bands++;
            if (bands < 3) return "Agent stock spans only " + bands + " price bands";
            for (NpcMarket.Candidate c : cands) {
                if (!Sky.items().exists(c.id())) return "candidate " + c.id() + " is not registered";
                ItemDef d = Sky.items().def(c.id());
                if (!NpcMarket.stockable(d, false)) return "candidate " + c.id() + " is not stockable";
            }
            return null;
        });

        // 5. An escrowed item round-trips through the database encoding with its id and uuid intact.
        Sky.commands().selfTest("auction.item-codec", () -> {
            String id = null;
            for (ItemDef d : Sky.items().all()) {
                if (d.category == ItemCategory.SWORD && d.isUnique()) { id = d.id; break; }
            }
            if (id == null) id = "DIAMOND_SWORD";
            ItemStack it = Sky.items().create(id, 1);
            String uuid = AuctionHouse.uuidOf(it);
            ItemStack back = ItemCodec.decode(ItemCodec.encode(it));
            if (!id.equals(Sky.items().idOf(back))) return "id changed: " + id + " -> " + Sky.items().idOf(back);
            if (uuid != null && !uuid.equals(AuctionHouse.uuidOf(back))) return "uuid changed for " + id;
            ItemStack shown = ItemCodec.display(back);
            if (Sky.items().idOf(shown) != null) return "display copy still carries the sky id (could be taken as a real item)";
            Listing l = AuctionHouse.build(back, id, true, 1000, System.currentTimeMillis(), 60);
            if (l.tab != AhTab.WEAPONS && Sky.items().def(id).category.isWeapon()) return "a weapon landed in tab " + l.tab;
            return null;
        });

        // 6. Live state is consistent: no item escrowed twice, every item readable, states add up.
        Sky.commands().selfTest("auction.store-consistent", () -> AuctionAdmin.auditReport(house));

        // 7. Browser tabs: every item category maps to a tab, and the six tabs are all reachable.
        Sky.commands().selfTest("auction.tabs", () -> {
            java.util.EnumSet<AhTab> hit = java.util.EnumSet.noneOf(AhTab.class);
            for (ItemCategory c : ItemCategory.values()) hit.add(AhTab.of(c, false));
            hit.add(AhTab.of(ItemCategory.MATERIAL, true));
            if (hit.size() != AhTab.values().length) return "unreachable tabs: " + java.util.EnumSet.complementOf(hit);
            if (AhTab.of(ItemCategory.SWORD, false) != AhTab.WEAPONS) return "swords not in Weapons";
            if (AhTab.of(ItemCategory.NECKLACE, false) != AhTab.ARMOR) return "equipment not in Armor";
            if (AhTab.of(ItemCategory.ACCESSORY, false) != AhTab.ACCESSORIES) return "accessories misfiled";
            if (AhTab.of(ItemCategory.PET, false) != AhTab.TOOLS_MISC) return "pets not in Tools & Misc";
            return null;
        });

        // 9. The view after a purchase: a BIN bought, delivered and evicted in one click still draws as a
        //    receipt from the object the view holds - never "Auction not found" (PLAYTEST wave5 #6).
        Sky.commands().selfTest("auction.view-after-purchase", () -> {
            Listing l = new Listing();
            l.id = "selftest-view-" + System.nanoTime();
            l.bin = true; l.startingBid = 100; l.sellerProfile = "seller-prof"; l.sellerName = "Seller";
            l.state = Listing.State.SOLD; l.topBid = 100; l.topBidderProfile = "buyer-prof"; l.topBidderName = "Buyer";
            l.sellerClaimed = true; l.buyerClaimed = true;
            if (house.store.get(l.id) != null) return "the self-test id collided with a live listing";
            if (Listing.forView(house.store.get(l.id), l) != l) return "an evicted, settled purchase does not resolve for its view";
            if (l.outcomeFor("buyer-prof") != Listing.Outcome.BOUGHT) return "buyer side reads " + l.outcomeFor("buyer-prof");
            if (l.outcomeFor("seller-prof") != Listing.Outcome.SOLD_COLLECTED) return "seller side reads " + l.outcomeFor("seller-prof");
            l.buyerClaimed = false;
            if (Listing.forView(null, l) != null) return "an UNsettled listing missing from the store must not be drawn from a stale copy";
            return null;
        });
    }

    private static void check(List<String> bad, String what, double got, double want) {
        if (Math.abs(got - want) > 0.05) bad.add(what + " = " + AuctionRules.fmt(got) + " (want " + AuctionRules.fmt(want) + ")");
    }
}
