package in.ps108.model;

public enum Slot {
    DAY, NIGHT, DAY_AND_NIGHT;

    public static Slot parse(String value) {
        return switch (value.trim().toUpperCase()) {
            case "DAY" -> DAY;
            case "NIGHT" -> NIGHT;
            case "DAY+NIGHT", "DAY_AND_NIGHT" -> DAY_AND_NIGHT;
            default -> throw new IllegalArgumentException("Invalid slot: " + value);
        };
    }

    public int count() { return this == DAY_AND_NIGHT ? 2 : 1; }
}
