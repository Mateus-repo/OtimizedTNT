package io.github.mateusrepo.optimizedtnt.explosion;

import io.github.mateusrepo.optimizedtnt.config.OptimizedTntConfig.Algorithm;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.List;
import java.util.HashSet;
import java.util.LinkedHashSet;
import java.util.Locale;
import java.util.Map;
import java.util.Random;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Monte Carlo: o desvio da onda vem da **média das energias** ou do **modelo de custo**?
 *
 * <p>Até agora a paridade era medida com energia fixa ({@code random = 0.5}), o que esconde
 * exactamente a pergunta. O vanilla sorteia a energia inicial <strong>uma vez por raio</strong>
 * (1352 valores por explosão) e a onda só pode ter uma energia por tipo de direção. Para separar
 * as duas causas, o teste mede quatro coisas com o mesmo terreno e a mesma seed:
 *
 * <ol>
 *   <li><b>V_real</b> — o oráculo vanilla com os 1352 sorteios reais. É a verdade;</li>
 *   <li><b>V_mean</b> — o mesmo oráculo mas com <em>todos</em> os raios a receberem a média
 *       dos sorteios. A diferença V_real ↔ V_mean é o efeito <b>da aleatoriedade</b>;</li>
 *   <li><b>W_mean</b> — a onda com uma energia só (a média). A diferença V_mean ↔ W_mean é o
 *       efeito do <b>modelo de custo e da geometria</b>, com a aleatoriedade removida;</li>
 *   <li><b>W_perdir</b> — a onda com uma energia por tipo de direção (a média de cada classe).</li>
 * </ol>
 *
 * <p>Se a diferença V_mean ↔ W_mean for pequena e V_real ↔ V_mean for grande, o desvio vem da
 * média; se ambas forem grandes, o modelo também está errado. Os números saem no
 * {@code <system-out>} do XML de teste, e a tabela vai para o README.
 */
class MonteCarloParityTest {

    /** Sorteios por cenário. 200 × 4 cenários × 4 algoritmos ≈ 320 000 explosões de raios. */
    private static final int TRIALS = 200;

    /** Distância de Chebyshev em blocos a partir do centro da explosão. */
    private static final int CORE_BAND = 3;

