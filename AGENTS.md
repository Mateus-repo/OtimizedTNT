# AGENTS.md — Optimized TNT

Mod **server-side** para Minecraft **26.3** (Fabric) que substitui o cálculo de blocos afetados
por explosões (`ServerExplosion#calculateExplodedPositions`) por uma **wavefront / Dijkstra em
blocos**. Detalhes para o utilizador final em `README.md`; plano técnico, decisões e tarefas em
`progresso.md` (**ler antes de começar qualquer trabalho**).

## Stack

| | |
|---|---|
| Minecraft | 26.3 (Mojmap, sem Yarn) |
| Loader | Fabric Loader 0.19.5+, Fabric Loom `1.18-SNAPSHOT` |
| Java | 25 |
| Lado | `environment: "server"` |
| **Fabric API** | **Não usar** (comandos por mixin em `Commands`, config por Gson) |
| Licença | MIT |

Estado atual: **implementado e validado** (F0–F5, F7 parcial). 22 testes unitários verdes,
paridade exacta do `RAY_CACHE` verificada, medições em `README.md` e `docs/benchmarks.md`.
Medir sempre com números reais: o harness tem ruído de ±60% nos casos pequenos e a sonda em
memória **subestima** o custo do mundo real.

## Estrutura prevista

```
src/main/java/…/optimizedtnt/
├── OptimizedTnt.java                  # ModInitializer
├── config/OptimizedTntConfig.java     # JSON em config/optimizedtnt.json
├── command/OptimizedTntCommand.java
├── explosion/ExplosionWavefront.java  # algoritmo principal
├── explosion/ExplosionRayCache.java   # alternativa fiel
├── explosion/ResistanceCache.java
└── mixin/{ServerExplosionMixin,CommandsMixin}.java
```

## Convenções

- Documentação, commits e respostas ao utilizador em **PT-PT**. Identificadores de código em inglês.
- **Nunca assumir nomes Mojmap, assinaturas ou versões de memória**: confirmar com `javap` no jar
  desofuscado da cache do Loom (ver skill `mixin-explosion`) ou no repo 26.3 de referência.
- Fallback seguro: qualquer falha do mod → o vanilla corre (**não cancelar** o `@Inject`).
- Não aceitar a EULA do Minecraft em nome do utilizador; não pedir nem guardar tokens.
- Não alterar versões de Loom/Minecraft/loader sem confirmar com o utilizador.
- Não editar `LICENSE`.

## Comandos úteis

```bash
./gradlew compileJava   # verificação rápida
./gradlew test          # testes unitários
./gradlew build         # build completo → build/libs/optimizedtnt-<versão>.jar
./gradlew runServer     # servidor de teste
```

## Skills (`.opencode/skills/`)

Carregar a skill relevante com a tool `skill` **antes** de trabalhar no assunto.

| Skill | Usar quando |
|---|---|
| `auto-commit` | Concluíste uma tarefa ou o utilizador pede commit |
| `auto-push` | Depois do commit, para enviar para o GitHub |
| `progress-tracking` | Fim de tarefa, decisão tomada, pergunta em aberto respondida |
| `fabric-mod-setup` | Fase F0: criar/alterar a base Gradle/Loom, `fabric.mod.json`, mixins.json |
| `gradle-build-verify` | Compilar, testar, diagnosticar erros de build |
| `mixin-explosion` | Criar/alterar `ServerExplosionMixin` ou `CommandsMixin` |
| `wavefront-algorithm` | Implementar/alterar wavefront, ray cache, cache de resistência |
| `unit-testing-parity` | Escrever/correr testes e validar paridade com o vanilla |
| `benchmark-optimization` | Medir, otimizar, decidir o algoritmo por defeito |
| `release-github` | **Só a pedido**: versão, tag e release |

## Fluxo de trabalho (automático)

Para cada tarefa concluída, por esta ordem:

1. Implementar; carregar as skills do domínio (tabela acima).
2. Verificar: `./gradlew compileJava` e, se houver testes, `./gradlew test` (skill `gradle-build-verify`).
3. Atualizar `progresso.md` (skill `progress-tracking`).
4. **Auto-commit** (skill `auto-commit`): commits lógicos, Conventional Commits em PT-PT.
5. **Auto-push** (skill `auto-push`): `./gradlew build` verde → push para o GitHub.

Limites do fluxo automático:

- Se o utilizador disser "não faças commit/push", **respeitar** até ele dizer o contrário.
- Se a verificação falhar, **não** fazer commit de código partido nem push; corrigir ou reportar.
- **Nunca** `git push --force`, apagar branches/tags remotas, nem reescrever histórico já enviado
  (reforçado em `opencode.json`).
- Sem remoto `origin` configurado: perguntar o URL ao utilizador.
- Em caso de dúvida ou conflito inesperado: parar e perguntar.

## Pontos que o plano deixou em aberto — todos resolvidos

Resolvidos com `javap -p -c` sobre o jar desofuscado da cache do Loom, e registados em
`progresso.md` §8 (não voltar a adivinhar nomes nem fórmulas):

1. Escala da penalidade de resistência: é **por unidade de caminho**, não por bloco. Um bloco é
   atravessado por ~3,3 amostras de 0,3, o que dá `(r + 0.3)` por bloco.
2. Energia inicial: `radius × (0.7 + 0.6 × random.nextFloat())`.
3. Aleatoriedade: o vanilla sorteia **1352 vezes** por explosão; o mod consome as 1352 para não
   perturbar a sequência do `RandomSource` e usa a média.
4. Fallback para o vanilla: **não cancelar** o `@Inject`; nunca `setReturnValue(null)`.

Confirmados com `javap` e que valem para qualquer trabalho futuro: `BlockPos.of(int,int,int)` não
existe em 26.3 (usar `BlockPos.of(long)`), `level.random` é `protected` (usar `getRandom()`),
`isInWorldBounds` está em `Level` e não em `BlockGetter`, permissões são
`Commands.LEVEL_ADMINS.check(src.permissions())`, e `RandomSource` não implementa
`java.util.random.RandomGenerator` (daí a interface `FloatSource`).

## Medir: regras

- Números só com método: aquecer a variante, reportar o **mínimo de várias rondas**, e dizer o
  ruído. A média deu resultados contraditórios (1,8× entre chamadas).
- O harness (`WavefrontBenchmark`) usa a **sonda densa**; com `TestProbe` (HashMap<Long,Float>) o
  boxing mascara o algoritmo.
- Para comparar algoritmos **in-game**, cada explosão tem de acontecer em terreno idêntico:
  reconstruir o terreno antes de cada uma e uma TNT de cada vez. Cascatas de TNTs coladas medem
  crateras diferentes em cada fase e não servem para nada.
- Não afirmar "nunca é mais rápido/mais lento" sem medir: já falhou uma vez
  (a onda **é** mais lenta que o vanilla em ar aberto com raio ≥ 8, 0,74×).
