package com.payangar.bucheron.network;

import com.payangar.bucheron.Constants;
import net.minecraft.core.BlockPos;
import net.minecraft.network.RegistryFriendlyByteBuf;
import net.minecraft.network.codec.StreamCodec;
import net.minecraft.network.protocol.common.custom.CustomPacketPayload;
import net.minecraft.resources.Identifier;

import java.util.ArrayList;
import java.util.List;

/**
 * The shape of a falling tree, sent once to every player tracking it.
 *
 * <p>Only the shape travels. Both sides run the same deterministic pendulum integration from the
 * same starting angle, so the animation needs no further packets.
 *
 * <p>Written by hand rather than through composite codecs: the payload carries a variable-length
 * list of pairs, and spelling out the buffer reads keeps the wire format obvious.
 *
 * @param entityId      the falling tree entity this shape belongs to
 * @param fallDirection ordinal of the horizontal direction the tree topples towards
 * @param pieces        every block of the tree, positioned relative to the stump
 */
public record TreeShapePayload(int entityId, byte fallDirection, List<Piece> pieces) implements CustomPacketPayload {

    /** A single block of the tree: where it sits relative to the stump, and what it looks like. */
    public record Piece(BlockPos offset, int stateId) {
    }

    public static final CustomPacketPayload.Type<TreeShapePayload> TYPE =
        new CustomPacketPayload.Type<>(Identifier.fromNamespaceAndPath(Constants.MOD_ID, "tree_shape"));

    public static final StreamCodec<RegistryFriendlyByteBuf, TreeShapePayload> CODEC =
        StreamCodec.of(TreeShapePayload::write, TreeShapePayload::read);

    private static void write(RegistryFriendlyByteBuf buffer, TreeShapePayload payload) {
        buffer.writeVarInt(payload.entityId);
        buffer.writeByte(payload.fallDirection);
        buffer.writeVarInt(payload.pieces.size());
        for (Piece piece : payload.pieces) {
            buffer.writeBlockPos(piece.offset());
            buffer.writeVarInt(piece.stateId());
        }
    }

    private static TreeShapePayload read(RegistryFriendlyByteBuf buffer) {
        int entityId = buffer.readVarInt();
        byte fallDirection = buffer.readByte();
        int count = buffer.readVarInt();
        List<Piece> pieces = new ArrayList<>(count);
        for (int i = 0; i < count; i++) {
            pieces.add(new Piece(buffer.readBlockPos(), buffer.readVarInt()));
        }
        return new TreeShapePayload(entityId, fallDirection, pieces);
    }

    @Override
    public Type<? extends CustomPacketPayload> type() {
        return TYPE;
    }
}
