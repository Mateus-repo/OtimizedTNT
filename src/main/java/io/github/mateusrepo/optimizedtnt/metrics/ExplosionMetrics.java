package io.github.mateusrepo.optimizedtnt.metrics;

/**
 * Contadores de desempenho, desligados por omissão.
 *
 * <p>Só registam quando {@code metrics} está ligado na configuração, para não haver custo
 * nenhum por omissão. Tudo acontece na thread do servidor, por isso não é preciso sincronização.
 */
public final class ExplosionMetrics {

    private static boolean enabled;
    private static long explosions;
    private static long blocksDestroyed;
    private static long blockReads;
    private static long totalNanos;

    private ExplosionMetrics() {
    }

    public static void setEnabled(boolean value) {
        enabled = value;
        if (!value) {
            reset();
        }
    }

    public static boolean isEnabled() {
        return enabled;
    }

    public static void record(long blocks, long reads, long nanos) {
        if (!enabled) {
            return;
        }
        explosions++;
        blocksDestroyed += blocks;
        blockReads += reads;
        totalNanos += nanos;
    }

    public static void reset() {
        explosions = 0;
        blocksDestroyed = 0;
        blockReads = 0;
        totalNanos = 0;
    }

    public static long explosions() {
        return explosions;
    }

    public static long blocksDestroyed() {
        return blocksDestroyed;
    }

    public static long blockReads() {
        return blockReads;
    }

    public static long totalNanos() {
        return totalNanos;
    }

    /** @return microssegundos por explosão, ou 0 se ainda não houve medições. */
    public static double averageMicros() {
        return explosions == 0 ? 0.0 : totalNanos / 1000.0 / explosions;
    }

    /** @return média de blocos afetados por explosão. */
    public static double averageBlocks() {
        return explosions == 0 ? 0.0 : (double) blocksDestroyed / explosions;
    }

    /** @return média de leituras de bloco por explosão. */
    public static double averageReads() {
        return explosions == 0 ? 0.0 : (double) blockReads / explosions;
    }
}
