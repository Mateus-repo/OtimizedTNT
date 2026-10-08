package io.github.mateusrepo.optimizedtnt.explosion;

import java.util.Arrays;

/**
 * Células numa grelha densa indexada por offset ao centro da explosão.
 *
 * <p>É o caminho rápido: um passo da onda passa a ser uma subtração e um índice, contra uma
 * consulta a uma tabela hash. Com vizinhança 26 e raio 8 são ~209 000 relaxamentos por
 * explosão, e é precisamente aí que a onda estava a perder ao vanilla.
 *
 * <p>Não há tabelas de geração para os valores: "desconhecido" é o próprio valor
 * ({@link Float#NaN}, tanto na energia como no custo de resistência), e {@code Arrays.fill} de
 * alguns milhares de {@code float} é da ordem das microssegundos. O registo de blocos já
 * emitidos usa uma marca de geração num {@code byte}, que dá a volta sem percorrer a tabela.
 */
final class DenseExplosionCells implements ExplosionCells {

    /** Teto de células: 2^22 daria 16 MB por array de {@code float}, já é demasiado. */
    private static final long MAX_CELLS = 1L << 22;

    private static final byte MAX_GENERATION = 100;

    private final int side;
    private final int half;
    private final int centerX;
    private final int centerY;
    private final int centerZ;

    private final float[] energy;
    private final float[] charge;
    private final byte[] emitted;
    private byte generation = 1;

    DenseExplosionCells(int half, int centerX, int centerY, int centerZ) {
        this.half = half;
        this.side = half * 2 + 1;
        this.centerX = centerX;
        this.centerY = centerY;
        this.centerZ = centerZ;
        int cells = side * side * side;
        this.energy = new float[cells];
        this.charge = new float[cells];
        this.emitted = new byte[cells];
    }

    /** @return {@code true} se a grelha para este alcance cabe no orçamento de memória. */
    static boolean fits(int half) {
        long side = half * 2L + 1L;
        return side * side * side <= MAX_CELLS;
    }

    int half() {
        return half;
    }

    @Override
    public void begin() {
        Arrays.fill(energy, Float.NaN);
        Arrays.fill(charge, Float.NaN);
        generation++;
        if (generation >= MAX_GENERATION) {
            // Dá a volta: recomeça do 1 e invalida as marcas antigas.
            Arrays.fill(emitted, (byte) 0);
            generation = 1;
        }
    }

    @Override
    public float energy(long packed) {
        return energy[index(packed)];
    }

    @Override
    public boolean improveEnergy(long packed, float value) {
        int index = index(packed);
        float current = energy[index];
        if (Float.isNaN(current) || value > current) {
            energy[index] = value;
            return true;
        }
        return false;
    }

    @Override
    public float charge(long packed) {
        return charge[index(packed)];
    }

    @Override
    public void putCharge(long packed, float value) {
        charge[index(packed)] = value;
    }

    @Override
    public boolean isEmitted(long packed) {
        return emitted[index(packed)] == generation;
    }

    @Override
    public void markEmitted(long packed) {
        emitted[index(packed)] = generation;
    }

    /** Indexa pelo offset ao centro da explosão. */
    private int index(long packed) {
        int dx = ExplosionWavefront.unpackX(packed) - centerX + half;
        int dy = ExplosionWavefront.unpackY(packed) - centerY + half;
        int dz = ExplosionWavefront.unpackZ(packed) - centerZ + half;
        if ((dx | dy | dz) < 0 || dx >= side || dy >= side || dz >= side) {
            throw new IllegalStateException("célula fora da grelha: packed=" + packed
                    + " offset=(" + (ExplosionWavefront.unpackX(packed) - centerX)
                    + "," + (ExplosionWavefront.unpackY(packed) - centerY)
                    + "," + (ExplosionWavefront.unpackZ(packed) - centerZ)
                    + ") half=" + half);
        }
        return (dx * side + dy) * side + dz;
    }
}