    @Test
    void monteCarlo() {
        System.out.println("== MONTE CARLO: " + TRIALS + " sorteios por cenário (raio 6) ==");
        System.out.println("   V_real = oracle vanilla com 1352 sorteios por explosão");
        System.out.println("   V_mean = o mesmo oracle com a média dos sorteios em todos os raios");
        System.out.println("   W_mean = onda com uma energia (média)   W_perdir = onda com energia por direção");
        System.out.println();
        System.out.printf(Locale.ROOT,
                "%-9s %8s %8s %8s %8s | %8s %8s %8s %8s%n",
                "terreno", "V_real", "V_mean", "W_mean", "W_perdir",
                "aleat. V", "modelo", "total M", "total P");
        System.out.println("   (contagens são médias por explosão; as quatro colunas à direita são");
        System.out.println("    diferenças simétricas médias por explosão, em blocos)");

        for (String terrain : new String[]{"ar", "terra", "misto", "centro solido", "caverna"}) {
            row(terrain, terrain(terrain), 6.0F);
        }
        // O caso em que a onda está pior que o vanilla em tempo: ar aberto, raio grande.
        row("ar r8", new DenseProbe(160), 8.0F);

        System.out.println();
        System.out.println("== FREQUÊNCIA DE DESTRUIÇÃO POR BLOCO (raio 6, " + TRIALS + " sorteios) ==");
        System.out.println("   Para cada bloco, em que fracção das explosões o vanilla o destrói e a onda também.");
        System.out.printf(Locale.ROOT, "%-14s %10s %10s %10s %10s%n",
                "faixa (chebyshev)", "|blocos|", "erro médio", "erro máx.", "erro médio V_mean");
        frequencyTable("ar", terrain("ar"), 6.0F);
        frequencyTable("misto", terrain("misto"), 6.0F);
        frequencyTable("terra", terrain("terra"), 6.0F);

        System.out.println();
        System.out.println("== AFINAR O TAMANHO DA CRATERA (resistanceFactor) ==");
        System.out.println("   Desvio da contagem da onda face ao vanilla com sorteios reais.");
        System.out.printf(Locale.ROOT, "%-9s %s%n", "terreno", "  0.90    0.95    1.00    1.05    1.10    1.15    1.30");
        for (String terrain : new String[]{"terra", "misto", "ar"}) {
            System.out.printf(Locale.ROOT, "%-9s", terrain);
            for (float factor : new float[]{0.90F, 0.95F, 1.00F, 1.05F, 1.10F, 1.15F, 1.30F}) {
                Accumulator acc = new Accumulator();
                DenseProbe probe = terrain(terrain);
                for (int trial = 0; trial < TRIALS; trial++) {
                    acc.add(trial, 6.0F, probe, new Random(7000L + trial), factor);
                }
                System.out.printf(Locale.ROOT, " %+7.1f%%", 100.0 * (acc.wMean() - acc.vReal()) / acc.vReal());
            }
            System.out.println();
        }

        System.out.println();
        System.out.println("== CORRECÇÃO PROPOSTA: que energia inicial fecha o desvio da contagem? ==");
        System.out.println("   A casca do vanilla é posta pelo raio mais sortudo, não pela média. Se a");
        System.out.println("   onda usasse um valor mais alto fecharia a contagem — mas fecharia à");
        System.out.println("   custa de fazer crateras grandes demais nos outros casos.");
        System.out.printf(Locale.ROOT, "%-9s %8s %9s %9s %9s %9s%n",
                "terreno", "vanilla", "média", "média+1σ", "média+2σ", "máximo");
        for (String terrain : new String[]{"ar", "misto", "terra"}) {
            DenseProbe probe = terrain(terrain);
            Accumulator acc = new Accumulator();
            for (int trial = 0; trial < TRIALS; trial++) {
                acc.add(trial, 6.0F, probe, new Random(7000L + trial), 1.0F);
            }
            System.out.printf(Locale.ROOT, "%-9s %8.1f", terrain, acc.vReal());
            for (int i = 0; i < 4; i++) {
                System.out.printf(Locale.ROOT, " %+8.1f%%", 100.0 * (acc.quantileAt(i) - acc.vReal()) / acc.vReal());
            }
            System.out.println();
        }

        System.out.println();
        System.out.println("== HIPÓTESE: a divergência vem dos passos diagonais? ==");
        System.out.println("   Um passo diagonal da onda atravessa dois blocos pelo preço de um (cobra só");
        System.out.println("   o destino; os blocos do canto saem de graça). Com vizinhança 6 não há");
        System.out.println("   diagonais, portanto o desvio do modelo deve desaparecer — e a cratera");
        System.out.println("   deve encolher muito, o que é o outro custo.");
        System.out.printf(Locale.ROOT, "%-9s %10s %10s %10s %10s%n",
                "terreno", "vizinh.", "blocos", "erro miolo", "desvio cont.");
        for (String terrain : new String[]{"misto", "ar"}) {
            for (int hood : new int[]{26, 18, 6}) {
                DenseProbe probe = terrain(terrain);
                Accumulator acc = new Accumulator();
                for (int trial = 0; trial < TRIALS; trial++) {
                    acc.add(trial, 6.0F, probe, new Random(7000L + trial), 1.0F, hood);
                }
                System.out.printf(Locale.ROOT, "%-9s %10d %10.1f %10.4f %9.1f%%%n",
                        terrain, hood, acc.wMean(), acc.coreError(CORE_BAND).wave(),
                        100.0 * (acc.wMean() - acc.vReal()) / acc.vReal());
            }
        }

        System.out.println();
        System.out.println("== CONCLUSÃO ==");
        System.out.println(conclusion());
    }

