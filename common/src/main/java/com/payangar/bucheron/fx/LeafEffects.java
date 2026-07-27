package com.payangar.bucheron.fx;

import com.payangar.bucheron.LeafParticles;
import com.payangar.bucheron.entity.FallingTreeEntity;
import net.minecraft.core.BlockPos;
import net.minecraft.core.particles.ParticleOptions;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.sounds.SoundSource;
import net.minecraft.tags.BlockTags;
import net.minecraft.util.RandomSource;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.SoundType;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.phys.Vec3;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * What the canopy of a falling tree looks and sounds like: the sparse trail of leaves shed in
 * flight, the burst when leaf blocks hit the ground, and the merged crush sounds.
 *
 * <p>Sister class of {@code TreeSweep}, which handles what the fall does to entities.
 */
public final class LeafEffects {

    /** Capped per tick so a big canopy cannot flood clients with particle packets. */
    private static final int MAX_PARTICLES_PER_TICK = 3;

    /**
     * Faster leaves shed more. Below this speed, nothing sheds at all.
     *
     * <p>Kept deliberately sparse: the trail in mid-air is only a hint of movement. The real burst
     * belongs to the moment a leaf block hits something, which is when the canopy actually breaks
     * apart.
     */
    private static final float SHED_CHANCE_PER_SPEED = 0.15F;
    private static final float MIN_SHED_SPEED = 0.01F;

    /** Per crushed leaf block. One packet each, because each needs its own direction. */
    private static final int BURST_PARTICLES = 8;

    /** Ejection speed of a burst, varied per particle so the spray is not a uniform shell. */
    private static final double BURST_SPEED = 0.35;

    /**
     * Extra push on the vertical component of a burst.
     *
     * <p>A direction picked uniformly over the hemisphere spends most of its area near the horizon,
     * so an even spread ends up hugging the ground. Weighting the vertical axis tilts the whole
     * spray upwards and is what makes the cloud rise rather than spread.
     */
    private static final double BURST_VERTICAL_GAIN = 1.6;

    /**
     * A canopy scraping the ground crushes many blocks at once, and playing every break sound
     * would be both wasteful and mush to the ear. Impacts falling inside the same cell of this
     * grid are merged into a single louder sound.
     *
     * <p>Merging by locality rather than capping matters: a cap silences sounds by iteration
     * order, so two impacts at opposite ends of a big tree could cancel each other while two
     * adjacent ones both play.
     */
    private static final int CRUSH_SOUND_CELL = 3;

    /** Leaves are thrown along the canopy's motion, but a fraction of it reads better than 1:1. */
    private static final float FLING_SCALE = 0.35F;

    /**
     * How much of a leaf's downward motion is turned into lift.
     *
     * <p>A leaf does not ride the branch into the ground. The canopy shoves a wall of air ahead of
     * itself, that air escapes upwards once it is squeezed against the ground, and the leaf goes
     * with it. So the horizontal part of the tangential velocity is carried over as is, while the
     * downward part is reflected rather than kept.
     *
     * <p>The arc then comes for free from the particle engine: {@code v = v * friction - gravity}
     * damps the initial burst exponentially, giving a sharp rise, an apex, and a slow drift down.
     * That asymmetry is what reads as a leaf rather than as a thrown pebble.
     */
    private static final float UPWARD_FLING = 0.8F;

    private LeafEffects() {
    }

    /**
     * Leaves rain down as the canopy sweeps through the air. The chance scales with how fast the
     * block is actually moving, so the crown showers while the stump barely sheds anything.
     */
    public static void shed(ServerLevel level, FallingTreeEntity tree, float atAngle) {
        List<FallingTreeEntity.Piece> pieces = tree.pieces();
        if (pieces.isEmpty()) {
            return;
        }

        RandomSource random = tree.getRandom();
        int emitted = 0;
        int size = pieces.size();
        // Start somewhere random, otherwise the per-tick cap would always favour the same leaves.
        int start = random.nextInt(size);

        for (int i = 0; i < size && emitted < MAX_PARTICLES_PER_TICK; i++) {
            FallingTreeEntity.Piece piece = pieces.get((start + i) % size);
            if (!piece.state().is(BlockTags.LEAVES)) {
                continue;
            }

            float speed = Math.abs(tree.angularVelocity()) * tree.radiusOf(piece);
            if (speed < MIN_SHED_SPEED || random.nextFloat() > speed * SHED_CHANCE_PER_SPEED) {
                continue;
            }

            Vec3 pos = tree.worldPositionOf(piece, atAngle);
            Vec3 tangential = tree.worldVelocityOf(piece, atAngle).scale(FLING_SCALE);
            // Keep the horizontal sweep, reflect the plunge into lift. Leaves on the far side of
            // the pivot are already rising, and Math.abs leaves their lift untouched.
            Vec3 velocity = new Vec3(tangential.x, Math.abs(tangential.y) * UPWARD_FLING, tangential.z);

            // count = 0 makes the three trailing values a velocity rather than random offsets.
            // It is the only way to fling a leaf: with a positive count the particles would simply
            // hang where they spawned, which is what made the first version look lifeless.
            level.sendParticles(LeafParticles.resolve(piece.state(), level, BlockPos.containing(pos)),
                pos.x, pos.y, pos.z, 0, velocity.x, velocity.y, velocity.z, 1.0);
            emitted++;
        }
    }

