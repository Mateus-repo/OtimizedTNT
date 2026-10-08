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

> Estado: funcional, com testes de paridade e medido dentro do jogo (120 explosões idênticas
> por algoritmo): `WAVEFRONT` **4,7×** mais rápido que o vanilla, `RAY_CACHE` **3,1×** e com
> crateras indistinguíveis das do vanilla. Métodos, números completos e limites em
> [`docs/benchmarks.md`](docs/benchmarks.md).

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

Método, limites e todas as medições (incluindo as que não são favoráveis) em
[`docs/benchmarks.md`](docs/benchmarks.md). Resumo:
### Resultados medidos em testes

Valores de `./gradlew test` (`WavefrontBenchmark`), com sonda em memória e energia fixa.
**Leituras** = leituras de estado de bloco; o número de processamentos da fila mede o quanto a
extracção repete trabalho (1,0 é o ideal).

| Cenário | Blocos | Leituras (vanilla → onda) | Processamentos | Tempo (vanilla → onda) |
|---|---|---|---|---|
| Ar, raio 4 | 823 → 799 | 24 336 → **799** | 1 111 (1,39× blocos) | 239 µs → **91 µs** (2,6×) |
| Ar, raio 6 | 2 459 → 2 249 | 40 619 → **2 249** | 3 665 (1,63×) | 508 µs → **422 µs** (1,2×) |
| Ar, raio 8 | 5 047 → 4 963 | 48 672 → **4 963** | 8 059 (1,62×) | 753 µs → 1 014 µs (**0,74×**) |
| Terra, raio 6 | 275 → 335 | 4 523 → **343** | 487 (1,45×) | 140 µs → **23 µs** (6,1×) |
| Terra, raio 8 | 565 → 697 | 9 111 → **727** | 1 063 (1,53×) | 230 µs → **83 µs** (2,8×) |
| Pedra, raio 6 | 1 → 1 | 5 408 → **1** | 1 (1,00×) | 31 µs → **1,0 µs** (32×) |
| Caverna, raio 8 | 220 → 242 | 2 575 → **242** | 1,5× blocos | 98 µs → **21 µs** (4,7×) |

Duas leituras honestas destes números:

- **Em ar aberto e raio ≥ 8, a onda é mais lenta que o vanilla (0,74×).** Não é um bug de
  implementação: o vanilla dispara 1 352 raios fixos e percorre ~35 amostras cada um
  (~47 000 amostras para raio 8), enquanto a onda relaxa 26 vizinhos por bloco alcançado
  (~8 000 extracções × 26 ≈ 209 000 relaxamentos). Em campo aberto a onda paga por bloco o
  que o vanilla paga por raio — e são mais relaxamentos do que amostras.
- Em qualquer coisa que **não** seja ar aberto a onda ganha sempre, porque a resistência corta
  a frente cedo: pedra é 16× a 32× mais rápida, terra 3× a 6×, caverna 5× a 16×.

O alcance do jogo real está abaixo do ponto fraco: TNT tem raio 4 (2,6× mais rápido mesmo no
ar) e o cristal do fim raio 6 (1,2×). Para medir explosões de raio 8+ em ar aberto há que usar
`RAY_CACHE` ou `VANILLA`.

Este harness tem ruído de ±60% nos casos pequenos (é um microbenchmark sem JMH, e as execuções
não partilhamProfile JIT); está registado o mínimo de 5 rondas por variante para o reduzir.
E a sonda é um array: **subestima o custo real**, porque no jogo uma leitura de resistência é um
`getBlockState` + `getExplosionResistance`, ordens de grandeza mais cara. Por isso as medições
in-game abaixo são as que decidem.

### Resultados medidos dentro do jogo

Servidor 26.3 real, **120 explosões idênticas por algoritmo**, com o terreno reconstruído antes
de cada uma (uma camada de terra sobre pedra, TNT a detonar 1,5 blocos acima da superfície).
As métricas do mod medem os dois lados; com a optimização ligada o lado vanilla fica a 0 porque
o código vanilla não corre.

| Algoritmo | Blocos/explosão | Leituras/explosão | µs/explosão (aos 40 → 120) |
|---|---|---|---|
| **vanilla** (mod desligado) | 622,6 | ~18 por raio | 2 207,6 → **1 718,5** |
| `WAVEFRONT` vizinhança 26 | 515,5 | **599** | 657,6 → **367,7** |
| `RAY_CACHE` | 623,0 | 720 | 891,6 → **549,3** |

**Ganhos: `WAVEFRONT` 4,7×, `RAY_CACHE` 3,1×.**

Duas leituras honestas destes números:

- **Metade do ganho só aparece depois do JIT aquecer.** As médias caem de forma monótona
  (para o vanilla: 2 208 → 1 840 → 1 719 µs). Quem medir 10 explosões conclui que a onda é
  3,4× e não 4,7× mais rápida, e mesmo aos 120 a média ainda desce — 4,7× é um piso, não um
  tecto.
- **A cratera da onda é mais pequena neste terreno** (515,5 contra 622,6, −17%), porque é uma
  camada fina de terra. Em terreno com volume é o contrário: +10% a +44%. O `RAY_CACHE` dá
  623,0 contra 622,6 — 0,06%, que é só a diferença entre usar a média dos 1352 sorteios como
  energia e usar um sorteio por raio.

Ou seja: o número de leituras de bloco deixa de crescer com o número de raios e passa a crescer
só com o volume da cratera.

## Diferenças face ao vanilla (honestidade)

A onda trabalha à escala do bloco, o vanilla amostra continuamente. Daí resultam desvios reais:

