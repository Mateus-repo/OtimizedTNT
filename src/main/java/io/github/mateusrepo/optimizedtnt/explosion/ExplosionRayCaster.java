package io.github.mateusrepo.optimizedtnt.explosion;

import java.util.HashSet;
import it.unimi.dsi.fastutil.longs.LongOpenHashSet;
import java.util.Set;

/**
 * Percorre os 1352 raios do vanilla 26.3.
 *
 * <p>É a réplica exacta do bytecode de {@code ServerExplosion#calculateExplodedPositions()}, e
 * por isso serve de oráculo para os testes de paridade. Também é a base do modo
 * {@code RAY_CACHE}, que se limita a memorizar a resistência por bloco — a forma da explosão
 * fica idêntica, mas cada bloco é lido do mundo uma vez em vez de 3 a 4.
 *
 * <pre>
 * forca = radius * (0.7 + 0.6 * random.nextFloat())      // um nextFloat por raio
 * enquanto forca &gt; 0:
 *     pos = bloco que contém (x, y, z)
 *     se !isInWorldBounds(pos): aborta o raio
 *     res = getBlockExplosionResistance(...)
 *     se res presente: forca -= (res + 0.3) * 0.3        // penalidade ANTES do teste
 *     se forca &gt; 0 &amp;&amp; shouldBlockExplode(...): regista pos
 *     avança 0.3 na direcção; forca -= 0.225
 * </pre>
 */
public final class ExplosionRayCaster {

    private ExplosionRayCaster() {
    }

    private static final ThreadLocal<Memo> MEMO = ThreadLocal.withInitial(Memo::new);

    /**
     * Executa os raios.
     *
     * @param random   o RNG do mundo (ou um substituto determinístico nos testes)
     * @param sink     recebe cada bloco afetado
     * @param memoize  {@code true} para memorizar a resistência por bloco (modo RAY_CACHE)
     * @return quantidade de blocos registados
     */
    public static int forEach(
            ExplosionParams params,
            BlockProbe probe,
            FloatSource random,
            ExplosionSink sink,
            boolean memoize) {

        // O Memo é reutilizado entre explosões (por thread): eram três arrays novos por
        // explosão, sem necessidade — só a geração é que muda.
        Memo memo = memoize ? MEMO.get() : null;
        if (memo != null) {
            memo.begin();
        }
        // O vanilla devolve um Set, por isso nunca repete posições. Um HashSet<Long> faz
        // boxing de Long em CADA amostra que passa (dezenas de milhares por explosão), o que
        // era boa parte do custo do modo RAY_CACHE; LongOpenHashSet é o mesmo conjunto sem
        // qualquer alocação.
        LongOpenHashSet seen = new LongOpenHashSet();
        int registered = 0;

        double centerX = params.centerX();
        double centerY = params.centerY();
        double centerZ = params.centerZ();
        float radius = params.radius();

        for (int i = 0; i < 16; i++) {
            for (int j = 0; j < 16; j++) {
                for (int k = 0; k < 16; k++) {
                    if (i != 0 && i != 15 && j != 0 && j != 15 && k != 0 && k != 15) {
                        continue; // só a casca do cubo 16×16×16
                    }

                    float vx = i / 15.0F * 2.0F - 1.0F;
                    float vy = j / 15.0F * 2.0F - 1.0F;
                    float vz = k / 15.0F * 2.0F - 1.0F;
                    float length = (float) Math.sqrt(vx * vx + vy * vy + vz * vz);
                    vx /= length;
                    vy /= length;
                    vz /= length;

                    float strength = ExplosionRandomEnergy.energyFor(radius, random.nextFloat());

                    double x = centerX;
                    double y = centerY;
                    double z = centerZ;

                    while (strength > 0.0F) {
                        int bx = (int) Math.floor(x);
                        int by = (int) Math.floor(y);
                        int bz = (int) Math.floor(z);

                        if (!probe.inBounds(bx, by, bz)) {
                            break; // o vanilla aborta o raio fora do mundo
                        }

                        long packed = ExplosionWavefront.pack(bx, by, bz);
                        float resistance = memo == null
                                ? probe.resistance(bx, by, bz)
                                : memo.get(packed, probe, bx, by, bz);
                        if (!Float.isNaN(resistance)) {
                            strength -= (resistance + 0.3F) * 0.3F;
                        }

                        // Ver o set ANTES de perguntar shouldExplode: quase todas as amostras
                        // caem em blocos já registados, e shouldExplode é uma chamada ao
                        // mundo (getBlockState). O vanilla também só avalia uma vez por bloco,
                        // porque o Set é o que decide o add.
                        if (strength > 0.0F && !seen.contains(packed)) {
                            if (probe.shouldExplode(bx, by, bz, strength)) {
                                seen.add(packed);
                                sink.accept(packed);
                                registered++;
                            }
                        }

                        x += vx * 0.3F;
                        y += vy * 0.3F;
                        z += vz * 0.3F;
                        strength -= Neighborhood.STEP_DECAY;
                    }
                }
            }
        }
        return registered;
    }

