---
name: release-github
description: Prepara e publica uma release do mod no GitHub (versão, build, tag, jar). Usa apenas quando o utilizador pedir explicitamente para fazer release/publicar uma versão.
license: MIT
compatibility: opencode
---

# Release no GitHub

**Só a pedido explícito.** Esta é a única skill autorizada a criar/enviar tags.

## Pré-condições

- Árvore limpa e branch correto (`git status`, `git branch --show-current`).
- `./gradlew clean build` e `./gradlew test` verdes (`gradle-build-verify`).
- Validação de paridade/benchmark feita ou conscientemente adiada (`progresso.md` F7).

## Passos

1. **Versão** (SemVer): confirmar com o utilizador (ex. `1.0.0`). Atualizar a versão em `gradle.properties` e no README (nome do jar, tabelas).
2. **Changelog**: atualizar/criar `CHANGELOG.md` a partir dos commits (`git log --oneline <tag-anterior>..HEAD`), agrupado por `feat`/`fix`/`perf`.
3. Commit `chore(release): vX.Y.Z` (`auto-commit`).
4. **Build final**: `./gradlew clean build`; jar em `build/libs/optimizedtnt-X.Y.Z.jar` (não publicar `-sources`/`-dev`).
5. **Push do commit** (`auto-push`), depois a tag anotada:
   ```bash
   git tag -a vX.Y.Z -m "Optimized TNT X.Y.Z"
   git push origin vX.Y.Z
   ```
6. **Release** (se o `gh` estiver instalado e autenticado):
   ```bash
   gh release create vX.Y.Z build/libs/optimizedtnt-X.Y.Z.jar --title "Optimized TNT X.Y.Z" --notes-file CHANGELOG.md
   ```
   Sem `gh`: dar ao utilizador os passos para criar a release na página do GitHub e indicar o caminho do jar.
7. Registar em `progresso.md` (§7) e confirmar o URL da release.

## Notas

- Declarar na release: Minecraft 26.3, Fabric Loader 0.19.5+, Java 25, **server-side, sem Fabric API**.
- Nunca reescrever/mover uma tag já publicada.
