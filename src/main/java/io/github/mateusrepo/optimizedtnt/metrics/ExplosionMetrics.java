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
    private static long vanillaExplosions;
    private static long vanillaTotalNanos;
    private static long vanillaBlocksDestroyed;

    private ExplosionMetrics() {
    }

    public static void setEnabled(boolean value) {
        // Liga ou desliga reinicia sempre os contadores: cada série de medição tem de começar
        // do zero, senão os números de fases diferentes ficam misturados.
        enabled = value;
        reset();
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
        vanillaExplosions = 0;
        vanillaTotalNanos = 0;
    }

    /**
     * Regista uma explosão calculada pelo vanilla.
     *
     * <p>É o que permite comparar os dois lados na mesma sessão: com a otimização desligada o
     * mod não entra na conta, mas mesmo assim mede quanto o vanilla gastou.
     */
    public static void recordVanilla(long nanos, java.util.Collection<?> blocks) {
        if (!enabled) {
            return;
        }
        vanillaExplosions++;
        vanillaTotalNanos += nanos;
        vanillaBlocksDestroyed += blocks == null ? 0 : blocks.size();
    }

    public static long vanillaExplosions() {
        return vanillaExplosions;
    }

    /** @return média de blocos que o vanilla destruiu por explosão. */
    public static double vanillaAverageBlocks() {
        return vanillaExplosions == 0 ? 0.0 : (double) vanillaBlocksDestroyed / vanillaExplosions;
    }

    /** @return microssegundos que o vanilla gastou, em média por explosão. */
    public static double vanillaAverageMicros() {
        return vanillaExplosions == 0 ? 0.0 : vanillaTotalNanos / 1000.0 / vanillaExplosions;
    }

    /**
     * @return rácio de tempo vanilla/otimizado, ou 0 se não houver os dois lados medidos
     */
    public static double speedup() {
        if (explosions == 0 || vanillaExplosions == 0 || totalNanos == 0) {
            return 0.0;
        }
        return vanillaTotalNanos / (double) totalNanos;
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
