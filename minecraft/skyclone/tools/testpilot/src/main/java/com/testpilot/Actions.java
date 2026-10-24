package com.testpilot;

import com.google.gson.JsonObject;
import com.mojang.blaze3d.platform.NativeImage;
import com.testpilot.mixin.MinecraftInvoker;
import net.minecraft.client.Minecraft;
import net.minecraft.client.Screenshot;
import net.minecraft.client.gui.screens.inventory.AbstractContainerScreen;
import net.minecraft.client.gui.screens.inventory.InventoryScreen;
import net.minecraft.client.player.LocalPlayer;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.core.component.DataComponents;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.network.chat.Component;
import net.minecraft.network.protocol.game.ServerboundPlayerActionPacket;
import net.minecraft.util.Mth;
import net.minecraft.world.InteractionHand;
import net.minecraft.world.InteractionResult;
import net.minecraft.world.entity.Display;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.Marker;
import net.minecraft.world.effect.MobEffects;
import net.minecraft.world.entity.ai.attributes.Attributes;
import net.minecraft.world.entity.decoration.ArmorStand;
import net.minecraft.world.inventory.AbstractContainerMenu;
import net.minecraft.world.inventory.ContainerInput;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.component.ItemLore;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.phys.AABB;
import net.minecraft.world.phys.BlockHitResult;
import net.minecraft.world.phys.EntityHitResult;
import net.minecraft.world.phys.HitResult;
import net.minecraft.world.phys.Vec3;
import org.lwjgl.glfw.GLFW;

import java.io.File;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;
import java.util.function.Supplier;

/** Endpoint implementations. Called on an HTTP thread; game work goes to the client thread. */
public final class Actions {

    private Actions() {
    }

    public static JsonObject dispatch(String endpoint, JsonObject b) {
        return switch (endpoint) {
            case "", "ping" -> onClient(Actions::ping);
            case "state" -> onClient(() -> StateDump.state(mc(), dbl(b, "radius", 32), (int) dbl(b, "chat", 100)));
            case "chatsince" -> chatSince(b);
            case "chat" -> onClient(() -> chat(str(b, "text", null)));
            case "look" -> onClient(() -> look(b));
            case "attack" -> attack(b);
            case "attackhold" -> attackHold(b);
            case "use" -> use(b);
            case "usehold" -> useHold(b);
            case "hotbar" -> onClient(() -> hotbar((int) dbl(b, "slot", -1)));
            case "drop" -> onClient(() -> drop(bool(b, "all", false)));
            case "swapoffhand" -> onClient(Actions::swapOffhand);
            case "click" -> onClient(() -> click(b));
            case "close" -> onClient(Actions::close);
            case "openinv" -> onClient(Actions::openInv);
            case "move" -> move(b);
            case "walkto" -> walkTo(b);
            case "stop" -> onClient(Actions::stop);
            case "screenshot" -> screenshot(b);
            case "wait" -> waitTicks(b);
            case "findblocks" -> onClient(() -> findBlocks(b));
            case "clearance" -> onClient(() -> clearance(b));
            default -> throw new PilotException(404, "unknown endpoint /" + endpoint
                    + " (ping state chatsince chat look attack attackhold use usehold hotbar drop swapoffhand click close"
                    + " openinv move walkto stop screenshot wait findblocks clearance)");
        };
    }

    // ------------------------------------------------------------------ plumbing

    private static Minecraft mc() {
        return Minecraft.getInstance();
    }

    private static LocalPlayer player() {
        LocalPlayer p = mc().player;
        if (p == null || mc().level == null || mc().gameMode == null) {
            throw new PilotException(409, "not in a world");
        }
        return p;
    }

    private static JsonObject onClient(Supplier<JsonObject> s) {
        return await(mc().submit(s), 15);
    }

    private static JsonObject await(CompletableFuture<JsonObject> f, double seconds) {
        try {
            return f.get((long) (seconds * 1000), TimeUnit.MILLISECONDS);
        } catch (TimeoutException e) {
            throw new PilotException(504, "timed out after " + seconds + "s waiting for the client thread");
        } catch (ExecutionException e) {
            Throwable c = e.getCause();
            if (c instanceof PilotException pe) {
                throw pe;
            }
            throw new PilotException(500, c.getClass().getSimpleName() + ": " + c.getMessage());
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new PilotException(500, "interrupted");
        }
    }

    private static double dbl(JsonObject b, String k, double d) {
        return b.has(k) && !b.get(k).isJsonNull() ? b.get(k).getAsDouble() : d;
    }

    private static boolean bool(JsonObject b, String k, boolean d) {
        return b.has(k) && !b.get(k).isJsonNull() ? b.get(k).getAsBoolean() : d;
    }

    private static String str(JsonObject b, String k, String d) {
        return b.has(k) && !b.get(k).isJsonNull() ? b.get(k).getAsString() : d;
    }

    private static JsonObject ok() {
        JsonObject o = new JsonObject();
        o.addProperty("ok", true);
        return o;
    }

    private static JsonObject pos(LocalPlayer p) {
        JsonObject o = new JsonObject();
        o.addProperty("x", p.getX());
        o.addProperty("y", p.getY());
        o.addProperty("z", p.getZ());
        o.addProperty("yaw", p.getYRot());
        o.addProperty("pitch", p.getXRot());
        return o;
    }

    // ------------------------------------------------------------------ simple

    private static JsonObject ping() {
        Minecraft mc = mc();
        JsonObject o = ok();
        o.addProperty("mod", "testpilot");
        o.addProperty("port", TestPilot.port());
        o.addProperty("inWorld", mc.player != null && mc.level != null);
        o.addProperty("player", mc.player == null ? null : mc.player.getGameProfile().name());
        o.addProperty("focused", mc.getWindow().isFocused());
        o.addProperty("osFocused", osFocused(mc));
        o.addProperty("screen", mc.screen == null ? null : mc.screen.getTitle().getString());
        o.addProperty("screenType", mc.screen == null ? null : mc.screen.getClass().getSimpleName());
        o.addProperty("chatCount", ChatLog.total());
        o.addProperty("tick", Jobs.clientTicks());
        return o;
    }

    /** What GLFW says right now (not the game's cached flag). */
    static boolean osFocused(Minecraft mc) {
        return GLFW.glfwGetWindowAttrib(mc.getWindow().handle(), GLFW.GLFW_FOCUSED) == GLFW.GLFW_TRUE;
    }

    /** Blocks whose id contains {@code block} within a cube of {@code radius} (max 24), nearest first. */
    private static JsonObject findBlocks(JsonObject b) {
        String needle = str(b, "block", null);
        if (needle == null || needle.isEmpty()) {
            throw new PilotException("findblocks needs {block: \"coal_ore\", radius?, limit?}");
        }
        needle = needle.toLowerCase(Locale.ROOT);
        LocalPlayer p = player();
        var level = mc().level;
        int r = Mth.clamp((int) dbl(b, "radius", 8), 1, 24);
        int limit = Math.max(1, (int) dbl(b, "limit", 20));
        boolean exposedOnly = bool(b, "exposed", false);
        Vec3 eye = p.getEyePosition();
        BlockPos c = p.blockPosition();
        java.util.List<Object[]> hits = new java.util.ArrayList<>();
        BlockPos.MutableBlockPos m = new BlockPos.MutableBlockPos();
        for (int dx = -r; dx <= r; dx++) {
            for (int dy = -r; dy <= r; dy++) {
                for (int dz = -r; dz <= r; dz++) {
                    m.set(c.getX() + dx, c.getY() + dy, c.getZ() + dz);
                    String id = BuiltInRegistries.BLOCK.getKey(level.getBlockState(m).getBlock()).toString();
                    if (!id.contains(needle)) {
                        continue;
                    }
                    boolean exposed = false;
                    for (Direction d : Direction.values()) {
                        if (level.getBlockState(m.relative(d)).isAir()) {
                            exposed = true;
                            break;
                        }
                    }
                    if (exposedOnly && !exposed) {
                        continue;
                    }
                    hits.add(new Object[] {m.immutable(), id, exposed, eye.distanceTo(Vec3.atCenterOf(m))});
                }
            }
        }
        hits.sort(java.util.Comparator.comparingDouble(h -> (double) h[3]));
        com.google.gson.JsonArray out = new com.google.gson.JsonArray();
        for (int i = 0; i < Math.min(limit, hits.size()); i++) {
            Object[] h = hits.get(i);
            BlockPos bp = (BlockPos) h[0];
            JsonObject o = new JsonObject();
            o.addProperty("x", bp.getX());
            o.addProperty("y", bp.getY());
            o.addProperty("z", bp.getZ());
            o.addProperty("id", (String) h[1]);
            o.addProperty("exposed", (Boolean) h[2]);
            o.addProperty("distance", (Double) h[3]);
            out.add(o);
        }
        JsonObject o = ok();
        o.addProperty("found", hits.size());
        o.add("blocks", out);
        return o;
    }

    private static JsonObject chatSince(JsonObject b) {
        JsonObject o = ok();
        o.add("lines", ChatLog.since((long) dbl(b, "since", 0)));
        o.addProperty("chatCount", ChatLog.total());
        return o;
    }

