package io.github.mateusrepo.optimizedtnt.explosion;

import java.util.Arrays;

/**
 * Células em tabelas hash abertas, com contador de geração.
 *
 * <p>É o caminho de recurso: só entra quando o alcance da explosão não cabe numa grelha densa
 * (ver {@link DenseExplosionCells#fits}). O alcance é limitado, por isso a grelha é o caso
 * comum; mas uma explosão com raio enorme não caberia em memória, e aí é melhor um hash.
 */
final class HashExplosionCells implements ExplosionCells {

    private static final int INITIAL_CAPACITY = 4096;

    private final LongFloatMap energy = new LongFloatMap(INITIAL_CAPACITY);
    private final LongFloatMap charge = new LongFloatMap(INITIAL_CAPACITY);
    private final LongFloatMap emitted = new LongFloatMap(1024);

    @Override
    public void begin() {
        energy.nextGeneration();
        charge.nextGeneration();
        emitted.nextGeneration();
    }

    @Override
    public float energy(long packed) {
        return energy.get(packed);
    }

    @Override
    public boolean improveEnergy(long packed, float value) {
        float current = energy.get(packed);
        if (Float.isNaN(current) || value > current) {
            energy.put(packed, value);
            return true;
        }
        return false;
    }

    @Override
    public float charge(long packed) {
        return charge.get(packed);
    }

    @Override
    public void putCharge(long packed, float value) {
        charge.put(packed, value);
    }

    @Override
    public boolean isEmitted(long packed) {
        return !Float.isNaN(emitted.get(packed));
    }

    @Override
    public void markEmitted(long packed) {
        emitted.put(packed, 1.0F);
    }

    /**
     * Mapa aberto {@code long → float} com contador de geração, para não ser preciso limpar a
     * tabela entre explosões. {@link Float#NaN} significa "chave ausente".
     *
     * <p>Escrito à mão em vez de usar {@code Long2FloatOpenHashMap} para que o núcleo não
     * dependa de nada fora de {@code java.*} e se possa testar sem o Minecraft no classpath.
     */
    private static final class LongFloatMap {

        private long[] keys;
        private float[] values;
        private int[] stamps;
        private int mask;
        private int generation;
        private int size;
        private final int threshold;

        LongFloatMap(int expected) {
            int capacity = tableSizeFor(expected);
            keys = new long[capacity];
            values = new float[capacity];
            stamps = new int[capacity];
            mask = capacity - 1;
            threshold = capacity * 3 / 4;
            generation = 1;
        }

        void nextGeneration() {
            generation++;
            if (generation == Integer.MAX_VALUE) {
                Arrays.fill(stamps, 0);
                generation = 1;
            }
            size = 0;
        }

        float get(long key) {
            int index = index(key);
            return stamps[index] == generation ? values[index] : Float.NaN;
        }

        void put(long key, float value) {
            int index = index(key);
            if (stamps[index] == generation) {
                values[index] = value;
                return;
            }
            if (size > threshold) {
                grow();
                index = index(key);
            }
            stamps[index] = generation;
            keys[index] = key;
            values[index] = value;
            size++;
        }

        private int index(long key) {
            int index = mix(key) & mask;
            while (stamps[index] == generation && keys[index] != key) {
                index = (index + 1) & mask;
            }
            return index;
        }

        private void grow() {
            long[] oldKeys = keys;
            float[] oldValues = values;
            int[] oldStamps = stamps;
            int oldGeneration = generation;

            int capacity = keys.length << 1;
            keys = new long[capacity];
            values = new float[capacity];
            stamps = new int[capacity];
            mask = capacity - 1;
            size = 0;
            generation = oldGeneration + 1;

            for (int i = 0; i < oldStamps.length; i++) {
                if (oldStamps[i] == oldGeneration) {
                    insert(oldKeys[i], oldValues[i]);
                }
            }
        }

        private void insert(long key, float value) {
            int index = index(key);
            stamps[index] = generation;
            keys[index] = key;
            values[index] = value;
        }

        private static int mix(long key) {
            long h = key * 0x9E3779B97F4A7C15L;
            h ^= h >>> 31;
            h *= 0xBF58476D1CE4E5B9L;
            h ^= h >>> 27;
            return (int) h;
        }

        private static int tableSizeFor(int expected) {
            int needed = Math.max(16, expected * 2);
            int capacity = 16;
            while (capacity < needed) {
                capacity <<= 1;
            }
            return capacity;
        }
    }
}
