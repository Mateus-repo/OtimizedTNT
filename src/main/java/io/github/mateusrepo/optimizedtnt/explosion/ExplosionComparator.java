package io.github.mateusrepo.optimizedtnt.explosion;

import io.github.mateusrepo.optimizedtnt.OptimizedTnt;
import io.github.mateusrepo.optimizedtnt.config.OptimizedTntConfig;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.Explosion;
import net.minecraft.world.level.ExplosionDamageCalculator;

import java.util.HashSet;
import java.util.Locale;
import java.util.Set;

/**
 * Compara o resultado do algoritmo escolhido com o do vanilla, para uma explosão real.
 *
 * <p>É o que o comando {@code /optimizedtnt compare} liga durante uma explosão. Usa sempre um
 * valor aleatório fixo nos dois lados, para a comparação ser determinística e para não tocar no
 * RNG do mundo — comparar não pode alterar o estado do servidor.
 */
public final class ExplosionComparator {

    /** Valor aleatório usado nos dois lados: a média dos 1352 sorteios do vanilla. */
    private static final float FIXED_RANDOM = 0.5F;

    private ExplosionComparator() {
    }

    /** Corre os dois algoritmos e regista o desvio no log. */
    public static void compare(
            Explosion explosion,
            Level level,
            double centerX,
            double centerY,
            double centerZ,
            float radius,
            ExplosionDamageCalculator damageCalculator,
            OptimizedTntConfig config) {

        BlockProbe probe = ExplosionOptimizer.probe(
                explosion, level, damageCalculator, config.isCacheBlockResistance());

        ExplosionParams params = ExplosionOptimizer.fixedParams(
                centerX, centerY, centerZ, radius, FIXED_RANDOM,
                config.getNeighborhood(), config.getResistanceFactor());

        Set<Long> vanilla;
        long vanillaNanos;
        {
            // Aquecimento antes de medir, senão o primeiro algoritmo paga a JIT.
            VanillaRayExplosion.compute(params, probe, FIXED_RANDOM);
            long start = System.nanoTime();
            vanilla = VanillaRayExplosion.compute(params, probe, FIXED_RANDOM);
            vanillaNanos = System.nanoTime() - start;
        }

        Set<Long> optimized = new HashSet<>();
        long optimizedNanos;
        {
            runOptimized(config, params, probe, optimized);
            optimized.clear();
            long start = System.nanoTime();
            runOptimized(config, params, probe, optimized);
            optimizedNanos = System.nanoTime() - start;
        }

        Set<Long> onlyVanilla = new HashSet<>(vanilla);
        onlyVanilla.removeAll(optimized);
        Set<Long> onlyOptimized = new HashSet<>(optimized);
        onlyOptimized.removeAll(vanilla);
        int both = vanilla.size() - onlyVanilla.size(); // |A ∩ B|; a subtração abaixo dá a união

        OptimizedTnt.LOGGER.info(
                "[compare] vanilla={} otimizado={} em ambos={} só vanilla={} só otimizado={}"
                        + " — algoritmo={} vizinhança={} fatorResistência={} raio={}"
                        + " | vanilla={} µs, otimizado={} µs ({}×)",
                vanilla.size(), optimized.size(), both,
                onlyVanilla.size(), onlyOptimized.size(),
                config.getAlgorithm(), config.getNeighborhood(),
                config.getResistanceFactor(), radius,
                vanillaNanos / 1000, optimizedNanos / 1000,
                optimizedNanos == 0 ? "?" : String.format(Locale.ROOT, "%.1f",
                        vanillaNanos / (double) optimizedNanos));
    }

    private static void runOptimized(
            OptimizedTntConfig config, ExplosionParams params, BlockProbe probe, Set<Long> out) {
        if (config.getAlgorithm() == OptimizedTntConfig.Algorithm.RAY_CACHE) {
            out.addAll(ExplosionRayCache.compute(params, probe, FIXED_RANDOM));
        } else {
            ExplosionWavefront.compute(params, probe, out::add);
        }
    }
}
