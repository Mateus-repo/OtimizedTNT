---
name: wavefront-algorithm
description: Especificação e guia de implementação do algoritmo wavefront (Dijkstra por blocos com bucket queue) que substitui os 1352 raios do vanilla, incluindo custos, paridade e pontos a verificar no bytecode. Usa ao implementar ou alterar ExplosionWavefront, ExplosionRayCache ou ResistanceCache.
license: MIT
compatibility: opencode
---

# Algoritmo wavefront

Objetivo: cada `BlockPos` avaliado **uma vez** (`O(blocos alcançados)`) em vez de ~34 000 amostras para ~1 800 blocos (TNT raio 4).

## Modelo (unidades do vanilla)

Energia por bloco = energia inicial − custo acumulado. Custos:

| Evento | Custo |
|---|---|
| Passo em eixo | `0.75` (= `0.225` por passo de `0.3` × 3,33 passos/bloco) |
| Diagonal de face (`√2`) | `1.0607` |
| Diagonal de canto (`√3`) | `1.2990` |
| Resistência do bloco `r` | ver "Ponto a verificar #1" |

Bloco é registado se, depois de aplicar a resistência, `energia > 0` **e** `damageCalculator.shouldBlockExplode(explosion, level, pos, state, energia)`.

O custo de entrar num nó depende só do nó destino → o grafo tem custos ≥ 0 e **Dijkstra é válido**: a primeira vez que um nó sai da fila tem o custo mínimo, e nunca é reprocessado.

## Estrutura

- **Bucket queue (Dial)**: array de buckets indexado por `floor(custoAcumulado / passo)` (passo ≈ 0.05); tamanho `ceil(E0 / passo) + 1`. Sem `PriorityQueue`.
- **Visitados**: `Long2FloatOpenHashMap` (fastutil, já vem no MC), chave `BlockPos.asLong()` / empacotada; capacidade inicial estimada do volume (`~4/3·π·R³`).
- **Sem alocação no loop quente**: nada de `new BlockPos`, `Optional` desnecessários nem boxing. Usar `BlockPos.MutableBlockPos` reutilizado só quando for mesmo preciso falar com a API do jogo.
- **Vizinhança** configurável 6/18/26 (offsets pré-calculados com o respetivo custo).
- `level.isInWorldBounds(pos)` antes de **qualquer** acesso ao mundo.
- Saída: `ObjectArrayList<BlockPos>`; a ordem não importa (o vanilla usa `HashSet`).

## Implementação por fases

1. **Simples e correta primeiro**: ao descobrir um nó, ler estado/resistência **uma vez**, guardar e usar. Isto já cumpre "cada posição é consultada uma vez".
2. **Só depois, e se o benchmark justificar**, avaliar o estado de forma preguiçosa (custo provisório sem resistência, completar no pop e reinserir se necessário). Medir com a skill `benchmark-optimization`.
3. `ExplosionRayCache` (plano B): os mesmos 1352 raios mas com `LongOpenHashSet` para não repetir `getBlockState`/resistência.

## Cache de resistência (`ResistanceCache`)

- Cache por `BlockState` só é correto se a resistência **não depende da posição nem do contexto**. `ExplosionDamageCalculator` pode ser substituído por mods ou por `EntityBasedExplosionDamageCalculator`.
- Regra: usar a cache **apenas** quando o calculador for o vanilla conhecido e a opção `cacheBlockResistance` estiver ligada; caso contrário chamar sempre `getBlockExplosionResistance` (respeita D4).
- Limitar o tamanho (ex. 4096) ou usar identidade fraca.

## ⚠️ Pontos a verificar no bytecode (antes de fixar o algoritmo)

Inspecionar com `javap -p -c` (ver `mixin-explosion`). O texto atual do README/progresso tem inconsistências:

1. **Escala da resistência.** No vanilla a penalidade `(r + 0.3) × 0.3` aplica-se **por amostra de 0.3 blocos**, e um raio atravessa ≈ 3,33 amostras por bloco. O efeito por bloco atravessado é por isso ≈ `(r + 0.3)` (≈ ×3,3), **não** `(r + 0.3) × 0.3` como diz a tabela "Entrar num bloco…". Implementar `(r+0.3)×0.3` por bloco faria crateras bem maiores que o vanilla. Validar com os testes de paridade (`unit-testing-parity`).
2. **Energia inicial.** O README diz `radius × (1 + 0.42 × rand)`, o pseudo-código do progresso tem `(radius + 0.7*rand*0.6) * radius`, e a fórmula conhecida do vanilla é `radius × (0.7 + 0.6 × rand)`. Confirmar a real em 26.3.
3. **Aleatoriedade.** O vanilla sorteia **um valor por raio** (1352 `nextFloat()` por explosão). Usar um único `rand` altera a distribuição e o consumo do RNG do servidor, ao contrário do que `progresso.md` §5 afirma. Opções: consumir 1352 valores para manter a sequência, e/ou variar a energia por direção. Decidir e registar em `progresso.md`.
4. **Resistência do bloco central** e se a energia é verificada `> 0` antes ou depois da penalidade (a ordem importa para a paridade).

Depois de confirmar cada ponto, corrigir o `progresso.md` (§2/§3/§7) com a skill `progress-tracking`.
