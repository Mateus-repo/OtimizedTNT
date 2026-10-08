# Optimized TNT

Mod **server-side** para Minecraft **26.3** (Fabric) que substitui o cálculo de explosões do
vanilla — 1352 raios sobrepostos — por uma **wavefront expansion (Dijkstra sobre blocos)**, em
que cada bloco candidato é lido do mundo **uma única vez**.

| | |
|---|---|
| Minecraft | 26.3 |
| Loader | Fabric 0.19.5+ |
| Lado | **Servidor dedicado** (nada é enviado ao cliente) |
| Fabric API | **Não necessária** |
| Java | 25 |
| mappings | Mojmap (oficiais) |
| Licença | MIT |

> Estado: funcional e com testes de paridade. A validação dentro do jogo (crateras reais, comando
> `/optimizedtnt compare`) está pendente de aceitar a EULA — ver
> [`progresso.md`](progresso.md).

---

## O problema

Em 26.3 o vanilla calcula os blocos afetados em
`net.minecraft.world.level.ServerExplosion#calculateExplodedPositions()` com um loop
`16 × 16 × 16` que gera **1352 raios** (`16³ − 14³`, só a "casca" do cubo) e marcha cada um em
passos de `0.3` blocos:

```java
for (int i = 0; i < 16; i++) for (int j = 0; j < 16; j++) for (int k = 0; k < 16; k++) {
    float strength = radius * (0.7F + random.nextFloat() * 0.6F);
    while (strength > 0.0F) {
        BlockPos pos = BlockPos.containing(x, y, z);
        // getBlockState + getFluidState + getBlockExplosionResistance + shouldBlockExplode
        if (strength > 0.0F && damageCalculator.shouldBlockExplode(...)) set.add(pos);
        x += dir.x * 0.3; y += dir.y * 0.3; z += dir.z * 0.3;
        strength -= 0.225F;
    }
}
```

Os raios atravessam-se. Medido neste terreno, uma explosão de raio 4 **no ar** faz **24 336
leituras de bloco** para destruir 796 blocos — porque cada bloco é amostrado 3 a 4 vezes, uma
por amostra de `0.3`. Numa corrente de TNT isso multiplica-se e atira o tick do servidor abaixo
dos 20 TPS.

## A solução

Em vez de disparar raios que se cruzam, expande-se uma frente a partir do centro: cada bloco é
lido **uma vez** e propagado **uma vez**.

O truque para manter a paridade é reparar que, no vanilla, **ambos os custos são proporcionais ao
comprimento do caminho percorrido** e não ao número de blocos:

- a viagem custa `0.225` por amostra de `0.3` → **`0.75` por unidade de comprimento**;
- a resistência custa `(r + 0.3) × 0.3` por amostra de `0.3` → **`(r + 0.3)` por bloco**
  atravessado (um bloco é atravessado por ~3,3 amostras).

Daí o modelo, nas mesmas unidades de energia do vanilla:

| Evento | Custo |
|---|---|
| Percorrer comprimento `L` (viagem) | `0.75 × L` |
| Atravessar resistência `r` (na propagação) | `(r + 0.3) × resistanceFactor` |
| — eixo X/Y/Z | `L = 1` |
| — diagonal de face | `L = √2 ≈ 1.4142` |
| — diagonal de canto | `L = √3 ≈ 1.7321` |
| Energia inicial | `radius × (0.7 + 0.6 × random.nextFloat())` |

E dois detalhes que vieram da leitura do bytecode e que fazem muita diferença:

- **meio passo de folga**: o vanilla não amostra o centro do bloco, mas a face por onde o raio
  entra, meio passo mais perto. Um bloco é registado se a energia ainda for positiva **nessa
  face** — sem esta folga a onda fica sempre mais pequena que o vanilla.
- **primeira amostra**: num bloco muito resistente, o vanilla destrói-o logo à primeira amostra,
  mesmo sem conseguir atravessá-lo. A onda distingue "destruir" (basta a primeira amostra) de
  "propagar" (é preciso atravessar).

### Resultados medidos

Valores de `./gradlew test`, com grelha em memória e energia fixa (comparação determinística):

| Cenário | Blocos (vanilla → onda) | **Leituras de bloco** | Ganho |
|---|---|---|---|
| Raio 4, no ar | 796 → 799 | 24 336 → **799** | **30×** |
| Raio 8, no ar | 5 136 → 4 963 | 48 672 → **4 963** | **9,8×** |
| Raio 6, terra | — | 22 984 → **855** | **27×** |
| Raio 8, pedra | 10 → 1 | 5 408 → **27** | **200×** |