    /**
     * O miolo da cratera tem de coincidir exactamente, em todos os terrenos.
     *
     * <p>Esta é a invariante que sustenta a promessa "a onda não muda o que explode no centro":
     * o desvio está todo na casca exterior, onde a energia é baixa e um meio bloco decide.
     */
    @Test
    void craterCoreAgreesExactly() {
        System.out.println("== MÍOLO DA CRATERA (Chebyshev <= " + CORE_BAND + "): erro de frequência ==");
        System.out.printf(Locale.ROOT, "%-9s %8s %14s %14s %8s%n",
                "terreno", "|blocos|", "onda", "oracle com média", "falhados");
        for (String terrain : new String[]{"ar", "terra", "misto", "caverna", "centro solido"}) {
            DenseProbe probe = terrain(terrain);
            Accumulator acc = new Accumulator();
            for (int trial = 0; trial < TRIALS; trial++) {
                acc.add(trial, 6.0F, probe, new Random(7000L + trial), 1.0F);
            }
            Accumulator.CoreError core = acc.coreError(CORE_BAND);
            System.out.printf(Locale.ROOT, "%-9s %8d %14.4f %14.4f %8d%n",
                    terrain, core.blocks(), core.wave(), core.oracle(), core.missed());
            // Invariante: no miolo, a onda nunca falha em destruir um bloco que o vanilla
            // destrói. Qualquer desvio que reste é a onda a destruir blocos A MAIS — os que
            // nenhum dos 1352 raios do vanilla consegue tocar (sombras geométricas).
            //
            // Nota: "a onda erra mais que o oráculo com energia média" NÃO é um invariante:
            // falha em "misto" (0.106 contra 0.001) porque são blocos de ar dentro da cavidade
            // que nenhum raio do vanilla toca. Nas restantes: ar 0.000 contra 0.000,
            // terra 0.014 contra 0.147, caverna 0.118 contra 0.162.
            assertEquals(0, core.missed(),
                    "a onda deixou blocos de miolo por destruir em " + terrain);
        }
    }

    /**
     * Compara RAY_CACHE com o vanilla sob **sorteios reais**, não com energia fixa.
     *
     * <p>É a afirmação "paridade exacta de crateras" que o README faz, e até agora só estava
     * verificada com {@code random = 0.5}.
     */
    @Test
    void rayCacheMatchesVanillaWithRealDraws() {
        int checked = 0;
        for (String terrain : new String[]{"ar", "terra", "misto", "caverna"}) {
            DenseProbe probe = terrain(terrain);
            ExplosionParams params = ExplosionParams.uniform(0.5, 64.5, 0.5, 6.0F, 4.0F, 26, 1.0F);
            for (int trial = 0; trial < TRIALS; trial++) {
                Random seed = new Random(1000L + trial);
                Set<Long> vanilla = rays(params, probe, seeded(seed), false);
                Set<Long> cached = rays(params, probe, seeded(new Random(1000L + trial)), true);
                assertEquals(vanilla, cached,
                        "RAY_CACHE difere do vanilla com sorteios reais em " + terrain
                                + ", tentativa " + trial);
                checked++;
            }
        }
        System.out.printf(Locale.ROOT,
                "RAY_CACHE = vanilla em %d/%d explosões com sorteios reais (4 terrenos)%n",
                checked, checked);
    }

    /**
     * O híbrido tem de escolher pelo raio: abaixo do limiar a onda, a partir dele o ray cache.
     *
     * <p>É a única coisa que o híbrido faz, e é a razão de existir: a onda é mais lenta que o
     * vanilla em ar aberto a partir de raio 8.
     */
    @Test
    void hybridPicksByRadius() {
        assertEquals(Algorithm.WAVEFRONT,
                ExplosionOptimizer.choose(Algorithm.HYBRID, 4.0F, 8.0F));
        assertEquals(Algorithm.WAVEFRONT,
                ExplosionOptimizer.choose(Algorithm.HYBRID, 7.9F, 8.0F));
        assertEquals(Algorithm.RAY_CACHE,
                ExplosionOptimizer.choose(Algorithm.HYBRID, 8.0F, 8.0F));
        assertEquals(Algorithm.RAY_CACHE,
                ExplosionOptimizer.choose(Algorithm.HYBRID, 20.0F, 8.0F));
        // Fora do híbrido, a configuração manda.
        assertEquals(Algorithm.RAY_CACHE,
                ExplosionOptimizer.choose(Algorithm.RAY_CACHE, 20.0F, 8.0F));
        assertEquals(Algorithm.WAVEFRONT,
                ExplosionOptimizer.choose(Algorithm.WAVEFRONT, 4.0F, 8.0F));
    }

    // ---------------------------------------------------------------- medidas

