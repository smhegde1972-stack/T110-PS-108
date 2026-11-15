package in.ps108.model;

import java.math.BigDecimal;
import java.util.Set;

public record Venue(String id, String name, int capacity, BigDecimal weekdayBase, Set<Slot> slots) {
    public Venue {
        if (id == null || id.isBlank()) throw new IllegalArgumentException("Venue id is required");
        if (capacity < 1) throw new IllegalArgumentException("Venue capacity must be positive");
        if (weekdayBase == null || weekdayBase.signum() < 0) throw new IllegalArgumentException("Invalid venue base rate");
        slots = Set.copyOf(slots);
    }
}