Ou seja: o número de leituras de bloco deixa de crescer com o número de raios e passa a crescer
só com o volume da cratera.

## Diferenças face ao vanilla (honestidade)

A onda trabalha à escala do bloco, o vanilla amostra continuamente. Daí resultam desvios reais:

| Cenário | Desvio da contagem de blocos | Onde |
|---|---|---|
| Ar (raio 4 / 5 / 8) | 0,4% / 10% / 3,4% | casca exterior |
| Terra (raio 4 / 5 / 8) | +36% / −21% / +16% | cratera pequena, sensível a meio bloco |
| Pedra (raio 4 / 5 / 8) | 0 / −1 bloco / −9 blocos | limites (contagens absolutas minúsculas) |
| **Miolo da cratera** (≤ 25% do alcance) | **0% a ~10%** | — |

As causas, todas compreensíveis:

- a onda é simétrica ao **bloco** onde nasce o raio; o vanilla é simétrico ao **ponto exacto**;
- a onda chega a blocos "diagonalmente acessíveis" por onde nenhum raio passa exactamente, e não
  atravessa diagonais isoladas (o vanilla também não);
- numa diagonal a resistência é cobrada uma vez por bloco em vez de ser repartida pelos blocos
  que o passo atravessa.

Tudo o resto (fogo, drops, decay, entity damage, knockback, `getHitPlayers`) continua a ser
tratado pelo código vanilla intacto: o mod só substitui **quais** blocos são afetados.

**Se preferires paridade exacta a velocidade**, usa `algorithm: "RAY_CACHE"`: mantém os 1352
raios do vanilla mas memoriza a resistência por bloco, o que dá **resultado idêntico** ao vanilla
(também verificado por teste) com ~3× menos leituras de bloco.

## Instalação (servidor vanilla com Fabric)

1. Copia `optimizedtnt-1.0.0.jar` para a pasta `mods/` do servidor.
2. Reinicia. Sem Fabric API, sem libraries extra, sem nada no cliente.

Na primeira vez é criado `config/optimizedtnt.json`.

## Configuração

`config/optimizedtnt.json`, guardado automaticamente (e sempre que o servidor para):

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
| `enabled` | `true` / `false` | Liga/desliga a otimização, sem reiniciar o servidor. |
| `scope` | `TNT_ONLY` / `ALL_EXPLOSIONS` | Só TNT (`PrimedTnt` / `MinecartTNT`) ou todas as explosões (creepers, wind charges, end crystals, beds…). |
| `algorithm` | `WAVEFRONT` / `RAY_CACHE` / `VANILLA` | `WAVEFRONT` é o novo; `RAY_CACHE` dá forma idêntica ao vanilla; `VANILLA` não substitui nada. |
| `neighborhood` | `6` / `18` / `26` | Vizinhança da expansão. Mais alto = mais fiel e mais rápido. |
| `resistanceFactor` | float `≥ 0` | Multiplicador do custo de resistência. `1.0` é o valor medido; valores menores dão explosões maiores. |
| `randomnessMode` | `MEAN` / `PER_DIRECTION` | `MEAN` usa a média dos 1352 sorteios; `PER_DIRECTION` usa a média por tipo de direção. Ambos consomem os 1352 valores. |
| `cacheBlockResistance` | `true` / `false` | Cache da resistência por `BlockState`, só quando o calculador é o vanilla conhecido. |
| `metrics` | `true` / `false` | Contadores de tempo e leituras, para benchmark. Custo zero quando desligado. |

Um ficheiro corrompido ou com valores inválidos **nunca** crasha o servidor: cai nos valores por
omissão com um aviso no log.

### Comandos (sem Fabric API)

| Comando | Descrição |
|---|---|
| `/optimizedtnt status` | Configuração ativa + métricas |
| `/optimizedtnt reload` | Relê o JSON (permite ligar/desligar sem reiniciar) |
| `/optimizedtnt save` | Grava a configuração |
| `/optimizedtnt on` \| `off` | Liga/desliga a otimização |
| `/optimizedtnt algorithm wavefront\|ray_cache\|vanilla` | Muda de algoritmo |
| `/optimizedtnt scope tnt_only\|all_explosions` | Muda o âmbito |
| `/optimizedtnt neighborhood 6\|18\|26` | Muda a vizinhança |
| `/optimizedtnt resistance <0..10>` | Muda o fator de resistência |
| `/optimizedtnt randomness mean\|per_direction` | Muda o tratamento da aleatoriedade |
| `/optimizedtnt metrics on\|off` | Liga/desliga as métricas |
| `/optimizedtnt compare` | Corre o vanilla e o algoritmo escolhido na próxima explosão e regista o desvio no log |

