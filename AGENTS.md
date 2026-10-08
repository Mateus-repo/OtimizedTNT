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

Estado atual: planeamento concluído, **implementação por iniciar** (só existe documentação).

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

## Pontos conhecidos a corrigir no plano

Documentados em detalhe nas skills `wavefront-algorithm` e `mixin-explosion`; resolver e registar em
`progresso.md` antes de implementar o núcleo:

1. Escala da penalidade de resistência (vanilla: por amostra de 0.3 → ≈ `r+0.3` por bloco).
2. Fórmula da energia inicial (README, progresso e vanilla divergem).
3. Aleatoriedade: o vanilla sorteia por raio (1352×); o plano usa um valor por explosão.
4. Fallback para o vanilla = **não cancelar**, e não `setReturnValue(null)`.
