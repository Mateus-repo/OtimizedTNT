package io.github.mateusrepo.optimizedtnt.explosion;

import java.util.Arrays;

/**
 * Onda de blocos (Dijkstra) que substitui os 1352 raios do vanilla.
 *
 * <p>Em vez de disparar raios que se cruzam e avaliarem o mesmo bloco dezenas de vezes,
 * expande-se uma frente a partir do centro. Cada bloco é lido do mundo <strong>uma única
 * vez</strong> e propagado uma única vez.
 *
 * <p><b>Porque é que funciona como Dijkstra.</b> A resistência de um bloco é cobrada
 * <em>quando a aresta para ele é relaxada</em>, e não quando o nó sai da fila. Assim a chave
 * da fila é exatamente a energia que vai propagar, o custo de entrar num bloco depende só do
 * bloco (e do comprimento do passo, que é fixo para cada par vizinho), e a primeira vez que um
 * nó sai da fila é a definitiva. Se a resistência fosse cobrada à saída da fila, um nó com
 * muita resistência sairia cedo demais e voltaria a ser processado quando aparecesse um
 * caminho melhor — o mesmo bloco seria lido duas ou três vezes.
 *
 * <p>Fidelidade ao vanilla, nas mesmas unidades de energia:
 * <ul>
 *   <li>viagem: {@code 0.75 × L} por passo de comprimento {@code L} (o vanilla perde
 *       {@code 0.225} por amostra de {@code 0.3});</li>
 *   <li>resistência: {@code (r + 0.3) × resistanceFactor} à entrada no bloco (o vanilla subtrai
 *       {@code (r + 0.3) × 0.3} por amostra de {@code 0.3}, e um bloco é atravessado por
 *       ~3.3 amostras, ou seja {@code (r + 0.3)} por bloco);</li>
 *   <li>meio passo de folga: o vanilla não amostra o centro do bloco mas a face por onde o raio
 *       entra, meio passo mais perto, por isso o bloco também é registado se ainda houver
 *       energia nessa face;</li>
 *   <li>registo: só com energia positiva e {@code shouldExplode} a aceitar, como no vanilla,
 *       incluindo o bloco central.</li>
 * </ul>
 *
 * <p><strong>Desvios aceites:</strong> a onda chega a blocos "diagonalmente acessíveis" por onde
 * nenhum raio passa exatamente, e não atravessa diagonais isoladas (o vanilla também não);
 * numa diagonal a resistência é cobrada uma vez por bloco em vez de ser repartida pelos
 * blocos que o passo atravessa, o que encolhe um pouco a cratera em terreno resistente. Por
 * isso o formato pode divergir nas bordas e em obstáculos finos — e {@code resistanceFactor}
 * existe para afinar isso.
 */
public final class ExplosionWavefront {

    /** Capacidade inicial dos arrays de trabalho; crescem se for preciso. */
    private static final int INITIAL_CAPACITY = 4096;

    /** Valor sentinela que marca "fora dos limites do mundo". */
    private static final float OUT_OF_BOUNDS = Float.POSITIVE_INFINITY;

    /** Valor sentinela que marca "chave ausente" nos mapas de {@code float}. */
    private static final float ABSENT = Float.NEGATIVE_INFINITY;

    private static final ThreadLocal<Workspace> WORKSPACE = ThreadLocal.withInitial(Workspace::new);

    private ExplosionWavefront() {
    }

