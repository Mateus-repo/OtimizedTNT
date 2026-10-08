package io.github.mateusrepo.optimizedtnt;

import io.github.mateusrepo.optimizedtnt.config.OptimizedTntConfig;
import io.github.mateusrepo.optimizedtnt.metrics.ExplosionMetrics;
import net.fabricmc.api.ModInitializer;
import net.fabricmc.loader.api.FabricLoader;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Entry point do mod (lado servidor, sem Fabric API).
 */
public final class OptimizedTnt implements ModInitializer {

    public static final String MOD_ID = "optimizedtnt";
    public static final Logger LOGGER = LoggerFactory.getLogger("Optimized TNT");

    /**
     * Liga a comparação com o vanilla na próxima explosão ({@code /optimizedtnt compare}).
     *
     * <p>Volta a {@code false} sozinha depois de uma explosão, para não arrastar o custo extra
     * de correr os dois algoritmos.
     */
    public static volatile boolean COMPARE;

    private static volatile boolean failureReported;

    @Override
    public void onInitialize() {
        OptimizedTntConfig.load(FabricLoader.getInstance().getConfigDir());
        applyRuntimeState();
        LOGGER.info("Optimized TNT carregado: {}", OptimizedTntConfig.get());
    }

    /** Volta a aplicar o que a configuração controla em tempo de execução. */
    public static void applyRuntimeState() {
        ExplosionMetrics.setEnabled(OptimizedTntConfig.get().isMetrics());
    }

    /**
     * Regista uma falha do mod <strong>uma única vez</strong>.
     *
     * <p>O mod nunca deixa uma exceção rebentar o tick do servidor: o {@code @Inject} limita-se a
     * não cancelar e o vanilla corre. Mas também não vale a pena encher o log a cada explosão.
     */
    public static void reportFailure(String message, Throwable throwable) {
        if (failureReported) {
            return;
        }
        failureReported = true;
        LOGGER.error("{}: {}. A usar o cálculo do vanilla. "
                + "Esta mensagem só aparece uma vez.", message, throwable.toString());
    }
}
