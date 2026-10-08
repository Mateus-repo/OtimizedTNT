# Progresso — Optimized TNT (Fabric 26.3, server-side)

Documento de trabalho: planeamento, decisões tomadas, tarefas e riscos.
O README é para o utilizador final; este ficheiro é o plano técnico e o registo de progresso.

**Estado global:** planeamento concluído, implementação por iniciar.
Última atualização: 2026-10-08

---

## 1. Âmbito

| Item | Decisão |
|---|---|
| Lado | Servidor dedicado apenas (`environment: "server"`, sem entrypoint client obrigatório) |
| Fabric API | Não usada — loader + mixin + comandos manuais |
| Optimização | Substituição de `ServerExplosion#calculateExplodedPositions()` |
| Elenco | `TNT_ONLY` por omissão, `ALL_EXPLOSIONS` opcional |
| Persistência | `config/optimizedtnt.json`, guardado automaticamente, hot-reload |
| UI | Mod Menu opcional (só útil se instalado no cliente) |

Fora de âmbito (por agora): explosions de cliente, realms/Lan, multi-loader (NeoForge),
paridade exata bit-a-bit com o vanilla.

## 2. Ambiente alvo (confirmado)

Confirmado contra `SubtleEffects-26.3` e contra o jar desofuscado de 26.3 em cache
(`~/.gradle/caches/fabric-loom/.../minecraft-common-deobf-26.3.jar`), inspecionado com
`javap` — nomes abaixo são **Mojmap reais de 26.3**:

- `net.minecraft.world.level.ServerExplosion` (implements `Explosion`)
  - `private List<BlockPos> calculateExplodedPositions()` ← alvo do mixin
  - `private void interactWithBlocks(List<BlockPos>)`, `createFire(...)`, `hurtEntities()`
  - `public int explode()` → game event → `calculateExplodedPositions()` → `hurtEntities()`
    → `interactWithBlocks(...)` (se `blockInteraction != KEEP`) → `createFire(...)` (se `fire`)
  - campos final: `level` (`ServerLevel`), `center` (`Vec3`), `radius` (`float`),
    `damageCalculator` (`ExplosionDamageCalculator`), `blockInteraction`, `fire`
- `net.minecraft.world.level.ExplosionDamageCalculator`
  - `Optional<Float> getBlockExplosionResistance(Explosion, BlockGetter, BlockPos, BlockState, FluidState)`
  - `boolean shouldBlockExplode(Explosion, BlockGetter, BlockPos, BlockState, float)`
  - default: `Optional.empty()` só se bloco **e** fluido são ar; senão
    `max(block.getExplosionResistance(), fluid.getExplosionResistance())`
- `net.minecraft.world.level.EntityBasedExplosionDamageCalculator` (subclasse, TNT usa)
- `net.minecraft.world.level.Explosion$BlockInteraction` → `KEEP`, `DESTROY`,
  `DESTROY_WITH_DECAY`, `TRIGGER_BLOCK`
- `net.minecraft.world.level.Level$ExplosionInteraction` → `NONE`, `BLOCK`, `MOB`, `TNT`, `TRIGGER`
- Entidades: `net.minecraft.world.entity.item.PrimedTnt` (não existe `TntBlockEntity` em 26.3),
  `net.minecraft.world.entity.vehicle.minecart.MinecartTNT`, `WindCharge`
- Toolchain: Java 25, Loom `1.18-SNAPSHOT`, fabric-loader `0.19.5`

### Algoritmo vanilla 26.3 (lido do bytecode, para paridade)

```
raios: 16³ − 14³ = 1352 (só a casca: pelo menos um eixo em {0, 15})
dir   = normaliza(i/15*2−1, j/15*2−1, k/15*2−1)
forca = (radius + 0.7 * random.nextFloat() * 0.6) * radius      // radius * (1 + 0.42*rand)
while (forca > 0):
    pos = BlockPos.containing(x, y, z)
    if !level.isInWorldBounds(pos): break
    estado = getBlockState(pos); fluido = getFluidState(pos)
    res = damageCalculator.getBlockExplosionResistance(...)      // Optional<Float>
    if res.isPresent(): forca -= (res + 0.3) * 0.3
    if forca > 0 && damageCalculator.shouldBlockExplode(..., forca): set.add(pos)
    x += dir*0.3; y += dir*0.3; z += dir*0.3
    forca -= 0.225
retorna new ObjectArrayList<>(set)                              // it.unimi.dsi.fastutil
```

Notas importantes para a implementação:
- A penalidade de resistência aplica-se **ao entrar** no bloco (incluindo o bloco central).
- O `set` é um `HashSet` → **a ordem do vanilla não é determinística**; a nossa também não
  precisa de ser. `interactWithBlocks` não ordena.
