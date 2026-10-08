package io.github.mateusrepo.optimizedtnt.explosion;

import io.github.mateusrepo.optimizedtnt.config.OptimizedTntConfig;
import io.github.mateusrepo.optimizedtnt.metrics.ExplosionMetrics;
import it.unimi.dsi.fastutil.objects.ObjectArrayList;
import net.minecraft.core.BlockPos;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.Explosion;
import net.minecraft.world.level.ExplosionDamageCalculator;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.material.FluidState;

import java.util.IdentityHashMap;
import java.util.List;
import java.util.Map;

/**
 * Adaptador entre o núcleo (que só conhece {@link BlockProbe}) e o Minecraft.
 *
 * <p>É o único sítio do mod que fala com {@code Level}, {@link ExplosionDamageCalculator}
 * e {@link Explosion}. Também escolhe o algoritmo e devolve a lista de posições que
 * {@code calculateExplodedPositions} tem de devolver.
 */
public final class ExplosionOptimizer {

    private ExplosionOptimizer() {
    }

    /**
     * Calcula os blocos afetados de uma explosão.
     *
     * @param random o RNG do mundo: consome-se exatamente o mesmo número de valores que o
     *               vanilla, para não alterar a sequência do servidor
     * @return lista de posições, no formato que {@code calculateExplodedPositions} devolve
     */
    public static List<BlockPos> compute(
            Explosion explosion,
            Level level,
            double centerX,
            double centerY,
            double centerZ,
            float radius,
            ExplosionDamageCalculator damageCalculator,
            net.minecraft.util.RandomSource random,
            OptimizedTntConfig config) {

        FloatSource floats = random::nextFloat;
        boolean perDirection = config.getRandomnessMode() == OptimizedTntConfig.RandomnessMode.PER_DIRECTION;
        ExplosionRandomEnergy.Energies energies = ExplosionRandomEnergy.sample(radius, floats, perDirection);

        ExplosionParams params = new ExplosionParams(
                centerX, centerY, centerZ, radius,
                energies.axis(), energies.face(), energies.corner(),
                config.getNeighborhood(), config.getResistanceFactor());

        LevelProbe probe = new LevelProbe(
                explosion, level, damageCalculator, config.isCacheBlockResistance());

        ObjectArrayList<BlockPos> result = new ObjectArrayList<>();
        boolean measure = ExplosionMetrics.isEnabled();
        long start = measure ? System.nanoTime() : 0L;

        int blocks;
        if (config.getAlgorithm() == OptimizedTntConfig.Algorithm.RAY_CACHE) {
            blocks = ExplosionRayCache.forEach(params, probe, floats, packed -> result.add(toBlockPos(packed)));
        } else {
            blocks = ExplosionWavefront.compute(params, probe, packed -> result.add(toBlockPos(packed))).blocks();
        }

        if (measure) {
            ExplosionMetrics.record(blocks, probe.reads(), System.nanoTime() - start);
        }
        return result;
    }

    private static BlockPos toBlockPos(long packed) {
        // BlockPos.of(long) usa exactamente o mesmo formato que o nosso empacotamento.
        return BlockPos.of(packed);
    }

    /** Cria um {@link BlockProbe} sobre o mundo, para uso fora do caminho principal. */
    public static BlockProbe probe(
            Explosion explosion,
            Level level,
            ExplosionDamageCalculator damageCalculator,
            boolean cacheBlockResistance) {
        return new LevelProbe(explosion, level, damageCalculator, cacheBlockResistance);
    }

    /** Parâmetros com uma energia fixa, para comparações determinísticas. */
    public static ExplosionParams fixedParams(
            double centerX, double centerY, double centerZ, float radius, float randomValue,
            int neighborhood, float resistanceFactor) {
        float energy = ExplosionRandomEnergy.energyFor(radius, randomValue);
        return new ExplosionParams(centerX, centerY, centerZ, radius,
                energy, energy, energy, neighborhood, resistanceFactor);
    }

    /**
     * {@link BlockProbe} sobre o mundo.
     *
     * <p>A resistência é sempre pedida ao {@link ExplosionDamageCalculator} da explosão, para que
     * mods e plugins que a customizem continuem a ser respeitados. A cache por
     * {@link BlockState} só é usada quando o calculador é exatamente a classe base do vanilla
     * (aí a resistência só depende do estado e do fluido) e a opção está ligada.
     */
    private static final class LevelProbe implements BlockProbe {

        private final Explosion explosion;
        private final Level level;
        private final ExplosionDamageCalculator damageCalculator;
        private final boolean useStateCache;
        private final Map<BlockState, Float> stateCache;
        private final BlockPos.MutableBlockPos scratch = new BlockPos.MutableBlockPos();

        // Memo de uma entrada: a onda lê a resistência e logo a seguir pergunta se o bloco
        // explode, no mesmo sítio, por isso o estado quase sempre vem de cache.
        private long memoKey = Long.MIN_VALUE;
        private BlockState memoState;

        private long reads;

        LevelProbe(Explosion explosion, Level level, ExplosionDamageCalculator damageCalculator,
                boolean cacheBlockResistance) {
            this.explosion = explosion;
            this.level = level;
            this.damageCalculator = damageCalculator;
            // Só é seguro cachear se a resistência não depender da posição nem da explosão.
            this.useStateCache = cacheBlockResistance
                    && damageCalculator.getClass() == ExplosionDamageCalculator.class;
            this.stateCache = useStateCache ? new IdentityHashMap<>() : null;
        }

        long reads() {
            return reads;
        }

        @Override
        public boolean inBounds(int x, int y, int z) {
            scratch.set(x, y, z);
            return level.isInWorldBounds(scratch);
        }

        @Override
        public float resistance(int x, int y, int z) {
            reads++;
            scratch.set(x, y, z);
            BlockState state = stateAt(x, y, z);
            if (useStateCache) {
                Float cached = stateCache.get(state);
                if (cached != null) {
                    return cached;
                }
                float value = computeResistance(state);
                stateCache.put(state, value);
                return value;
            }
            return computeResistance(state);
        }

        @Override
        public boolean shouldExplode(int x, int y, int z, float energy) {
            scratch.set(x, y, z);
            return damageCalculator.shouldBlockExplode(explosion, level, scratch, stateAt(x, y, z), energy);
        }

        private BlockState stateAt(int x, int y, int z) {
            long key = ExplosionWavefront.pack(x, y, z);
            if (key != memoKey || memoState == null) {
                memoKey = key;
                memoState = level.getBlockState(scratch);
            }
            return memoState;
        }

        private float computeResistance(BlockState state) {
            FluidState fluid = level.getFluidState(scratch);
            var resistance = damageCalculator.getBlockExplosionResistance(
                    explosion, level, scratch, state, fluid);
            return resistance.isPresent() ? resistance.get() : Float.NaN;
        }
    }
}
