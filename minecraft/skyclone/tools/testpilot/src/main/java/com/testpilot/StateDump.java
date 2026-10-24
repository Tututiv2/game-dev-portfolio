package com.testpilot;

import com.google.gson.JsonArray;
import com.google.gson.JsonNull;
import com.google.gson.JsonObject;
import com.testpilot.mixin.PlayerTabOverlayAccessor;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.client.gui.screens.inventory.AbstractContainerScreen;
import net.minecraft.client.multiplayer.PlayerInfo;
import net.minecraft.client.player.LocalPlayer;
import net.minecraft.core.BlockPos;
import net.minecraft.core.component.DataComponents;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.network.chat.Component;
import net.minecraft.network.chat.numbers.NumberFormat;
import net.minecraft.network.chat.numbers.StyledFormat;
import net.minecraft.world.entity.Display;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.EquipmentSlot;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.entity.decoration.ArmorStand;
import net.minecraft.world.entity.player.Inventory;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.inventory.AbstractContainerMenu;
import net.minecraft.world.inventory.Slot;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.component.CustomData;
import net.minecraft.world.item.component.ItemLore;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.phys.AABB;
import net.minecraft.world.phys.BlockHitResult;
import net.minecraft.world.phys.EntityHitResult;
import net.minecraft.world.phys.HitResult;
import net.minecraft.world.phys.Vec3;
import net.minecraft.world.scores.DisplaySlot;
import net.minecraft.world.scores.Objective;
import net.minecraft.world.scores.PlayerScoreEntry;
import net.minecraft.world.scores.PlayerTeam;
import net.minecraft.world.scores.Scoreboard;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;

/** Builds the /state snapshot. Client thread only. */
public final class StateDump {

    private static final Comparator<PlayerScoreEntry> SIDEBAR_ORDER = Comparator
            .comparing(PlayerScoreEntry::value).reversed()
            .thenComparing(PlayerScoreEntry::owner, String.CASE_INSENSITIVE_ORDER);

    private StateDump() {
    }

    public static JsonObject state(Minecraft mc, double radius, int chatLines) {
        JsonObject o = new JsonObject();
        o.addProperty("inWorld", mc.player != null && mc.level != null);
        o.addProperty("focused", mc.getWindow().isFocused());
        o.addProperty("osFocused", Actions.osFocused(mc));
        o.addProperty("tick", Jobs.clientTicks());
        o.add("screen", screen(mc));
        o.add("chat", ChatLog.last(chatLines));
        o.addProperty("chatCount", ChatLog.total());
        o.add("actionBar", ChatLog.actionBarJson());
        LocalPlayer p = mc.player;
        if (p == null || mc.level == null) {
            return o;
        }
        o.add("player", player(mc, p));
        JsonArray hotbar = new JsonArray();
        Inventory inv = p.getInventory();
        for (int i = 0; i < 9; i++) {
            JsonObject it = item(inv.getItem(i));
            it.addProperty("slot", i);
            hotbar.add(it);
        }
        o.add("hotbar", hotbar);
        JsonArray all = new JsonArray();
        for (int i = 0; i < 36; i++) {
            ItemStack s = inv.getItem(i);
            if (!s.isEmpty()) {
                JsonObject it = item(s);
                it.addProperty("slot", i);
                all.add(it);
            }
        }
        o.add("inventory", all);
        JsonObject eq = new JsonObject();
        for (EquipmentSlot es : new EquipmentSlot[] {EquipmentSlot.HEAD, EquipmentSlot.CHEST, EquipmentSlot.LEGS,
                EquipmentSlot.FEET, EquipmentSlot.OFFHAND}) {
            ItemStack s = p.getItemBySlot(es);
            eq.add(es.getName(), s.isEmpty() ? JsonNull.INSTANCE : item(s));
        }
        o.add("equipment", eq);
        o.add("sidebar", sidebar(mc, p));
        o.add("tab", tab(mc));
        GuiAccess gui = (GuiAccess) mc.gui;
        o.add("title", textPair(gui.testpilot$title()));
        o.add("subtitle", textPair(gui.testpilot$subtitle()));
        o.add("crosshair", crosshair(mc));
        if (radius > 0) {
            o.add("entities", entities(mc, p, radius));
        }
        return o;
    }

