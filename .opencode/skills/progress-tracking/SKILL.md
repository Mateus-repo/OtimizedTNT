---
name: progress-tracking
description: Mantém o progresso.md atualizado (tarefas das fases F0-F7, estado global, log de decisões, perguntas em aberto). Usa sempre que concluíres uma tarefa, tomares uma decisão de design ou o utilizador responder a uma pergunta em aberto.
license: MIT
compatibility: opencode
---

# Progress tracking

`progresso.md` é o plano técnico e o registo vivo do projeto. O `README.md` é para o utilizador final do mod. **Não misturar os dois.**

## Estrutura do `progresso.md`

| Secção | O que fazer |
|---|---|
| Cabeçalho | Atualizar **Estado global** e **Última atualização** (`date +%F`) |
| §1 Âmbito | Só mudar se o utilizador alterar o âmbito |
| §2 Ambiente alvo | Acrescentar nomes/assinaturas confirmados com `javap` |
| §3 Decisões de design (D1…) | Nova decisão → novo `D<n+1>`, nunca renumerar |
| §4 Fases F0-F7 | `- [ ]` → `- [x]` quando **realmente** feito e verificado |
| §5 Riscos | Acrescentar riscos novos; atualizar mitigações |
| §6 Perguntas em aberto | Quando respondida, **remover** daqui e registar a decisão em §7 (e em §3 se for de design) |
| §7 Log de decisões | Linha nova: `**AAAA-MM-DD** — o que se decidiu e porquê` |

## Regras

1. Marcar `[x]` só se o critério foi cumprido (compila, teste passa, comando corre). Se ficou a meio, deixar `[ ]` e acrescentar nota curta.
2. O "Estado global" deve refletir a realidade (ex.: "F0 e F1 concluídas; F2 em curso").
3. Não apagar histórico: decisões antigas que mudam passam a "(substituída por D<n>)".
4. Se uma descoberta contradiz o plano (ex.: nome de classe diferente do documentado), corrigir o §2 **e** registar no §7.
5. Se o comportamento visível ao utilizador mudar (config, comandos, defaults), atualizar também o `README.md`.
6. Idioma: PT-PT, tom técnico e conciso.

## Fluxo no fim de cada tarefa

1. Atualizar `progresso.md` (checkboxes, estado, log).
2. Incluir essa alteração no **mesmo commit** do trabalho (ou num `docs(progresso): ...` à parte se for só documentação).
3. Seguir para `auto-commit` → `auto-push` conforme o `AGENTS.md`.