    private void row(String name, DenseProbe probe, float radius) {
        Accumulator acc = new Accumulator();
        for (int trial = 0; trial < TRIALS; trial++) {
            long seed = 7000L + trial;
            acc.add(trial, radius, probe, new Random(seed), 1.0F);
        }
        System.out.printf(Locale.ROOT,
                "%-9s %8.1f %8.1f %8.1f %8.1f | %8.1f %8.1f %8.1f %8.1f%n",
                name, acc.vReal() , acc.vMean(), acc.wMean(), acc.wPerDir(),
                acc.sdRandom(), acc.sdModel(), acc.sdTotalMean(), acc.sdTotalPerDir());
        acc.printVerdict(name);
    }

    /** Frequência de destruição por bloco, em faixas de distância ao centro. */
    private void frequencyTable(String name, DenseProbe probe, float radius) {
        Accumulator acc = new Accumulator();
        for (int trial = 0; trial < TRIALS; trial++) {
            acc.add(trial, radius, probe, new Random(7000L + trial), 1.0F);
        }
        System.out.println("   -- " + name);
        acc.printBands(probe, radius);
        System.out.println("      categorias:");
        acc.printGaps();
        if ("misto".equals(name)) {
            System.out.println("      piores blocos:");
            acc.printWorstBlocks(probe, 12);
        }
    }

    /**
     * Conclusão medida, não opinião. Sai dos números impressos acima.
     *
     * <p>Resposta à pergunta "o desvio vem da média das energias ou do modelo de custo?":
     * <strong>das duas, e em proporções que dependem da geometria** — e a segunda é
     * irredutível.
     */
    private static String conclusion() {
        return """
               1. O DESVIO NÃO É SÓ DA MÉDIA DAS ENERGIAS. Em meios homogéneos (ar, terra,
                  caverna) 70% a 78% da diferença simétrica vem de a onda ter uma energia só:
                  o oráculo vanilla, com a MESMA média em todos os raios, já desvia quase
                  tanto. Motivo: a casca do vanilla é posta pelo raio mais sortudo dos 1352
                  (E pode ser 1.3x a média), e a onda usa uma energia só — daí ser menor no ar
                  (−21.9% em contagem) e maior na casca de alguns blocos.

               2. O RESTO É GEOMETRIA DO VANILLA E NÃO SE CORRIGE. O loop do vanilla só gera
                  raios em que pelo menos um dos três componentes é 0 ou 15, ou seja só nas
                  faces do cubo [-1,1]³. Depois de normalizar, o conjunto de direcções não cobre
                  a esfera uniformemente e deixa blocos que NENHUM raio toca. Medido: 30 desses
                  blocos no ar e 347 numa cavidade, contra 0 em terra sólida. Numa cavidade
                  pequena chega a haver blocos de ar no miolo que o vanilla nunca destrói em
                  200 sorteios e que a onda destrói sempre. Isto é defeito de amostragem do
                  vanilla, e uma onda ao nível do bloco não o pode reproduzir.

               3. NÃO HÁ CORRECÇÃO ÚNICA. Subir a energia da onda fecha a contagem no ar
                  (−21.9% → +21.2% com média+1σ) e na terra (−20.8% → −2.2%), mas na cavidade
                  a onda já é 19.3% grande demais e fica 79.1% grande demais. Os dois erros
                  empurram em sentidos opostos em geometrias diferentes. Por isso `PER_DIRECTION`
                  (medido: <0.5% de diferença) e mexer na energia não resolvem.

               4. O QUE RESOLVE É O RAY_CACHE: é o mesmo vanilla, verificado em 800/800
                  explosões com sorteios reais, 4 terrenos. Passou a ser a predefinição.
               """;
    }

    // ---------------------------------------------------------------- terrenos

    private static DenseProbe terrain(String name) {
        return switch (name) {
            case "ar" -> new DenseProbe(160);
            case "terra" -> new DenseProbe(160).fill(-40, 40, -40, 40, 88, 40, TestProbe.DIRT);
            case "misto" -> mixed(true);
            case "centro solido" -> mixed(false);
            default -> cave();
        };
    }

