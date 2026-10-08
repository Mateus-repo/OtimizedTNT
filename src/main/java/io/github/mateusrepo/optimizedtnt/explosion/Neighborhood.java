package io.github.mateusrepo.optimizedtnt.explosion;

/**
 * Tabelas de vizinhança pré-calculadas.
 *
 * <p>Os números vêm do vanilla 26.3. A energia perde {@code 0.225} por amostra de {@code 0.3}
 * de comprimento, ou seja {@code 0.75} por unidade de comprimento — logo um passo em eixos
 * custa {@code 0.75}, uma diagonal de face {@code 0.75 × √2 ≈ 1.0607} e uma de canto
 * {@code 0.75 × √3 ≈ 1.2990}.
 */
public final class Neighborhood {

    /** Tipo de direção: só eixos, diagonal de face, ou diagonal de canto. */
    public static final int AXIS = 0;
    public static final int FACE = 1;
    public static final int CORNER = 2;

    /** Custo de viagem por unidade de comprimento: {@code 0.225 / 0.3}. */
    public static final float TRAVEL_PER_UNIT = 0.75F;

    /** Constante somada à resistência no vanilla: {@code (r + 0.3) × 0.3}. */
    public static final float RESISTANCE_BIAS = 0.3F;

    /** Perda de energia por amostra do vanilla. */
    public static final float STEP_DECAY = 0.225F;

    /** Comprimento de cada amostra do vanilla. */
    public static final float STEP_LENGTH = 0.3F;

    /** Lado da grelha de direções do vanilla. */
    public static final int VANILLA_GRID = 16;

    /** Número de raios do vanilla: {@code 16³ − 14³}. */
    public static final int VANILLA_RAY_COUNT = VANILLA_GRID * VANILLA_GRID * VANILLA_GRID
            - 14 * 14 * 14;

    public static final Neighborhood SIX = build(6);
    public static final Neighborhood EIGHTEEN = build(18);
    public static final Neighborhood TWENTY_SIX = build(26);

    public final int size;
    public final int[] dx;
    public final int[] dy;
    public final int[] dz;
    /** Comprimento euclidiano do passo. */
    public final float[] length;
    /** {@link #AXIS}, {@link #FACE} ou {@link #CORNER}. */
    public final int[] kind;
    /** Custo de viagem do passo: {@link #TRAVEL_PER_UNIT} × {@link #length}. */
    public final float[] travelCost;

    private Neighborhood(int size, int[] dx, int[] dy, int[] dz, float[] length, int[] kind) {
        this.size = size;
        this.dx = dx;
        this.dy = dy;
        this.dz = dz;
        this.length = length;
        this.kind = kind;
        this.travelCost = new float[size];
        for (int i = 0; i < size; i++) {
            this.travelCost[i] = TRAVEL_PER_UNIT * length[i];
        }
    }

    public static Neighborhood of(int size) {
        return switch (size) {
            case 6 -> SIX;
            case 18 -> EIGHTEEN;
            default -> TWENTY_SIX;
        };
    }

    private static Neighborhood build(int size) {
        int[] dx = new int[size];
        int[] dy = new int[size];
        int[] dz = new int[size];
        float[] length = new float[size];
        int[] kind = new int[size];
        int count = 0;

        for (int x = -1; x <= 1; x++) {
            for (int y = -1; y <= 1; y++) {
                for (int z = -1; z <= 1; z++) {
                    if (x == 0 && y == 0 && z == 0) {
                        continue;
                    }
                    int nonZero = (x != 0 ? 1 : 0) + (y != 0 ? 1 : 0) + (z != 0 ? 1 : 0);
                    if (size == 6 && nonZero != 1) {
                        continue;
                    }
                    if (size == 18 && nonZero == 3) {
                        continue;
                    }
                    dx[count] = x;
                    dy[count] = y;
                    dz[count] = z;
                    length[count] = (float) Math.sqrt(nonZero);
                    kind[count] = switch (nonZero) {
                        case 1 -> AXIS;
                        case 2 -> FACE;
                        default -> CORNER;
                    };
                    count++;
                }
            }
        }

        int[] fx = new int[count];
        int[] fy = new int[count];
        int[] fz = new int[count];
        float[] fl = new float[count];
        int[] fk = new int[count];
        System.arraycopy(dx, 0, fx, 0, count);
        System.arraycopy(dy, 0, fy, 0, count);
        System.arraycopy(dz, 0, fz, 0, count);
        System.arraycopy(length, 0, fl, 0, count);
        System.arraycopy(kind, 0, fk, 0, count);
        return new Neighborhood(count, fx, fy, fz, fl, fk);
    }

    /**
     * Percorre as 1352 direções do vanilla pela ordem em que ele as consome do RNG.
     *
     * @param out recebe o tipo de direção de cada raio
     */
    public static void forEachVanillaRayKind(RayKindConsumer out) {
        for (int i = 0; i < VANILLA_GRID; i++) {
            for (int j = 0; j < VANILLA_GRID; j++) {
                for (int k = 0; k < VANILLA_GRID; k++) {
                    if (i != 0 && i != VANILLA_GRID - 1
                            && j != 0 && j != VANILLA_GRID - 1
                            && k != 0 && k != VANILLA_GRID - 1) {
                        continue;
                    }
                    int nonZero = (i != 0 && i != VANILLA_GRID - 1 ? 1 : 0)
                            + (j != 0 && j != VANILLA_GRID - 1 ? 1 : 0)
                            + (k != 0 && k != VANILLA_GRID - 1 ? 1 : 0);
                    out.accept(switch (nonZero) {
                        case 1 -> AXIS;
                        case 2 -> FACE;
                        default -> CORNER;
                    });
                }
            }
        }
    }

    @FunctionalInterface
    public interface RayKindConsumer {
        void accept(int kind);
    }
}
