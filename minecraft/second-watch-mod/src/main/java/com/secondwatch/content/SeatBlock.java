package com.secondwatch.content;

import com.secondwatch.core.TheSeat;
import com.secondwatch.game.RunState;
import com.secondwatch.game.Runs;
import net.minecraft.core.BlockPos;
import net.minecraft.network.chat.Component;
import net.minecraft.sounds.SoundEvents;
import net.minecraft.sounds.SoundSource;
import net.minecraft.world.InteractionResult;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.phys.BlockHitResult;

/**
 * The way out that the previous group built, and the last thing in the book.
 *
 * <p>It is a plain block you sit in by right-clicking, and everything about how
 * it behaves is designed to make the players talk to each other:
 *
 * <ul>
 *   <li>it does nothing at all before the final night, so finding it early only
 *       teaches them that it exists</li>
 *   <li>the departure takes twenty seconds, so nobody leaves on an impulse and
 *       whoever is staying has to stand there through all of it</li>
 *   <li>it fits one</li>
 * </ul>
 *
 * <p>The rules themselves live in {@link TheSeat}, which is pure and tested, so
 * that no special case in here can quietly soften them.
 */
public class SeatBlock extends Block {

    public SeatBlock(Properties properties) {
        super(properties);
    }

    @Override
    protected InteractionResult useWithoutItem(BlockState state, Level level, BlockPos pos,
                                               Player player, BlockHitResult hit) {
        if (level.isClientSide()) return InteractionResult.SUCCESS;

        RunState run = Runs.get();
        if (run == null || !run.started()) {
            player.sendOverlayMessage(Component.literal("§7It is cold and it does nothing."));
            return InteractionResult.CONSUME;
        }

        String name = player.getName().getString();
        TheSeat.Result result =
                run.seat().attempt(name, run.dread().tier(), System.currentTimeMillis());

        switch (result) {
            case NOT_READY -> player.sendOverlayMessage(Component.literal(
                    "§7It hums, and nothing happens. Not yet."));

            case IN_PROGRESS -> player.sendOverlayMessage(Component.literal(
                    "§7Somebody is already in it."));

            case SPENT -> player.sendOverlayMessage(Component.literal(
                    "§8It fits one. It fitted one."));

            case BEGUN -> {
                // Announced to everybody, because the point of the twenty
                // seconds is that the other player knows, and has to decide what
                // to do about it while it runs.
                level.getServer().getPlayerList().broadcastSystemMessage(
                        Component.literal("§f" + name + " is in the seat."), false);
                level.playSound(null, pos, SoundEvents.BEACON_ACTIVATE,
                        SoundSource.BLOCKS, 1.2f, 0.6f);
            }
        }
        return InteractionResult.SUCCESS;
    }
}