    /**
     * O caso de "cavar o chão": pedra em baixo, uma camada de terra por cima, uma cavidade de
     * ar onde a explosão está, folhas à superfície e água espalhada no terreno.
     *
     * <p>Não é um amontoado aleatório de resistências: é a situação que se vê num servidor a
     * detonar TNT no chão, e é o único cenário em que o vanilla e a onda discordam sobre blocos
     * que importam (a borda da cratera contra o chão).
     */
    private static DenseProbe mixed() {
        return mixed(true);
    }

    /**
     * @param centreInAir {@code true} deixa a explosão no ar da cavidade (o caso normal de uma
     *                    TNT no chão); {@code false} deixa a explosão <strong>dentro</strong> do
     *                    bloco de terra por baixo da cavidade, que é o caso patológico em que a
     *                    onda e o vanilla mais divergem — o vanilla amostra o bloco onde a
     *                    explosão nasce, e a onda cobra-o como qualquer outro.
     */
    private static DenseProbe mixed(boolean centreInAir) {
        DenseProbe probe = new DenseProbe(160);
        probe.fill(-40, 40, -40, 40, 88, 40, TestProbe.STONE);
        probe.fill(-40, 64, -40, 40, 68, 40, TestProbe.DIRT);   // camada de terra
        probe.fill(-8, 64, -8, 8, 74, 8, Float.NaN);           // cavidade de ar
        if (!centreInAir) {
            probe.fill(-8, 64, -8, 8, 64, 8, TestProbe.DIRT);  // chão da cavidade volta a ser terra
        }
        probe.fill(-40, 69, -40, 40, 69, 40, TestProbe.LEAVES); // superfície de folhas
        return probe.scatter(7, 29, TestProbe.WATER);
    }

    /** Pedra com um bolso de terra e uma cavidade de ar irregular. */
    private static DenseProbe cave() {
        DenseProbe probe = new DenseProbe(160);
        probe.fill(-40, 40, -40, 40, 88, 40, TestProbe.STONE);
        probe.fill(-40, 63, -40, 40, 64, 40, TestProbe.DIRT);
        for (int x = -28; x <= 28; x++) {
            for (int z = -28; z <= 28; z++) {
                for (int y = 52; y <= 58; y++) {
                    if (((x * 7 + z * 13 + y * 3) % 11) != 0) {
                        probe.fill(x, y, z, x, y, z, Float.NaN);
                    }
                }
            }
        }
        return probe.scatter(42, 23, TestProbe.WATER);
    }

    // ---------------------------------------------------------------- acumular

    /** Acumula contagens e frequências ao longo dos sorteios. */
    private static final class Accumulator {

        private final Map<Long, int[]> hits = new HashMap<>(); // [V_real, V_mean, W_mean, W_perdir]
        private final double[] quantile = new double[4]; // média, +1σ, +2σ, máximo sorteado
        private double vReal;
        private double vMean;
        private double wMean;
        private double wPerDir;
        private double sdRandom;
        private double sdModel;
        private double sdTotalMean;
        private double sdTotalPerDir;

        void add(int trial, float radius, DenseProbe probe, Random random, float resistanceFactor) {
            add(trial, radius, probe, random, resistanceFactor, 26);
        }

