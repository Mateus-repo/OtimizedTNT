package io.github.mateusrepo.optimizedtnt.explosion;

/**
 * Parâmetros de uma explosão, já reduzidos às unidades de energia do vanilla.
 *
 * <p>A energia inicial do vanilla é {@code radius * (0.7 + 0.6 * random.nextFloat())} e é
 * sorteada <strong>uma vez por raio</strong> (1352 por explosão). Como a onda não tem raios,
 * guardamos aqui a média por tipo de direção ({@link #axisEnergy()}, {@link #faceEnergy()},
 * {@link #cornerEnergy()}), que em {@code MEAN} são todas iguais à média global.
 */
public final class ExplosionParams {

    private final double centerX;
    private final double centerY;
    private final double centerZ;
    private final float radius;
    private final float axisEnergy;
    private final float faceEnergy;
    private final float cornerEnergy;
    private final Neighborhood neighborhood;
    private final float resistanceFactor;

    public ExplosionParams(
            double centerX,
            double centerY,
            double centerZ,
            float radius,
            float axisEnergy,
            float faceEnergy,
            float cornerEnergy,
            int neighborhood,
            float resistanceFactor) {
        this.centerX = centerX;
        this.centerY = centerY;
        this.centerZ = centerZ;
        this.radius = radius;
        this.axisEnergy = axisEnergy;
        this.faceEnergy = faceEnergy;
        this.cornerEnergy = cornerEnergy;
        this.neighborhood = Neighborhood.of(neighborhood);
        this.resistanceFactor = resistanceFactor;
    }

    /** Cria parâmetros com uma única energia para todas as direções. */
    public static ExplosionParams uniform(
            double centerX, double centerY, double centerZ, float radius, float energy,
            int neighborhood, float resistanceFactor) {
        return new ExplosionParams(centerX, centerY, centerZ, radius,
                energy, energy, energy, neighborhood, resistanceFactor);
    }

    public double centerX() {
        return centerX;
    }

    public double centerY() {
        return centerY;
    }

    public double centerZ() {
        return centerZ;
    }

    public float radius() {
        return radius;
    }

    public Neighborhood neighborhood() {
        return neighborhood;
    }

    public float resistanceFactor() {
        return resistanceFactor;
    }

    /** Energia inicial para um passo do tipo indicado. */
    public float energyFor(int kind) {
        return switch (kind) {
            case Neighborhood.AXIS -> axisEnergy;
            case Neighborhood.FACE -> faceEnergy;
            default -> cornerEnergy;
        };
    }
}