    // ------------------------------------------------------------------ player

    private static JsonObject player(Minecraft mc, LocalPlayer p) {
        JsonObject o = new JsonObject();
        o.addProperty("name", p.getGameProfile().name());
        o.addProperty("uuid", p.getUUID().toString());
        o.addProperty("x", p.getX());
        o.addProperty("y", p.getY());
        o.addProperty("z", p.getZ());
        BlockPos bp = p.blockPosition();
        o.addProperty("blockX", bp.getX());
        o.addProperty("blockY", bp.getY());
        o.addProperty("blockZ", bp.getZ());
        o.addProperty("yaw", p.getYRot());
        o.addProperty("pitch", p.getXRot());
        o.addProperty("dimension", mc.level.dimension().identifier().toString());
        o.addProperty("health", p.getHealth());
        o.addProperty("maxHealth", p.getMaxHealth());
        o.addProperty("absorption", p.getAbsorptionAmount());
        o.addProperty("food", p.getFoodData().getFoodLevel());
        o.addProperty("saturation", p.getFoodData().getSaturationLevel());
        o.addProperty("xpLevel", p.experienceLevel);
        o.addProperty("onGround", p.onGround());
        // breath: air ticks left (300 = full, 15 s) - a walker that cannot see this drowns (wave 5)
        o.addProperty("inWater", p.isInWater());
        o.addProperty("underWater", p.isUnderWater());
        o.addProperty("air", p.getAirSupply());
        o.addProperty("maxAir", p.getMaxAirSupply());
        o.addProperty("selectedSlot", p.getInventory().getSelectedSlot());
        o.addProperty("attackStrength", p.getAttackStrengthScale(0f));
        o.addProperty("gameMode", mc.gameMode == null ? "?" : mc.gameMode.getPlayerMode().getName());
        return o;
    }

    // ------------------------------------------------------------------ items

    public static JsonObject item(ItemStack s) {
        JsonObject o = new JsonObject();
        if (s == null || s.isEmpty()) {
            o.addProperty("empty", true);
            return o;
        }
        o.addProperty("id", BuiltInRegistries.ITEM.getKey(s.getItem()).toString());
        o.addProperty("count", s.getCount());
        o.addProperty("name", s.getHoverName().getString());
        o.addProperty("nameLegacy", Text.legacy(s.getStyledHoverName()));
        ItemLore lore = s.get(DataComponents.LORE);
        JsonArray plain = new JsonArray();
        JsonArray legacy = new JsonArray();
        if (lore != null) {
            for (Component c : lore.lines()) {
                plain.add(c.getString());
            }
            for (Component c : lore.styledLines()) {
                legacy.add(Text.legacy(c));
            }
        }
        o.add("lore", plain);
        o.add("loreLegacy", legacy);
        CustomData cd = s.get(DataComponents.CUSTOM_DATA);
        if (cd != null) {
            String snbt = cd.copyTag().toString();
            o.addProperty("customData", snbt.length() > 4000 ? snbt.substring(0, 4000) + "..." : snbt);
        }
        return o;
    }

    // ------------------------------------------------------------------ screen

    public static JsonObject screen(Minecraft mc) {
        Screen sc = mc.screen;
        if (sc == null) {
            return null;
        }
        JsonObject o = new JsonObject();
        o.addProperty("type", sc.getClass().getSimpleName());
        o.addProperty("class", sc.getClass().getName());
        o.addProperty("title", sc.getTitle().getString());
        o.addProperty("titleLegacy", Text.legacy(sc.getTitle()));
        if (sc instanceof AbstractContainerScreen<?> acs) {
            AbstractContainerMenu menu = acs.getMenu();
            o.addProperty("containerId", menu.containerId);
            try {
                o.addProperty("menuType", BuiltInRegistries.MENU.getKey(menu.getType()).toString());
            } catch (RuntimeException e) {
                o.addProperty("menuType", "player_inventory");
            }
            int containerSlots = 0;
            JsonArray slots = new JsonArray();
            for (int i = 0; i < menu.slots.size(); i++) {
                Slot sl = menu.slots.get(i);
                boolean playerInv = sl.container instanceof Inventory;
                if (!playerInv) {
                    containerSlots++;
                }
                ItemStack st = sl.getItem();
                JsonObject so = item(st);
                so.addProperty("slot", i);
                so.addProperty("container", !playerInv);
                so.addProperty("containerSlot", sl.getContainerSlot());
                slots.add(so);
            }
            o.addProperty("containerSlots", containerSlots);
            o.add("slots", slots);
            o.add("carried", item(menu.getCarried()));
        }
        return o;
    }

