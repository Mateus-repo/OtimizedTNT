---
name: auto-commit
description: Faz commit automático, seguro e em commits lógicos das alterações do projeto, com mensagens Conventional Commits em PT-PT. Usa ao concluir uma tarefa/fase ou quando o utilizador pedir para fazer commit.
license: MIT
compatibility: opencode
---

# Auto-commit

Cria commits limpos e seguros. **Não faz push** — para isso usa a skill `auto-push`.

## Quando usar

- Acabaste uma tarefa (um item de `progresso.md`, um bug, uma refatoração) e o projeto compila.
- O utilizador pede "commit", "guarda isto" ou equivalente.
- Não usar se o utilizador disse explicitamente para não fazer commit.

## Procedimento

1. **Ver o estado**: `git status --short` e `git diff --stat`. Se não houver alterações, parar e dizer isso.
2. **Identidade git**: `git config user.name` e `git config user.email`. Se estiverem vazios, **pedir** ao utilizador; nunca inventar nem gravar config.
3. **`.gitignore`**: confirmar que existe e cobre `build/`, `.gradle/`, `run/`, `.idea/`. Se faltar, corrigir antes de qualquer `git add`.
4. **Nunca commitar** (se aparecerem no `git status`, parar e avisar):
   - segredos: `.env*`, tokens, chaves, `*.pem`, `local.properties`
   - `build/`, `.gradle/`, `run/` (mundos de teste, logs, `eula.txt`), jars compilados (exceto `gradle/wrapper/gradle-wrapper.jar`)
   - ficheiros enormes ou binários sem razão
5. **Verificação de segredos** no que vai ser commitado:
   ```bash
   git diff --cached | grep -inE "(ghp_|github_pat_|api[_-]?key|secret|password|token)" || true
   ```
   Qualquer hit real → desfazer o stage e avisar o utilizador.
6. **Compilar antes de commitar código**: se existir `gradlew` e houve alterações em `src/` ou em ficheiros `*.gradle`/`*.properties`, correr `./gradlew compileJava` (ver skill `gradle-build-verify`). Se falhar, corrigir primeiro. Só se o utilizador pedir explicitamente se faz um commit `wip:`.
7. **Dividir em commits lógicos** — uma ideia por commit (ex.: config separada de algoritmo, docs separadas de código). Fazer stage **por caminhos explícitos**:
   ```bash
   git add src/main/java/.../config/OptimizedTntConfig.java
   ```
   Evitar `git add -A`/`git add .` sem ter revisto o `git status`.
8. **Mensagem** (Conventional Commits, descrição em PT-PT):
   ```
   tipo(âmbito): descrição curta no imperativo, sem ponto final
   ```
   - Máx. ~72 caracteres na primeira linha; corpo opcional (o *porquê*), separado por linha em branco.
   - Tipos: `feat`, `fix`, `perf`, `refactor`, `test`, `bench`, `docs`, `build`, `chore`, `ci`.
   - Âmbitos deste projeto: `config`, `explosion`, `mixin`, `command`, `build`, `test`, `bench`, `docs`, `skills`.
   - Breaking changes: `!` depois do âmbito + `BREAKING CHANGE:` no corpo.
9. **Commitar**: `git commit -m "assunto" -m "corpo opcional"`.
   - Nunca `--no-verify`.
   - Nunca `--amend` num commit que já foi enviado para o remoto.
10. **Confirmar**: `git log --oneline -n 5` e `git status --short` (deve estar limpo).

## Exemplos

```
feat(explosion): adiciona expansão wavefront com bucket queue
fix(mixin): devolve ao vanilla quando o scope não faz match
perf(explosion): evita getBlockState repetido por vizinho
test(explosion): compara wavefront com raios vanilla em caixa fechada
docs(progresso): marca F1 como concluída
build: configura Loom e toolchain Java 25
```

## Depois

Se o fluxo for automático (ver `AGENTS.md`), seguir para a skill `auto-push`.
