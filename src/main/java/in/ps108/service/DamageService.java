package in.ps108.service;

import java.math.BigDecimal;
import java.util.LinkedHashMap;
import java.util.Locale;
import java.util.Map;

/** Applies damage codes and quantities to the configured venue damage rate card. */
public final class DamageService {
    public record Charge(Map<String, Integer> quantities, Map<String, BigDecimal> lineAmounts, BigDecimal total) {
        public Charge {
            quantities = Map.copyOf(quantities);
            lineAmounts = Map.copyOf(lineAmounts);
        }
    }

    public Map<String, Integer> parse(String specification) {
        Map<String, Integer> quantities = new LinkedHashMap<>();
        if (specification == null || specification.isBlank() || specification.trim().equals("-")) return quantities;
        for (String item : specification.split(";")) {
            String[] pair = item.trim().split(":", 2);
            if (pair.length != 2 || pair[0].isBlank())
                throw new IllegalArgumentException("Damage items must use CODE:QUANTITY pairs separated by semicolons");
            String code = pair[0].trim().toUpperCase(Locale.ROOT);
            int quantity;
            try { quantity = Integer.parseInt(pair[1].trim()); }
            catch (NumberFormatException e) { throw new IllegalArgumentException("Invalid quantity for damage code " + code); }
            if (quantity < 0) throw new IllegalArgumentException("Damage quantity must be zero or greater for " + code);
            if (quantities.putIfAbsent(code, quantity) != null)
                throw new IllegalArgumentException("Duplicate damage code " + code);
        }
        return quantities;
    }

    public Charge calculate(Map<String, Integer> quantities, Map<String, BigDecimal> rates) {
        if (quantities == null || rates == null) throw new IllegalArgumentException("Damage quantities and rate card are required");
        Map<String, BigDecimal> lines = new LinkedHashMap<>();
        BigDecimal total = BigDecimal.ZERO;
        for (var entry : quantities.entrySet()) {
            String code = entry.getKey();
            Integer quantity = entry.getValue();
            BigDecimal rate = rates.get(code);
            if (code == null || rate == null) throw new IllegalArgumentException("Unknown damage code: " + code);
            if (quantity == null || quantity < 0) throw new IllegalArgumentException("Invalid damage quantity for " + code);
            BigDecimal amount = Money.round(rate.multiply(BigDecimal.valueOf(quantity)));
            lines.put(code, amount);
            total = total.add(amount);
        }
        return new Charge(quantities, lines, Money.round(total));
    }
}
