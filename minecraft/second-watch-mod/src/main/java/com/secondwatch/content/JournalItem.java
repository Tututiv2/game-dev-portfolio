package com.secondwatch.content;

import com.secondwatch.core.Journal;
import com.secondwatch.game.JournalBook;
import com.secondwatch.game.RunState;
import com.secondwatch.game.Runs;
import net.minecraft.network.chat.Component;
import net.minecraft.server.MinecraftServer;
import net.minecraft.world.InteractionHand;
import net.minecraft.world.InteractionResult;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.level.Level;

import java.util.List;

/**
 * A bundle of somebody else's notes. Right-click and it becomes the book.
 *
 * <p>This exists so the journal can be PLACED - in a chest in a ruined base, in
 * a loot table, in the hands of whatever is left of the person who wrote it -
 * rather than only conjured by a command. The book it produces is a plain
 * vanilla written book on purpose: what makes it frightening is the words, and
 * an object that looks like every other book in Minecraft is doing the horror a
 * favour.
 *
 * <p>It resolves on use rather than on placement because the prescient entry has
 * to name somebody who is actually here, and the world has no idea who is
 * playing until they open it.
 */
public class JournalItem extends Item {

    public JournalItem(Properties properties) {
        super(properties);
    }

    @Override
    public InteractionResult use(Level level, Player player, InteractionHand hand) {
        if (level.isClientSide()) return InteractionResult.SUCCESS;

        RunState run = Runs.get();
        MinecraftServer server = level.getServer();
        if (run == null || server == null) {
            player.sendOverlayMessage(
                    Component.literal("§7The pages are blank."));
            return InteractionResult.CONSUME;
        }

        List<String> names = RunState.namesOf(server);
        if (names.isEmpty()) names = List.of(player.getName().getString());

        Journal journal = run.journalFor(names);
        ItemStack book = JournalBook.create(journal, run.group().diarist().name());

        ItemStack held = player.getItemInHand(hand);
        if (!player.hasInfiniteMaterials()) {
            held.shrink(1);
        }
        if (!player.addItem(book)) {
            player.drop(book, false);
        }

        player.sendOverlayMessage(
                Component.literal("§7Somebody kept this."));
        return InteractionResult.SUCCESS;
    }
}
