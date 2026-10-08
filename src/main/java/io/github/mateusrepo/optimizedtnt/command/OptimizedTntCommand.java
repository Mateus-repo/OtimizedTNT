package io.github.mateusrepo.optimizedtnt.command;

import com.mojang.brigadier.CommandDispatcher;
import com.mojang.brigadier.arguments.FloatArgumentType;
import com.mojang.brigadier.arguments.IntegerArgumentType;
import com.mojang.brigadier.builder.LiteralArgumentBuilder;
import com.mojang.brigadier.context.CommandContext;
import io.github.mateusrepo.optimizedtnt.OptimizedTnt;
import io.github.mateusrepo.optimizedtnt.config.OptimizedTntConfig;
import io.github.mateusrepo.optimizedtnt.metrics.ExplosionMetrics;
import net.minecraft.commands.CommandSourceStack;
import net.minecraft.commands.Commands;
import net.minecraft.network.chat.Component;

import java.util.Locale;

/**
 * Comando {@code /optimizedtnt}, registado por mixin (sem Fabric API).
 *
 * <pre>
 * /optimizedtnt status              configuração e métricas
 * /optimizedtnt reload              relê o config/optimizedtnt.json
 * /optimizedtnt save                grava a configuração atual
 * /optimizedtnt on|off              liga/desliga a otimização
 * /optimizedtnt algorithm &lt;...&gt;      wavefront | ray_cache | vanilla
 * /optimizedtnt scope &lt;...&gt;          tnt_only | all_explosions
 * /optimizedtnt neighborhood &lt;6|18|26&gt;
 * /optimizedtnt resistance &lt;valor&gt;  fator de resistência (≥ 0)
 * /optimizedtnt randomness &lt;...&gt;    mean | per_direction
 * /optimizedtnt metrics &lt;on|off&gt;
 * /optimizedtnt compare              compara com o vanilla na próxima explosão
 * </pre>
 */
public final class OptimizedTntCommand {

    private static final String NAME = "optimizedtnt";

    private OptimizedTntCommand() {
    }

    /** A ser chamado pelo {@code CommandsMixin}. */
    public static void registerInto(CommandDispatcher<CommandSourceStack> dispatcher) {
        dispatcher.register(build());
    }

    private static LiteralArgumentBuilder<CommandSourceStack> build() {
        return Commands.literal(NAME)
                .requires(source -> Commands.LEVEL_ADMINS.check(source.permissions()))
                .then(Commands.literal("status").executes(OptimizedTntCommand::status))
                .then(Commands.literal("reload").executes(OptimizedTntCommand::reload))
                .then(Commands.literal("save").executes(OptimizedTntCommand::save))
                .then(Commands.literal("compare").executes(OptimizedTntCommand::compare))
                .then(Commands.literal("on").executes(ctx -> toggle(ctx, true)))
                .then(Commands.literal("off").executes(ctx -> toggle(ctx, false)))
                .then(Commands.literal("metrics")
                        .then(Commands.literal("on").executes(ctx -> metrics(ctx, true)))
                        .then(Commands.literal("off").executes(ctx -> metrics(ctx, false))))
                .then(Commands.literal("algorithm")
                        .then(Commands.literal("wavefront").executes(ctx -> algorithm(ctx, "WAVEFRONT")))
                        .then(Commands.literal("ray_cache").executes(ctx -> algorithm(ctx, "RAY_CACHE")))
                        .then(Commands.literal("vanilla").executes(ctx -> algorithm(ctx, "VANILLA"))))
                .then(Commands.literal("scope")
                        .then(Commands.literal("tnt_only").executes(ctx -> scope(ctx, "TNT_ONLY")))
                        .then(Commands.literal("all_explosions").executes(ctx -> scope(ctx, "ALL_EXPLOSIONS"))))
                .then(Commands.literal("randomness")
                        .then(Commands.literal("mean").executes(ctx -> randomness(ctx, "MEAN")))
                        .then(Commands.literal("per_direction").executes(ctx -> randomness(ctx, "PER_DIRECTION"))))
                .then(Commands.literal("neighborhood")
                        .then(Commands.argument("tamanho", IntegerArgumentType.integer(1, 64))
                                .executes(ctx -> neighborhood(ctx,
                                        IntegerArgumentType.getInteger(ctx, "tamanho")))))
                .then(Commands.literal("resistance")
                        .then(Commands.argument("fator", FloatArgumentType.floatArg(0.0F, 10.0F))
                                .executes(ctx -> resistance(ctx,
                                        FloatArgumentType.getFloat(ctx, "fator")))));
    }

