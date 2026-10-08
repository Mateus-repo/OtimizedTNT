# Optimized TNT

Mod **server-side** para Minecraft **26.3** (Fabric) que substitui o cálculo de explosões do
vanilla — baseada em 1352 raios sobrepostos — por uma **wavefront expansion (Dijkstra sobre
blocos)**, em que cada bloco candidato é avaliado **uma única vez**.

| | |
|---|---|
| Minecraft | 26.3 |
| Loader | Fabric 0.19.5+ |
| Lado | **Servidor dedicado** (nenhum pacote é enviado ao cliente) |
| Fabric API | **Não necessária** |
| Java | 25 |
| mappings | Mojmap (oficiais) |
| Licença | MIT |

> Estado: em planeamento/implementação. Ver [`progresso.md`](progresso.md) para o plano
> detalhado, tarefas e decisões em aberto.

---

## O problema

Em 26.3 o vanilla calcula os blocos afetados em
`net.minecraft.world.level.ServerExplosion#calculateExplodedPositions()` com um loop
`16 × 16 × 16` que gera **1352 raios** (`16³ − 14³`, só a "casca" do cubo) e marcha cada um
em passos de `0.3` blocos até a força chegar a zero:

```java
for (int i = 0; i < 16; i++) for (int j = 0; j < 16; j++) for (int k = 0; k < 16; k++) {
    // ... normaliza direção (i/15*2-1, j/15*2-1, k/15*2-1)
    float strength = (radius + 0.7F * random.nextFloat() * 0.6F) * radius;
    while (strength > 0.0F) {
        BlockPos pos = BlockPos.containing(x, y, z);
        // getBlockState + getFluidState + getBlockExplosionResistance + shouldBlockExplode
        if (strength > 0.0F && damageCalculator.shouldBlockExplode(...)) set.add(pos);
        x += dx * 0.3; y += dy * 0.3; z += dz * 0.3;
        strength -= 0.225F;
    }
}
```

Os raios atravessam-se: numa TNT de raio 4 são ~34 000 amostras de `BlockPos` para ~1 800
blocos realmente distintos. O `HashSet` só evita duplicados **no resultado** — o trabalho
(`getBlockState`, `getFluidState`, resistência, `shouldBlockExplode`) já foi feito. Numa
corrente de TNT isso multiplica-se e atira o tick do servidor abaixo dos 20 TPS.

## A solução: wavefront / Dijkstra em blocos

Em vez de disparar 1352 raios e deixar que se cruzem, expandimos a partir do centro uma
frente que guarda, para cada bloco, a **energia restante**. A energia usa exatamente as
unidades do vanilla (unidades `strength`).

O truque para manter a paridade é perceber que **no vanilla ambos os custos são proporcionais
ao comprimento do caminho percorrido**, e não ao número de blocos:

- o travelling custa `0.225` por amostra de `0.3` de comprimento → **`0.75` por unidade de
  comprimento**;
- a resistência custa `(r + 0.3) × 0.3` por amostra de `0.3` → **`(r + 0.3)` por unidade de
  comprimento dentro do bloco**.

Isto é independente da direção do raio: um raio diagonal gasta o mesmo *por unidade de
comprimento*, só que atravessa mais blocos. Logo a onda pode avançar bloco a bloco com:

| Evento | Custo (unidades vanilla) |
|---|---|
| Percorrer comprimento `L` (viagem) | `0.75 × L` |
| Atravessar resistência `r` no mesmo comprimento `L` | `(r + 0.3) × L × resistanceFactor` |
| — eixo X/Y/Z | `L = 1` |
| — diagonal de face | `L = √2 ≈ 1.4142` |
| — diagonal de canto | `L = √3 ≈ 1.7321` |
| Energia inicial | `radius × (0.7 + 0.6 × random.nextFloat())` |

`resistanceFactor` vale `1.0` por omissão (o valor que reproduz o vanilla em médias) e existe
para compensar os casos em que o vanilla só amostra 1–2 vezes dentro de um bloco (efeito
"corner clipping"), que a onda não reproduz.

Um bloco é registado quando, **após** subtrair a resistência, a energia ainda é `> 0` e
`shouldBlockExplode(...)` devolve `true` — exatamente a semântica do vanilla, incluindo a
penalidade aplicada ao bloco central.