    private static JsonObject chat(String text) {
        if (text == null || text.isEmpty()) {
            throw new PilotException("chat needs {text}");
        }
        LocalPlayer p = player();
        if (text.startsWith("/")) {
            p.connection.sendCommand(text.substring(1));
        } else {
            p.connection.sendChat(text);
        }
        JsonObject o = ok();
        o.addProperty("sent", text);
        o.addProperty("chatCount", ChatLog.total());
        return o;
    }

    private static JsonObject hotbar(int slot) {
        if (slot < 0 || slot > 8) {
            throw new PilotException("hotbar {slot} must be 0-8 (8 = the key labelled 9)");
        }
        LocalPlayer p = player();
        p.getInventory().setSelectedSlot(slot);
        JsonObject o = ok();
        o.addProperty("slot", slot);
        o.add("item", StateDump.item(p.getInventory().getItem(slot)));
        return o;
    }

    private static JsonObject drop(boolean all) {
        LocalPlayer p = player();
        boolean dropped = !p.isSpectator() && p.drop(all);
        if (dropped) {
            p.swing(InteractionHand.MAIN_HAND);
        }
        JsonObject o = ok();
        o.addProperty("dropped", dropped);
        return o;
    }

    private static JsonObject swapOffhand() {
        LocalPlayer p = player();
        p.connection.send(new ServerboundPlayerActionPacket(
                ServerboundPlayerActionPacket.Action.SWAP_ITEM_WITH_OFFHAND, BlockPos.ZERO, Direction.DOWN));
        return ok();
    }

    private static JsonObject click(JsonObject b) {
        LocalPlayer p = player();
        if (!b.has("slot")) {
            throw new PilotException("click needs {slot, button?, action?}");
        }
        int slot = (int) dbl(b, "slot", -1);
        int button = (int) dbl(b, "button", 0);
        ContainerInput action;
        try {
            action = ContainerInput.valueOf(str(b, "action", "PICKUP").toUpperCase(Locale.ROOT));
        } catch (IllegalArgumentException e) {
            throw new PilotException("action must be one of PICKUP QUICK_MOVE SWAP CLONE THROW QUICK_CRAFT PICKUP_ALL");
        }
        AbstractContainerMenu menu = p.containerMenu;
        if (slot != -999 && (slot < 0 || slot >= menu.slots.size())) {
            throw new PilotException("slot " + slot + " out of range 0.." + (menu.slots.size() - 1)
                    + " for the open menu (containerId " + menu.containerId + ")");
        }
        JsonObject o = ok();
        o.addProperty("containerId", menu.containerId);
        if (slot >= 0) {
            o.add("before", StateDump.item(menu.slots.get(slot).getItem()));
        }
        mc().gameMode.handleContainerInput(menu.containerId, slot, button, action, p);
        o.addProperty("slot", slot);
        o.addProperty("button", button);
        o.addProperty("action", action.name());
        return o;
    }

    private static JsonObject close() {
        Minecraft mc = mc();
        JsonObject o = ok();
        if (mc.screen == null) {
            o.addProperty("closed", false);
            return o;
        }
        o.addProperty("closed", true);
        o.addProperty("title", mc.screen.getTitle().getString());
        if (mc.screen instanceof AbstractContainerScreen<?> && mc.player != null) {
            mc.player.closeContainer();
        } else {
            mc.setScreen(null);
        }
        return o;
    }

    private static JsonObject openInv() {
        LocalPlayer p = player();
        Minecraft mc = mc();
        if (mc.gameMode.isServerControlledInventory()) {
            p.sendOpenInventory();
        } else {
            mc.setScreen(new InventoryScreen(p));
        }
        return ok();
    }

    private static JsonObject stop() {
        Jobs.cancelExclusive("/stop");
        Jobs.releaseKeys();
        return ok();
    }

    // ------------------------------------------------------------------ aiming

    /** Rotation that points the eyes at {@code t}. */
    static float[] aimAt(LocalPlayer p, Vec3 t) {
        Vec3 eye = p.getEyePosition();
        double dx = t.x - eye.x;
        double dy = t.y - eye.y;
        double dz = t.z - eye.z;
        double h = Math.sqrt(dx * dx + dz * dz);
        float yaw = (float) (Mth.atan2(dz, dx) * (180.0 / Math.PI)) - 90.0f;
        float pitch = (float) (-(Mth.atan2(dy, h) * (180.0 / Math.PI)));
        return new float[] {Mth.wrapDegrees(yaw), Mth.clamp(pitch, -90f, 90f)};
    }

    static void setRotation(LocalPlayer p, float yaw, float pitch) {
        pitch = Mth.clamp(pitch, -90f, 90f);
        p.setYRot(yaw);
        p.setXRot(pitch);
        p.yRotO = yaw;
        p.xRotO = pitch;
        p.setYHeadRot(yaw);
        p.yHeadRotO = yaw;
        p.yBodyRot = yaw;
    }

    /**
     * A point on {@code block} the crosshair can actually reach: the centre,
     * else the centre of each face that is open to air, else a few points
     * towards each open face's corners. Each is tested with the client's own
     * pick, so "visible" means exactly what clicking would hit. Null if none.
     */
    static Vec3 visiblePoint(Minecraft mc, LocalPlayer p, BlockPos block) {
        float yaw = p.getYRot();
        float pitch = p.getXRot();
        java.util.List<Vec3> candidates = new java.util.ArrayList<>();
        Vec3 c = Vec3.atCenterOf(block);
        candidates.add(c);
        Vec3 eye = p.getEyePosition();
        java.util.List<Direction> faces = new java.util.ArrayList<>(java.util.List.of(Direction.values()));
        // Faces turned towards the eye first.
        faces.sort(java.util.Comparator.comparingDouble(d -> -(
                (eye.x - c.x) * d.getStepX() + (eye.y - c.y) * d.getStepY() + (eye.z - c.z) * d.getStepZ())));
        for (Direction d : faces) {
            if (!mc.level.getBlockState(block.relative(d)).getCollisionShape(mc.level, block.relative(d)).isEmpty()) {
                continue;
            }
            Vec3 fc = c.add(d.getStepX() * 0.49, d.getStepY() * 0.49, d.getStepZ() * 0.49);
            candidates.add(fc);
            for (double u : new double[] {-0.3, 0.3}) {
                for (double v : new double[] {-0.3, 0.3}) {
                    double ox = d.getStepX() != 0 ? 0 : u;
                    double oy = d.getStepY() != 0 ? 0 : (d.getStepX() != 0 ? u : v);
                    double oz = d.getStepZ() != 0 ? 0 : v;
                    candidates.add(fc.add(ox, oy, oz));
                }
            }
        }
        for (Vec3 v : candidates) {
            float[] r = aimAt(p, v);
            setRotation(p, r[0], r[1]);
            pick();
            if (mc.hitResult instanceof BlockHitResult bhr && mc.hitResult.getType() == HitResult.Type.BLOCK
                    && bhr.getBlockPos().equals(block)) {
                return v;
            }
        }
        setRotation(p, yaw, pitch);
        return null;
    }

    /** Centre of the entity's bounding box: the body, not over its head. */
    static Vec3 bodyCentre(Entity e) {
        return e.getBoundingBox().getCenter();
    }

    static void pick() {
        ((MinecraftInvoker) mc()).testpilot$pick(1.0f);
    }

    static boolean ignorable(Entity e) {
        return e instanceof Display || e instanceof Marker || (e instanceof ArmorStand as && as.isMarker());
    }

    /** Nearest entity whose type, name, custom name or nameplate contains {@code needle}. */
    static Entity nearest(Minecraft mc, LocalPlayer p, String needle, double radius) {
        String n = needle.toLowerCase(Locale.ROOT);
        AABB box = p.getBoundingBox().inflate(radius);
        List<Entity> list = mc.level.getEntities(p, box, e -> e.isAlive() && e.distanceTo(p) <= radius);
        Entity best = null;
        double bestD = Double.MAX_VALUE;
        for (Entity e : list) {
            if (ignorable(e) || (e instanceof ArmorStand && !n.contains("armor"))) {
                continue;
            }
            StringBuilder hay = new StringBuilder();
            hay.append(BuiltInRegistries.ENTITY_TYPE.getKey(e.getType())).append(' ');
            hay.append(e.getName().getString()).append(' ');
            if (e.getCustomName() != null) {
                hay.append(e.getCustomName().getString()).append(' ');
            }
            String plate = StateDump.nameplate(mc, e, null);
            if (plate != null) {
                hay.append(plate);
            }
            if (hay.toString().toLowerCase(Locale.ROOT).contains(n)) {
                double d = e.distanceTo(p);
                if (d < bestD) {
                    bestD = d;
                    best = e;
                }
            }
        }
        return best;
    }

    static Entity resolveEntity(Minecraft mc, LocalPlayer p, JsonObject b) {
        if (b.has("entity") && !b.get("entity").isJsonNull()) {
            int id = b.get("entity").getAsInt();
            Entity e = mc.level.getEntity(id);
            if (e == null) {
                throw new PilotException(404, "no entity with id " + id + " is loaded");
            }
            return e;
        }
        if (b.has("nearest") && !b.get("nearest").isJsonNull()) {
            String needle = b.get("nearest").getAsString();
            Entity e = nearest(mc, p, needle, dbl(b, "radius", 32));
            if (e == null) {
                throw new PilotException(404, "no living entity like '" + needle + "' within "
                        + dbl(b, "radius", 32) + " blocks");
            }
            return e;
        }
        return null;
    }