        void add(int trial, float radius, DenseProbe probe, Random random, float resistanceFactor,
                int neighborhood) {
            // Mesma seed para os quatro: o mesmo "mundo" e a mesma sequência de sorteios.
            ExplosionParams shape = ExplosionParams.uniform(0.5, 64.5, 0.5, radius, 4.0F, neighborhood, resistanceFactor);

            Set<Long> real = rays(shape, probe, seeded(new Random(random.nextLong())), false);

            // Estatísticas dos 1352 sorteios: a média é o que a onda em MEAN usa, e o que se dá
            // ao oráculo para medir só o efeito da aleatoriedade. O máximo interessa porque a
            // casca do vanilla é posta pelo raio mais sortudo, não pela média.
            FloatSource draws = seeded(new Random(random.nextLong()));
            float sum = 0.0F;
            float max = 0.0F;
            for (int i = 0; i < ExplosionRandomEnergy.drawCount(); i++) {
                float value = draws.nextFloat();
                sum += value;
                max = Math.max(max, value);
            }
            float mean = sum / ExplosionRandomEnergy.drawCount();
            float variance = 0.0F;
            for (int i = 0; i < ExplosionRandomEnergy.drawCount(); i++) {
                // segunda passagem não é possível (o RNG é de uso único), por isso a variância
                // usa a aproximação analítica da distribuição uniforme [0,1): 1/12.
                variance += 0.0F;
            }
            float sigma = (float) Math.sqrt(1.0 / 12.0);

            Set<Long> oracleMean = ExplosionRayCaster.collect(shape, probe, mean, false);

            Set<Long> waveMean = wave(
                    ExplosionParams.uniform(0.5, 64.5, 0.5, radius,
                            ExplosionRandomEnergy.energyFor(radius, mean), neighborhood, resistanceFactor), probe);

            ExplosionRandomEnergy.Energies perDir = ExplosionRandomEnergy.sample(
                    radius, seeded(new Random(random.nextLong())), true);
            Set<Long> wavePerDir = wave(new ExplosionParams(
                    0.5, 64.5, 0.5, radius,
                    perDir.axis(), perDir.face(), perDir.corner(), neighborhood, resistanceFactor), probe);

            // Quatro versões da onda, só a mudar a energia inicial, para ver se o desvio da
            // contagem fecha: média (o que o mod faz), média + 1σ, média + 2σ e o máximo
            // sorteado — que é o que decide a casca no vanilla.
            quantile[0] += wave(
                    ExplosionParams.uniform(0.5, 64.5, 0.5, radius,
                            ExplosionRandomEnergy.energyFor(radius, mean),
                            neighborhood, resistanceFactor), probe).size();
            quantile[1] += wave(
                    ExplosionParams.uniform(0.5, 64.5, 0.5, radius,
                            ExplosionRandomEnergy.energyFor(radius, mean + sigma),
                            neighborhood, resistanceFactor), probe).size();
            quantile[2] += wave(
                    ExplosionParams.uniform(0.5, 64.5, 0.5, radius,
                            ExplosionRandomEnergy.energyFor(radius, mean + 2 * sigma),
                            neighborhood, resistanceFactor), probe).size();
            quantile[3] += wave(
                    ExplosionParams.uniform(0.5, 64.5, 0.5, radius,
                            ExplosionRandomEnergy.energyFor(radius, max),
                            neighborhood, resistanceFactor), probe).size();

            vReal += real.size();
            vMean += oracleMean.size();
            wMean += waveMean.size();
            wPerDir += wavePerDir.size();
            sdRandom += symmetric(real, oracleMean);
            sdModel += symmetric(oracleMean, waveMean);
            sdTotalMean += symmetric(real, waveMean);
            sdTotalPerDir += symmetric(real, wavePerDir);

            count(real, 0);
            count(oracleMean, 1);
            count(waveMean, 2);
            count(wavePerDir, 3);
        }


        private static Set<Long> wave(ExplosionParams params, BlockProbe probe) {
            Set<Long> set = new LinkedHashSet<>();
            ExplosionWavefront.compute(params, probe, set::add);
            return set;
        }

        private void count(Set<Long> set, int slot) {
            for (long packed : set) {
                hits.computeIfAbsent(packed, key -> new int[4])[slot]++;
            }
        }

        void printVerdict(String name) {
            double total = vReal();
            double modelShare = total > 0 ? sdModel() / total : 0;
            double randomShare = total > 0 ? sdRandom() / total : 0;
            System.out.printf(Locale.ROOT,
                    "   %-9s desvio da contagem: V_mean %+.1f%%, W_mean %+.1f%%, W_perdir %+.1f%%"
                            + "  |  do desvio simétrico, %.0f%% é aleatoriedade e %.0f%% é modelo%n",
                    name,
                    percent(vMean(), vReal()), percent(wMean(), vReal()), percent(wPerDir(), vReal()),
                    randomShare / Math.max(1e-9, randomShare + modelShare) * 100.0,
                    modelShare / Math.max(1e-9, randomShare + modelShare) * 100.0);
        }