- `hurtEntities()` corre **depois** do cálculo dos blocos → pode tocar em blocos já
  alterados; comportamento vanilla preservado porque não mexemos nessa parte.
- Só `ServerExplosion` calcula posições no servidor; o cliente só faz partículas/som.

## 3. Decisões de design

| # | Decisão | Porquê |
|---|---|---|
| D1 | Mixin único em `calculateExplodedPositions` (`@At("HEAD")`, `cancellable`) | Ponto único de falha; fallback para vanilla é trivial (`cir.setReturnValue(null)`) |
| D2 | `@Shadow` nos campos `level/center/radius/damageCalculator` | Evita access widener e acesso por reflexão |
| D3 | `TNT_ONLY` por omissão | Alvo declarado: TNT. Menor risco de alterar gameplay de outros mecanismos |
| D4 | Reutilizar `ExplosionDamageCalculator` em vez de reimplementar resistência | Compatibilidade com mods/plugins que customizem explosões |
| D5 | Energia em unidades vanilla (0.75 por bloco, `(r+0.3)*0.3` por resistência) | Paridade numérica direta, mais fácil de validar |
| D6 | Dijkstra com bucket queue (Dial), sem `PriorityQueue` | Custo `O(V)` em vez de `O(V log V)`; os custos são quase discretos (0.75/1.06/1.30) |
| D7 | `Long2FloatOpenHashMap` para visitados (chave = `BlockPos.asLong()`) | Sem alocação de `BlockPos` durante a expansão; fastutil já vem no Minecraft |
| D8 | Só fazer `getBlockState` no nó *processado*, nunca por vizinho | O `Long2FloatOpenHashMap.containsKey` é a "barreira" barata antes da operação cara |
| D9 | Cache de resistência por `BlockState` (identidade) | `getExplosionResistance` é constante por estado |
| D10 | `neighborhood` configurável (6/18/26) | Compensação entre fidelidade e velocidade; permite dumb-down se o formato divergir demais |
| D11 | Algoritmo `RAY_CACHE` como segunda opção | Plano B de alta fidelidade: mantém os 1352 raios mas memoiza blocos já amostrados |
| D12 | Comando por mixin em `Commands` | Evitar `fabric-command-api-v2` (requisito: sem Fabric API) |
| D13 | Mod Menu com `modCompileOnly` + guarda `isModLoaded` | Zero risco no servidor dedicado; o jar não declara dependência |
| D14 | Métricas desligadas por omissão | Overhead zero por omissão |

## 4. Fases / tarefas

### F0 — Ambiente (por fazer)
- [ ] `settings.gradle` + `build.gradle` com Loom `1.18-SNAPSHOT`, sem Yarn (Mojmap)
- [ ] `gradle.properties`: group, mod id, java 25, loader 0.19.5
- [ ] `gradle wrapper` (usar o wrapper já existente noutros repos como base)
- [ ] `fabric.mod.json`: `environment: "server"`, sem `fabric-api` em `depends`
- [ ] `optimizedtnt.mixins.json` + `optimizedtnt.refmap.json`
- [ ] Build limpo e `./gradlew runServer` a arrancar

### F1 — Config (por fazer)
- [ ] `OptimizedTntConfig` com Gson (já disponível no MC), defaults seguros
- [ ] `load()` tolerante a JSON corrompido (não crashar o servidor, usar defaults + aviso)
- [ ] `save()` automático em alteração
- [ ] Enum `Scope` (`TNT_ONLY`, `ALL_EXPLOSIONS`) e `Algorithm` (`WAVEFRONT`, `RAY_CACHE`, `VANILLA`)
- [ ] Log no arranque com a config efetiva

### F2 — Núcleo wavefront (por fazer)
- [ ] `ExplosionWavefront#compute(ServerLevel, Vec3, float, ExplosionDamageCalculator, Explosion self)`
- [ ] Bucket queue com energia quantizada (passo 0.05) + lista de visitados `long`→energia
- [ ] Expansão 6/18/26 com custos 0.75 / 1.0607 / 1.2990
- [ ] Penalidade `(r + 0.3) * 0.3` à entrada, usando `getBlockExplosionResistance`
- [ ] `isInWorldBounds` check antes de qualquer acesso
- [ ] Saída em `ObjectArrayList<BlockPos>`
- [ ] `ResistanceCache` por identidade de `BlockState`
- [ ] Teste standalone (JUnit via `runServer` ou `test`) contra o vanilla: contagens e desvio

### F3 — Integração (por fazer)
- [ ] `ServerExplosionMixin` com `@Inject` cancellable e gate por config + scope
- [ ] Scope `TNT_ONLY`: `getDirectSourceEntity() instanceof PrimedTnt || instanceof MinecartTNT`
- [ ] Guardar `explode()` para metrics (blocos, nanos) — ou usar profiler `explosion_blocks`
- [ ] Fallback: config off / scope mismatch / exceção → vanilla (try/catch que regista 1 vez)

