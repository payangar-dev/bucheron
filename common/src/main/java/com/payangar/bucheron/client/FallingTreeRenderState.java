package com.payangar.bucheron.client;

import net.minecraft.client.renderer.block.MovingBlockRenderState;
import net.minecraft.client.renderer.entity.state.EntityRenderState;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;

import java.util.ArrayList;
import java.util.List;

public class FallingTreeRenderState extends EntityRenderState {

    /** One drawable block: its position relative to the stump, and its render state. */
    public record RenderPiece(BlockPos offset, MovingBlockRenderState block) {
    }

    public final List<RenderPiece> pieces = new ArrayList<>();

    /** Radians from upright, interpolated between ticks. */
    public float fallAngle;

    public Direction fallDirection = Direction.NORTH;
}