    private static JsonObject look(JsonObject b) {
        Minecraft mc = mc();
        LocalPlayer p = player();
        JsonObject o = ok();
        Entity e = resolveEntity(mc, p, b);
        float[] rot;
        if (e != null) {
            rot = aimAt(p, bodyCentre(e));
            o.add("target", StateDump.entity(mc, p, e, null));
        } else if (b.has("x") && b.has("y") && b.has("z")) {
            Vec3 t = new Vec3(dbl(b, "x", 0), dbl(b, "y", 0), dbl(b, "z", 0));
            rot = aimAt(p, t);
        } else if (b.has("yaw") || b.has("pitch")) {
            rot = new float[] {(float) dbl(b, "yaw", p.getYRot()), (float) dbl(b, "pitch", p.getXRot())};
        } else {
            throw new PilotException("look needs {yaw,pitch} | {x,y,z} | {entity:id} | {nearest:\"name\"}");
        }
        setRotation(p, rot[0], rot[1]);
        pick();
        o.add("rotation", pos(p));
        o.add("crosshair", StateDump.state(mc, 0, 0).get("crosshair"));
        return o;
    }

    private static JsonObject hitInfo(Minecraft mc) {
        JsonObject h = new JsonObject();
        HitResult hr = mc.hitResult;
        h.addProperty("type", hr == null ? "NONE" : hr.getType().name());
        if (hr instanceof EntityHitResult ehr) {
            h.addProperty("entity", ehr.getEntity().getId());
            h.addProperty("entityType", BuiltInRegistries.ENTITY_TYPE.getKey(ehr.getEntity().getType()).toString());
        } else if (hr instanceof BlockHitResult bhr && hr.getType() == HitResult.Type.BLOCK) {
            BlockPos bp = bhr.getBlockPos();
            h.addProperty("x", bp.getX());
            h.addProperty("y", bp.getY());
            h.addProperty("z", bp.getZ());
            h.addProperty("block", BuiltInRegistries.BLOCK.getKey(mc.level.getBlockState(bp).getBlock()).toString());
        }
        return h;
    }

    // ------------------------------------------------------------------ attack

    /**
     * Left click. With a target: aim at its body, let one tick pass so the
     * server has the new rotation, re-aim (it moves), pick, then run the
     * client's own startAttack() - i.e. exactly what a click does.
     */
    private static JsonObject attack(JsonObject b) {
        Jobs.Job job = new Jobs.Job() {
            Entity target;

            @Override
            public JsonObject tick(Minecraft mc, int t) {
                LocalPlayer p = player();
                if (mc.screen != null) {
                    throw new PilotException(409, "a screen is open (" + mc.screen.getTitle().getString() + "); /close first");
                }
                if (t == 0) {
                    target = resolveEntity(mc, p, b);
                    if (target != null) {
                        float[] r = aimAt(p, bodyCentre(target));
                        setRotation(p, r[0], r[1]);
                        return null;
                    }
                }
                if (target != null) {
                    float[] r = aimAt(p, bodyCentre(target));
                    setRotation(p, r[0], r[1]);
                }
                pick();
                JsonObject o = ok();
                o.add("hit", hitInfo(mc));
                boolean onTarget = target == null
                        || (mc.hitResult instanceof EntityHitResult ehr && ehr.getEntity() == target);
                o.addProperty("onTarget", onTarget);
                o.addProperty("attackStrength", p.getAttackStrengthScale(0f));
                ((MinecraftInvoker) mc).testpilot$startAttack();
                o.addProperty("attacked", mc.hitResult != null && mc.hitResult.getType() == HitResult.Type.ENTITY);
                o.addProperty("cooldownTicks", (int) Math.ceil(p.getCurrentItemAttackStrengthDelay()));
                if (target != null) {
                    o.addProperty("target", target.getId());
                    o.addProperty("distance", target.distanceTo(p));
                    o.addProperty("targetAlive", target.isAlive());
                }
                return o;
            }

            @Override
            public void cleanup(Minecraft mc) {
            }
        };
        return await(Jobs.submit(job), 10);
    }

    /**
     * Hold left click: press (startAttack), then keep {@code keyAttack} down so
     * vanilla's own continueAttack() breaks the block with real progress.
     */
    private static JsonObject attackHold(JsonObject b) {
        double seconds = dbl(b, "seconds", 1.0);
        boolean hasBlock = b.has("x") && b.has("y") && b.has("z");
        BlockPos block = hasBlock ? BlockPos.containing(dbl(b, "x", 0), dbl(b, "y", 0), dbl(b, "z", 0)) : null;
        int ticks = (int) Math.round(seconds * 20);
        Jobs.Job job = new Jobs.Job() {
            String before;
            Vec3 aim;

            @Override
            public JsonObject tick(Minecraft mc, int t) {
                LocalPlayer p = player();
                if (block != null && aim != null) {
                    float[] r = aimAt(p, aim);
                    setRotation(p, r[0], r[1]);
                }
                if (t == 0) {
                    if (mc.screen != null) {
                        throw new PilotException(409, "a screen is open; /close first");
                    }
                    if (block != null) {
                        before = BuiltInRegistries.BLOCK.getKey(mc.level.getBlockState(block).getBlock()).toString();
                        aim = visiblePoint(mc, p, block);
                        if (aim == null) {
                            pick();
                            JsonObject o = PilotServer.error("no line of sight to " + before + " at "
                                    + block.toShortString() + " within reach; the crosshair stops at " + hitInfo(mc));
                            o.add("hit", hitInfo(mc));
                            return o;
                        }
                        float[] r = aimAt(p, aim);
                        setRotation(p, r[0], r[1]);
                    }
                    pick();
                    Jobs.hold(mc.options.keyAttack, true);
                    ((MinecraftInvoker) mc).testpilot$startAttack();
                    return null;
                }
                // "Broken" = the block is no longer what we started on. Servers with
                // custom mining (SkyClone's DigEngine) replace an ore with a regen
                // placeholder such as bedrock instead of air.
                boolean broken = block != null && !BuiltInRegistries.BLOCK
                        .getKey(mc.level.getBlockState(block).getBlock()).toString().equals(before);
                if (broken || t >= ticks || mc.screen != null) {
                    Jobs.hold(mc.options.keyAttack, false);
                    JsonObject o = ok();
                    o.addProperty("ticks", t);
                    if (block != null) {
                        o.addProperty("before", before);
                        o.addProperty("after", BuiltInRegistries.BLOCK.getKey(mc.level.getBlockState(block).getBlock()).toString());
                        o.addProperty("broken", broken);
                    }
                    o.add("hit", hitInfo(mc));
                    return o;
                }
                return null;
            }

            @Override
            public boolean exclusive() {
                return true;
            }
        };
        return await(Jobs.submit(job), seconds + 10);
    }

    /**
     * Hold right click: press (startUseItem), keep {@code keyUse} down for {@code seconds}, then let
     * go, so vanilla's own handleKeybinds() calls releaseUsingItem() - a real bow draw and loose
     * (a click releases on the same tick, below a bow's 0.1 power floor, and shoots nothing).
     * With {@code x,y,z} it keeps aiming at that exact point (plus {@code lift} blocks of
     * elevation, for arrow drop) on every tick of the draw.
     */
    private static JsonObject useHold(JsonObject b) {
        if (bool(b, "probe", false)) {
            return ok();   // lets a script ask "does this jar have usehold?" without pressing anything
        }
        double seconds = dbl(b, "seconds", 1.2);
        boolean hasPoint = b.has("x") && b.has("y") && b.has("z");
        Vec3 point = hasPoint ? new Vec3(dbl(b, "x", 0), dbl(b, "y", 0) + dbl(b, "lift", 0), dbl(b, "z", 0)) : null;
        int ticks = Math.max(1, (int) Math.round(seconds * 20));
        Jobs.Job job = new Jobs.Job() {
            @Override
            public JsonObject tick(Minecraft mc, int t) {
                LocalPlayer p = player();
                if (point != null) {
                    float[] r = aimAt(p, point);
                    setRotation(p, r[0], r[1]);
                }
                if (t == 0) {
                    if (mc.screen != null) {
                        throw new PilotException(409, "a screen is open; /close first");
                    }
                    return point != null ? null : start(mc);   // let the rotation reach the server first
                }
                if (t == 1 && point != null) return start(mc);
                if (t >= ticks + (point != null ? 1 : 0) || mc.screen != null) {
                    Jobs.hold(mc.options.keyUse, false);
                    JsonObject o = ok();
                    o.addProperty("ticks", t);
                    o.addProperty("wasUsing", p.isUsingItem());
                    o.add("hit", hitInfo(mc));
                    return o;
                }
                return null;
            }

            private JsonObject start(Minecraft mc) {
                pick();
                Jobs.hold(mc.options.keyUse, true);
                ((MinecraftInvoker) mc).testpilot$startUseItem();
                return null;
            }

            @Override
            public void cleanup(Minecraft mc) {
                Jobs.hold(mc.options.keyUse, false);
            }

            @Override
            public boolean exclusive() {
                return true;
            }
        };
        return await(Jobs.submit(job), seconds + 10);
    }