| Cenário (vizinhança 26) | Contagem (vanilla → onda) | Desvio | Diferença simétrica |
|---|---|---|---|
| Ar, raio 4 | 823 → 799 | −2,9% | 2,9% |
| Ar, raio 6 | 2 459 → 2 249 | −8,5% | 12,9% |
| Ar, raio 8 | 5 047 → 4 963 | −1,7% | **17,4%** |
| Terra, raio 4 | 81 → 117 | +44% | 44,4% |
| Terra, raio 6 | 275 → 335 | +22% | 21,8% |
| Terra, raio 8 | 565 → 697 | +23% | 23,4% |
| Pedra, raio 6 | 1 → 1 | 0 | 0% |
| Pedra, raio 8 | 7 → 1 | −86% | 85,7% (contagens minúsculas) |
| Caverna, raio 4 | 48 → 56 | +17% | 16,7% |
| Caverna, raio 8 | 220 → 242 | +10% | 10,0% |

Ou seja, e é preciso ser claro sobre isto: **a onda não é sempre maior que o vanilla**, como se
tinha afirmado antes. No ar ela é **mais pequena** (2% a 9% em contagem, mas com 17% de blocos
diferentes em raio 8 — a forma desloca-se na casca exterior); em terra e caverna é maior
(10% a 44%); em pedra rara vez passa do primeiro bloco, porque a resistência corta a frente aos
primeiros passos e a diferença de meio bloco decide tudo.

O que nunca acontece é a cratera ficar fragmentada: o desvio é sempre um conjunto que é
subconjunto ou superconjunto do outro, concentrado na casca exterior, e o miolo da cratera
coincide quase exactamente (≤ 25% do alcance, 0% a ~10% de desvio).

As causas, todas compreensíveis:

- a onda é simétrica ao **bloco** onde nasce o raio; o vanilla é simétrico ao **ponto exacto**;
- a onda chega a blocos "diagonalmente acessíveis" por onde nenhum raio passa exactamente, e não
  atravessa diagonais isoladas (o vanilla também não);
- numa diagonal a resistência é cobrada uma vez por bloco em vez de ser repartida pelos blocos
  que o passo atravessa.

Em terreno com volume a cratera da onda fica **maior** (terra: +22% a +44%). Numa camada fina, ao
contrário: no benchmark in-game (uma camada de terra sobre pedra) a onda dá 515,5 blocos contra
622,6 do vanilla, **−17%**. O sinal do desvio depende do terreno, e vale a pena dizer isso em vez
de uma regra única. Com `neighborhood: 6` a forma fica mais próxima da do vanilla, ao preço de
deixar blocos por destruir (ar raio 8: 5 047 → 2 047 blocos) — o que num servidor costuma ser pior
do que uma cratera um pouco maior.

Tudo o resto (fogo, drops, decay, entity damage, knockback, `getHitPlayers`) continua a ser
tratado pelo código vanilla intacto: o mod só substitui **quais** blocos são afetados.

### Qual configuração usar

| Prioridade | Configuração |
|---|---|
| Crateras **idênticas** ao vanilla | `algorithm: "RAY_CACHE"` — 0,06% de diferença de blocos e **3,1× mais rápido** que o vanilla, medido in-game |
| Máxima performance | `algorithm: "WAVEFRONT"` + `neighborhood: 26` (predefinição) — **4,7×** medido in-game |
| Cratera mais parecida, sem perder blocos | `algorithm: "WAVEFRONT"` + `neighborhood: 18` |
| Afinar o tamanho da cratera | `resistanceFactor`: `1.15`–`1.3` encolhe a cratera |

A predefinição é `WAVEFRONT` + `26` porque é o mais rápido dos três (4,7× contra 3,1× in-game) e
porque o `scope: TNT_ONLY` limita o alcance da mudança. O custo é a cratera: em terreno com
volume é 10% a 44% maior, em camadas finas 17% menor. **Quem quiser a cratera do vanilla sem
pensar no assunto muda para `RAY_CACHE` num comando, sem reiniciar**, e só abdica de 1,5× de
velocidade.

**Sobre o `RAY_CACHE`:** mantém os 1352 raios do vanilla e memoriza a resistência por bloco, o
que dá resultado indistinguível do vanilla — 623,0 blocos contra 622,6 in-game, e o teste de
paridade passa contra o oráculo vanilla — com **720 leituras de bloco por explosão** em vez das
~18 por raio que o vanilla faz (24 336 por explosão para raio 4, medido no harness). É a opção
para quem não quer ver crateras diferentes.

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
| `neighborhood` | `6` / `18` / `26` | Vizinhança da expansão. Mais alto = mais fiel **e mais lento** (cada bloco relaxa 26 vizinhos em vez de 6). |
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

Verificado **dentro do jogo** (servidor 26.3 real, TNT com `/optimizedtnt compare`): os mixins
aplicam-se, a onda e o `RAY_CACHE` correm, o comando responde e as métricas registam. Os números
estão nas tabelas acima.

Pendente: medição de impacto no MSPT/TPS com `spark` em cascatas grandes de TNT, e cenários mais
variados in-game (obsidiana, água, end crystal). Os scripts de teste ficaram em `tools/`.

**Mod Menu**: não incluído. Este mod é `environment: "server"` e uma ecrã de configuração só
existe no cliente; para um servidor não é necessário instalar nada nos jogadores. Se for preciso,
a UI pode ser adicionada como um mod cliente separado que partilha o mesmo ficheiro de
configuração.

## Licença

MIT — ver [`LICENSE`](LICENSE).