### F4 — Comandos (por fazer)
- [ ] `CommandsMixin` a registar o literal `/optimizedtnt` (perm nível 2)
- [ ] `status`, `reload`, `save`, `compare`
- [ ] `compare`: corre ambos os algoritmos e loga `|A−B|`, `A−B`, `B−A` (paridade)

### F5 — Ray cache (opcional)
- [ ] `ExplosionRayCache`: mesmo loop vanilla + `LongOpenHashSet` de visitados por raio
- [ ] Medir vs wavefront; escolher default consoante o resultado

### F6 — Mod Menu (opcional)
- [ ] `modCompileOnly` Mod Menu, entrypoint `client` guardado
- [ ] `OptimizedTntConfigScreen` a editar o mesmo JSON
- [ ] `custom.modmenu` links no `fabric.mod.json`

### F7 — Validação e polish
- [ ] Teste de paridade em cenários: cratera em terreno plano, paredes, caixa fechada,
      obsidiana (resistência 3600), água, portas, TNT em cadeia, end crystal, wind charge
- [ ] Benchmark com `spark`: 1 TNT, 10 TNTs simultâneas, 50 TNTs, TNT dentro de chunk carregado
- [ ] Verificar `explode()` return value (contagem de blocos) igual ao esperado
- [ ] README final, screenshots/logs de benchmark, release no GitHub

## 5. Riscos e mitigações

| Risco | Impacto | Mitigação |
|---|---|---|
| Wavefront mais lenta que o vanilla em explosões pequenas | Ganho nulo | `metrics` + benchmark; `neighborhood: 6`; escolher `RAY_CACHE` como default se o wavefront não ganhar |
| Formato da cratera diferente do vanilla | Feedback negativo de jogadores | `TNT_ONLY` por omissão, `neighborhood` ajustável, `/optimizedtnt compare` para documentar o desvio |
| Conflito com outro mod que mixine `calculateExplodedPositions` | Crash ou duplo cálculo | Detetar se outro mixin já injetou no mesmo ponto (log) e desligar a otimização com aviso |
| Quebra em versões futuras da 26.x | Mod deixa de carregar | Alvo `26.3` estrito no `fabric.mod.json`; mixin `require = 1` para falhar cedo e claramente |
| Chunk não carregado / `isInWorldBounds` | Exceções | Reutilizar o check vanilla antes de qualquer acesso ao mundo |
| Determinismo entre servidores | Replays/difis de seed | `random.nextFloat()` continua a ser chamado **uma vez por explosão**, com o mesmo consumo da sequência → não altera a sequência aleatória do RNG do servidor |
| Overhead do cache de resistência | Memória | Cache pequeno (limite de N entradas, ex. 4096) ou `WeakHashMap` por identidade |

## 6. Perguntas em aberto

1. **Fidelidade vs velocidade:** aceitar o desvio de formato em cantos/obstáculos, ou
  haustar `RAY_CACHE` como default? (decidir após F2 + benchmark)
2. **Vizinhança default:** 26 (rápido, mais divergent) ou 18 (meio termo)?
3. **`ALL_EXPLOSIONS`:** incluir creepers/wind charges/end crystals desde já, ou ficar
   atrás de flag? (assumido: incluído, atrás de config)
4. **Mod Menu:** vale a pena o `entrypoint client` num mod declarado `server`? Alternativa
   é não ter UI e só config por ficheiro + comando.
5. **Nome/id definitivo:** `optimizedtnt`? (repo chama-se `OtimizedTNT`, com typo)
6. **Suporte a 26.2/26.4:** o Loom multiversion é viável aqui ou ficamos só em 26.3?

## 7. Log de decisões

- **2026-10-08** — Confirmado que a 26.3 ainda usa o algoritmo de raios em
  `ServerExplosion#calculateExplodedPositions` (verificado no bytecode); a otimização faz
  sentido e o alvo está correto.
- **2026-10-08** — `TntBlockEntity` deixou de existir em 26.3; a deteção de TNT passa a ser
  por entidade (`PrimedTnt`, `MinecartTNT`).
- **2026-10-08** — Sem Fabric API: comandos via mixin em `Commands`, config via Gson.
- **2026-10-08** — Mojmap em vez de Yarn (o que está disponível em cache é Mojmap e é o
  que o outro repo 26.3 do utilizador usa).
- **2026-10-08** — Adicionados `AGENTS.md`, `opencode.json`, `.gitignore` e 10 skills de opencode em `.opencode/skills/` (auto-commit, auto-push, progress-tracking, fabric-mod-setup, gradle-build-verify, mixin-explosion, wavefront-algorithm, unit-testing-parity, benchmark-optimization, release-github). Pontos a corrigir no plano identificados em `AGENTS.md` (resistência, energia inicial, aleatoriedade, fallback).