    // ------------------------------------------------------------------ use

    private static JsonObject use(JsonObject b) {
        String target = str(b, "target", "auto").toLowerCase(Locale.ROOT);
        InteractionHand hand = str(b, "hand", "main").toLowerCase(Locale.ROOT).startsWith("off")
                ? InteractionHand.OFF_HAND : InteractionHand.MAIN_HAND;
        boolean aimFirst = b.has("entity") || b.has("nearest") || (b.has("x") && b.has("y") && b.has("z"));
        Jobs.Job job = new Jobs.Job() {
            Entity entity;

            @Override
            public JsonObject tick(Minecraft mc, int t) {
                LocalPlayer p = player();
                if (t == 0) {
                    entity = resolveEntity(mc, p, b);
                }
                if (entity != null) {
                    float[] r = aimAt(p, bodyCentre(entity));
                    setRotation(p, r[0], r[1]);
                } else if (b.has("x") && b.has("y") && b.has("z")) {
                    Vec3 v = Vec3.atCenterOf(BlockPos.containing(dbl(b, "x", 0), dbl(b, "y", 0), dbl(b, "z", 0)));
                    float[] r = aimAt(p, v);
                    setRotation(p, r[0], r[1]);
                }
                if (t == 0 && aimFirst) {
                    return null; // let the rotation reach the server first
                }
                pick();
                JsonObject o = ok();
                o.addProperty("target", target);
                o.add("hit", hitInfo(mc));
                String result;
                switch (target) {
                    case "auto" -> {
                        ((MinecraftInvoker) mc).testpilot$startUseItem();
                        result = "startUseItem";
                    }
                    case "air" -> {
                        InteractionResult r = mc.gameMode.useItem(p, hand);
                        if (r instanceof InteractionResult.Success s && s.swingSource() == InteractionResult.SwingSource.CLIENT) {
                            p.swing(hand);
                        }
                        result = r.toString();
                    }
                    case "block" -> {
                        if (!(mc.hitResult instanceof BlockHitResult bhr) || mc.hitResult.getType() != HitResult.Type.BLOCK) {
                            throw new PilotException(409, "crosshair is not on a block");
                        }
                        InteractionResult r = mc.gameMode.useItemOn(p, hand, bhr);
                        if (r instanceof InteractionResult.Success s && s.swingSource() == InteractionResult.SwingSource.CLIENT) {
                            p.swing(hand);
                        }
                        result = r.toString();
                    }
                    case "entity" -> {
                        Entity e = entity;
                        if (e == null && mc.hitResult instanceof EntityHitResult ehr) {
                            e = ehr.getEntity();
                        }
                        if (e == null) {
                            throw new PilotException(409, "no entity given and none under the crosshair");
                        }
                        InteractionResult r = mc.gameMode.interact(p, e, new EntityHitResult(e, bodyCentre(e)), hand);
                        if (r instanceof InteractionResult.Success s && s.swingSource() == InteractionResult.SwingSource.CLIENT) {
                            p.swing(hand);
                        }
                        o.addProperty("entity", e.getId());
                        result = r.toString();
                    }
                    default -> throw new PilotException("use target must be auto|air|block|entity");
                }
                o.addProperty("result", result);
                return o;
            }

            @Override
            public void cleanup(Minecraft mc) {
            }
        };
        return await(Jobs.submit(job), 10);
    }

    // ------------------------------------------------------------------ movement

    private static JsonObject move(JsonObject b) {
        double forward = dbl(b, "forward", 0);
        double strafe = dbl(b, "strafe", 0);
        boolean jump = bool(b, "jump", false);
        boolean sneak = bool(b, "sneak", false);
        boolean sprint = bool(b, "sprint", false);
        double seconds = dbl(b, "seconds", 1.0);
        // Raw keys walk off edges as readily as the old walker did - and the AI show's stuck-recovery
        // ladder answers a walk that stopped AT an edge with "/move forward, jump, sprint" toward the
        // same target. So /move goes through the same guard (Motion) unless told not to.
        boolean edgeGuard = bool(b, "edgeGuard", true);
        // a jump press within vanilla's double-tap window toggles flight when the player may fly
        // (Rat "Turbo Tail"); held back unless the caller is asking to toggle it (see Motion.JumpGate)
        boolean flyToggle = bool(b, "flyToggle", false);
        int ticks = Math.max(1, (int) Math.round(seconds * 20));
        Jobs.Job job = new Jobs.Job() {
            Vec3 start;
            final WorldWatch watch = new WorldWatch();
            double supportY = Double.NaN;
            int guardTicks, jumpsHeld;
            String risk;

            @Override
            public JsonObject tick(Minecraft mc, int t) {
                LocalPlayer p = player();
                var o = mc.options;
                if (t == 0) {
                    start = p.position();
                    watch.start(mc, p);
                }
                String left = watch.check(mc, p);
                if (t >= ticks || left != null) {
                    Jobs.releaseKeys();
                    JsonObject r = ok();
                    r.add("pos", pos(p));
                    r.addProperty("moved", left != null ? 0 : p.position().distanceTo(start));
                    r.addProperty("ticks", t);
                    r.addProperty("guardTicks", guardTicks);
                    if (jumpsHeld > 0) {
                        r.addProperty("jumpsHeldBack", jumpsHeld);
                    }
                    r.addProperty("edge", risk != null);
                    if (risk != null) {
                        r.addProperty("guardRisk", risk);
                    }
                    if (left != null) {
                        r.addProperty("stoppedBy", left);
                        r.addProperty("worldChanged", watch.worldChanged);
                    }
                    return r;
                }
                Motion.Keys k = new Motion.Keys();
                k.yaw = p.getYRot();
                k.forward = forward > 0.01;
                k.back = forward < -0.01;
                k.right = strafe > 0.01;
                k.left = strafe < -0.01;
                k.jump = jump;
                k.sneak = sneak;
                k.sprint = sprint;
                if (k.jump && !flyToggle && !JUMP_GATE.allow(p.tickCount, o.keyJump.isDown(), p.getAbilities().mayfly)) {
                    k.jump = false;
                    jumpsHeld++;
                }
                if (p.onGround() || p.onClimbable() || p.isInWater() || Double.isNaN(supportY)) {
                    supportY = p.getY();
                }
                if (edgeGuard && !p.isInWater() && !p.getAbilities().flying) {
                    Motion.Choice ch = Motion.choose(mc.level, body(p, supportY), k);
                    if (ch.level > 0) {
                        guardTicks++;
                        risk = ch.risk;
                        k = ch.keys;
                        if (k.stopSprint && p.isSprinting()) {
                            p.setSprinting(false);
                        }
                        if (Math.abs(Mth.wrapDegrees(k.yaw - p.getYRot())) > 1e-3) {
                            setRotation(p, Mth.wrapDegrees(k.yaw), p.getXRot());
                        }
                    }
                }
                Jobs.hold(o.keyUp, k.forward);
                Jobs.hold(o.keyDown, k.back);
                Jobs.hold(o.keyRight, k.right);
                Jobs.hold(o.keyLeft, k.left);
                JUMP_GATE.applied(p.tickCount, o.keyJump.isDown(), k.jump);
                Jobs.hold(o.keyJump, k.jump);
                Jobs.hold(o.keyShift, k.sneak);
                Jobs.hold(o.keySprint, k.sprint);
                return null;
            }

            @Override
            public boolean exclusive() {
                return true;
            }
        };
        return await(Jobs.submit(job), seconds + 10);
    }

    /** The local player's movement state for {@link Motion}: position, velocity, flags, and its own numbers. */
    static Motion.Body body(LocalPlayer p, double supportY) {
        Motion.Body b = new Motion.Body();
        b.x = p.getX();
        b.y = p.getY();
        b.z = p.getZ();
        Vec3 v = p.getDeltaMovement();
        b.vx = v.x;
        b.vy = v.y;
        b.vz = v.z;
        b.onGround = p.onGround();
        b.sprinting = p.isSprinting();
        b.horizontalCollision = p.horizontalCollision;
        // MOVEMENT_SPEED carries SkyBlock's Speed stat; the sprint modifier is a x1.3 on top of it
        double speed = p.getAttributeValue(Attributes.MOVEMENT_SPEED);
        b.speed = p.isSprinting() ? speed / 1.3 : speed;
        double jump = p.getAttributeValue(Attributes.JUMP_STRENGTH);
        var boost = p.getEffect(MobEffects.JUMP_BOOST);
        b.jumpPower = jump + (boost == null ? 0 : 0.1 * (boost.getAmplifier() + 1));
        b.gravity = p.getAttributeValue(Attributes.GRAVITY);
        b.stepHeight = p.maxUpStep();
        // the real hitbox (SCALE and pose): a Rat "Shapeshift" player is 0.18 wide, and a guard that
        // simulates a 0.6-wide body lets it slide past what would have stopped a person (wave 5)
        b.halfW = p.getBbWidth() / 2.0;
        b.height = p.getBbHeight();
        b.supportY = Double.isNaN(supportY) ? p.getY() : supportY;
        return b;
    }

