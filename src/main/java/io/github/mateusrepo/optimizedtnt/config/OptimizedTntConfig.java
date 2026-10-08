package io.github.mateusrepo.optimizedtnt.config;

import com.google.gson.Gson;
import com.google.gson.GsonBuilder;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import io.github.mateusrepo.optimizedtnt.OptimizedTnt;

import java.io.IOException;
import java.io.Reader;
import java.io.Writer;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;

/**
 * Configuração do mod, guardada em {@code config/optimizedtnt.json}.
 *
 * <p>Princípios: um ficheiro corrompido ou com valores estranhos <strong>nunca</strong> pode
 * crashar o servidor — cai-se sempre nos defaults com um aviso no log. O carregamento e o
 * guardado são manuais (sem Fabric API), pelo que {@code reload} é o que permite ligar e
 * desligar a otimização sem reiniciar.
 */
public final class OptimizedTntConfig {

    public static final String FILE_NAME = "optimizedtnt.json";

    /** A quem se aplica a otimização. */
    public enum Scope {
        /** Só TNT (e carrinhos de TNT). */
        TNT_ONLY,
        /** Todas as explosões do servidor. */
        ALL_EXPLOSIONS;

        public static Scope parse(String raw, Scope fallback) {
            if (raw != null) {
                for (Scope value : values()) {
                    if (value.name().equalsIgnoreCase(raw.trim())) {
                        return value;
                    }
                }
            }
            return fallback;
        }
    }

    /** Algoritmo usado para escolher os blocos afetados. */
    public enum Algorithm {
        /** Os mesmos raios do vanilla, com a resistência memorizada por bloco. Forma idêntica. */
        RAY_CACHE,
        /**
         * Híbrido: a onda abaixo de {@link #getHybridMaxRadius()} e o ray cache acima.
         *
         * <p>Medido: em ar aberto a onda é <strong>mais lenta</strong> que o vanilla a partir de
         * raio 8 (0,74×), porque relaxa 26 vizinhos por bloco alcançado (~209 000 relaxamentos)
         * contra os ~47 000 amostras dos 1352 raios do vanilla. Nos raios que o jogo usa
         * (TNT = 4, cristal de fim = 6) isso não acontece, mas explosões de mods podem ter
         * raio 20 ou mais.
         */
        HYBRID,
        /** Onda de blocos (Dijkstra). Rápido, com desvio de forma nas bordas. */
        WAVEFRONT,
        /** Não substitui nada. */
        VANILLA;

        public static Algorithm parse(String raw, Algorithm fallback) {
            if (raw != null) {
                for (Algorithm value : values()) {
                    if (value.name().equalsIgnoreCase(raw.trim())) {
                        return value;
                    }
                }
            }
            return fallback;
        }
    }

    /** Como se traduzem os 1352 sorteios do vanilla numa energia única. */
    public enum RandomnessMode {
        /** Um valor: a média dos 1352 sorteios. */
        MEAN,
        /** Três valores: média por tipo de direção (eixo / diagonal de face / diagonal de canto). */
        PER_DIRECTION;

        public static RandomnessMode parse(String raw, RandomnessMode fallback) {
            if (raw != null) {
                for (RandomnessMode value : values()) {
                    if (value.name().equalsIgnoreCase(raw.trim())) {
                        return value;
                    }
                }
            }
            return fallback;
        }
    }

    private static final Gson GSON = new GsonBuilder().setPrettyPrinting().create();

    private static volatile OptimizedTntConfig instance = new OptimizedTntConfig();
    private static volatile Path file;

    private boolean enabled = true;
    private Scope scope = Scope.TNT_ONLY;
    private Algorithm algorithm = Algorithm.RAY_CACHE;
    private int neighborhood = 26;
    private float hybridMaxRadius = 8.0F;
    private float resistanceFactor = 1.0F;
    private RandomnessMode randomnessMode = RandomnessMode.MEAN;
    private boolean cacheBlockResistance = true;
    private boolean metrics = false;

    public boolean isEnabled() {
        return enabled;
    }

    public Scope getScope() {
        return scope;
    }

    public Algorithm getAlgorithm() {
        return algorithm;
    }

    public int getNeighborhood() {
        return neighborhood;
    }

    /** Raio a partir do qual o {@link Algorithm#HYBRID} usa o ray cache. */
    public float getHybridMaxRadius() {
        return hybridMaxRadius;
    }

    public float getResistanceFactor() {
        return resistanceFactor;
    }

    public RandomnessMode getRandomnessMode() {
        return randomnessMode;
    }

    public boolean isCacheBlockResistance() {
        return cacheBlockResistance;
    }

    public boolean isMetrics() {
        return metrics;
    }

    public boolean isOptimizing() {
        return enabled && algorithm != Algorithm.VANILLA;
    }

    public void setEnabled(boolean value) {
        enabled = value;
    }

    public void setScope(Scope value) {
        scope = value;
    }

    public void setAlgorithm(Algorithm value) {
        algorithm = value;
    }

    public void setNeighborhood(int value) {
        neighborhood = normalizeNeighborhood(value);
    }

    public void setHybridMaxRadius(float value) {
        hybridMaxRadius = value > 0.0F ? value : 8.0F;
    }

    public void setResistanceFactor(float value) {
        resistanceFactor = value;
    }

    public void setRandomnessMode(RandomnessMode value) {
        randomnessMode = value;
    }

    public void setCacheBlockResistance(boolean value) {
        cacheBlockResistance = value;
    }

