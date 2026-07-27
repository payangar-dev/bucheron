package com.payangar.bucheron.tree;

import net.minecraft.core.BlockPos;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.level.BlockGetter;
import net.minecraft.world.level.block.state.BlockState;

import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Makes felling a tree cost what felling it block by block would have cost.
 *
 * <p>The tree comes down in one swing, so the swing has to be as long as the whole job. The factor
 * is the log count, which means the total time is unchanged compared to vanilla: what disappears is
 * the chore, not the effort.
 *
 * <p>The result must be identical on both sides. {@code getDestroyProgress} drives the cracking
 * animation on the client and the validation on the server, and if they disagree the block breaks
 * for one and not the other. Hence a shared cache keyed by player and target block: the same
 * answer is computed once and reused by both, and in single player they literally share it.
 */
public final class TreeMiningSpeed {

    /**
     * Bare hands, or anything that is not an effective tool here, on top of the size factor.
     *
     * <p>Kept moderate on purpose. It multiplies a factor that is already the log count, so it
     * compounds fast: a stiffer penalty made a plain oak unbearable by hand (playtested 2026-07-27).
     */
    private static final float NO_TOOL_PENALTY = 1.5F;

    private record Cached(BlockPos pos, int logs) {
    }

    /** One tiny entry per player, overwritten at each new target. Never evicted; harmless at this size. */
    private static final Map<UUID, Cached> CACHE = new ConcurrentHashMap<>();

    private TreeMiningSpeed() {
    }

    /**
     * @param progress the vanilla per-tick progress
     * @return the slowed progress, or the original value when this is not a fellable tree
     */
    public static float adjust(BlockState state, Player player, BlockGetter level, BlockPos pos, float progress) {
        if (player.isCreative() || progress <= 0.0F) {
            return progress;
        }

        int logs = logCount(player, state, level, pos);
        if (logs <= 0) {
            return progress;
        }

        float factor = logs;
        if (!isEffectiveTool(player.getMainHandItem(), state)) {
            factor *= NO_TOOL_PENALTY;
        }
        return progress / factor;
    }

    /** @return the number of logs in the tree at this position, or 0 when it is not a tree */
    private static int logCount(Player player, BlockState state, BlockGetter level, BlockPos pos) {
        Cached cached = CACHE.get(player.getUUID());
        if (cached != null && cached.pos().equals(pos)) {
            return cached.logs();
        }

        TreeShape shape = TreeScanner.scan(level, pos);
        int logs = shape == null ? 0 : shape.logs().size();

        CACHE.put(player.getUUID(), new Cached(pos.immutable(), logs));
        return logs;
    }

    /**
     * Vanilla gives a speed of 1.0 for bare hands and for anything unsuited, and more for a tool
     * that bites. Going through the speed rather than a tag means any modded axe counts, as long as
     * it is actually good at this.
     */
    private static boolean isEffectiveTool(ItemStack tool, BlockState state) {
        return tool.getDestroySpeed(state) > 1.0F;
    }
}