    /** Shared by every job: a double tap can span two /move calls. Client thread only. */
    static final Motion.JumpGate JUMP_GATE = new Motion.JumpGate();

    /**
     * A movement job belongs to the world it started in. Wave-4 playtest: {@code /walkto} aimed at
     * the Dwarven Mines -> Glacite Tunnels portal went through it, and then kept steering for the
     * OLD target's coordinates in the NEW world - it dragged the player 110 blocks into the tunnels
     * (cold and mobs, 637 -> 296 HP), and did the same on the way back. A portal, a /warp, a lift
     * ride or a void rescue all end the job: either the client was handed a new level (a different
     * world), or the player moved further in one tick than walking, falling or a ridden pet can.
     */
    static final class WorldWatch {
        /** Far more than one tick of the fastest legit motion (terminal fall ~3.9, a ridden pet ~1.1). */
        static final double TELEPORT_BLOCKS = 8.0;
        private Level level;
        private Vec3 last;
        boolean worldChanged;

        void start(Minecraft mc, LocalPlayer p) {
            level = mc.level;
            last = p.position();
        }

        /** Null while the player is still where the job started; otherwise why the job must stop. */
        String check(Minecraft mc, LocalPlayer p) {
            if (mc.level != level) {
                worldChanged = true;
                return "world changed: " + dim(level) + " -> " + dim(mc.level);
            }
            Vec3 now = p.position();
            double jump = now.distanceTo(last);
            last = now;
            return jump > TELEPORT_BLOCKS ? "teleported " + Math.round(jump) + " blocks" : null;
        }

        private static String dim(Level l) {
            return l == null ? "none" : l.dimension().identifier().toString();
        }
    }

    /**
     * Walk to a point the way a player would: plan a path over standable cells ({@link Nav#plan}),
     * walk cell centre to cell centre, jump whatever the player's own hitbox says is a jumpable
     * rise (a 15/16 dirt_path, a slab on a block, a full block), open closed wooden doors and
     * fence gates on the path - both leaves of a double door - climb ladders up and down, and
     * re-plan when stuck or when a partial path runs out. {@code path:false} gives the old
     * straight-line steering. Stops (not arrived, {@code stoppedBy}) on a world change or a teleport
     * ({@link WorldWatch}), and with {@code noRoute:true} when nothing walkable gets closer.
     */
    /** How far above or below a target's y still counts as arrived (a storey is ~4-5 blocks). */
    static final double ARRIVE_DY = 3.0;
    /** The breath guard surfaces once the air bar is below this fraction (0.6 of 15 s leaves 9 s to find air). */
    static final double SURFACE_AT = 0.6;