    /**
     * A leaf block does not pass through terrain: it bursts against it and leaves the tree.
     *
     * <p>This is where most of the foliage comes from. A canopy sweeping into the ground shatters,
     * and that contact is the visible event, far more than the trail it leaves in the air.
     *
     * <p>Runs on both sides: the calculation is identical and the client knows the blocks, so the
     * canopy comes apart the same way there without a single packet. Only the server emits the
     * particles and sounds.
     */
    public static void crushOnContact(FallingTreeEntity tree, float atAngle) {
        List<FallingTreeEntity.Piece> pieces = tree.pieces();
        if (pieces.isEmpty()) {
            return;
        }

        Level level = tree.level();
        ServerLevel serverLevel = level instanceof ServerLevel server ? server : null;
        List<FallingTreeEntity.Piece> crushed = new ArrayList<>();
        Map<Long, CrushCell> cells = new HashMap<>();

        for (FallingTreeEntity.Piece piece : pieces) {
            if (!piece.state().is(BlockTags.LEAVES)) {
                continue;
            }

            Vec3 pos = tree.worldPositionOf(piece, atAngle);
            BlockPos at = BlockPos.containing(pos);
            // Collision shape rather than "not air", so grass and flowers do not shred a canopy.
            if (level.getBlockState(at).getCollisionShape(level, at).isEmpty()) {
                continue;
            }

            crushed.add(piece);

            if (serverLevel != null) {
                // Particles stay per block: the burst has to happen where the leaves actually were.
                // One packet each, because count > 0 spreads them randomly, which is exactly right
                // for something shattering in every direction.
                burst(serverLevel, tree.getRandom(), piece, at, pos);
                cells.computeIfAbsent(soundCellKey(pos), key -> new CrushCell(pos, piece.state())).merge(pos);
            }
        }

        if (serverLevel != null) {
            playMergedCrushSounds(serverLevel, tree.getRandom(), cells);
        }
        pieces.removeAll(crushed);
    }

    /**
     * Sprays a leaf block into the air on impact.
     *
     * <p>Directions are spread over the **upper** hemisphere, never the full sphere: the ground is
     * right there, so a leaf thrown downwards makes no sense. Each particle gets its own velocity,
     * which needs one packet each ({@code count = 0} is the only form that carries a velocity), but
     * a burst happens once per felled tree rather than every tick, so the cost is a one-off.
     */
    private static void burst(ServerLevel level, RandomSource random, FallingTreeEntity.Piece piece, BlockPos at, Vec3 pos) {
        ParticleOptions particle = LeafParticles.resolve(piece.state(), level, at);

        for (int i = 0; i < BURST_PARTICLES; i++) {
            // Vertical component first, then the horizontal radius that keeps the direction a unit
            // vector. The square root biases the pick towards the zenith, so fewer leaves skim the
            // ground, and the gain then lifts the whole spray.
            double up = Math.sqrt(random.nextDouble());
            double radius = Math.sqrt(1.0 - up * up);
            double azimuth = random.nextDouble() * Math.PI * 2.0;

            double speed = BURST_SPEED * (0.6 + random.nextDouble() * 0.8);
            double vx = Math.cos(azimuth) * radius * speed;
            double vz = Math.sin(azimuth) * radius * speed;
            double vy = up * speed * BURST_VERTICAL_GAIN;

            level.sendParticles(particle,
                pos.x + (random.nextDouble() - 0.5) * 0.5,
                pos.y + (random.nextDouble() - 0.5) * 0.4,
                pos.z + (random.nextDouble() - 0.5) * 0.5,
                0, vx, vy, vz, 1.0);
        }
    }

    /** Impacts merged into one sound: their centre of mass, their material, and how many. */
    private static final class CrushCell {

        private final BlockState state;
        private double x;
        private double y;
        private double z;
        private int count;

        private CrushCell(Vec3 first, BlockState state) {
            this.state = state;
            this.x = first.x;
            this.y = first.y;
            this.z = first.z;
            this.count = 1;
        }

        private void merge(Vec3 pos) {
            // Running average, so the sound comes from the middle of the impacts it stands for.
            count++;
            x += (pos.x - x) / count;
            y += (pos.y - y) / count;
            z += (pos.z - z) / count;
        }
    }

    private static void playMergedCrushSounds(ServerLevel level, RandomSource random, Map<Long, CrushCell> cells) {
        for (CrushCell cell : cells.values()) {
            // The block's own break sound, without breaking anything: these leaves belong to the
            // falling tree, not to the world. Going through SoundType means the sound follows the
            // species for free, cherry included.
            SoundType sound = cell.state.getSoundType();
            float volume = sound.getVolume() * Math.min(0.95F, 0.5F + 0.1F * cell.count);
            level.playSound(null, cell.x, cell.y, cell.z, sound.getBreakSound(), SoundSource.BLOCKS,
                volume, sound.getPitch() * (0.9F + random.nextFloat() * 0.2F));
        }
    }

    private static long soundCellKey(Vec3 pos) {
        long x = Math.floorDiv((long) Math.floor(pos.x), CRUSH_SOUND_CELL);
        long y = Math.floorDiv((long) Math.floor(pos.y), CRUSH_SOUND_CELL);
        long z = Math.floorDiv((long) Math.floor(pos.z), CRUSH_SOUND_CELL);
        return (x & 0x1FFFFFL) | ((y & 0x1FFFFFL) << 21) | ((z & 0x1FFFFFL) << 42);
    }
}
