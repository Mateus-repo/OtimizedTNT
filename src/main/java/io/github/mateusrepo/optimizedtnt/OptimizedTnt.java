package io.github.mateusrepo.optimizedtnt;

import net.fabricmc.api.ModInitializer;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Entry point do mod (lado servidor).
 *
 * <p>O carregamento da configuração é feito em {@code OptimizedTntConfig} (fase F1); a MIXIN
 * responsável pela otimização está em {@code ServerExplosionMixin} (fase F3).
 */
public final class OptimizedTnt implements ModInitializer {

    public static final String MOD_ID = "optimizedtnt";
    public static final Logger LOGGER = LoggerFactory.getLogger("Optimized TNT");

    @Override
    public void onInitialize() {
        LOGGER.info("Optimized TNT carregado (side servidor, sem Fabric API)");
    }
}
