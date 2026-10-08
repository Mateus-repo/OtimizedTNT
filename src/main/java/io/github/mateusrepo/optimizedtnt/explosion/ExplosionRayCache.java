package io.github.mateusrepo.optimizedtnt.explosion;

import java.util.Set;

/**
 * Os mesmos raios do vanilla, com a resistência de cada bloco memorizada.
 *
 * <p>No vanilla cada amostra de {@code 0.3} volta a perguntar o estado do bloco ao mundo — e
 * como uma amostra cai normalmente 3 a 4 vezes dentro do mesmo bloco, cada bloco é lido 3 a 4
 * vezes para se saber a mesma resistência. Aqui é lido uma vez por bloco distinto; a
 * <em>forma</em> da explosão fica <strong>idêntica</strong>, porque a resistência continua a ser
 * cobrada uma vez por amostra, tal como antes.
 *
 * <p>É o modo de máxima fidelidade: mais lento que a onda, mas com o mesmo resultado que o
 * vanilla. Verificado por {@code ParityTest}.
 */
public final class ExplosionRayCache {

    private ExplosionRayCache() {
    }

    /**
     * Percorre os raios a registar cada bloco afetado.
     *
     * @param random o RNG do mundo: consome um valor por raio, como o vanilla
     * @return quantidade de blocos registados
     */
    public static int forEach(
            ExplosionParams params, BlockProbe probe, FloatSource random, ExplosionSink sink) {
        return ExplosionRayCaster.forEach(params, probe, random, sink, true);
    }

    /** Versão para testes, com um valor aleatório fixo. */
    public static Set<Long> compute(ExplosionParams params, BlockProbe probe, float fixedRandom) {
        return ExplosionRayCaster.collect(params, probe, fixedRandom, true);
    }
}
