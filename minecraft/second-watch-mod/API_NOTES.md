# 26.2 API notes

Written because Minecraft 26.2 is newer than the assistant's training data, so
nothing here is remembered — every line was checked against the shipped jar or
against Fabric's own metadata on 2026-08-17. Re-verify before trusting it.

## The mapping situation (the big one)

**Minecraft 26.x ships deobfuscated.** The client jar contains real names
(`net/minecraft/client/Camera.class`, `net/minecraft/world/level/Level.class`).

Evidence chain:

| Version | `downloads` keys | Java |
|---|---|---|
| 1.21.11 | client, **client_mappings**, server, **server_mappings** | 21 |
| 26.1 | client, server | 25 |
| 26.2 | client, server | 25 |

So Mojang stopped publishing obfuscation maps at 26.1, yarn stops at 1.21.11
(`meta.fabricmc.net/v2/versions/yarn/26.2` → `[]`), and intermediary for 26.2 is
a `0.0.0` stub.

**Consequences:**

- `build.gradle` has **no `mappings` line**. That is correct, not an omission.
  `loom.officialMojangMappings()` fails with *"Failed to find official mojang
  mappings for 26.2"*.
- Names are Mojmap, which is what the Paper projects elsewhere in this portfolio
  already use — so knowledge transfers between them for once.
- Canonical reference: `github.com/FabricMC/fabric-example-mod`, **default branch
  `26.2`**. When something here goes stale, that repo is the ground truth.

## Verified toolchain

| Thing | Version | Source |
|---|---|---|
| Minecraft | 26.2 | Mojang manifest, `latest.release` |
| Java | **25** | manifest `javaVersion.majorVersion` |
| Fabric Loom | 1.17.19 | fabric maven (`1.17-SNAPSHOT` resolves here) |
| Gradle | 9.5.1 | Fabric's 26.2 announcement |
| Fabric Loader | 0.19.3 | meta.fabricmc.net |
| Fabric API | 0.157.0+26.2 | modrinth |
| voicechat-api | **2.6.20** | maven.maxhenkel.de |

Note the last one: the Simple Voice Chat **mod** is at `2.6.22+26.2`, but the
**API artifact** is versioned separately and tops out at `2.6.20`. Asking for
2.6.22 fails to resolve. This cost one build.

Also note `implementation`, not `modImplementation`, for loader and fabric-api —
that is what the 26.2 example mod uses.

## Classes confirmed present in the 26.2 jar

Checked by listing the jar, not by memory:

- `net.minecraft.world.level.Level`, `LevelAccessor`, `LevelReader`, `LevelHeightAccessor`
- `net.minecraft.server.level.ServerLevel`
- `net.minecraft.world.entity.EntityType`, `Mob`, `MobCategory`
- `net.minecraft.world.entity.EntityTypes` ← **new in 26.x**, separate from `EntityType`
- `net.minecraft.world.level.dimension.DimensionType`
- `net.minecraft.core.registries.Registries`
- `net.minecraft.world.item.crafting.Recipe`, `RecipeAccess`, `RecipeBookCategory`
- `net.minecraft.sounds.SoundEvent`, `SoundEvents`
- `net.minecraft.network.protocol.game.ClientboundSetTimePacket`

That last one matters more than it looks: the endless night is sent **per
player** via `ClientboundSetTimePacket`, which means the sky can disagree between
the two clients without touching the server's real day counter. The whole Long
Night runs through one vanilla packet.

## 26.x API changes that cost a compile or a crash

Everything here was found by compiling or by running a real dedicated server, not from memory.

| Was | Is now in 26.2 |
|---|---|
| `ResourceLocation` | **`net.minecraft.resources.Identifier`** |
| `level.getDayTime()` / `setDayTime()` | a registry of `WorldClock`s driven by `server.clockManager()` |
| `source.hasPermission(2)` | `source.permissions().hasPermission(Permissions.COMMANDS_GAMEMASTER)` |
| `player.getGameProfile().getName()` | gone — use `player.getName().getString()` |
| `/time query daytime` | `/time query time` (there is also `/time of <clock> query`) |
| `ClientboundSetTimePacket(gameTime, dayTime, cycle)` | `(long gameTime, Map<Holder<WorldClock>, ClockNetworkState>)` |