    /**
     * Calcula os blocos afetados.
     *
     * @return quantidade de blocos registados
     */
    public static int compute(ExplosionParams params, BlockProbe probe, ExplosionSink sink) {
        Neighborhood hood = params.neighborhood();
        float factor = params.resistanceFactor();
        float axisCap = params.energyFor(Neighborhood.AXIS);
        float faceCap = params.energyFor(Neighborhood.FACE);
        float cornerCap = params.energyFor(Neighborhood.CORNER);

        Workspace ws = WORKSPACE.get();
        ws.begin();

        int centerX = floor(params.centerX());
        int centerY = floor(params.centerY());
        int centerZ = floor(params.centerZ());
        long center = pack(centerX, centerY, centerZ);

        // O bloco central é cobrado como qualquer outro (o raio do vanilla também precisa de
        // ~3.3 amostras para o atravessar); só o registo usa a primeira amostra.
        float centerResistance = ws.resistance(probe, center, centerX, centerY, centerZ);
        if (centerResistance == OUT_OF_BOUNDS) {
            return 0;
        }
        float centerEnergy = axisCap - charge(centerResistance, 1.0F, factor);
        ws.distances.put(center, centerEnergy);
        ws.push(center, centerEnergy, 0.0F);

        int accepted = 0;
        while (ws.size > 0) {
            ws.pop();
            long packed = ws.position;
            float energy = ws.energy;
            float slack = ws.slack;

            if (energy < ws.distances.get(packed)) {
                continue; // obsoleto: entretanto chegou energia melhor
            }

            int x = unpackX(packed);
            int y = unpackY(packed);
            int z = unpackZ(packed);

            // O vanilla não precisa de atravessar um bloco para o destruir: basta que o raio
            // tenha energia na primeira amostra dentro dele. Entrada e registo usam portanto
            // casos diferentes — à entrada cobramos a resistência do bloco inteiro (é o que
            // decide a propagação), mas no registo só descontamos a primeira amostra, como o
            // vanilla. A diferença devolvida aqui é essa.
            float stored = ws.resistances.get(packed);
            float firstSample = energy + slack;
            if (stored != ABSENT && !Float.isNaN(stored)) {
                firstSample += (stored + Neighborhood.RESISTANCE_BIAS)
                        * (1.0F - Neighborhood.STEP_LENGTH) * factor;
            }
            // O vanilla devolve um Set, por isso nunca repete posições. A onda trabalha com
            // uma lista e, sem este guarda, um nó melhorado depois de sair da fila apareceria
            // duas vezes — o que faria o mesmo bloco ser destruído duas vezes (drops
            // duplicados) e falsearia as contagens.
            if (firstSample > 0.0F
                    && !ws.emitted.contains(packed)
                    && probe.shouldExplode(x, y, z, firstSample)) {
                ws.emitted.put(packed, 1.0F);
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

                long neighbor = pack(x + hood.dx[i], y + hood.dy[i], z + hood.dz[i]);
                // A resistência só pode baixar a energia, portanto se já há melhor não vale a
                // pena sequer ler o bloco.
                if (next <= ws.distances.get(neighbor)) {
                    continue;
                }

                int nx = x + hood.dx[i];
                int ny = y + hood.dy[i];
                int nz = z + hood.dz[i];
                float resistance = ws.resistance(probe, neighbor, nx, ny, nz);
                if (resistance == OUT_OF_BOUNDS) {
                    continue;
                }

                // Destruir o bloco só exige energia na primeira amostra dentro dele; é
                // preciso conseguir atravessá-lo para o propagar. Numa parede de pedra o
                // vanilla destrói o primeiro bloco mesmo sem chegar ao segundo.
                if (next + neighborSlack + charge(resistance, 0.7F, factor) <= 0.0F) {
                    continue;
                }
                next -= charge(resistance, 1.0F, factor);

                if (next > ws.distances.get(neighbor)) {
                    ws.distances.put(neighbor, next);
                    ws.push(neighbor, next, neighborSlack);
                }
            }
        }
        return accepted;
    }

