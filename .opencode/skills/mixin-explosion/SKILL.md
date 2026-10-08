---
name: mixin-explosion
description: Regras para escrever e manter os mixins do mod (ServerExplosionMixin em calculateExplodedPositions e CommandsMixin para /optimizedtnt sem Fabric API), incluindo fallback para o vanilla e confirmação de nomes Mojmap com javap. Usa ao criar/alterar qualquer mixin.
license: MIT
compatibility: opencode
---

# Mixins do Optimized TNT

## Alvo principal

`net.minecraft.world.level.ServerExplosion#calculateExplodedPositions()` — `private List<BlockPos>`.
Fluxo vanilla em `explode()`: game event → `calculateExplodedPositions()` → `hurtEntities()` → `interactWithBlocks(...)` → `createFire(...)`. **Só substituímos a escolha dos blocos**; o resto fica intacto.

## ServerExplosionMixin

```java
@Mixin(ServerExplosion.class)
public abstract class ServerExplosionMixin {
    @Shadow @Final private ServerLevel level;
    @Shadow @Final private Vec3 center;
    @Shadow @Final private float radius;
    @Shadow @Final private ExplosionDamageCalculator damageCalculator;

    @Inject(method = "calculateExplodedPositions", at = @At("HEAD"), cancellable = true, require = 1)
    private void optimizedtnt$calc(CallbackInfoReturnable<List<BlockPos>> cir) {
        if (!OptimizedTntConfig.get().shouldHandle(/* explosão */)) return; // vanilla corre
        try {
            cir.setReturnValue(ExplosionWavefront.compute(/* ... */));
        } catch (Throwable t) {
            OptimizedTnt.logOnce("Falha no wavefront, a usar vanilla", t);
            // não cancelar → vanilla corre
        }
    }
}
```

(Os tipos/nomes exatos dos campos devem ser confirmados — ver "Confirmar nomes".)

### ⚠️ Fallback para o vanilla

**Para deixar o vanilla correr, simplesmente faz `return` sem chamar `cir.setReturnValue(...)`.**
`cir.setReturnValue(null)` **não** executa o vanilla: cancela o método e devolve `null`, o que partiria `interactWithBlocks`. (O `progresso.md` D1 e o README dizem "devolve `null`" — esse texto deve ser corrigido para "não cancela".)

### Regras

- `require = 1` (falhar cedo e claro se a 26.x mudar). Em `mixins.json`: `injectors.defaultRequire: 1`.
- Usar `@Shadow` em vez de access widener (D2). Prefixar métodos/campos injetados com `optimizedtnt$`.
- Gate barato primeiro (`enabled`, `algorithm != VANILLA`, scope) antes de qualquer trabalho.
- `TNT_ONLY`: `getDirectSourceEntity() instanceof PrimedTnt || instanceof MinecartTNT` (confirmar a API real; `TntBlockEntity` não existe em 26.3).
- Nunca deixar uma exceção do mod rebentar o tick: `try/catch` + log **uma vez** + vanilla.
- Detetar conflito: se outro mod também injeta em `calculateExplodedPositions`, registar aviso e desligar a otimização (ver `progresso.md` §5).
- Manter só `server` no `fabric.mod.json`; nada de classes client em mixins do servidor.

## CommandsMixin (`/optimizedtnt`, sem Fabric API)

- Registar o literal `optimizedtnt` com Brigadier no `CommandDispatcher` de `Commands` (inject no fim da construção/registo — confirmar assinatura com `javap`).
- Subcomandos: `status`, `reload`, `save`, `compare`.
- Permissão nível 2 (op). **A API de permissões pode ter mudado em 26.x** — confirmar com `javap` em vez de assumir `hasPermission(int)`.
- Mensagens ao jogador via `Component` (PT-PT ou EN; manter consistente com o README).

## Confirmar nomes (nunca de memória)

O jar desofuscado fica na cache do Loom:

```bash
JAR=$(find ~/.gradle/caches/fabric-loom -name 'minecraft-common-deobf-26.3*.jar' | head -1)
javap -p -cp "$JAR" net.minecraft.world.level.ServerExplosion
javap -p -c -cp "$JAR" net.minecraft.world.level.ServerExplosion | less   # bytecode do algoritmo
javap -p -cp "$JAR" net.minecraft.world.level.ExplosionDamageCalculator
javap -p -cp "$JAR" net.minecraft.commands.Commands
```

Se o jar não existir, correr primeiro um build para o Loom o gerar. Registar em `progresso.md` §2 qualquer nome que difira do documentado.
