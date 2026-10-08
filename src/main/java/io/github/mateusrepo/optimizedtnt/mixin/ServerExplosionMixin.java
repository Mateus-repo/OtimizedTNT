package io.github.mateusrepo.optimizedtnt.mixin;

import io.github.mateusrepo.optimizedtnt.OptimizedTnt;
import io.github.mateusrepo.optimizedtnt.config.OptimizedTntConfig;
import io.github.mateusrepo.optimizedtnt.explosion.ExplosionComparator;
import io.github.mateusrepo.optimizedtnt.explosion.ExplosionOptimizer;
import io.github.mateusrepo.optimizedtnt.metrics.ExplosionMetrics;
import net.minecraft.core.BlockPos;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.item.PrimedTnt;
import net.minecraft.world.entity.vehicle.minecart.MinecartTNT;
import net.minecraft.world.level.ExplosionDamageCalculator;
import net.minecraft.world.level.ServerExplosion;
import org.spongepowered.asm.mixin.Final;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.Unique;
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

    /** Instante de entrada no método; só usado quando as métricas estão ligadas. */
    @Unique
    private long optimizedtnt$start;

    /** {@code true} quando foi o mod a calcular os blocos (isto é, que não correu o vanilla). */
    @Unique
    private boolean optimizedtnt$handled;

    @Inject(method = "calculateExplodedPositions", at = @At("HEAD"), cancellable = true, require = 1)
    private void optimizedtnt$calculateExplodedPositions(CallbackInfoReturnable<List<BlockPos>> cir) {
        boolean measuring = ExplosionMetrics.isEnabled();
        if (measuring) {
            optimizedtnt$start = System.nanoTime();
        }
        optimizedtnt$handled = false;

        OptimizedTntConfig config = OptimizedTntConfig.get();

        // Gate barato primeiro: nenhum trabalho antes de saber que há o que fazer.
        if (!config.isOptimizing() || !matchesScope(config, ((ServerExplosion) (Object) this).getDirectSourceEntity())) {
            return; // o vanilla calcula os blocos; o inject de RETURN mede o custo dele
        }

        ServerExplosion self = (ServerExplosion) (Object) this;
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
            List<BlockPos> positions =
                    ExplosionOptimizer.compute(self, self.level(),
                            center.x, center.y, center.z, self.radius(),
                            damageCalculator, self.level().getRandom(), config);
            optimizedtnt$handled = true;
            cir.setReturnValue(positions);
        } catch (Throwable throwable) {
            // Uma falha do mod nunca pode rebentar o tick: regista uma vez e deixa o vanilla.
            OptimizedTnt.reportFailure("falha ao calcular a explosão otimizada", throwable);
        }
    }

    /**
     * Mede o custo do cálculo do <strong>vanilla</strong>.
     *
     * <p>Usa a bandeira {@code optimizedtnt$handled} em vez de confiar em o inject de RETURN
     * não disparar quando o anterior cancelou: é explícito e não depende do comportamento do
     * Mixin. É a linha de base que permite comparar os dois lados na mesma sessão — com a
     * otimização desligada o mod não calcula nada, mas continua a medir o vanilla.
     */
    @Inject(method = "calculateExplodedPositions", at = @At("RETURN"), require = 1)
    private void optimizedtnt$measureVanilla(CallbackInfoReturnable<List<BlockPos>> cir) {
        if (optimizedtnt$handled || !ExplosionMetrics.isEnabled() || optimizedtnt$start == 0L) {
            return;
        }
        ExplosionMetrics.recordVanilla(System.nanoTime() - optimizedtnt$start, cir.getReturnValue());
        optimizedtnt$start = 0L;
    }

    private static boolean matchesScope(OptimizedTntConfig config, Entity source) {
        if (config.getScope() == OptimizedTntConfig.Scope.ALL_EXPLOSIONS) {
            return true;
        }
        // Em 26.3 já não existe TntBlockEntity: a TNT é a entidade PrimedTnt.
        return source instanceof PrimedTnt || source instanceof MinecartTNT;
    }
}
