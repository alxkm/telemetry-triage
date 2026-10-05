package triage.bench;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.List;
import org.junit.jupiter.api.Test;
import triage.core.Cause;
import triage.core.Decision;

class ScenarioTest {
    @Test
    void generationIsDeterministicPerSeed() {
        Scenario a = Scenario.generate(ProcessModel.TANK, 42, 5000, Bench.GAP);
        Scenario b = Scenario.generate(ProcessModel.TANK, 42, 5000, Bench.GAP);
        assertArrayEquals(a.y, b.y);
        assertEquals(a.events, b.events);
    }

    @Test
    void everyFaultTypeAppearsAndEventsAreSeparated() {
        Scenario s = Scenario.generate(ProcessModel.THERMAL, 7, Bench.LENGTH, Bench.GAP);
        for (FaultType f : FaultType.values()) {
            assertTrue(s.events.stream().anyMatch(e -> e.type() == f), f.name());
        }
        for (int i = 1; i < s.events.size(); i++) {
            assertTrue(s.events.get(i).start() > s.events.get(i - 1).end() + Bench.TAIL);
        }
    }

    @Test
    void rateChannelIsUnaffectedByInstrumentFaults() {
        Scenario s = Scenario.generate(ProcessModel.TANK, 3, 8000, Bench.GAP, List.of(FaultType.BIAS_STEP), 10.0);
        Event e = s.events.get(0);
        double meanRate = 0;
        for (int t = e.start(); t < e.start() + 50; t++) {
            meanRate += s.rate[t] / 50;
        }
        assertEquals(0.0, meanRate, 0.05);
    }

    @Test
    void tallyMatchesDecisionsToEventWindows() {
        Scenario s = Scenario.generate(ProcessModel.TANK, 3, 8000, Bench.GAP, List.of(FaultType.SPIKE), Double.NaN);
        Event e = s.events.get(0);
        Tally t = new Tally();
        t.record(s, List.of(new Decision(Cause.DATA_PATH, e.start(), e.start() + 40, List.of()),
                new Decision(Cause.PROCESS, 700, 740, List.of())), Bench.WARMUP, Bench.TAIL);
        Tally.PerType p = t.perType.get(FaultType.SPIKE);
        assertEquals(1, p.detected);
        assertEquals(0, p.delays.get(0));
        assertEquals(1, t.falseAlarms);
    }
}
