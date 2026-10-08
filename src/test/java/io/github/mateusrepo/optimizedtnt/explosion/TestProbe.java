package io.github.mateusrepo.optimizedtnt.explosion;

import java.util.HashMap;
import java.util.Map;

/**
 * Grelha 3D em memória para testar o núcleo sem Minecraft.
 *
 * <p>Convenções do vanilla: ar e fluido vazio dão {@code Optional.empty()} (aqui
 * {@link Float#NaN}); qualquer outro bloco devolve a resistência máxima entre bloco e fluido,
 * como faz {@code ExplosionDamageCalculator}.
 */
final class TestProbe implements BlockProbe {

    /** Resistências usadas nos cenários. */
    static final float DIRT = 0.6F;
    static final float SAND = 0.5F;
    static final float LEAVES = 0.2F;
    static final float WOOD = 2.0F;
    static final float STONE = 6.0F;
    static final float OBSIDIAN = 3600.0F;
    static final float WATER = 100.0F;

    private final Map<Long, Float> blocks = new HashMap<>();
    private final Map<Long, Integer> reads = new HashMap<>();
    private int minY = Integer.MIN_VALUE;
    private int maxY = Integer.MAX_VALUE;

    int inBoundsCalls;
    int resistanceCalls;
    int shouldExplodeCalls;

    TestProbe put(int x, int y, int z, float resistance) {
        blocks.put(ExplosionWavefront.pack(x, y, z), resistance);
        return this;
    }

    TestProbe fill(int minX, int minY, int minZ, int maxX, int maxY, int maxZ, float resistance) {
        for (int x = minX; x <= maxX; x++) {
            for (int y = minY; y <= maxY; y++) {
                for (int z = minZ; z <= maxZ; z++) {
                    put(x, y, z, resistance);
                }
            }
        }
        return this;
    }

    /** Limita a altura do mundo, para testar o abortar do raio. */
    TestProbe withHeightLimits(int minY, int maxY) {
        this.minY = minY;
        this.maxY = maxY;
        return this;
    }

    boolean isSolid(int x, int y, int z) {
        Float value = blocks.get(ExplosionWavefront.pack(x, y, z));
        return value != null;
    }

    @Override
    public boolean inBounds(int x, int y, int z) {
        inBoundsCalls++;
        return y >= minY && y <= maxY;
    }

    @Override
    public float resistance(int x, int y, int z) {
        resistanceCalls++;
        long key = ExplosionWavefront.pack(x, y, z);
        reads.merge(key, 1, Integer::sum);
        Float value = blocks.get(key);
        return value == null ? Float.NaN : value;
    }

    @Override
    public boolean shouldExplode(int x, int y, int z, float energy) {
        shouldExplodeCalls++;
        // Como o vanilla por omissão: tudo explode.
        return true;
    }

    /** Quantas vezes cada posição foi lida (para provar que a onda lê cada bloco uma vez). */
    int readsAt(int x, int y, int z) {
        return reads.getOrDefault(ExplosionWavefront.pack(x, y, z), 0);
    }

    int maxReads() {
        int max = 0;
        for (int count : reads.values()) {
            max = Math.max(max, count);
        }
        return max;
    }

    void resetCounters() {
        inBoundsCalls = 0;
        resistanceCalls = 0;
        shouldExplodeCalls = 0;
        reads.clear();
    }
}
