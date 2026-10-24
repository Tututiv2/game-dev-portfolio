package com.secondwatch.content;

import com.secondwatch.game.RunState;
import com.secondwatch.game.Runs;
import com.secondwatch.game.Voice;
import net.minecraft.network.chat.Component;
import net.minecraft.sounds.SoundEvents;
import net.minecraft.sounds.SoundSource;
import net.minecraft.world.InteractionHand;
import net.minecraft.world.InteractionResult;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.level.Level;

/**
 * Strike it and it tells you how much of you the thing has.
 *
 * <p>This is the only instrument in the game that answers a question the players
 * genuinely want answered, and the answer is always the wrong kind of
 * reassuring: it reports the size of the vocabulary the entity holds in YOUR
 * voice. The number never goes down. It goes up every time you speak, which is
 * every time you keep track of your friend.
 *
 * <p>It deliberately does not tell you anything about the run's rules. It is a
 * mirror, not a detector - a device that revealed the tell would delete the
 * game.
 */
public class TuningForkItem extends Item {

    public TuningForkItem(Properties properties) {
        super(properties);
    }

    @Override
    public InteractionResult use(Level level, Player player, InteractionHand hand) {
        ItemStack held = player.getItemInHand(hand);

        if (!level.isClientSide()) {
            RunState run = Runs.get();

            if (run == null || !run.started()) {
                player.sendOverlayMessage(
                        Component.literal("§7It rings flat. Nothing is listening yet."));
            } else if (!Voice.present()) {
                player.sendOverlayMessage(
                        Component.literal("§7It rings flat. No voice chat is installed."));
            } else {
                String name = player.getName().getString();
                int mine = run.corpus().all(name).size();
                int total = run.corpus().size();

                if (mine == 0) {
                    player.sendOverlayMessage(
                            Component.literal("§fIt rings clean. It has nothing of you yet."));
                } else {
                    // Phrased as possession rather than as a statistic. "17 lines
                    // recorded" is a debug readout; "it has 17 of you" is the game.
                    player.sendOverlayMessage(Component.literal(
                            "§fIt has §c" + mine + "§f of you." + (total > mine
                                    ? " §7(" + (total - mine) + " of somebody else)" : "")));
                }
            }
        }

        level.playSound(null, player.getX(), player.getY(), player.getZ(),
                SoundEvents.AMETHYST_BLOCK_CHIME, SoundSource.PLAYERS, 0.7f, 1.4f);

        // It is loud, and loud is how everything here finds you.
        player.getCooldowns().addCooldown(held, 40);
        return InteractionResult.SUCCESS;
    }
}