### The clock system

`ServerClockManager` (from `server.clockManager()`) exposes `setPaused`, `setRate`, `setTotalTicks`,
`getTotalTicks`, `moveToTimeMarker` and `createFullSyncPacket()`. Clock keys are in `WorldClocks`
(`OVERWORLD`, `THE_END`); markers in `ClockTimeMarkers` (`DAY`, `NOON`, `NIGHT`, `MIDNIGHT`, …).

Get the holder with:

```java
server.registryAccess().lookupOrThrow(Registries.WORLD_CLOCK).getOrThrow(WorldClocks.OVERWORLD)
```

`ClockNetworkState` is `(long totalTicks, float partialTick, float rate)` and it travels in a
**clientbound** packet, so a per-player sky is possible if it is ever wanted.

### ⚠ Never `setRate(clock, 0)`

It is the obvious way to stop time and it silently breaks the world.

`ServerClockManager` is a `SavedData` and its rate codec rejects non-positive values. Nothing fails
at the call site. It fails much later, during `saveAllChunks`:

```
java.lang.IllegalStateException: Value must be positive: 0.0
    at SavedDataStorage.encodeUnchecked
    at MinecraftServer.saveAllChunks
```

So **the world stops being able to save**, the shutdown throws, and the watchdog then declares the
server crashed after hanging 60 seconds. On a recording night that reads as "Minecraft broke".

Use `setPaused(clock, true)`. It is the supported route — vanilla exposes it as `/time pause`.
Verified: paused alone holds the clock at 16000 across 12 s while game time advances normally, and
`save-all` returns "Saved the game".

## The phantom's body: `Mannequin`

26.2 ships `net.minecraft.world.entity.decoration.Mannequin extends Avatar`, an entity that renders
as a player and carries a `ResolvableProfile`. Give it the partner's profile
(`ResolvableProfile.createResolved(GameProfile)`) and it arrives wearing their skin and their name.

It is sent as **packets only** and never added to the world:

```java
Mannequin body = Mannequin.create(EntityTypes.MANNEQUIN, level);   // never added
viewer.connection.send(new ClientboundAddEntityPacket(body, 0, body.blockPosition()));
viewer.connection.send(new ClientboundSetEntityDataPacket(body.getId(),
        List.of(SynchedEntityData.DataValue.create(Mannequin.DATA_PROFILE, profile))));
```

Move with `ClientboundEntityPositionSyncPacket` + `ClientboundRotateHeadPacket`, remove with
`ClientboundRemoveEntitiesPacket`. Nothing on the server knows it exists, so it cannot collide, be
hit, or show up in `/kill @e` — and the other player cannot see it under any circumstances.

`Mannequin.DATA_PROFILE` is protected, hence the access widener.

### Access wideners use the `official` namespace

Because 26.x is deobfuscated, `named` is rejected:

```
Failed to process jar ... Expected official namespace for access widener entry, found: named
```

So the header is `accessWidener	v2	official`.

## Simple Voice Chat (2.6.20 API)

- register via `META-INF/services/de.maxhenkel.voicechat.api.VoicechatPlugin`
- `MicrophonePacketEvent` fires **once at the server and again per receiver** — take only the arrival
  where `getReceiverConnection() == null`, or every sentence is filed twice
- events arrive on the **voice thread**, not the server thread; guard shared state
- `OpusDecoder` is stateful — one per speaker, or real voices develop artefacts, which in this mod is
  the worst possible bug
- `AudioChannel.setFilter(Predicate<ServerPlayer>)` is what makes a *locational* channel audible to
  exactly one player, which is what the phantom needs
- 48 kHz mono 16-bit is ~96 KB/s, so stored speech must be capped or a long session ends in an OOM

Simple Voice Chat is `compileOnly` and optional. Every reference to it is funnelled through
`Voice.java` behind an `isModLoaded("voicechat")` check — otherwise the first code path that touches
the bridge throws `NoClassDefFoundError` mid-session rather than failing cleanly at startup.

## Content registration (26.x)

