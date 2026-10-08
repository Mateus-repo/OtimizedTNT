package io.github.mateusrepo.optimizedtnt.explosion;

import java.util.Arrays;

/**
 * Onda de blocos (Dijkstra) que substitui os 1352 raios do vanilla.
 *
 * <p>Em vez de disparar raios que se cruzam, expande-se uma frente a partir do centro: cada
 * bloco é lido do mundo <strong>uma vez</strong> e propagado uma vez.
 *
 * <p><b>Porque é que funciona como Dijkstra.</b> A resistência de um bloco é cobrada
 * <em>quando a aresta para ele é relaxada</em>, e não quando o nó sai da fila. Assim a chave
 * da fila é exatamente a energia que vai propagar, o custo de entrar num bloco depende só do
 * bloco (e do comprimento do passo, fixo para cada par vizinho), e a primeira vez que um nó
 * sai da fila é a definitiva. E a fila é um <strong>max-heap</strong>: o Dijkstra tem de
 * extrair o nó com <em>mais</em> energia restante, porque é esse valor que decide até onde a
 * onda chega. Um min-heap extrai o pior primeiro, e esse nó acaba por ser melhorado e
 * processado outra vez — com 26 relaxamentos por cada vez (medido: churn de 5,9 em vez de 1,6).
 *
 * <p>Fidelidade ao vanilla, nas mesmas unidades de energia:
 * <ul>
 *   <li>viagem: {@code 0.75 × L} por passo de comprimento {@code L} (o vanilla perde
 *       {@code 0.225} por amostra de {@code 0.3});</li>
 *   <li>resistência: {@code (r + 0.3) × resistanceFactor} à entrada no bloco (o vanilla
 *       subtrai {@code (r + 0.3) × 0.3} por amostra de {@code 0.3}, e um bloco é atravessado
 *       por ~3.3 amostras, ou seja {@code (r + 0.3)} por bloco);</li>
 *   <li>meio passo de folga: o vanilla não amostra o centro do bloco mas a face por onde o raio
 *       entra, meio passo mais perto, e regista o bloco se ainda houver energia nessa face;</li>
 *   <li>primeira amostra: num bloco muito resistente o vanilla destrói-o logo à primeira
 *       amostra, mesmo sem conseguir atravessá-lo — por isso "destruir" e "propagar" são
 *       testes diferentes.</li>
 * </ul>
 *
 * <p><strong>Desvios aceites:</strong> a onda chega a blocos "diagonalmente acessíveis" por onde
 * nenhum raio passa exactamente e não atravessa diagonais isoladas (o vanilla também não);
 * numa diagonal a resistência é cobrada uma vez por bloco em vez de ser repartida pelos
 * blocos que o passo atravessa. Medido: em ar a cratera é 2–8% mais pequena, em terra e
 * caverna 10–23% maior, em pedra pode ser muito menor. O desvio é simétrico (o conjunto é
 * subconjunto ou superconjunto, não fragmentado).
 */
public final class ExplosionWavefront {

    /** Capacidade inicial da fila; cresce se for preciso. */
    private static final int INITIAL_CAPACITY = 1024;

    private static final ThreadLocal<Workspace> WORKSPACE = ThreadLocal.withInitial(Workspace::new);

    private ExplosionWavefront() {
    }

