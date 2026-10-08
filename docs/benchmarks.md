# Medições do Optimized TNT

Todos os números aqui são medidos, com o método escrito ao lado. Onde a medição é inválida,
diz-se. Para gerar:

- harness (sonda em memória, sem Minecraft): `./gradlew test --tests "*WavefrontBenchmark"`,
  resultados em `build/test-results/test/TEST-*WavefrontBenchmark.xml` (`<system-out>`).
- dentro do jogo: `powershell -NoProfile -ExecutionPolicy Bypass -File tools\explosion-benchmark.ps1`
  (arranca o servidor de teste, faz 120 explosões por algoritmo com terreno reconstruído antes
  de cada uma, e imprime o resumo no fim).

---

## 1. Dentro do jogo — 26.3 real, 120 explosões idênticas por algoritmo

**Terreno:** janela de 9×9 com uma camada de terra em `y=-59` sobre pedra em `y=-60`; uma TNT
com `{fuse:0}` a detonar 1,5 blocos acima da superfície. O terreno é **reconstruído antes de
cada explosão** (três `fill`), por isso todas as 120 explosões de cada fase são iguais. Com
otimização ligada o lado "vanilla" fica a 0 porque o código vanilla não corre.

| Algoritmo | Blocos/explosão | Leituras/explosão | µs/explosão (aos 40 / 80 / 120) |
|---|---|---|---|
| **vanilla** (mod desligado) | 622,6 | — | 2 207,6 → 1 839,6 → **1 718,5** |
| `WAVEFRONT` vizinhança 26 | 515,5 | **599** | 657,6 → 444,6 → **367,7** |
| `RAY_CACHE` | 623,0 | 720 | 891,6 → 636,0 → **549,3** |

**Ganhos sobre o vanilla:** `WAVEFRONT` **4,7×**, `RAY_CACHE` **3,1×**.

Leitura honesta destes três números por coluna de tempo: são **médias acumuladas** (tempo total
a dividir por explosões), e estão a descer à medida que o JIT aquece. Aos 40 explosões o vanilla media 2 207 µs e a onda 658 µs; aos 120 são 1 719 µs e 368 µs. Ou seja
**metade do ganho aparente só aparece depois do aquecimento** — quem medir 10 explosões vai
concluir que a onda é 3,4× e não 4,7× mais rápida. Mesmo aos 120 a média ainda desce, portanto
4,7× é um piso, não um tecto.

Sobre os blocos:

- `RAY_CACHE` dá **623,0 contra 622,6** do vanilla: 0,06% de diferença, que é a consequência de
  usar a **média** dos 1352 sorteios como energia (decisão D16, para não perturbar a sequência do
  `RandomSource`) em vez de um sorteio por raio. É indistinguível na prática.
- `WAVEFRONT` dá **515,5 contra 622,6**: −17,2%. Neste terreno — uma camada fina de terra — a
  cratera da onda é **mais pequena**. Em terreno com volume o sentido é o oposto (ver §2): a onda
  é 10% a 44% maior. A direcção do desvio depende do terreno, e vale a pena dizer isso em vez de
  uma regra única.

---

## 2. Harness — sonda em memória, sem Minecraft

`WavefrontBenchmark`, com `DenseProbe` (a `TestProbe` usa `HashMap<Long,Float>` e o boxing
mascara o algoritmo). Energia fixa (mesma nos dois lados), `{fuse:0}` equivalente a
`FIXED_RANDOM`. Tempos em **mínimo de 5 rondas** depois de aquecer cada variante; com a média
o vanilla em ar raio 8 mediava 1 375 µs numa chamada e 753 µs noutra.

`churn` = extracções da fila por bloco registado (1,0 é o Dijkstra perfeito).