    /** Versão para testes: devolve o conjunto e usa um valor aleatório fixo. */
    public static Set<Long> collect(ExplosionParams params, BlockProbe probe, float fixedRandom,
            boolean memoize) {
        Set<Long> out = new HashSet<>();
        forEach(params, probe, new FixedRandom(fixedRandom), out::add, memoize);
        return out;
    }

    /** Gerador determinístico, para comparar os dois algoritmos com a mesma energia. */
    public static final class FixedRandom implements FloatSource {

        private final float value;

        public FixedRandom(float value) {
            this.value = value;
        }

        @Override
        public float nextFloat() {
            return value;
        }
    }

    /**
     * Resistências memorizadas durante uma explosão.
     *
     * <p>Um bloco é atravessado por ~3.3 amostras de {@code 0.3}, e cada uma volta a perguntar
     * ao mundo a resistência do mesmo bloco. Guardando-a, o custo passa a uma leitura por bloco
     * distinto. O valor {@code NaN} (ar) também é memorizado, daí o {@code int[]} de marcas.
     */
    private static final class Memo {

        private long[] keys = new long[2048];
        private float[] values = new float[2048];
        private int[] stamps = new int[2048];
        private int mask = keys.length - 1;
        private int generation = 1;
        private int size;

        /** Invalida o que ficou da explosão anterior sem percorrer a tabela. */
        void begin() {
            size = 0;
            generation++;
            if (generation == Integer.MAX_VALUE) {
                java.util.Arrays.fill(stamps, 0);
                generation = 1;
            }
        }

        float get(long key, BlockProbe probe, int x, int y, int z) {
            int index = index(key);
            if (stamps[index] == generation) {
                return values[index];
            }
            float value = probe.resistance(x, y, z);
            if (size * 4 >= keys.length * 3) {
                grow();
                index = index(key);
            }
            stamps[index] = generation;
            keys[index] = key;
            values[index] = value;
            size++;
            return value;
        }

        private int index(long key) {
            int index = (int) ((key * 0x9E3779B97F4A7C15L) >>> 32) & mask;
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

            keys = new long[keys.length << 1];
            values = new float[values.length << 1];
            stamps = new int[stamps.length << 1];
            mask = keys.length - 1;
            size = 0;
            generation = oldGeneration + 1;

            for (int i = 0; i < oldStamps.length; i++) {
                if (oldStamps[i] == oldGeneration) {
                    int index = index(oldKeys[i]);
                    stamps[index] = generation;
                    keys[index] = oldKeys[i];
                    values[index] = oldValues[i];
                }
            }
        }
    }

    /** Cria um gerador determinístico com um valor fixo, para os testes. */
    public static FloatSource fixed(float value) {
        return new FixedRandom(value);
    }
}