    private static JsonObject walkTo(JsonObject b) {
        if (!(b.has("x") && b.has("z"))) {
            throw new PilotException("walkto needs {x, y, z, timeout?, tolerance?, sprint?, path?}");
        }
        Vec3 target = new Vec3(dbl(b, "x", 0), dbl(b, "y", Double.NaN), dbl(b, "z", 0));
        double timeout = dbl(b, "timeout", 20);
        double tol = Math.max(0.2, dbl(b, "tolerance", 0.6));
        boolean sprint = bool(b, "sprint", false);
        boolean usePath = bool(b, "path", true);
        int maxNodes = Mth.clamp((int) dbl(b, "maxNodes", 8000), 200, 40000);
        int maxTicks = (int) Math.round(timeout * 20);
        // Never step off into a drop deeper than Nav.SAFE_DROP (see Nav.groundBelow). Off only for a
        // test that wants to walk off something on purpose.
        boolean edgeGuard = bool(b, "edgeGuard", true);
        // Never step into water (a fight from a dock: kill_nearest). Already in water, it still swims out.
        boolean dry = bool(b, "dry", false);
        Jobs.Job job = new Jobs.Job() {
            Vec3 start;
            int jumpCooldown;
            // breath guard: eyes under water and the air bar running down -> surface first
            boolean surfacing;
            int surfaced, underWaterTicks, lowestAir = Integer.MAX_VALUE;
            Vec3 lastAir, airGoal;
            // edge guard / bare-handed doors
            int edgeStops, plannedEdgeStops, doorsSkipped;
            int handBefore = -1, parkedIn = -1, parkedHotbar = -1, parkedLeftIn = -1;
            // path state
            List<Nav.Waypoint> path;
            boolean planComplete;
            int idx, replans, waypoints, doorsOpened, nodesExpanded;
            int straightUntil = -1;           // steer straight (old behaviour) until this tick
            int stuckChecks;
            Vec3 lastProgress;
            final Set<Long> avoid = new HashSet<>();
            final Map<Long, Integer> doorTries = new HashMap<>();
            // door state: 0 none, 1 aimed, 2 used, waiting
            BlockPos door;
            int doorPhase, doorWait;
            float pitchBeforeDoor;
            // straight-mode sidestep state
            Vec3 lastCheck;
            boolean stalled;
            int strafeTicks, strafeDir, lastSide, blockedTries;
            // world / route state
            final WorldWatch watch = new WorldWatch();
            int noRoute, retryAt, now;
            // edge guard (Motion): last height something held the player up, ticks the keys were toned
            // down, ticks standing still because every way on would fall, columns that proved unsafe
            double supportY = Double.NaN;
            int guardTicks, edgeHold;
            String lastRisk;
            final Set<Long> noGo = new HashSet<>();
            // ladder hole entry: the waypoint the grab point was worked out for
            Nav.Waypoint grabFor;
            Vec3 grab;

            /**
             * Plan from where the player stands. False when the plan gets him no closer: boxed in,
             * or every reachable cell is further from the target than this one (the target is up a
             * wall, behind a locked door, or this is the end of a partial plan's dead end).
             */
            boolean replan(LocalPlayer p, Level level) {
                double goalR = Math.max(tol, 0.75);
                Nav.Plan plan = Nav.plan(level, p.position(), target, goalR, p.maxUpStep(), avoid, maxNodes, noGo, dry);
                nodesExpanded += plan == null ? 0 : plan.expanded;
                if (useless(plan, p) && maxNodes < 40000 && noRoute == 0) {
                    // A partial plan's "closest cell so far" can be a dead end (the inside of the wall
                    // nearest the target), and from there the same budget finds nothing better. One
                    // search with four times the budget before believing there is no route (once per
                    // stop: it can hold the client thread for a few hundred ms).
                    Nav.Plan big = Nav.plan(level, p.position(), target, goalR, p.maxUpStep(), avoid, Math.min(40000, maxNodes * 4), noGo, dry);
                    nodesExpanded += big == null ? 0 : big.expanded;
                    plan = big;
                }
                boolean useful = !useless(plan, p);
                path = useful ? plan.path : null;
                planComplete = useful && plan.complete;
                idx = 0;
                if (useful) {
                    noRoute = 0;
                    if (waypoints == 0) {
                        waypoints = path.size();
                    }
                }
                return useful;
            }

            boolean useless(Nav.Plan plan, LocalPlayer p) {
                return Nav.leadsNowhere(plan, p.position());
            }

            /** A replan found nothing: stand still and look again in a second (see tick). */
            void noRoute(int t) {
                noRoute++;
                retryAt = t + 20;
                Jobs.releaseKeys();
            }

            JsonObject finish(LocalPlayer p, Minecraft mc, boolean arrived, double dist, int t) {
                return finish(p, mc, arrived, dist, t, null);
            }

            JsonObject finish(LocalPlayer p, Minecraft mc, boolean arrived, double dist, int t, String stoppedBy) {
                Jobs.releaseKeys();
                restoreHand(mc, p);
                JsonObject r = ok();
                r.addProperty("arrived", arrived);
                r.addProperty("distance", dist);
                if (stoppedBy != null) {
                    r.addProperty("stoppedBy", stoppedBy);
                } else if (mc.screen != null && !arrived) {
                    r.addProperty("stoppedBy", "screen opened: " + mc.screen.getTitle().getString());
                }
                r.addProperty("worldChanged", watch.worldChanged);
                r.addProperty("noRoute", noRoute >= 3);
                r.add("pos", pos(p));
                r.addProperty("moved", watch.worldChanged ? 0 : p.position().distanceTo(start));
                r.addProperty("ticks", t);
                r.addProperty("pathed", usePath && waypoints > 0);
                r.addProperty("waypoints", waypoints);
                r.addProperty("planComplete", planComplete);
                r.addProperty("replans", replans);
                r.addProperty("doorsOpened", doorsOpened);
                r.addProperty("doorsSkipped", doorsSkipped);
                r.addProperty("edgeStops", edgeStops);
                r.addProperty("edge", stoppedBy != null && stoppedBy.startsWith("blocked: edge"));
                r.addProperty("water", stoppedBy != null && stoppedBy.startsWith("blocked: water"));
                r.addProperty("guardTicks", guardTicks);
                if (lastRisk != null) {
                    r.addProperty("guardRisk", lastRisk);
                }
                if (parkedLeftIn >= 0) {
                    // a screen opened while the held item was parked for a door click: it is still there
                    r.addProperty("heldItemParkedIn", parkedLeftIn);
                }
                r.addProperty("nodesExpanded", nodesExpanded);
                r.addProperty("dry", dry);
                r.addProperty("surfaced", surfaced);
                r.addProperty("underWaterTicks", underWaterTicks);
                r.addProperty("air", p.getAirSupply());
                r.addProperty("maxAir", p.getMaxAirSupply());
                if (lowestAir != Integer.MAX_VALUE) {
                    r.addProperty("lowestAir", lowestAir);
                }
                return r;
            }

            /**
             * The breath guard: with the eyes under water and the air bar below
             * {@link #SURFACE_AT} of full, everything else waits - hold jump, and when there is no
             * air straight up (a dock deck, a roof) swim to the nearest spot that has some
             * ({@link Nav#nearestAir}), else back to where the head was last above water. Ends when
             * the bar is full again. Wave-5 regression, the Bayou dock: the walker had no idea of
             * air at all, followed a sea creature under the deck and drowned (half the purse).
             */
            boolean breathe(Minecraft mc, LocalPlayer p, Level level, int t) {
                int maxAir = p.getMaxAirSupply();
                boolean under = p.isUnderWater();
                if (under) {
                    underWaterTicks++;
                    lowestAir = Math.min(lowestAir, p.getAirSupply());
                } else {
                    lastAir = p.position();
                }
                if (!surfacing && under && p.getAirSupply() < maxAir * SURFACE_AT) {
                    surfacing = true;
                    surfaced++;
                    airGoal = null;
                }
                if (surfacing && !under && p.getAirSupply() >= maxAir) {
                    surfacing = false;
                    replans++;
                    if (usePath && !replan(p, level)) {
                        noRoute(t);
                    }
                }
                if (!surfacing) {
                    return false;
                }
                Motion.Keys k = new Motion.Keys();
                k.yaw = p.getYRot();
                k.jump = JUMP_GATE.allow(p.tickCount, mc.options.keyJump.isDown(), p.getAbilities().mayfly);
                if (under && !Nav.breathableAbove(level, Mth.floor(p.getX()), p.getY(), Mth.floor(p.getZ()))) {
                    if (airGoal == null || t % 20 == 0) {
                        Vec3 a = Nav.nearestAir(level, p.position(), 10, p.getBbWidth() / 2.0, p.getBbHeight());
                        airGoal = a != null ? a : lastAir;
                    }
                    if (airGoal != null) {
                        double ax = airGoal.x - p.getX(), az = airGoal.z - p.getZ();
                        if (ax * ax + az * az > 0.04) {
                            k.yaw = Motion.yawOf(ax, az);
                            k.forward = true;
                        }
                    }
                }
                applyKeys(mc, p, k);
                return true;
            }

            @Override
            public JsonObject tick(Minecraft mc, int t) {
                LocalPlayer p = player();
                Level level = mc.level;
                var o = mc.options;
                now = t;
                if (t == 0) {
                    start = p.position();
                    lastProgress = start;
                    lastAir = p.isUnderWater() ? null : start;
                    supportY = p.getY();
                    watch.start(mc, p);
                    if (usePath && !replan(p, level)) {
                        noRoute(t);
                    }
                }
                double dx = target.x - p.getX();
                double dz = target.z - p.getZ();
                double dist = Math.sqrt(dx * dx + dz * dz);
                String left = watch.check(mc, p);
                if (left != null) {
                    // the target's coordinates mean nothing in the new world (or past the teleport)
                    return finish(p, mc, false, dist, t, left);
                }
                if (breathe(mc, p, level, t)) {
                    // air first; an "arrival" with the head under water is not one worth stopping at
                    if (t >= maxTicks || mc.screen != null) {
                        return finish(p, mc, false, dist, t, t >= maxTicks
                                ? "timeout while surfacing for air (" + p.getAirSupply() + "/" + p.getMaxAirSupply() + ")" : null);
                    }
                    return null;
                }
                // horizontal distance, and the same floor when a y is given: a point straight below on
                // the floor under this one (the Tavern's ground floor, from upstairs) is not "here"
                boolean arrived = dist <= tol && (Double.isNaN(target.y) || Math.abs(p.getY() - target.y) <= ARRIVE_DY);
                if (arrived || t >= maxTicks || mc.screen != null) {
                    return finish(p, mc, arrived, dist, t);
                }

                // ---- no route from here: stand still, look again once a second (a chunk arriving,
                // a door swinging), and after three misses SAY so - the old walker steered straight at
                // the target instead and wandered (or pushed into a wall) until the timeout
                if (usePath && path == null && noRoute > 0) {
                    if (t >= retryAt) {
                        replans++;
                        if (!replan(p, level)) {
                            if (noRoute >= 2) {
                                noRoute++;
                                if (dist > 1.5 && Motion.edgeToward(level, p.getX(), p.getZ(), p.getY(), dx, dz, p.getBbWidth() / 2.0)) {
                                    edgeStops++;
                                    return finish(p, mc, false, dist, t, "blocked: edge - the target is past a drop of"
                                            + " more than " + Nav.SAFE_DROP + " blocks (an island edge, the void) and no walkable"
                                            + " way gets closer; stopped at the edge, this is as close as the ground goes");
                                }
                                return finish(p, mc, false, dist, t, dist <= 1.5
                                        ? "no route: this is the closest standable spot (the target point itself is"
                                                + " inside a block or has no room to stand)"
                                        : "no route: nothing walkable gets closer to the target from here (it is out of"
                                                + " reach on foot - up a wall, inside a block, behind a locked door - and"
                                                + " this is as close as the ground goes)");
                            }
                            noRoute(t);
                        }
                    }
                    if (path == null) {
                        return null;
                    }
                }

                // ---- doors on the next few cells of the path
                if (doorPhase > 0 || nextDoor(p, level)) {
                    doorStep(mc, p, level);
                    return null;
                }

                // ---- where to steer this tick
                Nav.Waypoint wp = null;
                boolean straight = !usePath || path == null || t < straightUntil;
                boolean onLadder = p.onClimbable();
                if (!straight) {
                    idx = Nav.advance(path, idx, p.getX(), p.getY(), p.getZ(), onLadder);
                    if (idx < path.size()) {
                        wp = path.get(idx);
                    } else if (!planComplete) {
                        replans++;
                        // a partial path ran out: plan the next leg from here
                        if (!replan(p, level)) {
                            noRoute(t);
                            return null;
                        }
                        if (!path.isEmpty()) {
                            wp = path.get(0);
                        } else {
                            straight = true;
                        }
                    } else {
                        straight = true;        // last cell reached: final approach to the exact point
                    }
                }
                // The top of a ladder down a hole: steer for the one spot where the whole body is over
                // the hole, so it drops in and catches the ladder (Nav.grabPoint).
                boolean entry = wp != null && wp.hang && !onLadder && p.getY() > wp.y - 0.3;
                double sx = target.x, sz = target.z;
                if (entry) {
                    if (grabFor != wp) {
                        grab = Nav.grabPoint(level, wp.bx, wp.y, wp.bz);
                        grabFor = wp;
                    }
                    sx = grab.x;
                    sz = grab.z;
                } else if (wp != null) {
                    sx = wp.cx();
                    sz = wp.cz();
                }
                double ax = sx - p.getX(), az = sz - p.getZ();
                double ad = Math.max(1e-6, Math.sqrt(ax * ax + az * az));
                double nx = ax / ad, nz = az / ad;
                // Ladders: a waypoint straight above or below is climbed (hold jump) or slid down
                // (let go of everything). Pushing forward on a ladder is a CLIMB in vanilla (walking
                // into a wall while on one), so W is released going down, and while centred on the
                // column there is no direction worth facing.
                boolean column = wp != null && wp.climb && !entry && ad < 0.3;
                boolean climbUp = wp != null && (onLadder || wp.climb) && wp.y > p.getY() + 0.05;
                boolean slideDown = onLadder && wp != null && wp.y < p.getY() - 0.3
                        && (ad < 0.6 || Nav.slideFirst(level, p.getBoundingBox(), ax / ad, az / ad));
                Vec3 v = p.getDeltaMovement();

                Motion.Keys want = new Motion.Keys();
                want.yaw = p.getYRot();
                if (!column && !(entry && ad < 0.15)) {
                    want.yaw = Motion.yawOf(ax, az);
                }
                if (entry && ad < 1.5) {
                    Motion.creep(want, v.x, v.z, ax, az, ad);
                } else {
                    want.forward = !column && !slideDown;
                }
                // never sprint with a deep drop within 1.5 blocks: walk the rim like a careful player
                want.sprint = sprint && dist > 3 && strafeTicks == 0 && !onLadder && !entry
                        && !Motion.nearVoid(level, p.getX(), p.getZ(), p.getY(), 1.5, p.getBbWidth() / 2.0);

                // ---- jump: whatever the real hitbox says is a jumpable rise, or the plan's own jump
                boolean jump = false;
                if (climbUp) {
                    jump = true;                // on a ladder, holding jump climbs it
                } else if (jumpCooldown > 0) {
                    jumpCooldown--;
                } else if (p.onGround() && !column && !entry) {
                    boolean boxJump = !Double.isNaN(Nav.jumpNeeded(level, p.getBoundingBox(), p.maxUpStep(), nx, nz, 0.35));
                    boolean planJump = wp != null && wp.jump && ad < 1.3 && wp.y > p.getY() + p.maxUpStep() + 1e-3;
                    if (boxJump || planJump) {
                        jump = true;
                        jumpCooldown = 6;
                    }
                }
                if (p.isInWater() && (wp == null || wp.y >= p.getY() - 0.5)) {
                    jump = true;                // swim: hold space like a player does
                }

                // ---- stuck: no real progress for 1.5 s -> re-plan; repeatedly -> straight mode for 2 s
                if (t % 10 == 0) {
                    stalled = lastCheck != null && p.position().distanceTo(lastCheck) < 0.15;
                    lastCheck = p.position();
                    if (p.position().distanceTo(lastProgress) > 0.6) {
                        lastProgress = p.position();
                        stuckChecks = 0;
                    } else if (++stuckChecks >= 3) {
                        stuckChecks = 0;
                        lastProgress = p.position();
                        if (usePath) {
                            replans++;
                            if (replans % 3 == 0) {
                                straightUntil = t + 40;     // re-planning did not help: wiggle free
                            }
                            if (!replan(p, level)) {
                                noRoute(t);
                                return null;
                            }
                        }
                    }
                }

                // ---- straight mode only: sidestep round a frame/post toward the open side
                if (straight) {
                    boolean blocked = p.horizontalCollision || stalled;
                    if (strafeTicks > 0) {
                        strafeTicks--;
                    } else if (blocked && !jump && p.onGround()) {
                        int side = openSide(mc, p, nx, nz);
                        if (side == 0) {
                            side = lastSide == 0 ? 1 : -lastSide;
                            blockedTries++;
                        }
                        strafeDir = side;
                        lastSide = side;
                        strafeTicks = 6;
                        if (blockedTries >= 4 && jumpCooldown == 0) {
                            jump = true;
                            jumpCooldown = 10;
                            blockedTries = 0;
                        }
                    }
                } else {
                    strafeTicks = 0;
                }
                if (strafeTicks > 0) {
                    want.right = strafeDir > 0;
                    want.left = strafeDir < 0;
                }
                // never a double tap while the player may fly (Rat "Turbo Tail"): it would toggle
                // flight, and the guard below models walking, not flying (Motion.JumpGate)
                if (jump && !JUMP_GATE.allow(p.tickCount, mc.options.keyJump.isDown(), p.getAbilities().mayfly)) {
                    jump = false;
                }
                want.jump = jump;

                // ---- edge guard: whatever is steering (a plan, the final approach to the exact
                // point, the straight "wiggle free", path:false), the keys are played forward through
                // the player's own physics first (Motion) and toned down - no sprint, no jump, let go,
                // brake - until braking right after them still ends standing on something.
                if (p.onGround() || onLadder || p.isInWater()) {
                    supportY = p.getY();
                }
                Motion.Keys use = want;
                if ((edgeGuard || dry) && !p.isInWater() && !p.getAbilities().flying) {
                    Motion.Body me = body(p, supportY);
                    me.dry = dry;                   // dry: stepping into water is refused like a fall
                    Motion.Choice ch = Motion.choose(level, me, want);
                    use = ch.keys;
                    if (ch.level > 0) {
                        guardTicks++;
                        lastRisk = ch.risk;
                    }
                    boolean resting = Math.sqrt(v.x * v.x + v.z * v.z) < 0.03 && (p.onGround() || onLadder);
                    if (!ch.holding()) {
                        edgeHold = 0;
                    } else if (resting && ++edgeHold >= 10) {
                        // half a second standing still because every way on would fall: an edge
                        edgeHold = 0;
                        edgeStops++;
                        applyKeys(mc, p, Motion.brake(level, me, want.yaw));
                        if (straight && usePath && path != null && t < straightUntil) {
                            straightUntil = -1;     // the wiggle was heading off the edge: back to the plan
                            return null;
                        }
                        if ((straight || wp == null) && "water".equals(lastRisk)) {
                            // dry walk (kill_nearest): the exact point is in the water
                            return finish(p, mc, false, dist, t, "blocked: water - the next step toward the target"
                                    + " is into water and this walk stays dry; stopped on the shore or the dock,"
                                    + " this is as close as dry ground goes");
                        }
                        if (straight || wp == null) {
                            // the final approach (the exact point is past the edge) or path:false
                            return finish(p, mc, false, dist, t, "blocked: edge - the next step toward the target"
                                    + " would fall more than " + Nav.SAFE_DROP + " blocks (" + lastRisk + "); stopped at the"
                                    + " edge, this is as close as the ground goes");
                        }
                        // a planned step the body cannot take without falling (a step down onto a
                        // ledge too short for the momentum, a rim that has crumbled since the plan):
                        // never plan through that column again, and plan from here
                        noGo.add(Nav.column(wp.bx, wp.bz));
                        if (++plannedEdgeStops >= 4) {
                            return finish(p, mc, false, dist, t, "blocked: edge - every way on toward the target needs"
                                    + " a step that would fall more than " + Nav.SAFE_DROP + " blocks; stopped at the edge");
                        }
                        replans++;
                        if (!replan(p, level)) {
                            noRoute(t);
                        }
                        return null;
                    }
                }
                applyKeys(mc, p, use);
                return null;
            }

            void applyKeys(Minecraft mc, LocalPlayer p, Motion.Keys k) {
                var o = mc.options;
                if (k.stopSprint && p.isSprinting()) {
                    p.setSprinting(false);
                }
                if (Math.abs(Mth.wrapDegrees(k.yaw - p.getYRot())) > 1e-3) {
                    setRotation(p, Mth.wrapDegrees(k.yaw), p.getXRot());
                }
                Jobs.hold(o.keyUp, k.forward);
                Jobs.hold(o.keyDown, k.back);
                Jobs.hold(o.keyLeft, k.left);
                Jobs.hold(o.keyRight, k.right);
                JUMP_GATE.applied(p.tickCount, o.keyJump.isDown(), k.jump);
                Jobs.hold(o.keyJump, k.jump);
                Jobs.hold(o.keyShift, k.sneak);
                Jobs.hold(o.keySprint, k.sprint);
            }

            /** A closed door (or its double-door partner) on the next cells and within reach. */
            boolean nextDoor(LocalPlayer p, Level level) {
                if (path == null || !usePath) {
                    return false;
                }
                Vec3 eye = p.getEyePosition();
                for (int i = idx; i < Math.min(path.size(), idx + 3); i++) {
                    for (BlockPos d : path.get(i).doors) {
                        for (BlockPos c : new BlockPos[] {d, Nav.partnerLeaf(level, d)}) {
                            if (c == null || avoid.contains(c.asLong())) {
                                continue;
                            }
                            if (doorTries.getOrDefault(c.asLong(), 0) >= 3) {
                                // opened three times and shut again (the server refused it): a wall
                                avoid.add(c.asLong());
                                replans++;
                                if (!replan(p, level)) {
                                    noRoute(now);
                                }
                                return false;
                            }
                            BlockState s = level.getBlockState(c);
                            if (Nav.openable(s) && !Nav.isOpen(s) && eye.distanceTo(Vec3.atCenterOf(c)) <= 3.5) {
                                door = c;
                                doorPhase = 1;
                                return true;
                            }
                        }
                    }
                }
                return false;
            }

            /**
             * Stand still, free the main hand, look at the door, right-click it, wait for it to
             * swing open. Phases: 1 free the hand + aim, 4 wait for a parking swap to settle, 2 click,
             * 3 wait for the door. The click is made BARE-HANDED (see {@link DoorHand}): a SkyBlock
             * item's ability fires on any right click, a door included (wave-4: Wither Impact).
             */
            void doorStep(Minecraft mc, LocalPlayer p, Level level) {
                Jobs.hold(mc.options.keyUp, false);
                Jobs.hold(mc.options.keyDown, false);
                Jobs.hold(mc.options.keySprint, false);
                Jobs.hold(mc.options.keyLeft, false);
                Jobs.hold(mc.options.keyRight, false);
                Jobs.hold(mc.options.keyJump, false);
                Vec3 c = Vec3.atCenterOf(door);
                float[] aim = aimAt(p, c);
                if (doorPhase == 1) {
                    pitchBeforeDoor = p.getXRot();
                    setRotation(p, aim[0], aim[1]);
                    DoorHand.Choice ch = DoorHand.choose(handKinds(p), p.getInventory().getSelectedSlot());
                    if (ch.refused()) {
                        skipDoor(p, level);             // every slot holds an ability item: route round
                        return;
                    }
                    if (handBefore < 0) {
                        handBefore = p.getInventory().getSelectedSlot();
                    }
                    p.getInventory().setSelectedSlot(ch.select());
                    if (ch.parkIn() >= 0 && p.containerMenu == p.inventoryMenu) {
                        // hotbar full: park the held item in an empty inventory slot for the click
                        // (a SWAP click on the player's own inventory menu, no screen needed)
                        mc.gameMode.handleContainerInput(p.inventoryMenu.containerId, ch.parkIn(), ch.select(),
                                ContainerInput.SWAP, p);
                        parkedIn = ch.parkIn();
                        parkedHotbar = ch.select();
                        doorPhase = 4;
                        doorWait = 4;                   // a refused swap comes back from the server first
                        return;
                    }
                    doorPhase = 2;                      // the rotation reaches the server before the click
                    return;
                }
                if (doorPhase == 4) {
                    setRotation(p, aim[0], aim[1]);
                    if (--doorWait > 0) {
                        return;
                    }
                    if (!p.getInventory().getItem(parkedHotbar).isEmpty()) {
                        parkedIn = -1;                  // the server refused the swap: nothing to put back
                        parkedHotbar = -1;
                    }
                    doorPhase = 2;
                }
                if (doorPhase == 2) {
                    setRotation(p, aim[0], aim[1]);
                    ItemStack held = p.getMainHandItem();
                    if (p.getInventory().getSelectedSlot() > DoorHand.LAST_HOTBAR
                            || (!held.isEmpty() && kindOf(held) == DoorHand.Kind.RISKY)) {
                        skipDoor(p, level);             // never click a door with an ability item
                        return;
                    }
                    Direction face = Direction.getApproximateNearest(p.getX() - c.x, 0, p.getZ() - c.z);
                    Vec3 hit = c.add(face.getStepX() * 0.4, 0, face.getStepZ() * 0.4);
                    InteractionResult res = mc.gameMode.useItemOn(p, InteractionHand.MAIN_HAND,
                            new BlockHitResult(hit, face, door, false));
                    if (res instanceof InteractionResult.Success s && s.swingSource() == InteractionResult.SwingSource.CLIENT) {
                        p.swing(InteractionHand.MAIN_HAND);
                    }
                    doorTries.merge(door.asLong(), 1, Integer::sum);
                    doorPhase = 3;
                    doorWait = 8;
                    return;
                }
                BlockState s = level.getBlockState(door);
                if (Nav.openable(s) && Nav.isOpen(s)) {
                    doorsOpened++;
                    endDoor(p);
                } else if (--doorWait <= 0) {
                    if (doorTries.getOrDefault(door.asLong(), 0) >= 3) {
                        avoid.add(door.asLong());          // locked / protected: route round it
                        replans++;
                        if (!replan(p, level)) {
                            noRoute(now);
                        }
                    }
                    endDoor(p);
                }
            }

            /** A door that cannot be clicked bare-handed: treat it like a locked one and route round. */
            void skipDoor(LocalPlayer p, Level level) {
                doorsSkipped++;
                avoid.add(door.asLong());
                BlockPos partner = Nav.partnerLeaf(level, door);
                if (partner != null) {
                    avoid.add(partner.asLong());
                }
                endDoor(p);
                replans++;
                if (!replan(p, level)) {
                    noRoute(now);
                }
            }

            void endDoor(LocalPlayer p) {
                restoreHand(Minecraft.getInstance(), p);
                setRotation(p, p.getYRot(), pitchBeforeDoor);
                door = null;
                doorPhase = 0;
                lastProgress = p.position();
                stuckChecks = 0;
            }

            /** Put a parked item back in its hotbar slot and re-select the slot the walk began with. */
            void restoreHand(Minecraft mc, LocalPlayer p) {
                if (p == null) {
                    return;
                }
                if (parkedIn >= 0) {
                    if (p.containerMenu == p.inventoryMenu && mc.gameMode != null) {
                        if (!p.getInventory().getItem(parkedIn).isEmpty()) {
                            mc.gameMode.handleContainerInput(p.inventoryMenu.containerId, parkedIn, parkedHotbar,
                                    ContainerInput.SWAP, p);
                        }
                    } else {
                        parkedLeftIn = parkedIn;        // a screen is open: say where the item is
                    }
                    parkedIn = -1;
                    parkedHotbar = -1;
                }
                if (handBefore >= 0) {
                    p.getInventory().setSelectedSlot(handBefore);
                    handBefore = -1;
                }
            }

            @Override
            public void cleanup(Minecraft mc) {
                Jobs.releaseKeys();
                restoreHand(mc, mc.player);
            }

            @Override
            public boolean exclusive() {
                return true;
            }
        };
        return await(Jobs.submit(job), timeout + 10);
    }

