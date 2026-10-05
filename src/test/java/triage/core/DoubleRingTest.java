package triage.core;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import org.junit.jupiter.api.Test;

class DoubleRingTest {
    @Test
    void keepsNewestValuesAndComputesOrderStatistics() {
        DoubleRing r = new DoubleRing(5);
        for (int i = 1; i <= 7; i++) {
            r.add(i);
        }
        assertEquals(5, r.size());
        assertEquals(7.0, r.get(0));
        assertEquals(3.0, r.get(4));
        assertEquals(5.0, r.median(5, 0));
        assertEquals(6.5, r.median(2, 0));
        assertEquals(1.0, r.slope(5), 1e-12);
        assertTrue(r.containsExactly(4.0, 1));
        assertFalse(r.containsExactly(7.0, 1));
        assertEquals(1, r.countExactly(6.0, 5));
    }
}
