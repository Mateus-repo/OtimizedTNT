package io.github.mateusrepo.optimizedtnt.explosion;

import org.junit.jupiter.api.Test;

import java.util.HashSet;
import java.util.Locale;
import java.util.Random;
import java.util.Set;

/**
 * Harness de microbenchmark do núcleo, sem Minecraft.
 *
 * <p>Não substitui o JMH (não queremos mais dependências), mas mede o que interessa para
 * escolher otimizações: blocos registados, processamentos da fila (o "churn", que é a
 * assinatura de uma fila de prioridade com a ordem errada), leituras de bloco e tempo por
 * explosão com aquecimento.
 *
 * <p>Imprime os resultados em vez de os exigir, porque os tempos absolutos dependem da
 * máquina. Os testes de paridade é que são assertions.
 */
class WavefrontBenchmark {

    private static final float FIXED_RANDOM = 0.5F;

    /**
     * Sonda densa (array 3D), sem boxing.
     *
     * <p>A {@link TestProbe} usa {@code HashMap<Long, Float>} e o boxing dela mascara
     * completamente o custo da onda — o benchmark media o mapa, não o algoritmo.
     */
    private static ExplosionParams params(float radius, int neighborhood) {
        float energy = ExplosionRandomEnergy.energyFor(radius, FIXED_RANDOM);
        return ExplosionParams.uniform(0.5, 64.5, 0.5, radius, energy, neighborhood, 1.0F);
    }

