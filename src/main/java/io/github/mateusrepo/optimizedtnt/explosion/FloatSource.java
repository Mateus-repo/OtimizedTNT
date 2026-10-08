package io.github.mateusrepo.optimizedtnt.explosion;

/**
 * Fonte de valores aleatórios, com o mínimo de superfície possível.
 *
 * <p>Existe porque o {@code RandomSource} do Minecraft 26.3 <strong>não</strong> implementa
 * {@link java.util.random.RandomGenerator}, e porque o núcleo não deve depender do jogo: assim
 * basta um método e os testes podem injectar um valor fixo.
 */
@FunctionalInterface
public interface FloatSource {

    float nextFloat();

    /** Fonte determinística com um valor fixo, para comparar algoritmos. */
    static FloatSource fixed(float value) {
        return () -> value;
    }
}
