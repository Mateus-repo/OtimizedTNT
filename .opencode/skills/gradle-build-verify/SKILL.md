---
name: gradle-build-verify
description: Compila, testa e valida o mod com Gradle (build, compileJava, test, runServer), interpreta erros comuns de Loom/Mixin/Java 25 e localiza o jar final. Usa antes de commits/pushes de código e ao diagnosticar falhas de build.
license: MIT
compatibility: opencode
---

# Gradle build & verificação

## Comandos

| Objetivo | Comando |
|---|---|
| Verificação rápida (antes de commit) | `./gradlew compileJava` |
| Testes unitários | `./gradlew test` |
| Build completo (antes de push) | `./gradlew build` |
| Servidor de teste | `./gradlew runServer` |
| Mais detalhe num erro | `./gradlew build --stacktrace` (ou `--info`) |
| Dependências em cache corrompidas | `./gradlew build --refresh-dependencies` |
| Limpar | `./gradlew clean build` |

No Windows: `.\gradlew.bat ...`. Se `gradlew` não for executável: `chmod +x gradlew`.

Saída: `build/libs/optimizedtnt-<versão>.jar`. Ignorar os jars `-sources` e `-dev`/`-all` ao distribuir.

## Pré-requisitos

- `java -version` deve ser **25** (ou o toolchain Gradle resolver o 25).
- Se ainda não existe `gradlew`/`build.gradle`, o projeto está na fase F0 → usar `fabric-mod-setup`.

## Erros frequentes

| Sintoma | Causa provável / ação |
|---|---|
| Não resolve `net.minecraft` / Loom falha | Versões em `gradle.properties` erradas ou falta repositório; comparar com o repo 26.3 de referência. Não "adivinhar" versões. |
| `Mixin apply failed` / `InvalidInjectionException` | Nome/assinatura do método alvo errado. Confirmar com `javap` (ver `mixin-explosion`). `require = 1` faz falhar cedo de propósito. |
| `ClassNotFoundException` em runtime no servidor | Mixin client-only registado na lista de mixins do servidor. |
| `Unsupported class file major version` | JDK errado; usar Java 25. |
| Falha só offline | Tentar `--offline` se a cache existir; senão precisa de rede. |

## Regras

- Não alterar versões de Loom/Minecraft/loader para "resolver" um erro sem confirmar com o utilizador.
- Reportar sempre o **primeiro** erro real, não o ruído do fim do log.
- Um `build` verde é condição para `auto-push` (se existir Gradle).