    /** Terreno com cavernas: pedra com um bolso de terra e uma cavidade de ar. */
    private static DenseProbe cave() {
        DenseProbe probe = new DenseProbe(160);
        probe.fill(-40, 40, -40, 40, 88, 40, TestProbe.STONE);
        probe.fill(-40, 63, -40, 40, 64, 40, TestProbe.DIRT);
        // cavidade irregular
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

    @Test
    void measureWavefront() {
        System.out.println("== ONDA ==");
        System.out.printf(Locale.ROOT, "%-10s %-5s %-8s %8s %8s %7s %8s %10s%n",
                "terreno", "raio", "vizinh.", "blocos", "process.", "churn", "leituras", "ns/expl.");
        measure("ar", new DenseProbe(160));
        measure("terra", new DenseProbe(160).fill(-40, 40, -40, 40, 88, 40, TestProbe.DIRT));
        measure("pedra", new DenseProbe(160).fill(-40, 40, -40, 40, 88, 40, TestProbe.STONE));
        measure("caverna", cave());
    }

    private void measure(String terrain, DenseProbe probe) {
        for (float radius : new float[]{4.0F, 6.0F, 8.0F}) {
            for (int neighborhood : new int[]{26, 6}) {
                ExplosionParams p = params(radius, neighborhood);

                // Aquecimento: JIT.
                for (int i = 0; i < 200; i++) {
                    run(p, probe);
                }

                int repetitions = 200;
                probe.resetReads();
                int blocks = 0;
                int processed = 0;
                long start = System.nanoTime();
                for (int i = 0; i < repetitions; i++) {
                    ExplosionWavefront.Result result = run(p, probe);
                    blocks = result.blocks();
                    processed = result.processed();
                }
                long elapsed = System.nanoTime() - start;

                System.out.printf(Locale.ROOT, "%-10s %-5.0f %-8d %8d %8d %7.2f %8.0f %10.0f%n",
                        terrain, radius, neighborhood, blocks, processed,
                        processed / (float) Math.max(1, blocks),
                        probe.reads() / (double) repetitions,
                        elapsed / (double) repetitions);
            }
        }
    }

    /**
     * Mede os três algoritmos lado a lado, que é a comparação que interessa para escolher o
     * default.
     */
    @Test
    void measureAllThree() {
        System.out.println("== ONDA vs VANILLA vs RAY_CACHE (ns/explosao) ==");
        System.out.printf(Locale.ROOT, "%-10s %-5s %10s %10s %10s %8s %8s%n",
                "terreno", "raio", "vanilla", "ray_cache", "onda", "onda/van", "cache/van");
        for (String terrain : new String[]{"ar", "terra", "pedra", "caverna"}) {
            DenseProbe probe = switch (terrain) {
                case "ar" -> new DenseProbe(160);
                case "terra" -> new DenseProbe(160).fill(-40, 40, -40, 40, 88, 40, TestProbe.DIRT);
                case "pedra" -> new DenseProbe(160).fill(-40, 40, -40, 40, 88, 40, TestProbe.STONE);
                default -> cave();
            };
            for (float radius : new float[]{4.0F, 6.0F, 8.0F}) {
                ExplosionParams p = params(radius, 26);
                double vanilla = time(() -> ExplosionRayCaster.collect(p, probe, FIXED_RANDOM, false), 20);
                double cache = time(() -> ExplosionRayCaster.collect(p, probe, FIXED_RANDOM, true), 20);
                double wave = time(() -> run(p, probe), 200);
                System.out.printf(Locale.ROOT, "%-10s %-5.0f %10.0f %10.0f %10.0f %8.2f %8.2f%n",
                        terrain, radius, vanilla, cache, wave,
                        vanilla / Math.max(1, wave), vanilla / Math.max(1, cache));
            }
        }
    }

    /**
     * Tempo por operação, em nanossegundos.
     *
     * <p>Aquecer a variante antes de a medir (cada uma com o seu próprio aquecimento, senão a
     * primeira fica a pagar a JIT) e ficar com o <strong>mínimo</strong> de várias rondas: a
     * média é puxada para baixo por pausas do GC e pelo ruído do sistema, e a mínimo é o
     * estimador mais estável para microbenchmarks sem JMH.
     */
    private static double time(Runnable action, int repetitions) {
        for (int i = 0; i < repetitions * 5; i++) {
            action.run();
        }
        double best = Double.MAX_VALUE;
        for (int round = 0; round < 5; round++) {
            long start = System.nanoTime();
            for (int i = 0; i < repetitions; i++) {
                action.run();
            }
            double average = (System.nanoTime() - start) / (double) repetitions;
            best = Math.min(best, average);
        }
        return best;
    }

    @Test
    void measureVanillaForReference() {
        System.out.println("== VANILLA (referencia) ==");
        System.out.printf(Locale.ROOT, "%-10s %-5s %8s %14s%n", "terreno", "raio", "blocos", "ns/expl.");
        for (String terrain : new String[]{"ar", "terra", "caverna"}) {
            DenseProbe probe = switch (terrain) {
                case "ar" -> new DenseProbe(160);
                case "terra" -> new DenseProbe(160).fill(-40, 40, -40, 40, 88, 40, TestProbe.DIRT);
                default -> cave();
            };
            for (float radius : new float[]{4.0F, 6.0F, 8.0F}) {
                ExplosionParams p = params(radius, 26);
                for (int i = 0; i < 10; i++) {
                    ExplosionRayCaster.collect(p, probe, FIXED_RANDOM, false);
                }
                int repetitions = 20;
                java.util.Set<Long> blocks = new java.util.HashSet<>();
                long start = System.nanoTime();
                for (int i = 0; i < repetitions; i++) {
                    blocks = ExplosionRayCaster.collect(p, probe, FIXED_RANDOM, false);
                }
                long elapsed = System.nanoTime() - start;
                System.out.printf(Locale.ROOT, "%-10s %-5.0f %8d %14.0f%n",
                        terrain, radius, blocks.size(), elapsed / (double) repetitions);
            }
        }
    }

    /**
     * Compara a contagem de blocos da onda com o oráculo vanilla, nos mesmos terrenos.
     *
     * <p>Responde a uma pergunta que a experiência in-game levantou: a onda dava uma cratera
     * 20% mais pequena que o vanilla. A ordem da fila de prioridade altera o resultado, por isso
     * isto tem de ser medido, não assumido.
     */
    @Test
    void compareFidelityWithVanilla() {
        System.out.println("== PARIDADE: onda vs vanilla (mesmo terreno) ==");
        System.out.printf(Locale.ROOT, "%-10s %-5s %-8s %8s %8s %8s %9s%n",
                "terreno", "raio", "vizinh.", "vanilla", "onda", "desvio", "simétrico");
        fidelity("ar", new DenseProbe(160));
        fidelity("terra", new DenseProbe(160).fill(-40, 40, -40, 40, 88, 40, TestProbe.DIRT));
        fidelity("pedra", new DenseProbe(160).fill(-40, 40, -40, 40, 88, 40, TestProbe.STONE));
        fidelity("caverna", cave());
    }

    private void fidelity(String terrain, DenseProbe probe) {
        for (float radius : new float[]{4.0F, 6.0F, 8.0F}) {
            for (int neighborhood : new int[]{26, 6}) {
                ExplosionParams p = params(radius, neighborhood);
                Set<Long> vanilla = ExplosionRayCaster.collect(p, probe, FIXED_RANDOM, false);
                Set<Long> wave = new HashSet<>();
                ExplosionWavefront.compute(p, probe, wave::add);

                Set<Long> onlyVanilla = new HashSet<>(vanilla);
                onlyVanilla.removeAll(wave);
                Set<Long> onlyWave = new HashSet<>(wave);
                onlyWave.removeAll(vanilla);
                double symmetric = (onlyVanilla.size() + onlyWave.size())
                        / (double) Math.max(1, vanilla.size());

                System.out.printf(Locale.ROOT, "%-10s %-5.0f %-8d %8d %8d %+7.1f%% %8.1f%%%n",
                        terrain, radius, neighborhood, vanilla.size(), wave.size(),
                        (wave.size() - vanilla.size()) * 100.0 / Math.max(1, vanilla.size()),
                        symmetric * 100);
            }
        }
    }

    private static ExplosionWavefront.Result run(ExplosionParams params, BlockProbe probe) {
        return ExplosionWavefront.compute(params, probe, packed -> { });
    }
}