Consequências:

- **Cada `BlockPos` é consultado uma vez.** O custo passa de `O(raios × passos)` para
  `O(blocos alcançados)`.
- A resistência continua a vir de `ExplosionDamageCalculator`
  (`getBlockExplosionResistance` / `shouldBlockExplode`), pelo que mods e plugins que
  customizem a resistência das explosões continuam a ser respeitados.
- Fila de prioridade própria (bucket queue / algoritmo de Dial, sem `log n`), mapa de visitados
  com chaves `BlockPos` empacotadas em `long` e cache de resistência por `BlockState` — o
  `getBlockState` só é chamado para nós que são realmente processados.
- **A sequência aleatória do servidor não muda.** O vanilla sorteia `nextFloat()` **1352 vezes
  por explosão** (um por raio) e esse consumo é observável por qualquer outro sistema. O mod
  consome exatamente os mesmos 1352 valores e usa a **média** como energia única
  (`randomnessMode: MEAN`), pelo que replays e seeds continuam reprodutíveis.

## Diferenças face ao vanilla (honestidade)

A wavefront usa vizinhança de **26** posições (ou 18/6, configurável) em vez de amostragem
contínua de um raio. Consequências:

- **O formato da explosão pode diferir ligeiramente**, sobretudo em **cantos** e ao redor de
  **obstáculos** (paredes finas, folhagem, portas): a cratera pode ficar 1 bloco mais larga ou
  mais estreita em pontos isolados.
- Blocos em posições "diagonalmente acessíveis" (por onde nenhum raio do vanilla passa
  exatamente) podem ser destruídos ou poupados de forma diferente.
- Diagonais puras (sem vizinho partilhado) nunca são atravessadas pelo vanilla; a wavefront
  atravessa-as. Reduzir a vizinhança para 6/18 aproxima o comportamento do vanilla à custa de
  performance — é exatamente isso que a opção `neighborhood` permite.

Tudo o resto (fogo, drops, decay, entity damage, knockback, `getHitPlayers`) continua a ser
tratado pelo código vanilla intacto: o mod só substitui **quais** blocos são afetados.

## Instalação (servidor vanilla com Fabric)