    // ------------------------------------------------------------------ sidebar / tab

    private static JsonObject sidebar(Minecraft mc, LocalPlayer p) {
        Scoreboard sb = mc.level.getScoreboard();
        Objective obj = null;
        PlayerTeam team = sb.getPlayersTeam(p.getScoreboardName());
        if (team != null) {
            DisplaySlot slot = DisplaySlot.teamColorToSlot(team.getColor());
            if (slot != null) {
                obj = sb.getDisplayObjective(slot);
            }
        }
        if (obj == null) {
            obj = sb.getDisplayObjective(DisplaySlot.SIDEBAR);
        }
        if (obj == null) {
            return null;
        }
        JsonObject o = new JsonObject();
        o.addProperty("title", obj.getDisplayName().getString());
        o.addProperty("titleLegacy", Text.legacy(obj.getDisplayName()));
        NumberFormat fmt = obj.numberFormatOrDefault(StyledFormat.SIDEBAR_DEFAULT);
        JsonArray lines = new JsonArray();
        JsonArray legacy = new JsonArray();
        JsonArray scores = new JsonArray();
        List<PlayerScoreEntry> entries = new ArrayList<>(sb.listPlayerScores(obj));
        entries.removeIf(PlayerScoreEntry::isHidden);
        entries.sort(SIDEBAR_ORDER);
        int n = 0;
        for (PlayerScoreEntry e : entries) {
            if (n++ >= 15) {
                break;
            }
            Component name = PlayerTeam.formatNameForTeam(sb.getPlayersTeam(e.owner()), e.ownerName());
            lines.add(name.getString());
            legacy.add(Text.legacy(name));
            scores.add(e.formatValue(fmt).getString());
        }
        o.add("lines", lines);
        o.add("linesLegacy", legacy);
        o.add("scores", scores);
        return o;
    }

    private static JsonObject tab(Minecraft mc) {
        JsonObject o = new JsonObject();
        PlayerTabOverlayAccessor t = (PlayerTabOverlayAccessor) mc.gui.getTabList();
        o.add("header", textPair(t.testpilot$header()));
        o.add("footer", textPair(t.testpilot$footer()));
        JsonArray names = new JsonArray();
        if (mc.getConnection() != null) {
            int n = 0;
            for (PlayerInfo info : mc.getConnection().getListedOnlinePlayers()) {
                if (n++ >= 80) {
                    break;
                }
                names.add(mc.gui.getTabList().getNameForDisplay(info).getString());
            }
        }
        o.add("players", names);
        return o;
    }

    private static JsonObject textPair(Component c) {
        JsonObject o = new JsonObject();
        o.addProperty("text", c == null ? null : c.getString());
        o.addProperty("legacy", c == null ? null : Text.legacy(c));
        return o;
    }

    // ------------------------------------------------------------------ world

    private static JsonObject crosshair(Minecraft mc) {
        JsonObject o = new JsonObject();
        HitResult hr = mc.hitResult;
        if (hr == null) {
            o.addProperty("type", "NONE");
            return o;
        }
        o.addProperty("type", hr.getType().name());
        if (hr instanceof BlockHitResult bhr && hr.getType() == HitResult.Type.BLOCK) {
            BlockPos bp = bhr.getBlockPos();
            BlockState st = mc.level.getBlockState(bp);
            JsonObject b = new JsonObject();
            b.addProperty("x", bp.getX());
            b.addProperty("y", bp.getY());
            b.addProperty("z", bp.getZ());
            b.addProperty("id", BuiltInRegistries.BLOCK.getKey(st.getBlock()).toString());
            b.addProperty("face", bhr.getDirection().getName());
            o.add("block", b);
        } else if (hr instanceof EntityHitResult ehr) {
            o.add("entity", entity(mc, mc.player, ehr.getEntity(), null));
        }
        return o;
    }

