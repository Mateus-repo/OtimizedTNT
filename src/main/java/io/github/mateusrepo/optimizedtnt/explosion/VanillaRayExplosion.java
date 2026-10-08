package io.github.mateusrepo.optimizedtnt.explosion;

import java.util.Set;

/**
 * Réplica exacta do algoritmo do vanilla 26.3 — o oráculo dos testes de paridade e do
 * comando {@code /optimizedtnt compare}.
 *
 * <p>Não é um caminho de otimização: repete de propósito todas as amostras dos 1352 raios,
 * incluindo as repetidas. Ver {@link ExplosionRayCaster} para o algoritmo.
 */
public final class VanillaRayExplosion {

    private VanillaRayExplosion() {
    }

    /** @return o conjunto de posições afetadas, tal como o vanilla as calcularia. */
    public static Set<Long> compute(ExplosionParams params, BlockProbe probe, float fixedRandom) {
        return ExplosionRayCaster.collect(params, probe, fixedRandom, false);
    }
}