| Cenário (vizinhança 26) | Blocos vanilla → onda | Leituras vanilla → onda | churn | Tempo vanilla → onda |
|---|---|---|---|---|
| Ar, raio 4 | 823 → 799 | 24 336 → **799** | 1,39 | 239 k → **91 k** (2,6×) |
| Ar, raio 6 | 2 459 → 2 249 | 40 619 → **2 249** | 1,63 | 508 k → **422 k** (1,2×) |
| Ar, raio 8 | 5 047 → 4 963 | 48 672 → **4 963** | 1,62 | 753 k → 1 014 k (**0,74×**) |
| Terra, raio 6 | 275 → 335 | 4 523 → **343** | 1,45 | 140 k → **23 k** (6,1×) |
| Terra, raio 8 | 565 → 697 | 9 111 → **727** | 1,53 | 230 k → **83 k** (2,8×) |
| Pedra, raio 6 | 1 → 1 | 5 408 → **1** | 1,00 | 31 k → **1,0 k** (32×) |
| Caverna, raio 8 | 220 → 242 | 2 575 → **242** | 1,50 | 98 k → **21 k** (4,7×) |

Com vizinhança 6 o churn é **1,00**: com uma só direcção por eixo há um caminho mínimo único, e
portanto cada bloco é extraído uma vez. O churn 1,4–1,6 com 26 vizinhança é o preço da
fidelidade — vários caminhos quase igualmente bons.

### Onde a onda perde, e porquê

**Em ar aberto com raio ≥ 8 a onda é mais lenta que o vanilla (0,74×), e não é um bug de
implementação.** O vanilla gasta ~47 000 amostras (1 352 raios × ~35 amostras de 0,3); a onda
gasta ~209 000 relaxamentos (26 vizinhos × 8 000 extracções da fila). Em campo aberto a onda paga
por bloco o que o vanilla paga por raio, e são mais relaxamentos do que amostras.

Confirmou-se ao tentar otimizar: remover a verificação de limites do índice da grelha **não mudou
nada** (1 168 k contra 1 014 k, dentro do ruído), o que mostra que o custo está no volume de
relaxamentos e não na aritmética de indexação.

O alcance real do jogo fica abaixo deste ponto: TNT tem raio 4 (2,6× mais rápido mesmo no ar) e
o cristal do fim raio 6 (1,2×). Para explosões de raio 8+ em campo aberto, `RAY_CACHE` ou
`VANILLA`.

### Ruído e limites do harness

- Ruído de ±60% nos casos pequenos (microbenchmark sem JMH, execuções sem Profile JIT
  partilhado). As tabelas acima são o mínimo de 5 rondas de uma execução; noutra execução os
  casos pequenos variaram até 60%.
- A sonda é um array. **Subestima o custo real** (no jogo, resistência = `getBlockState` +
  `getExplosionResistance`) e **subestima o valor do `RAY_CACHE`**, cuja memorização só paga se a
  leitura for cara. No harness o `RAY_CACHE` é mais lento que o vanilla (0,83× a 0,95×); in-game
  é 3,1× mais rápido. É a diferença entre uma sonda de 2 ns e uma leitura de mundo a 100 ns.
- Nenhum número de MSPT/TPS com `spark`: o relatório do spark é um payload protobuf que precisa
  de ser lido no visualizador web, e não é automatizável a partir do log.

---

## 3. O que mudou quando a onda deixou de usar tabelas hash

Indexar as células por offset ao centro (`ExplosionCells`, com `DenseExplosionCells` no caso
comum e `HashExplosionCells` como recurso para alcances que não cabem em memória) trocou dois
lookups em tabela hash por uma subtração e um índice em cada um dos ~209 000 relaxamentos.

| Cenário | Antes (hash) | Depois (grelha) | Vanilla |
|---|---|---|---|
| Ar, raio 4 | 237 k | **208 k** | 452 k |
| Ar, raio 6 | 504 k | **413 k** | 923 k |
| Ar, raio 8 | 1 602 k | **1 004 k** | 1 376 k |
| Terra, raio 6 | 79 k | **24 k** | 306 k |
| Terra, raio 8 | 149 k | **107 k** | 432 k |
| Caverna, raio 8 | 29 k | **22 k** | 223 k |

(Era da medição imediatamente antes da mudança; a coluna do vanilla é dessa mesma execução, e é
por isso que estes números não batem exactamente com as tabelas acima — o ruído do harness mudou
de figura quando a medição passou a ser o mínimo de rondas.)

O conjunto de blocos ficou **idêntico** em todos os cenários de paridade: mudou a
representação, não o resultado.