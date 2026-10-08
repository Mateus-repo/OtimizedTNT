package io.github.mateusrepo.optimizedtnt.explosion;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.Random;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * O consumo do RNG tem de ser idêntico ao do vanilla, senão muda o estado do mundo.
 */
class ExplosionRandomEnergyTest {

    @Test
    @DisplayName("consome exatamente 1352 valores, como os 1352 raios do vanilla")
    void consumesOneValuePerRay() {
        assertEquals(16 * 16 * 16 - 14 * 14 * 14, ExplosionRandomEnergy.drawCount());
        assertEquals(1352, ExplosionRandomEnergy.drawCount());

        CountingRandom random = new CountingRandom(new Random(0L));
        ExplosionRandomEnergy.sample(4.0F, random, false);
        assertEquals(1352, random.calls);

        random = new CountingRandom(new Random(0L));
        ExplosionRandomEnergy.sample(4.0F, random, true);
        assertEquals(1352, random.calls);
    }

    @Test
    @DisplayName("a sequência do RNG fica no mesmo ponto que no vanilla")
    void rngSequenceIsUnchanged() {
        Random mine = new Random(1234L);
        Random vanilla = new Random(1234L);

        ExplosionRandomEnergy.sample(4.0F, new CountingRandom(mine), false);
        for (int i = 0; i < 1352; i++) {
            vanilla.nextFloat();
        }

        assertEquals(vanilla.nextFloat(), mine.nextFloat(), 0.0F,
                "o estado do RNG divergiu depois de uma explosão");
    }

    @Test
    @DisplayName("MEAN dá a mesma energia nos três tipos de direção")
    void meanModeUsesOneEnergy() {
        ExplosionRandomEnergy.Energies energies =
                ExplosionRandomEnergy.sample(4.0F, new CountingRandom(new Random(7L)), false);

        assertEquals(energies.axis(), energies.face(), 0.0F);
        assertEquals(energies.axis(), energies.corner(), 0.0F);
    }

    @Test
    @DisplayName("PER_DIRECTION dá energías diferentes por tipo, dentro do intervalo do vanilla")
    void perDirectionVariesByKind() {
        ExplosionRandomEnergy.Energies energies =
                ExplosionRandomEnergy.sample(4.0F, new CountingRandom(new Random(99L)), true);

        float min = ExplosionRandomEnergy.energyFor(4.0F, 0.0F);
        float max = ExplosionRandomEnergy.energyFor(4.0F, 1.0F);
        for (float energy : new float[]{energies.axis(), energies.face(), energies.corner()}) {
            assertTrue(energy >= min && energy <= max,
                    "energia " + energy + " fora de [" + min + ", " + max + "]");
        }
    }

    @Test
    @DisplayName("a fórmula é radius * (0.7 + 0.6 * rand), como no vanilla")
    void energyFormulaMatchesVanilla() {
        assertEquals(4.0F * 1.0F, ExplosionRandomEnergy.energyFor(4.0F, 0.5F), 1e-6F);
        assertEquals(4.0F * 0.7F, ExplosionRandomEnergy.energyFor(4.0F, 0.0F), 1e-6F);
        assertEquals(4.0F * 1.3F, ExplosionRandomEnergy.energyFor(4.0F, 1.0F), 1e-6F);
    }

    /** {@link FloatSource} que conta quantas vezes o vanilla pediria um valor. */
    private static final class CountingRandom implements FloatSource {

        private final Random delegate;
        int calls;

        CountingRandom(Random delegate) {
            this.delegate = delegate;
        }

        @Override
        public float nextFloat() {
            calls++;
            return delegate.nextFloat();
        }
    }
}
