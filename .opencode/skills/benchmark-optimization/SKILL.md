---
name: benchmark-optimization
description: Método para medir e otimizar o desempenho das explosões (microbenchmarks, spark in-game, métricas do mod, regras de hot loop sem alocações) e decidir entre WAVEFRONT, RAY_CACHE e VANILLA com dados. Usa ao otimizar, ao comparar algoritmos ou ao registar resultados de performance.
license: MIT
compatibility: opencode
---

# Benchmark e otimização

**Regra nº 1: medir antes e depois. Não otimizar por intuição.** O risco já documentado (`progresso.md` §5) é o wavefront ser mais lento que o vanilla em explosões pequenas.

## O que medir

| Cenário | Variantes |
|---|---|
| 1 TNT (raio 4) | VANILLA, RAY_CACHE, WAVEFRONT (6/18/26) |
| 10 TNT simultâneas | idem |
| 50 TNT / cascata | idem |
| Raio grande (end crystal / cadeia) | idem |
| Terreno: plano, caverna, cheio de água/obsidiana | idem |

Métricas: tempo por explosão (ns), blocos visitados vs amostras vanilla equivalentes, alocações, MSPT/TPS no servidor.

## Níveis de medição

1. **Microbenchmark** do núcleo sobre `BlockProbe` (ver `unit-testing-parity`): aquecimento (JIT), N repetições, várias *forks*; JMH (plugin Gradle) é preferível a `nanoTime` manual. Guardar o código em `src/jmh/` ou `src/test/.../bench/`.
2. **Métricas internas** (`"metrics": true`): contadores e tempo médio via `/optimizedtnt status`. Medir tempo **só** com `metrics` ligado — overhead zero por omissão (D14).
3. **In-game com `spark`** (mod externo): `/spark profiler` antes/depois em cascatas de TNT; comparar MSPT.

## Regras para o hot loop

- Zero alocação por iteração: sem `new BlockPos`, sem boxing, sem `Optional`/streams/lambdas capturantes.
- Coleções primitivas fastutil (`Long2FloatOpenHashMap`, `LongOpenHashSet`) com capacidade inicial estimada; reutilizar buffers entre explosões (cuidado com threads).
- Um `getBlockState`/resistência por posição; barreira barata (`containsKey`) antes de operações caras.
- Cache de resistência só quando for correta (ver `wavefront-algorithm`).
- Pré-calcular offsets/custos da vizinhança em arrays `int`/`float` estáticos.
- Evitar `try/catch` e logging dentro do loop.

## Decisão com dados

- Se o wavefront não ganha em raios pequenos → limiar híbrido (ex. raio < N → vanilla) ou default `RAY_CACHE`.
- Se `neighborhood=6` ou `18` ganha em fidelidade sem perder muito → mudar o default (pergunta aberta §6 nº 2).
- Registar a decisão no log (`progress-tracking`).

## Registo de resultados

Guardar em `docs/benchmarks.md`: data, commit (`git rev-parse --short HEAD`), máquina/JDK, cenário, tabela de resultados e conclusão. Resultados sem commit/ambiente associados não servem para comparar.

## Fim de tarefa

Commit `perf(...)` ou `bench(...)` só com números que justifiquem a mudança no corpo da mensagem; depois `auto-push`.