1. Compilar ou descarregar o `.jar` (ver [Build](#build)).
2. Copiar o jar para a pasta `mods/` do servidor.
3. Reiniciar. Sem Fabric API, sem libraries extra, sem client-side.

Na primeira vez é criado `config/optimizedtnt.json`.

## Configuração

Ficheiro `config/optimizedtnt.json` (guardado automaticamente):

```json
{
  "enabled": true,
  "scope": "TNT_ONLY",
  "algorithm": "WAVEFRONT",
  "neighborhood": 26,
  "resistanceFactor": 1.0,
  "randomnessMode": "MEAN",
  "cacheBlockResistance": true,
  "metrics": false
}
```

| Chave | Valores | Descrição |
|---|---|---|
| `enabled` | `true` / `false` | Liga/desliga a otimização globalmente, sem reiniciar o servidor. |
| `scope` | `TNT_ONLY` / `ALL_EXPLOSIONS` | Só TNT (`PrimedTnt` / `MinecartTNT`) ou todas as explosões (creepers, wind charges, end crystals, beds, etc.). |
| `algorithm` | `WAVEFRONT` / `RAY_CACHE` / `VANILLA` | `WAVEFRONT` é o novo; `RAY_CACHE` mantém os raios mas memoiza blocos já visitados (mais fiel, mais lento); `VANILLA` desliga a substituição. |
| `neighborhood` | `6` / `18` / `26` | Vizinhança da expansão. Mais alto = mais fiel e mais rápido. |
| `resistanceFactor` | float `≥ 0` | Multiplicador do custo de resistência. `1.0` reproduz o vanilla em média; valores menores dão explosões maiores. Ajustar com `/optimizedtnt compare`. |
| `randomnessMode` | `MEAN` / `PER_DIRECTION` | `MEAN` usa a média dos 1352 sorteios do vanilla (uma energia para toda a explosão). `PER_DIRECTION` agrupa os sorteios por direção, aproximando a variação angular do vanilla. Ambos consomem os 1352 valores. |
| `cacheBlockResistance` | `true` / `false` | Cache da resistência por `BlockState` (só quando o calculador é o vanilla conhecido). |
| `metrics` | `true` / `false` | Log de contadores (blocos visitados, amostras vanilla equivalentes) para benchmark. |

### Comandos (sem Fabric API)

| Comando | Descrição |
|---|---|
| `/optimizedtnt status` | Mostra config ativa, explosões processadas e tempo médio gasto. |
| `/optimizedtnt reload` | Relê o JSON (permite ligar/desligar sem reiniciar). |
| `/optimizedtnt save` | Grava o estado atual no JSON. |
| `/optimizedtnt compare` | Executa os dois algoritmos na próxima explosão e loga o desvio (paridade). |

Requer permissão de administrador. Em 26.x a API já não é `hasPermission(int)`: usa-se
`Commands.LEVEL_ADMINS.check(source.permissions())`. Registados por mixin em `Commands`, logo
não é preciso `fabric-command-api-v2`.

### Mod Menu (opcional)

A integração é **opcional e inofensiva num servidor dedicado**: o `ModMenuApi` só é invocado
no entrypoint `client`, que nunca é carregado no servidor. Só é funcional se o jar for
também instalado no cliente — o que **não** é necessário para o servidor. Usa
`modCompileOnly`, pelo que o jar final não depende do Mod Menu.

## Como está feito

```
src/main/java/…/optimizedtnt/
├── OptimizedTnt.java              # ModInitializer: config + comando
├── config/OptimizedTntConfig.java # POJO + load/save JSON
├── command/OptimizedTntCommand.java
├── explosion/ExplosionWavefront.java      # algoritmo (Dijkstra + bucket queue)
├── explosion/ExplosionRayCache.java       # alternativa fiel (memoização)
├── explosion/ResistanceCache.java         # cache por BlockState
└── mixin/ServerExplosionMixin.java        # @Inject em calculateExplodedPositions (HEAD, cancellable)
    mixin/CommandsMixin.java               # registo do comando (sem Fabric API)
```

Ponto único de mixin: `ServerExplosion#calculateExplodedPositions`. O `@Inject` só cancela
quando a otimização está ativa **e** o escopo faz match; caso contrário **não cancela** e o
vanilla corre intacto (não usar `setReturnValue(null)`, que devolveria `null` e partiria
`interactWithBlocks`). `ServerLevel`, `center`, `radius` e `damageCalculator` são acessados
por `@Shadow`, por isso **não é necessário access widener**.

## Build

```bash
./gradlew build          # ou  .\gradlew.bat build  no Windows
```

Saída em `build/libs/optimizedtnt-1.0.0.jar` (o `-sources.jar` é ignorado na distribuição).

| Componente | Versão |
|---|---|
| Minecraft | 26.3 |
| Fabric Loom | `1.18-SNAPSHOT` (resolve para **1.18.3**) |
| fabric-loader | 0.19.5 (traz Mixin 0.8.7 e MixinExtras 0.5.5) |
| Java toolchain | 25 |
| mappings | Mojmap (oficiais, via `minecraft "com.mojang:minecraft:26.3"`) |
| Testes | JUnit 6.1.3 |

## Como verificar que funciona

1. `./gradlew runServer` num mundo de teste.
2. `/optimizedtnt metrics true`, depois TNT de raio 4 e de raio grande (end crystal, wind
   charge encadeado, `/summon` em cadeia).
3. `/optimizedtnt compare` → deve reportar desdevios deshape expected nas bordas e **zero**
   diferenças no miolo da cratera.
4. `spark` antes/depois: o ganho maior aparece em cascatas de TNT e em duty cycles com
   muitas explosões por tick.

## Compatibilidade

- Funciona com mods que substituam `ExplosionDamageCalculator` (a resistência continua a
  ser consultada).
- Se outro mod injetar/cancelar `calculateExplodedPositions`, há conflito potencial — documentado
  em `progresso.md` com a estratégia de fallback.
- Clientes vanilla **não precisam** de instalar nada (o mod é `environment: "server"`).

## Licença

MIT — ver [`LICENSE`](LICENSE).