    /** Custo de resistência ao atravessar {@code length} de um bloco com resistência dada. */
    private static float charge(float resistance, float length, float factor) {
        if (Float.isNaN(resistance)) {
            return 0.0F; // ar / fluido vazio: o vanilla devolve Optional.empty()
        }
        return (resistance + Neighborhood.RESISTANCE_BIAS) * length * factor;
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

    /**
     * Arrays de trabalho reutilizados entre explosões, por thread.
     *
     * <p>A fila é um min-heap binário sobre arrays primitivos (sem boxing) e os dois mapas
     * usam contador de geração, para não ser preciso limpar as tabelas entre explosões.
     */
    private static final class Workspace {

        long[] positions = new long[INITIAL_CAPACITY];
        float[] energies = new float[INITIAL_CAPACITY];
        float[] slacks = new float[INITIAL_CAPACITY];
        int size;

        long position;
        float energy;
        float slack;

        final DistanceMap distances = new DistanceMap(INITIAL_CAPACITY);
        final FloatMap resistances = new FloatMap(INITIAL_CAPACITY);
        /** Posições já registadas no sink, para a lista não ter repetidos. */
        final FloatMap emitted = new FloatMap(INITIAL_CAPACITY);

        void begin() {
            size = 0;
            distances.nextGeneration();
            resistances.nextGeneration();
            emitted.nextGeneration();
        }

        /**
         * Lê a resistência de uma posição uma única vez por explosão.
         *
         * <p>Usa {@code -Infinity} como "chave ausente" porque o valor guardável {@code NaN}
         * significa "ar" e é perfectamente válido.
         */
        float resistance(BlockProbe probe, long packed, int x, int y, int z) {
            float cached = resistances.get(packed);
            if (cached != ABSENT) {
                return cached;
            }
            if (!probe.inBounds(x, y, z)) {
                resistances.put(packed, OUT_OF_BOUNDS);
                return OUT_OF_BOUNDS;
            }
            float value = probe.resistance(x, y, z);
            resistances.put(packed, value);
            return value;
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

        private void siftUp(int index) {
            while (index > 0) {
                int parent = (index - 1) >>> 1;
                if (energies[parent] <= energies[index]) {
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
                int smallest = index;
                if (left < size && energies[left] < energies[smallest]) {
                    smallest = left;
                }
                if (right < size && energies[right] < energies[smallest]) {
                    smallest = right;
                }
                if (smallest == index) {
                    return;
                }
                swap(smallest, index);
                index = smallest;
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

    /**
     * Mapa aberto {@code long → float} com contador de geração.
     *
     * <p>Escrito à mão em vez de {@code Long2FloatOpenHashMap} para que o núcleo não dependa de
     * nada fora de {@code java.*} e se possa testar sem o Minecraft no classpath. O valor
     * {@link Float#NaN} significa "chave ausente".
     */
    private static final class DistanceMap {

        private long[] keys;
        private float[] values;
        private int[] stamps;
        private int mask;
        private int generation;
        private int size;
        private final int threshold;

        DistanceMap(int expected) {
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

        /**
         * @return a energia guardada, ou {@link Float#NEGATIVE_INFINITY} se a chave não existe.
         *         Nunca {@code NaN}: em Java qualquer comparação com {@code NaN} é {@code false}
         *         e o Dijkstra deixava de funcionar.
         */
        float get(long key) {
            int index = index(key);
            return stamps[index] == generation ? values[index] : ABSENT;
        }

        void put(long key, float value) {
            int index = index(key);
            if (stamps[index] != generation) {
                stamps[index] = generation;
                size++;
            }
            keys[index] = key;
            values[index] = value;
            if (size > threshold) {
                grow();
            }
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

    /**
     * Mapa aberto {@code long → float} para resistências, também com contador de geração.
     *
     * <p>Guarda {@link Float#NaN} como valor válido (ar) e {@link Float#POSITIVE_INFINITY}
     * como "fora do mundo", por isso a ausência é marcada por {@link Float#NEGATIVE_INFINITY},
     * que nunca é um valor guardável.
     */
    private static final class FloatMap {

        private long[] keys;
        private float[] values;
        private int[] stamps;
        private int mask;
        private int generation;
        private int size;

        FloatMap(int expected) {
            int capacity = tableSizeFor(expected);
            keys = new long[capacity];
            values = new float[capacity];
            stamps = new int[capacity];
            mask = capacity - 1;
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

        /** @return o valor guardado, ou {@link Float#NEGATIVE_INFINITY} se a chave não existe. */
        float get(long key) {
            int index = index(key);
            return stamps[index] == generation ? values[index] : ABSENT;
        }

        /** @return {@code true} se a chave existir na geração atual. */
        boolean contains(long key) {
            return stamps[index(key)] == generation;
        }

        void put(long key, float value) {
            int index = index(key);
            if (stamps[index] == generation) {
                values[index] = value;
                return;
            }
            // Cresce antes de escrever: crescer depois deixaria esta chave marcada como
            // ocupada com o conteúdo velho, e o rehash passá-la a nonsense.
            if (size * 4 >= keys.length * 3) {
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
            // Uma geração diferente da antiga, senão as chaves reinseridas seriam vistas como
            // pertencendo à geração antiga e portanto ausentes.
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