Ids must be supplied up front via `setId`, not inferred at registration:

```java
Identifier id = Identifier.fromNamespaceAndPath("secondwatch", "resonator");
Block block = new Block(properties.setId(ResourceKey.create(Registries.BLOCK, id)));
Registry.register(BuiltInRegistries.BLOCK, id, block);
BlockItem item = new BlockItem(block,
        new Item.Properties().useBlockDescriptionPrefix().setId(ResourceKey.create(Registries.ITEM, id)));
Registry.register(BuiltInRegistries.ITEM, id, item);
```

Creative tabs: `CreativeModeTab.builder(CreativeModeTab.Row.TOP, 0)` → register into
`BuiltInRegistries.CREATIVE_MODE_TAB`.

**Asset layout** — item models moved to their own definition files:

| Path | Purpose |
|---|---|
| `assets/<ns>/items/<name>.json` | `{"model": {"type": "minecraft:model", "model": "<ns>:block/<name>"}}` |
| `assets/<ns>/models/block/<name>.json` | the actual model (`parent: minecraft:block/cube_all`) |
| `assets/<ns>/blockstates/<name>.json` | `{"variants": {"": {"model": ...}}}` |
| `data/<ns>/recipe/<name>.json` | note: `recipe`, singular |

## Entities and mobs (26.x)

- `Zombie` moved to **`net.minecraft.world.entity.monster.zombie.Zombie`**
- `EntityType.Builder.of(factory, MobCategory).sized(w, h).build(ResourceKey<EntityType<?>>)`
- attributes via Fabric: `FabricDefaultAttributeRegistry.register(TYPE, Builder)`
- renderer via Fabric: `EntityRendererRegistry.register(TYPE, ZombieRenderer::new)` from
  `net.fabricmc.fabric.api.client.rendering.v1`
- **spawn eggs no longer take an entity type in the constructor.** `SpawnEggItem(Item.Properties)`
  only; the entity comes from a data component:
  `.component(DataComponents.ENTITY_DATA, TypedEntityData.of(TYPE, new CompoundTag()))`

Reusing a vanilla renderer is the cheap route: subclass a vanilla mob and register its renderer
against your type. Custom renderers now need a render-state class and a model layer, which is a
week of work for a silhouette nobody sees closely.

`Player.displayClientMessage(Component, boolean)` is **gone**. Use `sendOverlayMessage(Component)`
for the action bar, `sendSystemMessage(Component)` for chat.

## ⚠ 26.x rewrote the client GUI layer

This broke every custom-screen habit at once:

| Was | Is now |
|---|---|
| `new Screen(Component)` | `Screen(Minecraft, Font, Component)` — pass `Minecraft.getInstance()` and its `font` |
| `render(GuiGraphics, int, int, float)` | **gone** — drawing is a render-state pass, `extractRenderState(GuiGraphicsExtractor, …)` |
| `GuiGraphics` | no longer exists as a public draw target |
| `minecraft.setScreen(screen)` | `minecraft.setScreenAndShow(screen)` |
| `init(int, int)` | now **final** — override the no-arg `protected void init()` |

Widgets added with `addRenderableWidget` still draw themselves, so a screen that is only buttons needs
no draw code at all. That is the cheap way to build UI on 26.x, and it is what `TestPanelScreen` does.

**Fabric keybindings also moved:** `net.fabricmc.fabric.api.client.keybinding.v1.KeyBindingHelper`
→ `net.fabricmc.fabric.api.client.keymapping.v1.KeyMappingHelper.registerKeyMapping(...)`, and
`KeyMapping`'s category is now a `KeyMapping.Category` constant (`MISC`), not a string.

## Still to verify before writing against them

- entity renderer + model registration (client) — the API moved repeatedly across
  1.20→1.21 and has not been checked for 26.x
- custom dimension registration via datapack JSON vs code
- `splitEnvironmentSourceSets()` means client-only classes live in `src/client/java`
  and **must not** be referenced from `src/main/java`. This will be the most common
  compile error in this project.
- 26.2 has an **experimental Vulkan backend** alongside OpenGL. Any screen-space
  effect must be checked on both.
