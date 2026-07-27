package com.payangar.bucheron.tree;

import net.minecraft.core.BlockPos;

import java.util.ArrayList;
import java.util.List;

/**
 * Immutable description of a scanned tree, in world coordinates.
 *
 * @param origin the log the player broke, which is also the pivot the tree falls around
 * @param logs   every connected log, origin included
 * @param leaves every leaf block attached to those logs
 */
public record TreeShape(BlockPos origin, List<BlockPos> logs, List<BlockPos> leaves) {

    /** Every block the tree is made of. Logs first, so removal starts with the trunk. */
    public List<BlockPos> allBlocks() {
        List<BlockPos> all = new ArrayList<>(logs.size() + leaves.size());
        all.addAll(logs);
        all.addAll(leaves);
        return all;
    }

    /** Height in blocks, from the origin up to the highest block of the tree. */
    public int height() {
        int top = origin.getY();
        for (BlockPos pos : logs) {
            top = Math.max(top, pos.getY());
        }
        for (BlockPos pos : leaves) {
            top = Math.max(top, pos.getY());
        }
        return top - origin.getY() + 1;
    }
}
