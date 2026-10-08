package io.github.mateusrepo.optimizedtnt.mixin;

import com.mojang.brigadier.CommandDispatcher;
import io.github.mateusrepo.optimizedtnt.command.OptimizedTntCommand;
import net.minecraft.commands.CommandBuildContext;
import net.minecraft.commands.CommandSourceStack;
import net.minecraft.commands.Commands;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/**
 * Regista o comando {@code /optimizedtnt} sem Fabric API.
 *
 * <p>O servidor constrói a árvore de comandos em {@code Commands#<init>}, por isso injectar no
 * fim da construção é o ponto certo. Em 26.x as permissões já não são um {@code int}: são
 * {@code PermissionSet} / {@code PermissionCheck}, daí {@code Commands.LEVEL_ADMINS.check(...)}
 * no comando.
 *
 * <p>Num inject de construtor o handler tem de declarar os parâmetros do alvo, por isso
 * aparecem aqui o {@code CommandSelection} e o {@code CommandBuildContext}.
 */
@Mixin(Commands.class)
public abstract class CommandsMixin {

    @Inject(method = "<init>", at = @At("RETURN"), require = 1)
    private void optimizedtnt$registerCommand(
            Commands.CommandSelection selection,
            CommandBuildContext context,
            CallbackInfo ci) {
        CommandDispatcher<CommandSourceStack> dispatcher = ((Commands) (Object) this).getDispatcher();
        OptimizedTntCommand.registerInto(dispatcher);
    }
}
