---
name: fabric-mod-setup
description: Monta o esqueleto do mod Fabric server-side (fase F0 do progresso.md) - Gradle/Loom, fabric.mod.json, mixins.json, wrapper e arranque do runServer, sem Fabric API. Usa quando o projeto ainda não tem build ou quando for preciso mexer na estrutura base.
license: MIT
compatibility: opencode
---

# Fabric mod setup (F0)

O repositório tem para já só documentação (`README.md`, `progresso.md`). Esta skill guia a criação da base de build **sem inventar versões**.

## Regra de ouro

Os números de versão (Loom, loader, Minecraft 26.3, Java 25) estão em `progresso.md` §2 e no README. Para tudo o resto (plugin id do Loom, repositórios Maven, sintaxe do `build.gradle`) **usar como referência o outro repositório 26.3 do utilizador** (`SubtleEffects-26.3`, referido em `progresso.md`). Se não estiver disponível, **pedir ao utilizador** o `build.gradle`/`settings.gradle`/`gradle.properties` desse repo em vez de adivinhar. Loom `1.18-SNAPSHOT` pode precisar de repositório de snapshots e o plugin id/mappings variam entre versões.

## Checklist F0

1. **Perguntar/decidir e registar** (em `progresso.md` §6/§7): `group` e pacote Java (o README usa `…/optimizedtnt/`); mod id `optimizedtnt`.
2. `settings.gradle`, `build.gradle`, `gradle.properties` (mod id, versão `1.0.0`, `minecraft_version=26.3`, `loader_version=0.19.5`, Java 25).
3. Mappings **Mojmap** (sem Yarn).
4. Gradle wrapper (`gradle wrapper`, ou copiar do outro repo 26.3) — versionar `gradlew`, `gradlew.bat` e `gradle/wrapper/*`.
5. `src/main/resources/fabric.mod.json`:
   - `schemaVersion: 1`, `id: optimizedtnt`, `license: MIT`, `environment: "server"`
   - `entrypoints.main` → classe `OptimizedTnt` (ModInitializer)
   - `mixins: ["optimizedtnt.mixins.json"]`
   - `depends`: `fabricloader >=0.19.5`, `minecraft: 26.3`, `java >=25` — **sem `fabric-api`**
6. `src/main/resources/optimizedtnt.mixins.json`: `required: true`, `package` do pacote `mixin`, lista `mixins` (classes server-side), `injectors.defaultRequire: 1`. O `compatibilityLevel` deve ser o aceite pelo Mixin embutido no loader 0.19.5 — confirmar ao compilar/arrancar, não assumir.
7. **Refmap**: não criar à mão. Deixar o Loom gerar (se aplicável; em versões sem ofuscação pode nem existir). O `progresso.md` lista-o por precaução.
8. Mod Menu (F6) é opcional: `modCompileOnly`, sem dependência no jar final.
9. Validar: `./gradlew build` limpo e `./gradlew runServer` a arrancar (ver `gradle-build-verify`).

## Notas

- `run/eula.txt`: **não aceitar a EULA em nome do utilizador**. Dizer-lhe para a aceitar ele próprio se quiser correr o servidor.
- Depois de F0: marcar F0 em `progresso.md` (skill `progress-tracking`) e fazer `auto-commit` com `build: ...`.
