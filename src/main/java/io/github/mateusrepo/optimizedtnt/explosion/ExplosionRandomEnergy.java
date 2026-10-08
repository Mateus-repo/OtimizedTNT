package io.github.mateusrepo.optimizedtnt.explosion;


/**
 * Reprodutibilidade do arranque de energia.
 *
 * <p>O vanilla chama {@code random.nextFloat()} <strong>1352 vezes por explosão</strong> (uma
 * por raio). Esse consumo é observável — qualquer outro sistema que partilhe o RNG do mundo vê
 * a sequência avançar —, por isso esta classe consome exatamente os mesmos 1352 valores, na
 * mesma ordem, e só depois decide que energia usar:
 *
 * <ul>
 *   <li>{@code MEAN}: uma energia só, a média dos 1352 valores;</li>
 *   <li>{@code PER_DIRECTION}: a média de cada tipo de direção (eixo, diagonal de face,
 *       diagonal de canto), preservando alguma da variação angular do vanilla.</li>
 * </ul>
 *
 * <p>Nenhuma das opções altera a sequência do RNG.
 */
public final class ExplosionRandomEnergy {

    private ExplosionRandomEnergy() {
    }

    /** Fórmula do vanilla: {@code radius * (0.7 + 0.6 * rand)}. */
    public static float energyFor(float radius, float randomValue) {
        return radius * (0.7F + 0.6F * randomValue);
    }

    /** Quantos valores do RNG são consumidos: um por raio. */
    public static int drawCount() {
        return Neighborhood.VANILLA_RAY_COUNT;
    }

    /** Tipo de direção de cada um dos 1352 raios, na ordem em que o vanilla os sorteia. */
    private static final int[] VANILLA_RAY_KINDS = buildRayKinds();

    private static int[] buildRayKinds() {
        int[] kinds = new int[Neighborhood.VANILLA_RAY_COUNT];
        int[] cursor = {0};
        Neighborhood.forEachVanillaRayKind(kind -> kinds[cursor[0]++] = kind);
        if (cursor[0] != kinds.length) {
            throw new IllegalStateException(
                    "contagem de raios do vanilla mudou: " + cursor[0] + " != " + kinds.length);
        }
        return kinds;
    }

    /**
     * Consome {@link #drawCount()} valores do RNG e devolve as energias por tipo de direção.
     *
     * @param perDirection {@code true} para médias por tipo de direção, {@code false} para a
     *                     média global
     */
    public static Energies sample(float radius, FloatSource random, boolean perDirection) {
        float sum = 0.0F;
        float[] sumByKind = new float[3];
        int[] countByKind = new int[3];

        if (perDirection) {
            for (int kind : VANILLA_RAY_KINDS) {
                float value = random.nextFloat();
                sum += value;
                sumByKind[kind] += value;
                countByKind[kind]++;
            }
        } else {
            for (int i = 0; i < Neighborhood.VANILLA_RAY_COUNT; i++) {
                sum += random.nextFloat();
            }
        }

        float mean = sum / Neighborhood.VANILLA_RAY_COUNT;
        if (!perDirection) {
            float energy = energyFor(radius, mean);
            return new Energies(energy, energy, energy);
        }

        float axis = average(sumByKind[Neighborhood.AXIS], countByKind[Neighborhood.AXIS], mean);
        float face = average(sumByKind[Neighborhood.FACE], countByKind[Neighborhood.FACE], mean);
        float corner = average(sumByKind[Neighborhood.CORNER], countByKind[Neighborhood.CORNER], mean);
        return new Energies(energyFor(radius, axis), energyFor(radius, face), energyFor(radius, corner));
    }

    private static float average(float sum, int count, float fallback) {
        return count > 0 ? sum / count : fallback;
    }

    /**
     * As três energias iniciais, por tipo de direção.
     *
     * @param axis   energia inicial dos raios em eixos
     * @param face   energia inicial das diagonais de face
     * @param corner energia inicial das diagonais de canto
     */
    public record Energies(float axis, float face, float corner) {
    }
}
