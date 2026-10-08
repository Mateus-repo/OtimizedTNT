package io.github.mateusrepo.optimizedtnt.explosion;

/**
 * Sonda densa para os testes: grelha de {@code float} com a resistência por posição, sem
 * tabelas hash nem boxing.
 *
 * <p>A {@link TestProbe} usa {@code HashMap<Long, Float>} e o boxing dela mascara
 * completamente o custo do algoritmo — o benchmark mediria o mapa, não a onda. Para paridade
 * (comparar conjuntos) qualquer sonda serve; para tempo só esta.
 *
 * <p>{@link Float#NaN} é ar / sem resistência, como o {@code Optional.empty} do vanilla.
 */
final class DenseProbe implements BlockProbe {

    private final float[] grid;
    private final int size;
    private final int origin;
    private int reads;

    DenseProbe(int size) {
        this.size = size;
        this.origin = size / 2;
        this.grid = new float[size * size * size];
        java.util.Arrays.fill(grid, Float.NaN);
    }

    DenseProbe fill(int minX, int minY, int minZ, int maxX, int maxY, int maxZ, float resistance) {
        for (int x = minX; x <= maxX; x++) {
            for (int y = minY; y <= maxY; y++) {
                for (int z = minZ; z <= maxZ; z++) {
                    grid[index(x, y, z)] = resistance;
                }
            }
        }
        return this;
    }

    /** Espalha {@code 1/chance} dos blocos com a resistência indicada. */
    DenseProbe scatter(int seed, int chance, float resistance) {
        java.util.Random random = new java.util.Random(seed);
        for (int i = 0; i < grid.length; i++) {
            if (random.nextInt(chance) == 0) {
                grid[i] = resistance;
            }
        }
        return this;
    }

    private int index(int x, int y, int z) {
        return ((x + origin) * size + (y + origin)) * size + (z + origin);
    }

    @Override
    public boolean inBounds(int x, int y, int z) {
        return x >= -origin && x < origin && y >= -origin && y < origin && z >= -origin && z < origin;
    }

    @Override
    public float resistance(int x, int y, int z) {
        reads++;
        return grid[index(x, y, z)];
    }

    @Override
    public boolean shouldExplode(int x, int y, int z, float energy) {
        return true;
    }

    int reads() {
        return reads;
    }

    void resetReads() {
        reads = 0;
    }

    /** @return {@code true} se a resistência em (x, y, z) é ar. */
    boolean isAir(int x, int y, int z) {
        return Float.isNaN(grid[index(x, y, z)]);
    }
}