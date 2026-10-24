package com.secondwatch.content;

import com.secondwatch.SecondWatch;
import net.fabricmc.fabric.api.object.builder.v1.entity.FabricDefaultAttributeRegistry;
import net.minecraft.core.Registry;
import net.minecraft.core.component.DataComponents;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.core.registries.Registries;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.network.chat.Component;
import net.minecraft.resources.Identifier;
import net.minecraft.resources.ResourceKey;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.EntityType;
import net.minecraft.world.entity.MobCategory;
import net.minecraft.world.item.BlockItem;
import net.minecraft.world.item.CreativeModeTab;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.SpawnEggItem;
import net.minecraft.world.item.component.TypedEntityData;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.SoundType;
import net.minecraft.world.level.block.state.BlockBehaviour;
import net.minecraft.world.level.material.MapColor;

/**
 * Everything the mod adds to the game as a real object or a real creature.
 *
 * <p>The bestiary is built around two currencies rather than around monsters:
 * <b>light</b> and <b>sound</b>. Every entry either responds to one of them or
 * deliberately ignores both, and none of them are variations on "hits you
 * harder".
 *
 * <table border="1">
 *   <caption>the roster</caption>
 *   <tr><th></th><th>does</th><th>rule</th></tr>
 *   <tr><td>Follower</td><td>nothing</td><td>stops at 20 blocks, never closer</td></tr>
 *   <tr><td>Watcher</td><td>nothing</td><td>moves only while unobserved</td></tr>
 *   <tr><td>Mimic</td><td>speaks</td><td>says things you actually said</td></tr>
 *   <tr><td>Crawler</td><td>kills</td><td>frozen solid by light</td></tr>
 *   <tr><td>Tall One</td><td>kills</td><td>walks around light rather than into it</td></tr>
 *   <tr><td>Swarm</td><td>nothing</td><td>follows light, so it points at your base</td></tr>
 * </table>
 *
 * <p>Only two of the six can hurt you, and that ratio is deliberate. A world
 * where everything is lethal is a combat game; a world where nothing is stops
 * being tense within an hour. Two things with teeth is enough to make the other
 * four frightening by association.
 *
 * <p>All six borrow vanilla renderers. At forty blocks in the dark a silhouette
 * is the entire effect, so bespoke models would cost weeks and read worse - what
 * is custom here is behaviour, which is the only thing the players will
 * remember.
 */
public final class SecondWatchContent {

    // ------------------------------------------------------------- blocks

    public static final Block RESONATOR = registerBlock("resonator",
            BlockBehaviour.Properties.of()
                    .mapColor(MapColor.METAL)
                    .strength(3.5f)
                    .requiresCorrectToolForDrops()
                    .sound(SoundType.METAL)
                    .lightLevel(state -> 7));

    /**
     * The way out. Deliberately expensive: the players should have to decide to
     * build it, not stumble into it while tidying a chest.
     */
    public static final Block THE_SEAT = registerSeat("the_seat",
            BlockBehaviour.Properties.of()
                    .mapColor(MapColor.COLOR_LIGHT_GRAY)
                    .strength(5.0f)
                    .requiresCorrectToolForDrops()
                    .sound(SoundType.METAL)
                    .lightLevel(state -> 10));

    // ------------------------------------------------------------ entities

    public static final EntityType<Follower> FOLLOWER = registerEntity("follower",
            EntityType.Builder.of(Follower::new, MobCategory.MONSTER)
                    .sized(0.6f, 1.95f)
                    // Wider than vanilla: being visible from far away, and
                    // staying visible, is the entire point of these.
                    .clientTrackingRange(12));

    public static final EntityType<Watcher> WATCHER = registerEntity("watcher",
            EntityType.Builder.of(Watcher::new, MobCategory.MONSTER)
                    .sized(0.6f, 1.95f)
                    .clientTrackingRange(12));

    public static final EntityType<Mimic> MIMIC = registerEntity("mimic",
            EntityType.Builder.of(Mimic::new, MobCategory.MONSTER)
                    .sized(0.6f, 1.95f)
                    .clientTrackingRange(10));

    public static final EntityType<Crawler> CRAWLER = registerEntity("crawler",
            EntityType.Builder.of(Crawler::new, MobCategory.MONSTER)
                    .sized(1.4f, 0.9f)
                    .clientTrackingRange(8));

    public static final EntityType<TallOne> TALL_ONE = registerEntity("tall_one",
            EntityType.Builder.of(TallOne::new, MobCategory.MONSTER)
                    .sized(0.6f, 2.9f)
                    .clientTrackingRange(16));

    public static final EntityType<Swarm> SWARM = registerEntity("swarm",
            EntityType.Builder.of(Swarm::new, MobCategory.MONSTER)
                    .sized(0.4f, 0.3f)
                    .clientTrackingRange(8));

    // -------------------------------------------------------------- items

