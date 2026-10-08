---
name: auto-push
description: Envia os commits para o GitHub de forma segura (verifica build, faz rebase se necessário, nunca usa force). Usa depois do auto-commit ou quando o utilizador pedir push.
license: MIT
compatibility: opencode
---

# Auto-push

Envia commits locais para o remoto (GitHub) com verificações. Pressupõe que o trabalho já está commitado — se houver alterações por commitar, usa primeiro a skill `auto-commit`.

## Regras absolutas

- **Nunca** `git push --force` / `-f` / `--force-with-lease` sem o utilizador pedir explicitamente nessa mensagem.
- **Nunca** apagar branches ou tags remotas, nem enviar tags (isso é a skill `release-github`, e só a pedido).
- **Nunca** escrever tokens/passwords em ficheiros, URLs de remotos (`https://token@github.com/...`) ou no chat, nem pedi-los no chat.
- Se algo falhar de forma inesperada: **parar e reportar**, não improvisar.

## Procedimento

1. **Árvore limpa?** `git status --short`. Se houver alterações, aplicar `auto-commit` primeiro.
2. **Branch**: `git branch --show-current`. Se estiver vazio (detached HEAD), parar e avisar.
3. **Remoto**: `git remote -v`.
   - Sem `origin` → perguntar ao utilizador o URL do repositório GitHub (não inventar) e só então `git remote add origin <url>`.
4. **Verificação antes de enviar** (só se existir `gradlew`):
   ```bash
   ./gradlew build        # inclui testes quando existirem
   ```
   Se falhar → **não enviar**; mostrar o erro e corrigir (ver `gradle-build-verify`).
   Se o projeto ainda não tem Gradle (fase F0 por fazer), saltar este passo e dizê-lo.
5. **Sincronizar**:
   ```bash
   git fetch origin
   git status -sb          # ver se está atrás (behind)
   ```
   Se estiver atrás: `git pull --rebase origin <branch>`.
   Se houver conflitos → `git rebase --abort`, parar e reportar os ficheiros em conflito. Não resolver conflitos em silêncio.
6. **Enviar**:
   - Primeira vez neste branch: `git push -u origin <branch>`
   - Depois: `git push`
7. **Falha de autenticação**: explicar as opções (`gh auth login`, chave SSH, ou Personal Access Token via credential helper) e esperar que o utilizador resolva. Não contornar.
8. **Reportar**: branch, nº de commits enviados (`git log --oneline origin/<branch>..HEAD` *antes* do push) e o URL do repositório.

## Se `main` for protegido

Se o push for rejeitado por regras de proteção, **não** tentar contornar: sugerir criar um branch (`git switch -c feat/<tema>`), enviar esse e abrir PR (`gh pr create`, se o `gh` estiver instalado).