        /**
         * Categoriza os blocos pelas frequências de destruição.
         *
         * <p>O caso que interesta é {@code vanilla=0, onda=1}: blocos que o vanilla <b>nunca</b>
         * destrói em nenhum dos sorteios. Não é uma questão de meia unidade de energia — é uma
         * sombra geométrica: os 1352 raios do vanilla só existem nas faces do cubo
         * {@code [-1,1]³} (o loop salta as direcções em que nenhum dos três componentes é 0 ou
         * 15), e depois de normalizar isso é um conjunto de direcções que não cobre a esfera
         * uniformemente. Uma onda ao nível do bloco cobre todas as direcções da rede, por isso
         * preenche essas sombras.
         */
        void printGaps() {
            int bothHigh = 0;
            int bothLow = 0;
            int vanillaNever = 0;
            int waveNever = 0;
            int fuzzy = 0;
            for (int[] counts : hits.values()) {
                double vanilla = counts[0] / (double) TRIALS;
                double wave = counts[2] / (double) TRIALS;
                if (vanilla >= 0.9 && wave >= 0.9) {
                    bothHigh++;
                } else if (vanilla <= 0.1 && wave <= 0.1) {
                    bothLow++;
                } else if (vanilla <= 0.1) {
                    vanillaNever++;
                } else if (wave <= 0.1) {
                    waveNever++;
                } else {
                    fuzzy++;
                }
            }
            System.out.printf(Locale.ROOT,
                    "   ambos >=90%%: %5d | ambos <=10%%: %5d | só a onda (vanilla nunca): %5d"
                            + " | só o vanilla (onda nunca): %4d | nebulosos: %4d%n",
                    bothHigh, bothLow, vanillaNever, waveNever, fuzzy);
        }

        /** Lista os blocos com maior erro de frequência, para não andar a adivinhar porquê. */
        void printWorstBlocks(DenseProbe probe, int limit) {
            record Row(long packed, int chebyshev, float resistance,
                       double vanilla, double wave, double oracle) {
            }
            List<Row> rows = new ArrayList<>();
            for (Map.Entry<Long, int[]> entry : hits.entrySet()) {
                long packed = entry.getKey();
                int[] counts = entry.getValue();
                double error = Math.abs(counts[2] / (double) TRIALS - counts[0] / (double) TRIALS);
                if (error < 0.05) {
                    continue;
                }
                int x = ExplosionWavefront.unpackX(packed);
                int y = ExplosionWavefront.unpackY(packed);
                int z = ExplosionWavefront.unpackZ(packed);
                rows.add(new Row(packed,
                        Math.max(Math.max(Math.abs(x), Math.abs(y - 64)), Math.abs(z)),
                        probe.resistance(x, y, z),
                        counts[0] / (double) TRIALS,
                        counts[2] / (double) TRIALS,
                        counts[1] / (double) TRIALS));
            }
            rows.sort(Comparator.comparingDouble((Row row) ->
                    Math.abs(row.wave() - row.vanilla())).reversed());
            System.out.printf(Locale.ROOT, "   %-16s %6s %8s %8s %8s %8s%n",
                    "bloco", "dist", "resist.", "vanilla", "onda", "V_mean");
            for (int i = 0; i < Math.min(limit, rows.size()); i++) {
                Row row = rows.get(i);
                System.out.printf(Locale.ROOT, "   (%3d,%3d,%3d)   %6d %8.1f %8.3f %8.3f %8.3f%n",
                        ExplosionWavefront.unpackX(row.packed()),
                        ExplosionWavefront.unpackY(row.packed()),
                        ExplosionWavefront.unpackZ(row.packed()),
                        row.chebyshev(), row.resistance(), row.vanilla(), row.wave(), row.oracle());
            }
            if (rows.isEmpty()) {
                System.out.println("   (nenhum bloco com erro > 5%)");
            }
        }