    public void setMetrics(boolean value) {
        metrics = value;
    }

    /** Só 6, 18 ou 26 são válidos; o resto cai para 26 com aviso. */
    public static int normalizeNeighborhood(int value) {
        return switch (value) {
            case 6, 18, 26 -> value;
            default -> 26;
        };
    }

    public static OptimizedTntConfig get() {
        return instance;
    }

    public static Path getFile() {
        return file;
    }

    /** Carrega (ou cria) a configuração. Nunca lança. */
    public static void load(Path configDir) {
        Path path = configDir.resolve(FILE_NAME);
        file = path;

        if (Files.exists(path)) {
            try (Reader reader = Files.newBufferedReader(path, StandardCharsets.UTF_8)) {
                instance = fromJson(JsonParser.parseReader(reader));
            } catch (Exception e) {
                OptimizedTnt.LOGGER.warn(
                        "Não foi possível ler {} ({}). A usar a configuração por omissão.",
                        path, e.toString());
                instance = new OptimizedTntConfig();
                return;
            }
            OptimizedTnt.LOGGER.info("Configuração carregada de {}", path);
            return;
        }

        instance = new OptimizedTntConfig();
        OptimizedTnt.LOGGER.info("Configuração não encontrada, a criar {}", path);
        save();
    }

    /** Relê o ficheiro. Devolve {@code false} se não foi possível ler. */
    public static boolean reload() {
        Path path = file;
        if (path == null || !Files.exists(path)) {
            return false;
        }
        try (Reader reader = Files.newBufferedReader(path, StandardCharsets.UTF_8)) {
            instance = fromJson(JsonParser.parseReader(reader));
            return true;
        } catch (Exception e) {
            OptimizedTnt.LOGGER.warn("Falha ao recarregar {}: {}", path, e.toString());
            return false;
        }
    }

    /** Grava a configuração atual no ficheiro. */
    public static void save() {
        Path path = file;
        if (path == null) {
            return;
        }
        try {
            Files.createDirectories(path.getParent());
            try (Writer writer = Files.newBufferedWriter(path, StandardCharsets.UTF_8)) {
                GSON.toJson(instance, writer);
            }
        } catch (IOException e) {
            OptimizedTnt.LOGGER.warn("Falha ao gravar {}: {}", path, e.toString());
        }
    }

    /** Cria uma configuração a partir de JSON, tolerante a campos inválidos ou em falta. */
    static OptimizedTntConfig fromJson(JsonElement root) {
        OptimizedTntConfig config = new OptimizedTntConfig();
        if (root == null || !root.isJsonObject()) {
            return config;
        }
        JsonObject json = root.getAsJsonObject();

        config.enabled = readBoolean(json, "enabled", config.enabled);
        config.scope = Scope.parse(readString(json, "scope"), config.scope);
        config.algorithm = Algorithm.parse(readString(json, "algorithm"), config.algorithm);
        config.randomnessMode = RandomnessMode.parse(
                readString(json, "randomnessMode"), config.randomnessMode);
        config.cacheBlockResistance = readBoolean(
                json, "cacheBlockResistance", config.cacheBlockResistance);
        config.metrics = readBoolean(json, "metrics", config.metrics);

        if (json.has("neighborhood")) {
            try {
                config.neighborhood = normalizeNeighborhood(json.get("neighborhood").getAsInt());
            } catch (RuntimeException e) {
                OptimizedTnt.LOGGER.warn("neighborhood inválido, a usar 26");
            }
        }
        if (json.has("hybridMaxRadius")) {
            try {
                float value = json.get("hybridMaxRadius").getAsFloat();
                if (value <= 0.0F || !Float.isFinite(value)) {
                    OptimizedTnt.LOGGER.warn("hybridMaxRadius inválido ({}), a usar 8.0", value);
                } else {
                    config.hybridMaxRadius = value;
                }
            } catch (RuntimeException e) {
                OptimizedTnt.LOGGER.warn("hybridMaxRadius inválido, a usar 8.0");
            }
        }
        if (json.has("resistanceFactor")) {
            try {
                float value = json.get("resistanceFactor").getAsFloat();
                if (value < 0.0F || !Float.isFinite(value)) {
                    OptimizedTnt.LOGGER.warn("resistanceFactor inválido ({}), a usar 1.0", value);
                } else {
                    config.resistanceFactor = value;
                }
            } catch (RuntimeException e) {
                OptimizedTnt.LOGGER.warn("resistanceFactor inválido, a usar 1.0");
            }
        }
        return config;
    }

    private static boolean readBoolean(JsonObject json, String key, boolean fallback) {
        if (!json.has(key)) {
            return fallback;
        }
        try {
            return json.get(key).getAsBoolean();
        } catch (RuntimeException e) {
            return fallback;
        }
    }

    private static String readString(JsonObject json, String key) {
        if (!json.has(key)) {
            return null;
        }
        try {
            return json.get(key).getAsString();
        } catch (RuntimeException e) {
            return null;
        }
    }

    @Override
    public String toString() {
        return "enabled=" + enabled
                + ", scope=" + scope
                + ", algorithm=" + algorithm
                + ", neighborhood=" + neighborhood
                + ", hybridMaxRadius=" + hybridMaxRadius
                + ", resistanceFactor=" + resistanceFactor
                + ", randomnessMode=" + randomnessMode
                + ", cacheBlockResistance=" + cacheBlockResistance
                + ", metrics=" + metrics;
    }
}

