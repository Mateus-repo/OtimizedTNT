package io.github.mateusrepo.optimizedtnt.mixin;

import io.github.mateusrepo.optimizedtnt.OptimizedTnt;
import io.github.mateusrepo.optimizedtnt.config.OptimizedTntConfig;
import io.github.mateusrepo.optimizedtnt.explosion.ExplosionComparator;
import io.github.mateusrepo.optimizedtnt.explosion.ExplosionOptimizer;
import net.minecraft.core.BlockPos;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.item.PrimedTnt;
import net.minecraft.world.entity.vehicle.minecart.MinecartTNT;
import net.minecraft.world.level.ExplosionDamageCalculator;
import net.minecraft.world.level.ServerExplosion;
import org.spongepowered.asm.mixin.Final;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

import java.util.List;

/**
 * Substitui o cálculo dos blocos afetados por uma explosão.
 *
 * <p>Único ponto de falha do mod: se a otimização estiver desligada, o escopo não fizer match
 * ou algo correr mal, o {@code @Inject} <strong>não cancela</strong> e o vanilla corre intacto.
 * Nunca {@code setReturnValue(null)} — isso cancelaria o método e devolveria {@code null},
 * partindo {@code interactWithBlocks}.
 *
 * <p>Usa o máximo de API pública possível (os métodos da interface {@code Explosion}: {@code
 * level()}, {@code center()}, {@code radius()}, {@code getDirectSourceEntity()}) e só faz
 * {@code @Shadow} do {@code damageCalculator}, que não tem getter — menos nomes de campo
 * dependentes, logo menos formas de partir numa atualização do jogo.
 */
@Mixin(ServerExplosion.class)
public abstract class ServerExplosionMixin {

    /** Único campo sem getter público. Confirmado com javap: {@code private final}. */
    @Shadow @Final private ExplosionDamageCalculator damageCalculator;

    @Inject(method = "calculateExplodedPositions", at = @At("HEAD"), cancellable = true, require = 1)
    private void optimizedtnt$calculateExplodedPositions(CallbackInfoReturnable<List<BlockPos>> cir) {
        OptimizedTntConfig config = OptimizedTntConfig.get();

        ServerExplosion self = (ServerExplosion) (Object) this;

        // Gate barato primeiro: nenhum trabalho antes de saber que há o que fazer.
        if (!config.isOptimizing() || !matchesScope(config, self.getDirectSourceEntity())) {
            return;
        }

        try {
            if (OptimizedTnt.COMPARE) {
                // Uma explosão chega: comparar custa o dobro e não deve ficar ligado.
                OptimizedTnt.COMPARE = false;
                var center = self.center();
                ExplosionComparator.compare(self, self.level(),
                        center.x, center.y, center.z, self.radius(),
                        damageCalculator, config);
            }

            var center = self.center();
            cir.setReturnValue(ExplosionOptimizer.compute(self, self.level(),
                    center.x, center.y, center.z, self.radius(),
                    damageCalculator, self.level().getRandom(), config));
        } catch (Throwable throwable) {
            // Uma falha do mod nunca pode rebentar o tick: regista uma vez e deixa o vanilla.
            OptimizedTnt.reportFailure("falha ao calcular a explosão otimizada", throwable);
        }
    }

    private static boolean matchesScope(OptimizedTntConfig config, Entity source) {
        if (config.getScope() == OptimizedTntConfig.Scope.ALL_EXPLOSIONS) {
            return true;
        }
        // Em 26.3 já não existe TntBlockEntity: a TNT é a entidade PrimedTnt.
        return source instanceof PrimedTnt || source instanceof MinecartTNT;
    }
}