    public static final Item TUNING_FORK = registerItem("tuning_fork",
            new TuningForkItem(props("tuning_fork").stacksTo(1)));

    public static final Item JOURNAL = registerItem("journal",
            new JournalItem(props("journal").stacksTo(1)));

    public static final Item FOLLOWER_SPAWN_EGG = spawnEgg("follower_spawn_egg", FOLLOWER);
    public static final Item WATCHER_SPAWN_EGG = spawnEgg("watcher_spawn_egg", WATCHER);
    public static final Item MIMIC_SPAWN_EGG = spawnEgg("mimic_spawn_egg", MIMIC);
    public static final Item CRAWLER_SPAWN_EGG = spawnEgg("crawler_spawn_egg", CRAWLER);
    public static final Item TALL_ONE_SPAWN_EGG = spawnEgg("tall_one_spawn_egg", TALL_ONE);
    public static final Item SWARM_SPAWN_EGG = spawnEgg("swarm_spawn_egg", SWARM);

    // ---------------------------------------------------------------- tab

    public static final CreativeModeTab TAB = Registry.register(
            BuiltInRegistries.CREATIVE_MODE_TAB,
            id("all"),
            CreativeModeTab.builder(CreativeModeTab.Row.TOP, 0)
                    .title(Component.translatable("itemGroup.secondwatch.all"))
                    .icon(() -> new ItemStack(TUNING_FORK))
                    .displayItems((params, output) -> {
                        output.accept(JOURNAL);
                        output.accept(TUNING_FORK);
                        output.accept(RESONATOR);
                        output.accept(THE_SEAT);
                        // Ordered harmless-to-lethal, so the tab itself reads as
                        // the escalation the nights are going to walk through.
                        output.accept(FOLLOWER_SPAWN_EGG);
                        output.accept(WATCHER_SPAWN_EGG);
                        output.accept(MIMIC_SPAWN_EGG);
                        output.accept(SWARM_SPAWN_EGG);
                        output.accept(CRAWLER_SPAWN_EGG);
                        output.accept(TALL_ONE_SPAWN_EGG);
                    })
                    .build());

    private SecondWatchContent() {
    }

    public static Identifier id(String path) {
        return Identifier.fromNamespaceAndPath(SecondWatch.MOD_ID, path);
    }

    private static Item.Properties props(String name) {
        return new Item.Properties().setId(ResourceKey.create(Registries.ITEM, id(name)));
    }

    private static Item registerItem(String name, Item item) {
        return Registry.register(BuiltInRegistries.ITEM, id(name), item);
    }

    /**
     * 26.x spawn eggs take no entity type in the constructor - the entity comes
     * from a data component instead.
     */
    private static Item spawnEgg(String name, EntityType<?> type) {
        return registerItem(name, new SpawnEggItem(props(name)
                .component(DataComponents.ENTITY_DATA, TypedEntityData.of(type, new CompoundTag()))));
    }

    private static Block registerSeat(String name, BlockBehaviour.Properties properties) {
        Identifier identifier = id(name);
        Block block = new SeatBlock(properties.setId(ResourceKey.create(Registries.BLOCK, identifier)));
        Registry.register(BuiltInRegistries.BLOCK, identifier, block);
        Registry.register(BuiltInRegistries.ITEM, identifier,
                new BlockItem(block, props(name).useBlockDescriptionPrefix()));
        return block;
    }

    private static Block registerBlock(String name, BlockBehaviour.Properties properties) {
        Identifier identifier = id(name);
        Block block = new Block(properties.setId(ResourceKey.create(Registries.BLOCK, identifier)));
        Registry.register(BuiltInRegistries.BLOCK, identifier, block);
        Registry.register(BuiltInRegistries.ITEM, identifier,
                new BlockItem(block, props(name).useBlockDescriptionPrefix()));
        return block;
    }

    private static <T extends Entity> EntityType<T> registerEntity(
            String name, EntityType.Builder<T> builder) {
        Identifier identifier = id(name);
        return Registry.register(BuiltInRegistries.ENTITY_TYPE, identifier,
                builder.build(ResourceKey.create(Registries.ENTITY_TYPE, identifier)));
    }

    /** Touching this class is what loads it. Called from the mod entrypoint. */
    public static void register() {
        FabricDefaultAttributeRegistry.register(FOLLOWER, Follower.createFollowerAttributes());
        FabricDefaultAttributeRegistry.register(WATCHER, Watcher.createWatcherAttributes());
        FabricDefaultAttributeRegistry.register(MIMIC, Mimic.createMimicAttributes());
        FabricDefaultAttributeRegistry.register(CRAWLER, Crawler.createCrawlerAttributes());
        FabricDefaultAttributeRegistry.register(TALL_ONE, TallOne.createTallAttributes());
        FabricDefaultAttributeRegistry.register(SWARM, Swarm.createSwarmAttributes());
        SecondWatch.LOG.info("registered 6 entities, 3 items, 1 block and the creative tab");
    }
}