    private static int status(CommandContext<CommandSourceStack> ctx) {
        OptimizedTntConfig config = OptimizedTntConfig.get();
        reply(ctx, "§7Optimized TNT§r");
        reply(ctx, "§7ficheiro: §f" + OptimizedTntConfig.getFile());
        reply(ctx, config.toString().replace(", ", "§r, §7"));

        if (config.isMetrics()) {
            reply(ctx, String.format(Locale.ROOT,
                    "§7métricas: §f%d explosões, §f%.1f blocos e §f%.0f leituras por explosão, §f%.1f µs por explosão",
                    ExplosionMetrics.explosions(), ExplosionMetrics.averageBlocks(),
                    ExplosionMetrics.averageReads(), ExplosionMetrics.averageMicros()));
        } else {
            reply(ctx, "§7métricas desligadas (usa §f/optimizedtnt metrics on§7)");
        }
        if (OptimizedTnt.COMPARE) {
            reply(ctx, "§7comparação com o vanilla ativa para a próxima explosão");
        }
        return 1;
    }

    private static int reload(CommandContext<CommandSourceStack> ctx) {
        if (!OptimizedTntConfig.reload()) {
            reply(ctx, "§cNão foi possível reler a configuração.");
            return 0;
        }
        OptimizedTnt.applyRuntimeState();
        reply(ctx, "§aConfiguração recarregada: §f" + OptimizedTntConfig.get());
        return 1;
    }

    private static int save(CommandContext<CommandSourceStack> ctx) {
        OptimizedTntConfig.save();
        reply(ctx, "§aConfiguração gravada em §f" + OptimizedTntConfig.getFile());
        return 1;
    }

    private static int toggle(CommandContext<CommandSourceStack> ctx, boolean value) {
        OptimizedTntConfig.get().setEnabled(value);
        OptimizedTntConfig.save();
        reply(ctx, value ? "§aOtimização ligada." : "§eOtimização desligada (o vanilla corre).");
        return 1;
    }

    private static int metrics(CommandContext<CommandSourceStack> ctx, boolean value) {
        OptimizedTntConfig.get().setMetrics(value);
        OptimizedTntConfig.save();
        OptimizedTnt.applyRuntimeState();
        reply(ctx, value ? "§aMétricas ligadas." : "§eMétricas desligadas e contadores reiniciados.");
        return 1;
    }

    private static int algorithm(CommandContext<CommandSourceStack> ctx, String raw) {
        OptimizedTntConfig config = OptimizedTntConfig.get();
        config.setAlgorithm(OptimizedTntConfig.Algorithm.parse(raw, config.getAlgorithm()));
        OptimizedTntConfig.save();
        reply(ctx, "§aAlgoritmo: §f" + config.getAlgorithm());
        return 1;
    }

    private static int scope(CommandContext<CommandSourceStack> ctx, String raw) {
        OptimizedTntConfig config = OptimizedTntConfig.get();
        config.setScope(OptimizedTntConfig.Scope.parse(raw, config.getScope()));
        OptimizedTntConfig.save();
        reply(ctx, "§aÂmbito: §f" + config.getScope());
        return 1;
    }

    private static int randomness(CommandContext<CommandSourceStack> ctx, String raw) {
        OptimizedTntConfig config = OptimizedTntConfig.get();
        config.setRandomnessMode(OptimizedTntConfig.RandomnessMode.parse(raw, config.getRandomnessMode()));
        OptimizedTntConfig.save();
        reply(ctx, "§aAleatoriedade: §f" + config.getRandomnessMode());
        return 1;
    }

    private static int neighborhood(CommandContext<CommandSourceStack> ctx, int value) {
        OptimizedTntConfig config = OptimizedTntConfig.get();
        int normalized = OptimizedTntConfig.normalizeNeighborhood(value);
        config.setNeighborhood(normalized);
        OptimizedTntConfig.save();
        if (normalized != value) {
            reply(ctx, "§eVizinhança §f" + value + "§e inválida; a usar §f" + normalized);
        } else {
            reply(ctx, "§aVizinhança: §f" + normalized);
        }
        return 1;
    }

    private static int resistance(CommandContext<CommandSourceStack> ctx, float value) {
        OptimizedTntConfig config = OptimizedTntConfig.get();
        config.setResistanceFactor(value);
        OptimizedTntConfig.save();
        reply(ctx, "§aFator de resistência: §f" + value);
        return 1;
    }

    private static int compare(CommandContext<CommandSourceStack> ctx) {
        OptimizedTnt.COMPARE = true;
        reply(ctx, "§aNa próxima explosão vai correr o vanilla e o algoritmo escolhido e o "
                + "desvio vai aparecer no log do servidor.");
        return 1;
    }

    private static void reply(CommandContext<CommandSourceStack> ctx, String message) {
        ctx.getSource().sendSuccess(() -> Component.literal(message), false);
    }
}
