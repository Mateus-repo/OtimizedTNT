---
name: unit-testing-parity
description: Estratégia de testes do mod - núcleo do algoritmo em Java puro testável com JUnit 5, comparação de paridade wavefront vs raios vanilla, cenários de explosão e validação in-game com /optimizedtnt compare (sem Fabric API/gametest). Usa ao escrever ou correr testes, ou ao validar a fidelidade face ao vanilla.
license: MIT
compatibility: opencode
---

# Testes e paridade

Não há Fabric API → **não há Fabric GameTest**. A estratégia é: núcleo testável sem Minecraft + validação manual in-game.

## 1. Núcleo desacoplado

O algoritmo (`ExplosionWavefront`, `ExplosionRayCache`, `ResistanceCache`) não deve depender de `ServerLevel` diretamente. Definir uma interface mínima, p. ex.:

```java
interface BlockProbe {
    boolean inBounds(int x, int y, int z);
    float resistance(int x, int y, int z);        // NaN = ar/sem resistência (Optional.empty no vanilla)
    boolean shouldExplode(int x, int y, int z, float energy);
}
```

- Adaptador de produção (no mixin): usa `ServerLevel` + `ExplosionDamageCalculator`.
- Adaptador de teste: grelha 3D em memória.

Isto permite correr `./gradlew test` sem arrancar o jogo.

## 2. Referência vanilla em `src/test`

Reimplementar o loop vanilla (1352 raios, passo 0.3, `-0.225` por passo, `(r+0.3)*0.3` por amostra) sobre o mesmo `BlockProbe`, como **oráculo**. Para comparar de forma determinística, usar a mesma energia fixa nos dois lados (ex.: fator aleatório constante) — o aleatório por raio do vanilla torna impossível comparar resultados reais.

## 3. Configuração JUnit

Em `build.gradle`: `testImplementation` de JUnit 5 (versão atual estável, **confirmar** em vez de inventar) e `test { useJUnitPlatform() }`. Testes em `src/test/java/<pacote>/`.

## 4. Cenários obrigatórios (F7)

- Terreno plano (meia esfera) e explosão no ar
- Parede fina / folhagem / portas (resistência baixa)
- Caixa fechada (câmara de pedra)
- Obsidiana (resistência 3600) — nunca atravessada
- Água/fluido (resistência de fluido vs bloco)
- Centro dentro de bloco sólido
- Limite do mundo (`inBounds` falso)
- Vizinhança 6 / 18 / 26
- Raios pequeno (TNT = 4), médio e grande (end crystal / cadeia)

## 5. Métricas de paridade

Para cada cenário: `|A|`, `|B|`, `|A\B|`, `|B\A|` e Jaccard.

- **Miolo da cratera** (blocos com custo acumulado baixo, ex. < 50% da energia) → diferença **zero**.
- **Bordas** → desvio tolerado. Limiar inicial sugerido: diferença simétrica ≤ ~5% de `|A|` com `neighborhood=26`; é um ponto de partida, **ajustar com dados** e documentar em `progresso.md`.
- Se o desvio for estruturalmente maior (ver "pontos a verificar" em `wavefront-algorithm`), corrigir o modelo antes de mexer nos limiares.

Testes adicionais: `compute` não toca em posições fora de limites; não aloca `BlockPos` no hot path (verificável com contadores no `BlockProbe`); idempotência com a mesma energia.

## 6. Validação in-game (manual)

1. `./gradlew runServer` — a EULA em `run/eula.txt` tem de ser aceite **pelo utilizador**; não a aceitar automaticamente.
2. Mundo plano de teste; `/optimizedtnt metrics true`.
3. TNT raio 4, depois cadeia/end crystal/wind charge.
4. `/optimizedtnt compare` → desvio só nas bordas, zero no miolo.
5. Confirmar que com `algorithm: VANILLA` ou `enabled: false` o comportamento é o vanilla.

## Fim de tarefa

`./gradlew test` verde → só então `auto-commit` (`test(...)`/`feat(...)`) e `auto-push`.