    /**
     * Calcula os blocos afetados.
     *
     * @return resultado com a quantidade de blocos registados e de processamentos da fila
     */
    public static Result compute(ExplosionParams params, BlockProbe probe, ExplosionSink sink) {
        Neighborhood hood = params.neighborhood();
        float factor = params.resistanceFactor();
        float axisCap = params.energyFor(Neighborhood.AXIS);
        float faceCap = params.energyFor(Neighborhood.FACE);
        float cornerCap = params.energyFor(Neighborhood.CORNER);

        int centerX = floor(params.centerX());
        int centerY = floor(params.centerY());
        int centerZ = floor(params.centerZ());

        Workspace ws = WORKSPACE.get();
        ws.begin(Math.max(axisCap, Math.max(faceCap, cornerCap)), centerX, centerY, centerZ);

        ExplosionCells cells = ws.cells;
        long center = pack(centerX, centerY, centerZ);

        // O bloco central é cobrado como qualquer outro (o raio do vanilla também precisa de
        // ~3.3 amostras para o atravessar); só o registo usa a primeira amostra.
        float centerCharge = chargeOf(probe, cells, center, centerX, centerY, centerZ);
        if (Float.isNaN(centerCharge)) {
            return new Result(0, 0); // fora do mundo
        }
        float centerEnergy = axisCap - centerCharge * factor;
        cells.improveEnergy(center, centerEnergy);
        ws.push(center, centerEnergy, 0.0F);

        int accepted = 0;
        while (ws.size > 0) {
            ws.pop();
            long packed = ws.position;
            float energy = ws.energy;
            float slack = ws.slack;

            if (energy < cells.energy(packed)) {
                continue; // obsoleto: entretanto chegou energia melhor
            }

            int x = unpackX(packed);
            int y = unpackY(packed);
            int z = unpackZ(packed);

            // O vanilla não precisa de atravessar um bloco para o destruir: basta que o raio
            // tenha energia na primeira amostra dentro dele. À entrada cobramos a resistência
            // do bloco inteiro (é o que decide a propagação), mas no registo só descontamos a
            // primeira amostra, como o vanilla.
            float stored = cells.charge(packed);
            float firstSample = energy + slack;
            if (!Float.isNaN(stored)) {
                firstSample += stored * (1.0F - Neighborhood.STEP_LENGTH);
            }
            // O vanilla devolve um Set, por isso nunca repete posições; a lista não repete por
            // si só, e um bloco nunca pode ser destruído duas vezes (drops duplicados).
            if (firstSample > 0.0F
                    && !cells.isEmitted(packed)
                    && probe.shouldExplode(x, y, z, firstSample)) {
                cells.markEmitted(packed);
                sink.accept(packed);
                accepted++;
            }

            if (energy <= 0.0F) {
                continue; // sem energia para propagar
            }

            for (int i = 0; i < hood.size; i++) {
                float next = energy - hood.travelCost[i];

                float cap = switch (hood.kind[i]) {
                    case Neighborhood.AXIS -> axisCap;
                    case Neighborhood.FACE -> faceCap;
                    default -> cornerCap;
                };
                if (next > cap) {
                    next = cap; // um raio daquela categoria não passa da sua energia inicial
                }

                float neighborSlack = hood.travelCost[i] * 0.5F;
                if (next + neighborSlack <= 0.0F) {
                    continue; // o raio nem chegaria a tocar neste bloco
                }

                int nx = x + hood.dx[i];
                int ny = y + hood.dy[i];
                int nz = z + hood.dz[i];
                long neighbor = pack(nx, ny, nz);

                // A resistência só pode baixar a energia, portanto se já há melhor não vale a
                // pena sequer ler o bloco.
                float known = cells.energy(neighbor);
                if (!Float.isNaN(known) && next <= known) {
                    continue;
                }

                float resistanceCharge =
                        chargeOf(probe, cells, neighbor, nx, ny, nz);
                if (Float.isNaN(resistanceCharge)) {
                    continue; // fora dos limites do mundo
                }
                // Destruir o bloco só exige energia na primeira amostra dentro dele; é preciso
                // conseguir atravessá-lo para o propagar. Numa parede de pedra o vanilla
                // destrói o primeiro bloco mesmo sem chegar ao segundo.
                if (next + neighborSlack + resistanceCharge * (1.0F - Neighborhood.STEP_LENGTH) <= 0.0F) {
                    continue;
                }
                next -= resistanceCharge * factor;

                if (cells.improveEnergy(neighbor, next)) {
                    ws.push(neighbor, next, neighborSlack);
                }
            }
        }
        return new Result(accepted, ws.processed);
    }

    /**
     * Meia-extensão da grelha que cobre o alcance máximo possível.
     *
     * <p>Cada passo custa pelo menos {@link Neighborhood#TRAVEL_PER_UNIT}, logo o número de
     * passos a partir do centro é no máximo {@code energia / 0.75} e cada passo move no máximo
     * uma célula por eixo. Duas células de margem cobrem a folga de meio passo.
     */
    private static int halfFor(float energy) {
        return (int) Math.ceil(energy / Neighborhood.TRAVEL_PER_UNIT) + 2;
    }

    /** Escolhe grelha densa ou tabelas hash conforme o alcance cabe em memória. */
    private static ExplosionCells cellsFor(float energy, int centerX, int centerY, int centerZ) {
        int half = halfFor(energy);
        if (DenseExplosionCells.fits(half)) {
            return new DenseExplosionCells(half, centerX, centerY, centerZ);
        }
        return new HashExplosionCells();
    }

    /**
     * Lê o custo de resistência de uma célula, uma vez por explosão.
     *
     * @return {@code (r + 0.3)}, {@code 0} para ar, ou {@link Float#NaN} fora do mundo
     */
    private static float chargeOf(
            BlockProbe probe, ExplosionCells cells, long packed, int x, int y, int z) {
        float cached = cells.charge(packed);
        if (!Float.isNaN(cached)) {
            return cached;
        }
        if (!probe.inBounds(x, y, z)) {
            cells.putCharge(packed, Float.NaN);
            return Float.NaN;
        }
        float resistance = probe.resistance(x, y, z);
        float charge = Float.isNaN(resistance)
                ? 0.0F
                : resistance + Neighborhood.RESISTANCE_BIAS;
        cells.putCharge(packed, charge);
        return charge;
    }

