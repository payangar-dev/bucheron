package com.payangar.bucheron.client;

import net.minecraft.client.multiplayer.ClientLevel;
import net.minecraft.client.renderer.block.MovingBlockRenderState;
import net.minecraft.core.BlockPos;
import net.minecraft.world.level.ColorResolver;
import net.minecraft.world.level.block.state.BlockState;

import java.util.Map;

/**
 * A moving-block render state whose neighbourhood is the whole tree plus the surrounding
 * terrain, instead of the endless air the vanilla state reports. Ambient occlusion samples
 * neighbours through this object, so with real neighbours the falling tree keeps the exact
 * shading it had while standing and the swap from chunk rendering becomes invisible.
 */
public class TreePieceRenderState extends MovingBlockRenderState {

    /** Every piece of the tree, keyed by its standing world position. Shared across pieces. */
    private final Map<BlockPos, BlockState> treeBlocks;
    private final ClientLevel level;

    public TreePieceRenderState(Map<BlockPos, BlockState> treeBlocks, ClientLevel level) {
        this.treeBlocks = treeBlocks;
        this.level = level;
    }

    @Override
    public BlockState getBlockState(BlockPos pos) {
        BlockState piece = this.treeBlocks.get(pos);
        return piece != null ? piece : this.level.getBlockState(pos);
    }

    /** Biome-blended tint from the level, so foliage keeps its chunk-rendered colour. */
    @Override
    public int getBlockTint(BlockPos pos, ColorResolver resolver) {
        return this.level.getBlockTint(pos, resolver);
    }
}
