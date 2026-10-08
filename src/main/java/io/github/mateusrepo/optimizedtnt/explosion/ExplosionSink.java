package io.github.mateusrepo.optimizedtnt.explosion;

/**
 * Destino dos blocos afetados, com posições empacotadas em {@code long}
 * ({@code BlockPos.asLong}, o mesmo formato que o vanilla usa internamente).
 */
@FunctionalInterface
public interface ExplosionSink {

    void accept(long packedPos);
}
