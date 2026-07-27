package com.payangar.bucheron.tree;

import com.payangar.bucheron.entity.BucheronEntities;
import com.payangar.bucheron.entity.FallingTreeEntity;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.entity.EquipmentSlot;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.Blocks;

import java.util.ArrayList;
import java.util.List;

/** Turns a broken log into a falling tree. */
public final class TreeFelling {

    /**
     * Removing the tree must not ripple through the world: neighbour shape updates would decay
     * surrounding leaves and knock off attached blocks before the tree has even started to fall.
     */
    private static final int REMOVE_FLAGS =
        Block.UPDATE_CLIENTS | Block.UPDATE_KNOWN_SHAPE | Block.UPDATE_SUPPRESS_DROPS;

    /** Vanilla charges this much for breaking a single block. */
    private static final float EXHAUSTION_PER_LOG = 0.005F;

    /**
     * Share of the logs that actually yields wood. Felling a whole tree in one swing must not be
     * more profitable than mining it block by block, otherwise the mod is a straight upgrade rather
     * than a change of pace. The tree still falls whole, the wood simply does not all survive.
     */
    private static final float LOG_YIELD = 0.5F;

    /** Durability cost per log. Above one, so a whole tree bites into the tool.  */
    private static final int DURABILITY_PER_LOG = 2;

    private TreeFelling() {
    }

    /** @return true when the tree was felled, meaning vanilla block breaking must be skipped */
    public static boolean tryFell(ServerLevel level, BlockPos pos, ServerPlayer player) {
        TreeShape shape = TreeScanner.scan(level, pos);
        if (shape == null) {
            return false;
        }

        BlockPos origin = shape.origin();
        List<BlockPos> blocks = shape.allBlocks();
        ItemStack tool = player.getMainHandItem();

        // Every block falls, whatever it ends up dropping: the tree has to look intact on its way
        // down. Only the yield is reduced.
        List<FallingTreeEntity.Piece> pieces = new ArrayList<>(blocks.size());
        for (BlockPos block : blocks) {
            pieces.add(new FallingTreeEntity.Piece(block.subtract(origin), level.getBlockState(block)));
        }

        // Drops are read before removal, while the blocks still exist.
        List<BlockPos> logs = shape.logs();
        int harvested = Math.max(1, Math.round(logs.size() * LOG_YIELD));

        List<ItemStack> drops = new ArrayList<>();
        for (int i = 0; i < harvested; i++) {
            drops.addAll(dropsOf(level, logs.get(i), player, tool));
        }
        for (BlockPos leaf : shape.leaves()) {
            drops.addAll(dropsOf(level, leaf, player, tool));
        }

        // Taking the whole tree in one swing costs what taking it block by block would have cost.
        // Applied after the drops are resolved: a tool that breaks here must not empty the stack
        // the loot tables were just read with.
        int logCount = logs.size();
        if (tool.isDamageableItem()) {
            tool.hurtAndBreak(logCount * DURABILITY_PER_LOG, player, EquipmentSlot.MAINHAND);
        }
        player.causeFoodExhaustion(EXHAUSTION_PER_LOG * logCount);

        for (BlockPos block : blocks) {
            level.setBlock(block, Blocks.AIR.defaultBlockState(), REMOVE_FLAGS);
        }

        FallingTreeEntity tree = new FallingTreeEntity(BucheronEntities.FALLING_TREE, level);
        tree.setPos(origin.getX() + 0.5, origin.getY(), origin.getZ() + 0.5);
        tree.setShape(fallDirectionAwayFrom(origin, player), pieces, drops);
        level.addFreshEntity(tree);
        return true;
    }

    private static List<ItemStack> dropsOf(ServerLevel level, BlockPos pos, ServerPlayer player, ItemStack tool) {
        return Block.getDrops(level.getBlockState(pos), level, pos, level.getBlockEntity(pos), player, tool);
    }

    /**
     * The tree falls away from whoever cut it, snapped to a cardinal direction so the block grid
     * stays aligned with the world.
     */
    private static Direction fallDirectionAwayFrom(BlockPos origin, ServerPlayer player) {
        double dx = origin.getX() + 0.5 - player.getX();
        double dz = origin.getZ() + 0.5 - player.getZ();

        if (Math.abs(dx) > Math.abs(dz)) {
            return dx > 0 ? Direction.EAST : Direction.WEST;
        }
        return dz > 0 ? Direction.SOUTH : Direction.NORTH;
    }
}
