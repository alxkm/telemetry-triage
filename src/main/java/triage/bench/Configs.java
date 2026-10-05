package triage.bench;

import java.lang.reflect.RecordComponent;
import triage.core.TriageConfig;

/** Copies of the frozen configuration with one component changed, for sensitivity and ablation runs. */
public final class Configs {
    private Configs() {
    }

    public static TriageConfig with(TriageConfig base, String component, Object value) {
        try {
            RecordComponent[] comps = TriageConfig.class.getRecordComponents();
            Object[] args = new Object[comps.length];
            Class<?>[] types = new Class<?>[comps.length];
            boolean found = false;
            for (int i = 0; i < comps.length; i++) {
                types[i] = comps[i].getType();
                if (comps[i].getName().equals(component)) {
                    args[i] = value;
                    found = true;
                } else {
                    args[i] = comps[i].getAccessor().invoke(base);
                }
            }
            if (!found) {
                throw new IllegalArgumentException("No component " + component);
            }
            return TriageConfig.class.getDeclaredConstructor(types).newInstance(args);
        } catch (ReflectiveOperationException e) {
            throw new IllegalStateException(e);
        }
    }

    public static String describe(TriageConfig cfg) {
        StringBuilder sb = new StringBuilder();
        try {
            for (RecordComponent c : TriageConfig.class.getRecordComponents()) {
                sb.append(String.format("%-26s %s%n", c.getName(), c.getAccessor().invoke(cfg)));
            }
        } catch (ReflectiveOperationException e) {
            throw new IllegalStateException(e);
        }
        return sb.toString();
    }
}
