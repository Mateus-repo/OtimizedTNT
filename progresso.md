# Progresso — Optimized TNT (Fabric 26.3, server-side)

Documento de trabalho: planeamento, decisões tomadas, tarefas e riscos.
O README é para o utilizador final; este ficheiro é o plano técnico e o registo de progresso.

**Estado global:** F0 a F5 concluídas e verificadas (build, testes e arranque do servidor).
**F7 (validação in-game) bloqueada pela EULA.** F6 (Mod Menu) descartada por decisão de âmbito.
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
- Toolchain: Java 25, Loom `1.18-SNAPSHOT` (que resolve para **1.18.3**), fabric-loader `0.19.5`
- Confirmado no arranque do `runServer`: Mixin **0.8.7** e MixinExtras **0.5.5** vêm
  transitivamente pelo loader → **não** declarar `org.spongepowered:mixin` (a 0.8.5 nem existe
  nos repositórios do Loom e faz o build falhar).
- Loom 1.18 **já não usa `loom.mixin { defaultRefmapName }`** (aviso do próprio Loom: *"The
  mixin annotation is no longer enabled by default"*). Removido o bloco `mixin` do `build.gradle`
  e a chave `refmap` do `optimizedtnt.mixins.json`; o refmap não é gerado.

### Algoritmo vanilla 26.3 (confirmado no bytecode)

```
raios: 16³ − 14³ = 1352 (só a casca: pelo menos um eixo em {0, 15})
dir   = normaliza(i/15*2−1, j/15*2−1, k/15*2−1)
forca = radius * (0.7 + 0.6 * random.nextFloat())     // ← confirmado; UM nextFloat por raio
while (forca > 0):
    pos = BlockPos.containing(x, y, z)
    state = getBlockState(pos); fluido = getFluidState(pos)
    if !level.isInWorldBounds(pos): ABORTA o raio inteiro
    res = damageCalculator.getBlockExplosionResistance(...)      // Optional<Float>
    if res.isPresent(): forca -= (res + 0.3) * 0.3   // ← penalidade ANTES do teste
    if forca > 0 && damageCalculator.shouldBlockExplode(..., forca): set.add(pos)
    x += dir*0.3; y += dir*0.3; z += dir*0.3
    forca -= 0.225                                  // ← por amostra de 0.3
retorna new ObjectArrayList<>(set)                  // it.unimi.dsi.fastutil
```

**Os 4 pontos pendentes do `AGENTS.md`, resolvidos com `javap -p -c` (ver §8):**

| Ponto | Conclusão |
|---|---|
| 1. Escala da resistência | A penalidade é `(r + 0.3) × 0.3` **por amostra de 0.3 de comprimento**, não por bloco. Como os dois custos são proporcionais ao comprimento percorrido, o modelo passa a ser **por unidade de caminho**: viagem `0.75 × L`, resistência `(r + 0.3) × L × resistanceFactor`. Num raio axial dá `(r + 0.3)` por bloco — confirma a intuição da skill, mas o `(r + 0.3) × 0.3` por bloco do texto antigo dava crateras ~3,3× maiores. |
| 2. Energia inicial | `E0 = radius × (0.7 + 0.6 × random.nextFloat())`. Tanto o README antigo (`radius × (1 + 0.42×rand)`) como o pseudo-código anterior (`(radius + 0.7*rand*0.6) * radius`) estavam errados. Para raio 4: `E0 ∈ [2.8, 5.2]`. |
| 3. Aleatoriedade | O `nextFloat()` é chamado **1352 vezes por explosão** (1 por raio). Para não alterar a sequência do RNG do servidor, o mod consome os mesmos 1352 valores e usa a **média** (`randomnessMode: MEAN`); `PER_DIRECTION` fica como opção. O §5 afirmava que 1 sorteio não alterava o RNG — **estava errado**, corrigido em D16. |
| 4. Ordem e bloco central | A penalidade **é** aplicada ao bloco central (o raio começa por o amostrar) e o `set.add` só acontece **depois** de subtrair a resistência, com teste `forca > 0`. Ordem: penalidade → teste de energia → `shouldBlockExplode` → `add`. |

Outras confirmações relevantes:

- Fora dos limites do mundo **aborta o raio inteiro** (não é só saltar o bloco).
- O `set` é um `HashSet` → **a ordem do vanilla não é determinística**; a nossa também não
  precisa de ser. `interactWithBlocks` não ordena.
- `hurtEntities()` corre **depois** do cálculo dos blocos → comportamento vanilla preservado
  porque não mexemos nessa parte.
- Só `ServerExplosion` calcula posições no servidor; o cliente só faz partículas/som.
- `BlockPos.asLong(int, int, int)` existe → mapa de visitados sem alocar `BlockPos`.
- `Level.isInWorldBounds(BlockPos)` é o método a usar.

### Comandos e permissões em 26.3 (confirmado)

- `net.minecraft.commands.Commands` — construtor `Commands(CommandSelection, CommandBuildContext)`,
  dispatcher obtido por `getDispatcher()`; registar o literal no `@Inject` do construtor (RETURN).
- `CommandSourceStack.permissions()` → `net.minecraft.server.permissions.PermissionSet`.
- `PermissionCheck.check(PermissionSet)`; constantes prontas em `Commands`:
  `LEVEL_ALL`, `LEVEL_MODERATORS`, `LEVEL_GAMEMASTERS`, `LEVEL_ADMINS`, `LEVEL_OWNERS`.
- **Não existe** `hasPermission(int)` em 26.x → usar `Commands.LEVEL_ADMINS.check(src.permissions())`.

## 3. Decisões de design

| # | Decisão | Porquê |
|---|---|---|
| D1 | Mixin único em `calculateExplodedPositions` (`@At("HEAD")`, `cancellable`) | Ponto único de falha; o fallback para o vanilla é um `return` sem cancelar |
| D2 | `@Shadow` nos campos `level/center/radius/damageCalculator` | Evita access widener e acesso por reflexão |
| D3 | `TNT_ONLY` por omissão | Alvo declarado: TNT. Menor risco de alterar gameplay de outros mecanismos |
| D4 | Reutilizar `ExplosionDamageCalculator` em vez de reimplementar resistência | Compatibilidade com mods/plugins que customizem explosões |
| D5 | Energia em unidades vanilla, custos **por unidade de caminho** (D15) | Paridade numérica derivável em vez de aproximado |
| D6 | Dijkstra com bucket queue (Dial), sem `PriorityQueue` | Custo `O(V)` em vez de `O(V log V)`; os custos são quase discretos |
| D7 | `Long2FloatOpenHashMap` para visitados (chave = `BlockPos.asLong()`) | Sem alocação de `BlockPos` durante a expansão; fastutil já vem no Minecraft |
| D8 | Só fazer `getBlockState` no nó *processado*, nunca por vizinho | O `containsKey` é a barreira barata antes da operação cara |
| D9 | Cache de resistência por `BlockState` (identidade) | `getExplosionResistance` é constante por estado |
| D10 | `neighborhood` configurável (6/18/26) | Compensação entre fidelidade e velocidade |
| D11 | Algoritmo `RAY_CACHE` como segunda opção | Plano B de maior fidelidade |
| D12 | Comando por mixin em `Commands` | Evitar `fabric-command-api-v2` (requisito: sem Fabric API) |
| D13 | Mod Menu com `modCompileOnly` + guarda `isModLoaded` | Zero risco no servidor dedicado; o jar não declara dependência |
| D14 | Métricas desligadas por omissão | Overhead zero por omissão |
| D15 | Resistência cobrada **por unidade de comprimento**, `L × 0.75` (viagem) e `L × (r + 0.3) × resistanceFactor` (resistência) | No vanilla os dois custos são proporcionais ao comprimento percorrido (`0.225`/`0.3` e `(r+0.3)×0.3`/`0.3`), logo o modelo correto é por caminho e não por bloco. `resistanceFactor = 1.0` é exato para raios axiais em média |
| D16 | Consumir os **1352** `nextFloat()` do vanilla e usar a média (`MEAN`) | O consumo do RNG do servidor é observável; 1 único sorteio mudaria replays/seeds. `PER_DIRECTION` fica configurável |
| D17 | Permissões via `Commands.LEVEL_ADMINS.check(src.permissions())` | `hasPermission(int` não existe em 26.x |
| D18 | Fallback = `return` sem `cir.setReturnValue(...)` | `setReturnValue(null)` cancela o método e devolveria `null`, partindo `interactWithBlocks` |
| D19 | group `io.github.mateusrepo`, pacote `io.github.mateusrepo.optimizedtnt` | Identidade GitHub do utilizador (`Mateus-repo`); pacote neutro e publicável. **Confirmar com o utilizador** — mudar agora é trivial |
| D20 | JUnit 6.1.3 (BOM) | Versão estável atual confirmada em Maven Central, não inventada |
| D21 | Resistência cobrada ao **relaxar a aresta** | Se for cobrada ao extrair o nó, a fila ordena pela energia errada e o Dijkstra é inválido: cada bloco era lido 2–3 vezes |
| D22 | Meia-folga de passo no registo | O vanilla amostra a face de entrada, não o centro; sem a folga a onda fica 37% mais pequena (medido) |
| D23 | "Destruir" (primeira amostra) separado de "propagar" (bloco inteiro) | Em pedra o vanilla destrói o primeiro bloco sem o atravessar; sem esta separação a onda dava 1 bloco em vez de 10 (medido) |
| D24 | `FloatSource` próprio no núcleo | O `RandomSource` de 26.3 não implementa `RandomGenerator`, e o núcleo não deve depender do Minecraft |

## 4. Fases / tarefas

### F0 — Ambiente (concluída)
- [x] `settings.gradle` + `build.gradle` com Loom `1.18-SNAPSHOT` (→1.18.3), sem Yarn (Mojmap)
- [x] `gradle.properties`: mod id `optimizedtnt`, v1.0.0, Java 25, loader 0.19.5, MC 26.3
- [x] Gradle wrapper copiado do repo 26.3 de referência (Gradle 9.8.0)
- [x] `fabric.mod.json`: `environment: "server"`, sem `fabric-api` em `depends`
- [x] `optimizedtnt.mixins.json` com `injectors.defaultRequire: 1` (sem `refmap`, sem
      `compatibilityLevel` — não assumir; lista de mixins vazia até F3)
- [x] Entry point `OptimizedTnt` (ModInitializer)
- [x] `./gradlew build` verde → `build/libs/optimizedtnt-1.0.0.jar`, `fabric.mod.json`
      expandido corretamente (versões reais, sem `${...}` literais)
- [x] `./gradlew runServer`: Minecraft 26.3 + Loader 0.19.5 arrancam, o mod carrega
      (*"Optimized TNT carregado"*) e o servidor para na EULA — **por desenho, a EULA não é
      aceite pelo agente**; falta o utilizador a aceitar para poder testar o jogo
- [ ] `runServer` completo (depende do utilizador aceitar `run/server/eula.txt`)

### F1 — Config (concluída)
- [x] `OptimizedTntConfig` com Gson, enums `Scope`/`Algorithm`/`RandomnessMode`
- [x] `load()` tolerante (JSON corrompido → defaults + aviso, nunca crasha), grava se não existe
- [x] `reload()` / `save()` com validação de `neighborhood` (6/18/26) e `resistanceFactor`
- [x] Setters públicos (o comando vive noutro pacote)
- [x] Verificado in-game: `config/optimizedtnt.json` criado no primeiro arranque

### F2 — Núcleo wavefront (concluída)
- [x] `BlockProbe` como interface mínima + `FloatSource` (o `RandomSource` de 26.3 **não**
      implementa `java.util.random.RandomGenerator` — confirmado com javap)
- [x] `ExplosionWavefront`: Dijkstra com min-heap de arrays primitivos e dois mapas abertos
      `long → float` com contador de geração (sem limpar tabela entre explosões)
- [x] **Resistência cobrada ao relaxar a aresta**, não ao extrair o nó — sem isto a fila
      ordena pela energia errada, o Dijkstra é inválido e cada bloco é lido 2 a 3 vezes
- [x] Meia-folga de passo (o vanilla amostra a face de entrada, não o centro) e regra da
      primeira amostra (destruir ≠ propagar) — ambas decididas com medições, ver §7
- [x] Tabelas de vizinhança 6/18/26 pré-calculadas
- [x] `ExplosionRandomEnergy`: consome os 1352 `nextFloat()` do vanilla (D16)
- [x] `VanillaRayExplosion` como oráculo exacto (réplica do bytecode)
- [x] 19 testes JUnit verdes, incluindo "cada bloco é lido uma única vez"

### F3 — Integração (concluída)
- [x] `ExplosionOptimizer` (adaptador ao Minecraft, único ponto que fala com o jogo)
- [x] `ServerExplosionMixin` com `@Inject` cancellable e gate por config + scope
- [x] Scope `TNT_ONLY` por `PrimedTnt` / `MinecartTNT`
- [x] Só 1 `@Shadow` (`damageCalculator`); o resto usa a API pública da interface `Explosion`
- [x] Fallback: `try/catch` + `reportFailure` (uma mensagem só) + vanilla
- [x] `ExplosionMetrics` desligado por omissão (custo zero)
- [x] Cache de resistência por `BlockState` só quando o calculador é a classe base do vanilla

### F4 — Comandos (concluída)
- [x] `CommandsMixin` a registar o literal (inject no construtor, com os parâmetros do alvo)
- [x] `status`, `reload`, `save`, `on|off`, `algorithm`, `scope`, `randomness`, `neighborhood`,
      `resistance`, `metrics`, `compare`
- [x] Permissões com a API de 26.x: `Commands.LEVEL_ADMINS.check(src.permissions())` (D17)
- [x] `MinecraftServerMixin` grava a config ao parar o servidor

### F5 — Ray cache (concluída)
- [x] `ExplosionRayCaster` com percurso único e memoização opcional da resistência
- [x] `ExplosionRayCache` = vanilla + memo → **resultado idêntico ao vanilla**, verificado por teste
- [x] `ExplosionComparator` para `/optimizedtnt compare`, com gerador determinístico para não
      tocar no RNG do mundo

### F6 — Mod Menu (descartada)
- [x] Decidido não incluir: o mod é `environment: "server"` e uma UI só existe no cliente.
      Se for preciso, fazer um mod cliente separado que partilhe o mesmo ficheiro de
      configuração.

### F7 — Validação (parcial)
- [x] Testes de paridade: ar, terra, pedra, obsidiana, água, caixa fechada, folha, limites do
      mundo, vizinhança 6/18/26, raios 4/5/6/8
- [x] Leituras de bloco medidas (o ganho real)
- [ ] **In-game**: comparar crateras reais, `/optimizedtnt compare` num TNT, medir MSPT
      (bloqueado: a EULA de `run/server/eula.txt` tem de ser aceite pelo utilizador)
- [ ] Benchmark com `spark` e registo em `docs/benchmarks.md`

## 5. Resultados medidos (`./gradlew test`)

Grelha em memória, energia fixa (mesma nos dois lados), apenas blocos cheios de um meio.

| Cenário | Blocos vanilla → onda | Leituras vanilla → onda | Ganho |
|---|---|---|---|
| Raio 4, ar | 796 → 799 | 24 336 → 799 | **30×** |
| Raio 5, ar | 1 518 → 1 359 | 31 096 → 1 359 | **23×** |
| Raio 8, ar | 5 136 → 4 963 | 48 672 → 4 963 | **9,8×** |
| Raio 6, terra | — | 22 984 → 855 | **27×** |
| Raio 8, pedra | 10 → 1 | 5 408 → 27 | **200×** |

Jaccard (sobreposição) da onda vs vanilla: 0.86 a 0.88 no ar, 0.72 a 0.80 em terra.
Desvio no miolo da cratera (≤ 25% do alcance): **0% a ~10%**.

`RAY_CACHE` devolve **exactamente** o conjunto do vanilla (teste passa para raios 4/6/8 e para
o cenário com obsidiana e água).

## 6. Riscos e mitigações

| Risco | Impacto | Mitigação |
|---|---|---|
| Wavefront mais lenta que o vanilla em explosões pequenas | Ganho nulo | `metrics` + benchmark; `neighborhood: 6`; escolher `RAY_CACHE` como default se o wavefront não ganhar |
| Formato da cratera diferente do vanilla | Feedback negativo de jogadores | `TNT_ONLY` por omissão, `neighborhood` ajustável, `/optimizedtnt compare` para documentar o desvio |
| Conflito com outro mod que mixine `calculateExplodedPositions` | Crash ou duplo cálculo | Detetar se outro mixin já injetou no mesmo ponto (log) e desligar a otimização com aviso |
| Quebra em versões futuras da 26.x | Mod deixa de carregar | Alvo `26.3` estrito no `fabric.mod.json`; mixin `require = 1` para falhar cedo e claramente |
| Chunk não carregado / `isInWorldBounds` | Exceções | Reutilizar o check vanilla antes de qualquer acesso ao mundo |
| Determinismo entre servidores | Replays/difis de seed | O vanilla consome **1352** `nextFloat()` por explosão; o mod consome exatamente os mesmos 1352 (D16). Mesmo assim a **forma** da cratera não é bit-a-bit igual à do vanilla — é o custo aceite de trocar raios por uma onda |
| Overhead do cache de resistência | Memória | Cache pequeno (limite de N entradas, ex. 4096) ou `WeakHashMap` por identidade |

## 7. Perguntas em aberto

1. **Fidelidade vs velocidade:** aceitar o desvio de formato em cantos/obstáculos, ou
  haustar `RAY_CACHE` como default? (decidir após F2 + benchmark)
2. **Vizinhança default:** 26 (rápido, mais divergent) ou 18 (meio termo)?
3. **`ALL_EXPLOSIONS`:** incluir creepers/wind charges/end crystals desde já, ou ficar
   atrás de flag? (assumido: incluído, atrás de config)
4. **Mod Menu** — resolvido: **não incluído**. Um mod `environment: "server"` não tem onde
   mostrar uma UI; e num servidor não se instala nada nos jogadores. Se for preciso, faz-se um
   mod cliente separado a partilhar o mesmo ficheiro de configuração.
5. **Suporte a 26.2/26.4:** o Loom multiversion é viável aqui ou ficamos só em 26.3?
6. **Aceitação da EULA:** para a validação in-game é preciso o utilizador aceitar
   `run/server/eula.txt`; o agente não o faz por ele. Comando:
   `notepad run\server\eula.txt` → `eula=true`, depois `./gradlew runServer`.

## 8. Log de decisões

- **2026-10-08** — Confirmado que a 26.3 ainda usa o algoritmo de raios em
  `ServerExplosion#calculateExplodedPositions` (verificado no bytecode); a otimização faz
  sentido e o alvo está correto.
- **2026-10-08** — `TntBlockEntity` deixou de existir em 26.3; a deteção de TNT passa a ser
  por entidade (`PrimedTnt`, `MinecartTNT`).
- **2026-10-08** — Sem Fabric API: comandos via mixin em `Commands`, config via Gson.
- **2026-10-08** — Mojmap em vez de Yarn (o que está disponível em cache é Mojmap e é o
  que o outro repo 26.3 do utilizador usa).
- **2026-10-08** — **Resolvidos os 4 pontos pendentes** com `javap -p -c` sobre
  `minecraft-common-deobf-26.3.jar`:
  1. *Escala da resistência* — o custo é **por unidade de caminho**, não por bloco
     (D15). Corrigidos README e §2; o texto anterior dava crateras ~3,3× grandes demais.
  2. *Energia inicial* — `E0 = radius × (0.7 + 0.6 × rand)`. Corrigidos README e §2.
  3. *Aleatoriedade* — são **1352** sorteios por explosão; vamos consumi-los todos e usar a
     média (D16). Corrigida a afirmação errada em §6.
  4. *Ordem/bloco central* — penalidade no bloco central e `add` só depois de subtrair a
     resistência, com `forca > 0`. Igual ao que o README já descrevia; confirmado.
- **2026-10-08** — Corrigido o texto de fallback em todo o lado: para deixar o vanilla correr
  **não se cancela** o `@Inject` (D18); `setReturnValue(null)` devolveria `null` e partiria
  `interactWithBlocks`.
- **2026-10-08** — Confirmado que `hasPermission(int)` **não existe** em 26.3; as permissões
  passaram a `PermissionSet`/`PermissionCheck` (D17). O `CommandsMixin` tem de usar
  `Commands.LEVEL_ADMINS.check(src.permissions())`.
- **2026-10-08** — **F0 concluída.** Três correções ao plano da skill `fabric-mod-setup`:
  (1) Loom 1.18 já não usa `loom.mixin`/refmap → removidos; (2) **não** declarar
  `org.spongepowered:mixin:0.8.5` (não existe nos repositórios do Loom; o Mixin 0.8.7 vem pelo
  loader); (3) JUnit 6.1.3 confirmado em Maven Central (D20).
- **2026-10-08** — Escolhidos group/pacote `io.github.mateusrepo(.optimizedtnt)` (D19) e o
  `archivesName` ficou só `mod_id` (o Gradle já acrescenta a versão).
- **2026-10-08** — **F1–F5 implementadas.** Três decisões de algoritmo que só apareceram ao
  medir, não ao ler (D21–D23):
  1. **A resistência é cobrada ao relaxar a aresta, não ao extrair o nó.** Com o custo no nó, a
     fila fica ordenada pela energia *antes* da resistência enquanto a propagação usa a energia
     *depois*: um nó com muita resistência sai cedo demais e volta a ser processado quando
     aparece um caminho melhor — cada bloco era lido 2 a 3 vezes. Medido: `maxReads` por posição
     passou de 3 para 1.
  2. **Meia-folga de passo.** O vanilla amostra a face de entrada do bloco, não o centro, e um
     bloco é registado se a energia chegar positiva a essa face. Sem a folga, a onda dava 499
     blocos contra 796 do vanilla no ar (raio 4) — 37% mais pequena.
  3. **Destruir ≠ propagar.** Num meio muito resistente o vanilla destrói o primeiro bloco com a
     energia da *primeira* amostra, mesmo sem o atravessar. Com o modelo "pago o bloco inteiro
     para propagar", a onda dava 1 bloco contra 10 do vanilla em pedra (raio 8). Separámos os
     dois testes e os números passaram a bater.
  - Também: `RandomSource` de 26.3 não implementa `RandomGenerator` → interface `FloatSource`
    própria; `BlockPos.of(int,int,int)` não existe em 26.3 → `BlockPos.of(long)`; `level.random`
    é `protected` → `level.getRandom()` (público); `isInWorldBounds` está em `Level` e não em
    `BlockGetter`.
- **2026-10-08** — **F6 (Mod Menu) descartada** ejustificada no README: um mod `server` não tem
  onde mostrar UI.
- **2026-10-08** — `options.encoding = 'UTF-8'` no build: sem isso o javac lê os fontes na
  codificação da plataforma (cp1252 no Windows) e as mensagens de log com acentos saem
  corrompidas.
- **2026-10-08** — `build.gradle` deixou de declarar `org.spongepowered:mixin:0.8.5` (inexistente
  nos repositórios do Loom; o 0.8.7 vem pelo loader) e deixou de usar `loom.mixin`/refmap.
- **2026-10-08** — Adicionados `AGENTS.md`, `opencode.json`, `.gitignore` e 10 skills de opencode em `.opencode/skills/` (auto-commit, auto-push, progress-tracking, fabric-mod-setup, gradle-build-verify, mixin-explosion, wavefront-algorithm, unit-testing-parity, benchmark-optimization, release-github). Pontos a corrigir no plano identificados em `AGENTS.md` (resistência, energia inicial, aleatoriedade, fallback).
