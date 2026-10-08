package io.github.mateusrepo.optimizedtnt.explosion;

/**
 * Armazenamento das células alcançadas por uma onda.
 *
 * <p>Existem duas implementações porque o alcance de uma explosão é limitado mas não
 * previsível: numa explosão normal cabe uma grelha densa (indexação directa, sem hash), mas
 * uma explosão de raio enorme não caberia, e aí volta-se para tabelas hash.
 *
 * <p>Contrato, com o valor em unidades de energia do vanilla:
 * <ul>
 *   <li>{@link #energy} devolve {@link Float#NaN} para "desconhecido"; o valor real pode ser
 *       negativo (um nó que já não propaga), por isso o sentinela tem de ser o próprio
 *       {@code NaN} — e não o infinito, porque as comparações com {@code NaN} são sempre
 *       falsas e o Dijkstra deixava de funcionar;</li>
 *   <li>{@link #charge} guarda o custo de resistência <em>já calculado</em>
 *       ({@code r + 0.3}), com {@code 0} para ar — assim {@code 0} é um valor válido e
 *       {@code NaN} é "desconhecido", sem precisar de uma marca separada;</li>
 *   <li>{@link #markEmitted}/{@link #isEmitted} garantem que um bloco é registado uma só vez,
 *       como o {@code Set} do vanilla.</li>
 * </ul>
 */
public interface ExplosionCells {

    /** Inicia uma explosão nova, invalidando o que ficou da anterior sem percorrer as tabelas. */
    void begin();

    float energy(long packed);

    /** @return {@code true} se o valor foi improvingido (isto é, era maior que o conhecido). */
    boolean improveEnergy(long packed, float value);

    /** @return o custo de resistência guardado, ou {@link Float#NEGATIVE_INFINITY} se desconhecido. */
    float charge(long packed);

    void putCharge(long packed, float charge);

    boolean isEmitted(long packed);

    void markEmitted(long packed);
}
