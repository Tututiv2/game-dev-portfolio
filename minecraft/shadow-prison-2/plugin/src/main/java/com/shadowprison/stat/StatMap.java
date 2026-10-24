package com.shadowprison.stat;

import java.util.Collections;
import java.util.EnumMap;
import java.util.Map;

/** An additive bag of stats. Sources add into one; consumers read one number per stat. */
public final class StatMap {

    private final EnumMap<Stat, Long> values = new EnumMap<>(Stat.class);

    public StatMap add(Stat stat, long amount) {
        if (amount != 0) {
            values.merge(stat, amount, Long::sum);
        }
        return this;
    }

    public StatMap addAll(StatMap other) {
        other.values.forEach(this::add);
        return this;
    }

    public long get(Stat stat) {
        return values.getOrDefault(stat, 0L);
    }

    public boolean isEmpty() {
        return values.isEmpty();
    }

    public Map<Stat, Long> asMap() {
        return Collections.unmodifiableMap(values);
    }
}