    /**
     * Resultado de uma explosão.
     *
     * @param blocks     blocos registados (o que o vanilla devolveria num {@code Set})
     * @param processed  nós que saíram da fila, incluindo entradas obsoletas. Com a ordem
     *                   correcta do Dijkstra é próximo de {@code blocks}; muito acima disso
     *                   significa que a fila está a extrair pela ordem errada.
     */
    public record Result(int blocks, int processed) {

        /** Processamentos por bloco registado. 1,0 é o ideal. */
        public float churn() {
            return blocks == 0 ? 0.0F : processed / (float) blocks;
        }
    }

    // --- Empacotamento de posições, no mesmo formato de BlockPos.asLong ---

    public static long pack(int x, int y, int z) {
        return ((long) x & 0x3FFFFFFL) << 38
                | ((long) z & 0x3FFFFFFL) << 12
                | ((long) y & 0xFFFL);
    }

    public static int unpackX(long packed) {
        return (int) (packed >> 38);
    }

    public static int unpackY(long packed) {
        return (int) (packed << 52 >> 52);
    }

    public static int unpackZ(long packed) {
        return (int) (packed << 26 >> 38);
    }

    /** {@code Math.floor} para doubles, sem alocar. */
    private static int floor(double value) {
        int truncated = (int) value;
        return value < truncated ? truncated - 1 : truncated;
    }

    /** Fila de prioridade binária sobre arrays primitivos, sem boxing. */
    private static final class Workspace {

        long[] positions = new long[INITIAL_CAPACITY];
        float[] energies = new float[INITIAL_CAPACITY];
        float[] slacks = new float[INITIAL_CAPACITY];
        int size;
        /** Nós que saíram da fila nesta explosão (inclui entradas obsoletas). */
        int processed;

        long position;
        float energy;
        float slack;

        ExplosionCells cells;
        private int cellsHalf = -1;
        private int cellsCenterX;
        private int cellsCenterY;
        private int cellsCenterZ;

        /** Recomeça a explosão, reutilizando a grelha se alcance e centro não mudaram. */
        void begin(float energy, int centerX, int centerY, int centerZ) {
            size = 0;
            processed = 0;
            int half = halfFor(energy);
            boolean same = cells instanceof DenseExplosionCells dense
                    && dense.half() == half
                    && centerX == cellsCenterX && centerY == cellsCenterY && centerZ == cellsCenterZ;
            if (!same) {
                cells = cellsFor(energy, centerX, centerY, centerZ);
                cellsHalf = half;
                cellsCenterX = centerX;
                cellsCenterY = centerY;
                cellsCenterZ = centerZ;
            }
            cells.begin();
        }

        void push(long packed, float energy, float slack) {
            if (size == positions.length) {
                int grown = size * 2;
                positions = Arrays.copyOf(positions, grown);
                energies = Arrays.copyOf(energies, grown);
                slacks = Arrays.copyOf(slacks, grown);
            }
            positions[size] = packed;
            energies[size] = energy;
            slacks[size] = slack;
            siftUp(size);
            size++;
        }

        void pop() {
            processed++;
            position = positions[0];
            energy = energies[0];
            slack = slacks[0];
            size--;
            if (size > 0) {
                positions[0] = positions[size];
                energies[0] = energies[size];
                slacks[0] = slacks[size];
                siftDown(0);
            }
        }

        /** MAX-heap: o Dijkstra extrai o nó com mais energia restante. */
        private void siftUp(int index) {
            while (index > 0) {
                int parent = (index - 1) >>> 1;
                if (energies[parent] >= energies[index]) {
                    break;
                }
                swap(parent, index);
                index = parent;
            }
        }

        private void siftDown(int index) {
            while (true) {
                int left = index * 2 + 1;
                int right = left + 1;
                int largest = index;
                if (left < size && energies[left] > energies[largest]) {
                    largest = left;
                }
                if (right < size && energies[right] > energies[largest]) {
                    largest = right;
                }
                if (largest == index) {
                    return;
                }
                swap(largest, index);
                index = largest;
            }
        }

        private void swap(int a, int b) {
            long p = positions[a];
            positions[a] = positions[b];
            positions[b] = p;
            float e = energies[a];
            energies[a] = energies[b];
            energies[b] = e;
            float s = slacks[a];
            slacks[a] = slacks[b];
            slacks[b] = s;
        }
    }
}