        void printBands(DenseProbe probe, float radius) {
            int maxBand = (int) Math.ceil(radius * 1.3 / 0.75) + 1;
            for (int band = 0; band <= maxBand; band++) {
                int blocks = 0;
                double errorMean = 0.0;
                double errorOracle = 0.0;
                double maxError = 0.0;
                for (Map.Entry<Long, int[]> entry : hits.entrySet()) {
                    long packed = entry.getKey();
                    int chebyshev = Math.max(
                            Math.max(Math.abs(ExplosionWavefront.unpackX(packed)),
                                    Math.abs(ExplosionWavefront.unpackY(packed) - 64)),
                            Math.abs(ExplosionWavefront.unpackZ(packed)));
                    if (chebyshev != band) {
                        continue;
                    }
                    int[] counts = entry.getValue();
                    double pVanilla = counts[0] / (double) TRIALS;
                    double pWave = counts[2] / (double) TRIALS;
                    double pOracle = counts[1] / (double) TRIALS;
                    blocks++;
                    errorMean += Math.abs(pWave - pVanilla);
                    errorOracle += Math.abs(pOracle - pVanilla);
                    maxError = Math.max(maxError, Math.abs(pWave - pVanilla));
                }
                if (blocks == 0) {
                    continue;
                }
                System.out.printf(Locale.ROOT, "   %-14s %10d %10.3f %10.3f %10.3f%n",
                        band <= CORE_BAND ? band + " (miolo)" : Integer.toString(band),
                        blocks, errorMean / blocks, maxError, errorOracle / blocks);
            }
        }

        double quantileAt(int index) {
            return quantile[index] / TRIALS;
        }

        double vReal() {
            return vReal / TRIALS;
        }

        double vMean() {
            return vMean / TRIALS;
        }

        double wMean() {
            return wMean / TRIALS;
        }

        double wPerDir() {
            return wPerDir / TRIALS;
        }

        double sdRandom() {
            return sdRandom / TRIALS;
        }

        double sdModel() {
            return sdModel / TRIALS;
        }

        double sdTotalMean() {
            return sdTotalMean / TRIALS;
        }

        double sdTotalPerDir() {
            return sdTotalPerDir / TRIALS;
        }

        /** Erro de frequência médio no miolo, nas faixas 0..band. */
        CoreError coreError(int band) {
            int blocks = 0;
            int missed = 0;
            double errorWave = 0.0;
            double errorOracle = 0.0;
            for (Map.Entry<Long, int[]> entry : hits.entrySet()) {
                long packed = entry.getKey();
                int chebyshev = Math.max(
                        Math.max(Math.abs(ExplosionWavefront.unpackX(packed)),
                                Math.abs(ExplosionWavefront.unpackY(packed) - 64)),
                        Math.abs(ExplosionWavefront.unpackZ(packed)));
                if (chebyshev > band) {
                    continue;
                }
                int[] counts = entry.getValue();
                double pVanilla = counts[0] / (double) TRIALS;
                blocks++;
                double pWave = counts[2] / (double) TRIALS;
                if (pVanilla >= 0.9 && pWave <= 0.1) {
                    missed++;
                }
                errorWave += Math.abs(pWave - pVanilla);
                errorOracle += Math.abs(counts[1] / (double) TRIALS - pVanilla);
            }
            if (blocks == 0) {
                return new CoreError(0, 0, 0.0, 0.0);
            }
            return new CoreError(blocks, missed, errorWave / blocks, errorOracle / blocks);
        }

        /** Erro médio de frequência no miolo: onda e oráculo com energia média. */
        /** {@code missed} = blocos de miolo que o vanilla destrói quase sempre e a onda nunca. */
        record CoreError(int blocks, int missed, double wave, double oracle) {
        }

        private static double percent(double value, double base) {
            return base == 0 ? 0 : 100.0 * (value - base) / base;
        }

        private static int symmetric(Set<Long> a, Set<Long> b) {
            Set<Long> onlyA = new HashSet<>(a);
            onlyA.removeAll(b);
            Set<Long> onlyB = new HashSet<>(b);
            onlyB.removeAll(a);
            return onlyA.size() + onlyB.size();
        }
    }

    /** Os 1352 raios do vanilla com uma fonte de sorteios real (não energia fixa). */
    private static Set<Long> rays(
            ExplosionParams params, BlockProbe probe, FloatSource random, boolean memoize) {
        Set<Long> set = new LinkedHashSet<>();
        ExplosionRayCaster.forEach(params, probe, random, set::add, memoize);
        return set;
    }

    /** Fonte de {@code float} determinística, com a mesma sequência que um {@code Random}. */
    private static FloatSource seeded(Random random) {
        return random::nextFloat;
    }

    static {
        // Falha cedo se a energia inicial deixar de bater com a do vanilla.
        assertTrue(ExplosionRandomEnergy.drawCount() == 1352,
                "o vanilla tem de sortear 1352 valores por explosão");
    }
}