    /** What a right click with this stack risks: nothing (empty), little (no ability text), an ability. */
    static DoorHand.Kind kindOf(ItemStack s) {
        if (s.isEmpty()) {
            return DoorHand.Kind.EMPTY;
        }
        List<String> lines = new ArrayList<>();
        ItemLore lore = s.get(DataComponents.LORE);
        if (lore != null) {
            for (Component c : lore.lines()) {
                lines.add(c.getString());
            }
        }
        return DoorHand.risky(lines) ? DoorHand.Kind.RISKY : DoorHand.Kind.SAFE;
    }

    /** {@link #kindOf} for inventory indices 0-35 (0-8 hotbar, 9-35 main). */
    static DoorHand.Kind[] handKinds(LocalPlayer p) {
        DoorHand.Kind[] k = new DoorHand.Kind[36];
        for (int i = 0; i < 36; i++) {
            k[i] = kindOf(p.getInventory().getItem(i));
        }
        return k;
    }

    /**
     * How far one can walk without jumping from where the player stands, in {@code directions}
     * evenly spaced yaws (0 = south, 90 = west, Minecraft's own convention), up to {@code max}.
     * For pointing a ridden pet, a sprint or a camera at open ground.
     */
    private static JsonObject clearance(JsonObject b) {
        LocalPlayer p = player();
        Level level = mc().level;
        int n = Mth.clamp((int) dbl(b, "directions", 16), 4, 72);
        double max = Mth.clamp(dbl(b, "max", 16), 1, 48);
        com.google.gson.JsonArray dirs = new com.google.gson.JsonArray();
        double bestYaw = 0, best = -1;
        for (int i = 0; i < n; i++) {
            double yaw = i * 360.0 / n;
            double rad = Math.toRadians(yaw);
            double clear = Nav.clearance(level, p.position(), -Math.sin(rad), Math.cos(rad), max, p.maxUpStep());
            JsonObject d = new JsonObject();
            d.addProperty("yaw", Mth.wrapDegrees(yaw));
            d.addProperty("clear", clear);
            dirs.add(d);
            if (clear > best) {
                best = clear;
                bestYaw = Mth.wrapDegrees(yaw);
            }
        }
        JsonObject o = ok();
        o.add("directions", dirs);
        o.addProperty("bestYaw", bestYaw);
        o.addProperty("bestClear", best);
        return o;
    }

