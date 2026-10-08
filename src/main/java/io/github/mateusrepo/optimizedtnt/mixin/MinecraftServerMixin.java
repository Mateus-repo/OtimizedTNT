package io.github.mateusrepo.optimizedtnt.mixin;

import io.github.mateusrepo.optimizedtnt.config.OptimizedTntConfig;
import net.minecraft.server.MinecraftServer;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/**
 * Garante que a configuração fica gravada quando o servidor para.
 *
 * <p>As alterações feitas pelo comando já são gravadas de imediato; isto cobre o caso de o
 * ficheiro ter sido editado à mão, para que nada se perca num reinício abrupto.
 */
@Mixin(MinecraftServer.class)
public abstract class MinecraftServerMixin {

    @Inject(method = "stopServer", at = @At("RETURN"), require = 1)
    private void optimizedtnt$saveConfigOnStop(CallbackInfo ci) {
        OptimizedTntConfig.save();
    }
}