Requer permissão de administrador. Em 26.x a API já não é `hasPermission(int)`: usa-se
`Commands.LEVEL_ADMINS.check(source.permissions())`. O comando é registado por mixin em
`Commands`, logo não é preciso `fabric-command-api-v2`.

## Como está feito

```
src/main/java/io/github/mateusrepo/optimizedtnt/
├── OptimizedTnt.java                     # ModInitializer, log, falha única
├── config/OptimizedTntConfig.java        # POJO + load/save/reload tolerante
├── command/OptimizedTntCommand.java      # /optimizedtnt
├── explosion/                            # NÚCLEO — sem dependência do Minecraft
│   ├── BlockProbe.java                   #   interface mínima para o mundo
│   ├── ExplosionWavefront.java           #   Dijkstra com fila de prioridade e 2 mapas
│   ├── ExplosionRayCaster.java           #   os 1352 raios do vanilla (com memo opcional)
│   ├── ExplosionRayCache.java            #   RAY_CACHE
│   ├── VanillaRayExplosion.java          #   oráculo exacto
│   ├── ExplosionComparator.java          #   /optimizedtnt compare
│   ├── ExplosionParams.java              #   parâmetros e energias
│   ├── ExplosionRandomEnergy.java        #   os 1352 sorteios do vanilla
│   ├── ExplosionSink.java / FloatSource.java
│   ├── Neighborhood.java                 #   tabelas de vizinhança
│   └── ExplosionOptimizer.java           #   ADAPTADOR ao Minecraft
├── metrics/ExplosionMetrics.java
└── mixin/{ServerExplosionMixin,CommandsMixin,MinecraftServerMixin}.java
```

O `@Inject` só cancela quando a otimização está ativa **e** o escopo faz match; caso contrário
**não cancela** e o vanilla corre intacto. Nunca `setReturnValue(null)`, que devolveria `null` e
partiria `interactWithBlocks`. Se o cálculo otimizado lançar uma excepção, o mod regista **uma**
mensagem e deixa o vanilla correr — uma falha do mod nunca rebenta o tick.

O `ServerExplosionMixin` usa o máximo de API pública possível (os métodos da interface
`Explosion`) e só faz `@Shadow` de `damageCalculator`, que não tem getter: menos nomes de campo
dependentes, logo menos formas de partir numa atualização do jogo.

## Build e testes

```bash
./gradlew build          # build completo → build/libs/optimizedtnt-1.0.0.jar
./gradlew test           # testes de paridade (não precisam do Minecraft)
./gradlew runServer      # servidor de teste (precisa da EULA aceite)
```

| Componente | Versão |
|---|---|
| Minecraft | 26.3 |
| Fabric Loom | `1.18-SNAPSHOT` (resolve para **1.18.3**) |
| fabric-loader | 0.19.5 (traz Mixin 0.8.7 e MixinExtras 0.5.5) |
| Java toolchain | 25 |
| mappings | Mojmap (via `minecraft "com.mojang:minecraft:26.3"`) |
| Testes | JUnit 6.1.3 |

O núcleo é testável sem arrancar o jogo: `BlockProbe` abstrai o mundo e há uma grelha em memória
como sonda. Os testes comparam a onda e o `RAY_CACHE` contra o oráculo vanilla e medem o número
de leituras de bloco.

## Estado da validação

Feito e verificado: F0 (build), F1 (config), F2 (núcleo + testes de paridade), F3 (mixin +
adaptador), F4 (comandos), F5 (RAY_CACHE).

Pendente: validação dentro do jogo (as crateras reais, `/optimizedtnt compare` num TNT, e
medição de MSPT com `spark`). Requer que a EULA de `run/server/eula.txt` seja aceite — o que só
o dono do servidor pode fazer. Detalhes e próximos passos em [`progresso.md`](progresso.md).

**Mod Menu**: não incluído. Este mod é `environment: "server"` e uma ecrã de configuração só
existe no cliente; para um servidor não é necessário instalar nada nos jogadores. Se for preciso,
a UI pode ser adicionada como um mod cliente separado que partilha o mesmo ficheiro de
configuração.

## Licença

MIT — ver [`LICENSE`](LICENSE).