    /**
     * Which way to sidestep to get past an obstacle: +1 = right (D), -1 = left (A), 0 = unknown.
     * Probes columns 0.5..1.5 blocks to each side, one block ahead, for feet+head passable space.
     */
    private static int openSide(Minecraft mc, LocalPlayer p, double nx, double nz) {
        double rx = -nz, rz = nx;   // right-hand vector of the facing direction (x east, z south)
        for (double off : new double[]{0.5, 1.0, 1.5}) {
            boolean right = passable(mc, p, nx * 0.9 + rx * off, nz * 0.9 + rz * off);
            boolean left = passable(mc, p, nx * 0.9 - rx * off, nz * 0.9 - rz * off);
            if (right && !left) return 1;
            if (left && !right) return -1;
            if (left && right) return 1;
        }
        return 0;
    }

    /** Feet and head blocks at an offset from the player are both free to walk through. */
    private static boolean passable(Minecraft mc, LocalPlayer p, double ox, double oz) {
        BlockPos feet = BlockPos.containing(p.position().add(ox, 0.1, oz));
        var level = mc.level;
        return level.getBlockState(feet).getCollisionShape(level, feet).isEmpty()
                && level.getBlockState(feet.above()).getCollisionShape(level, feet.above()).isEmpty();
    }

    private static JsonObject waitTicks(JsonObject b) {
        int ticks = Math.max(0, (int) dbl(b, "ticks", 20));
        Jobs.Job job = new Jobs.Job() {
            @Override
            public JsonObject tick(Minecraft mc, int t) {
                if (t + 1 >= ticks) {
                    JsonObject o = ok();
                    o.addProperty("ticks", ticks);
                    return o;
                }
                return null;
            }

            @Override
            public void cleanup(Minecraft mc) {
            }
        };
        return await(Jobs.submit(job), ticks / 20.0 + 10);
    }

    // ------------------------------------------------------------------ screenshot

    /**
     * Reads the main render target (the frame the game renders, GUI included)
     * back from the GPU and writes it as PNG. Never touches the desktop, so it
     * works while unfocused, covered, or on another monitor.
     */
    private static JsonObject screenshot(JsonObject b) {
        Minecraft mc = mc();
        String requested = str(b, "path", null);
        Path path = requested != null ? Path.of(requested).toAbsolutePath()
                : new File(mc.gameDirectory, "screenshots/testpilot-" + System.currentTimeMillis() + ".png").toPath();
        CompletableFuture<JsonObject> f = new CompletableFuture<>();
        mc.execute(() -> {
            try {
                Files.createDirectories(path.getParent());
                Screenshot.takeScreenshot(mc.getMainRenderTarget(), (NativeImage img) -> {
                    try (img) {
                        img.writeToFile(path);
                        JsonObject o = ok();
                        o.addProperty("path", path.toString());
                        o.addProperty("width", img.getWidth());
                        o.addProperty("height", img.getHeight());
                        f.complete(o);
                    } catch (Throwable t) {
                        f.completeExceptionally(t);
                    }
                });
            } catch (Throwable t) {
                f.completeExceptionally(t);
            }
        });
        return await(f, 30);
    }
}
