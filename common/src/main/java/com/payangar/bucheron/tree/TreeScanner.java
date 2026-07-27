package com.payangar.bucheron.tree;

import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.tags.BlockTags;
import net.minecraft.world.level.BlockGetter;
import net.minecraft.world.level.block.LeavesBlock;
import net.minecraft.world.level.block.state.BlockState;

import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Queue;
import java.util.Set;

/**
 * Finds the tree a broken log belongs to.
 *
 * <p>Logs are gathered upwards only: a tree grows up, so refusing to walk down keeps a log cabin
 * from collapsing when its foundation is mined. A candidate with no attached leaves is treated as
 * a build rather than a tree and left to vanilla, which is the main safeguard against felling
 * player constructions.
 */
public final class TreeScanner {

    /** Beyond this, the tree is left to vanilla block breaking. */
    public static final int MAX_LOGS = 256;

    private TreeScanner() {
    }

    /** @return the tree rooted at the given log, or null when this is not a fellable tree */
    public static TreeShape scan(BlockGetter level, BlockPos origin) {
        if (!isLog(level.getBlockState(origin))) {
            return null;
        }

        List<BlockPos> logs = gatherLogs(level, origin);
        if (logs == null) {
            return null;
        }

        List<BlockPos> leaves = gatherLeaves(level, logs);
        if (leaves.isEmpty()) {
            return null;
        }

        return new TreeShape(origin.immutable(), logs, leaves);
    }

    /** @return connected logs, or null when the trunk exceeds {@link #MAX_LOGS} */
    private static List<BlockPos> gatherLogs(BlockGetter level, BlockPos origin) {
        List<BlockPos> logs = new ArrayList<>();
        Set<BlockPos> visited = new HashSet<>();
        Queue<BlockPos> queue = new ArrayDeque<>();

        BlockPos start = origin.immutable();
        queue.add(start);
        visited.add(start);

        while (!queue.isEmpty()) {
            BlockPos current = queue.poll();
            logs.add(current);
            if (logs.size() > MAX_LOGS) {
                return null;
            }

            // Same layer and the one above only, so the scan can never walk down into a floor.
            for (int dx = -1; dx <= 1; dx++) {
                for (int dy = 0; dy <= 1; dy++) {
                    for (int dz = -1; dz <= 1; dz++) {
                        if (dx == 0 && dy == 0 && dz == 0) {
                            continue;
                        }
                        BlockPos neighbour = current.offset(dx, dy, dz);
                        if (visited.contains(neighbour) || !isLog(level.getBlockState(neighbour))) {
                            continue;
                        }
                        BlockPos immutable = neighbour.immutable();
                        visited.add(immutable);
                        queue.add(immutable);
                    }
                }
            }
        }
        return logs;
    }

    /**
     * Breadth-first walk outwards from the logs. A leaf is kept only when its vanilla decay
     * distance matches the distance we reached it at, which is what proves it hangs off this
     * trunk rather than a neighbouring one.
     */
    private static List<BlockPos> gatherLeaves(BlockGetter level, List<BlockPos> logs) {
        Set<BlockPos> leaves = new LinkedHashSet<>();
        Set<BlockPos> visited = new HashSet<>();
        Queue<Node> queue = new ArrayDeque<>();

        for (BlockPos log : logs) {
            for (Direction direction : Direction.values()) {
                queue.add(new Node(log.relative(direction).immutable(), 1));
            }
        }

        while (!queue.isEmpty()) {
            Node node = queue.poll();
            if (node.distance > LeavesBlock.DECAY_DISTANCE || !visited.add(node.pos)) {
                continue;
            }

            BlockState state = level.getBlockState(node.pos);
            if (!isHarvestableLeaf(state) || decayDistanceOf(state) != node.distance) {
                continue;
            }

            leaves.add(node.pos);
            for (Direction direction : Direction.values()) {
                queue.add(new Node(node.pos.relative(direction).immutable(), node.distance + 1));
            }
        }
        return new ArrayList<>(leaves);
    }

    private static boolean isLog(BlockState state) {
        return state.is(BlockTags.LOGS);
    }

    /** Player-placed leaves are marked persistent and are never part of a tree. */
    private static boolean isHarvestableLeaf(BlockState state) {
        if (!state.is(BlockTags.LEAVES)) {
            return false;
        }
        return !state.hasProperty(LeavesBlock.PERSISTENT) || !state.getValue(LeavesBlock.PERSISTENT);
    }

    /** @return the vanilla decay distance, or -1 for leaves that do not carry the property */
    private static int decayDistanceOf(BlockState state) {
        return state.hasProperty(LeavesBlock.DISTANCE) ? state.getValue(LeavesBlock.DISTANCE) : -1;
    }

    private record Node(BlockPos pos, int distance) {
    }
}
