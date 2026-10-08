package io.github.mateusrepo.optimizedtnt.explosion;

/**
 * Acesso mínimo ao mundo para o cálculo da explosão.
 *
 * <p>Existe para que o núcleo ({@link ExplosionWavefront}, {@link ExplosionRayCache}) não
 * dependa de {@code ServerLevel} e possa ser testado sem arrancar o Minecraft.
 *
 * <p>Contrato (ver {@code ExplosionDamageCalculator} do vanilla):
 * <ul>
 *   <li>{@link #inBounds} corresponde a {@code Level.isInWorldBounds};</li>
 *   <li>{@link #resistance} devolve {@link Float#NaN} quando não há resistência, ou seja,
 *       quando o vanilla devolveria {@code Optional.empty()} (ar e fluido vazio);</li>
 *   <li>{@link #shouldExplode} corresponde a {@code shouldBlockExplode} e só é chamado
 *       <strong>depois</strong> de a energia restante ser positiva.</li>
 * </ul>
 */
public interface BlockProbe {

    boolean inBounds(int x, int y, int z);

    /**
     * @return a resistência do bloco, ou {@link Float#NaN} se não houver (ar/fluido vazio).
     */
    float resistance(int x, int y, int z);

    /** A energia já está calculada depois de subtraída a resistência, como no vanilla. */
    boolean shouldExplode(int x, int y, int z, float energy);
}