    private static JsonArray entities(Minecraft mc, LocalPlayer p, double radius) {
        AABB box = p.getBoundingBox().inflate(radius);
        List<Entity> list = mc.level.getEntities(p, box, e -> e.distanceTo(p) <= radius);
        list.sort(Comparator.comparingDouble(e -> e.distanceTo(p)));
        JsonArray out = new JsonArray();
        int n = 0;
        for (Entity e : list) {
            if (n++ >= 200) {
                break;
            }
            out.add(entity(mc, p, e, list));
        }
        return out;
    }

    public static JsonObject entity(Minecraft mc, Player p, Entity e, List<Entity> neighbours) {
        JsonObject o = new JsonObject();
        o.addProperty("id", e.getId());
        o.addProperty("uuid", e.getUUID().toString());
        o.addProperty("type", BuiltInRegistries.ENTITY_TYPE.getKey(e.getType()).toString());
        o.addProperty("name", e.getName().getString());
        Component cn = e.getCustomName();
        o.addProperty("customName", cn == null ? null : cn.getString());
        o.addProperty("customNameLegacy", cn == null ? null : Text.legacy(cn));
        if (e instanceof Display.TextDisplay td) {
            o.addProperty("text", td.getText().getString());
        }
        String plate = nameplate(mc, e, neighbours);
        o.addProperty("nameplate", plate);
        o.addProperty("x", e.getX());
        o.addProperty("y", e.getY());
        o.addProperty("z", e.getZ());
        Vec3 c = e.getBoundingBox().getCenter();
        o.addProperty("centerY", c.y);
        o.addProperty("height", e.getBbHeight());
        o.addProperty("distance", p == null ? -1 : e.distanceTo(p));
        o.addProperty("alive", e.isAlive());
        o.addProperty("inWater", e.isInWater());
        if (e instanceof LivingEntity le) {
            o.addProperty("health", le.getHealth());
            o.addProperty("maxHealth", le.getMaxHealth());
        }
        o.addProperty("isPlayer", e instanceof Player);
        return o;
    }

    /**
     * The label a player would read over this entity: its own custom name, or
     * (for mobs labelled by a floating text display / armour stand) the nearest
     * such label hovering over its head.
     */
    public static String nameplate(Minecraft mc, Entity e, List<Entity> neighbours) {
        if (e.getCustomName() != null) {
            return e.getCustomName().getString();
        }
        if (!(e instanceof LivingEntity) || e instanceof ArmorStand) {
            return null;
        }
        AABB bb = e.getBoundingBox();
        AABB above = new AABB(bb.minX - 0.6, bb.maxY - 0.6, bb.minZ - 0.6, bb.maxX + 0.6, bb.maxY + 2.2, bb.maxZ + 0.6);
        List<Entity> near = neighbours != null ? neighbours : mc.level.getEntities(e, above, x -> true);
        // Several stacked labels (e.g. "ISLAND HELPER" / "Starter kit & tips" /
        // "Gerald") read top to bottom, joined with " | ".
        List<Entity> labels = new ArrayList<>();
        for (Entity x : near) {
            if (x == e || !above.contains(x.position())) {
                continue;
            }
            if ((x instanceof Display.TextDisplay td && !td.getText().getString().isBlank())
                    || (x instanceof ArmorStand && x.getCustomName() != null)) {
                labels.add(x);
            }
        }
        if (labels.isEmpty()) {
            return null;
        }
        labels.sort(Comparator.comparingDouble((Entity x) -> x.getY()).reversed());
        StringBuilder sb = new StringBuilder();
        for (Entity x : labels) {
            String t = x instanceof Display.TextDisplay td ? td.getText().getString() : x.getCustomName().getString();
            if (sb.length() > 0) {
                sb.append(" | ");
            }
            sb.append(t.replace('\n', ' ').trim());
        }
        return sb.toString();
    }
}
