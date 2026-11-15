package in.ps108.service;

import org.junit.jupiter.api.Test;

import java.math.BigDecimal;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;

class DamageServiceTest {
    private final DamageService service = new DamageService();
    private final Map<String, BigDecimal> rates = Map.of(
            "CHAIR", new BigDecimal("1500.00"), "TABLE", new BigDecimal("5000.00"));

    @Test void parsesAndCalculatesRateCardCodesAndQuantities() {
        var charges = service.calculate(service.parse("CHAIR:2;TABLE:1"), rates);
        assertEquals(new BigDecimal("8000.00"), charges.total());
        assertEquals(new BigDecimal("3000.00"), charges.lineAmounts().get("CHAIR"));
    }

    @Test void acceptsEmptyDamageListAndRejectsUnknownOrInvalidEntries() {
        assertEquals(BigDecimal.ZERO.setScale(2), service.calculate(service.parse("-"), rates).total());
        assertThrows(IllegalArgumentException.class, () -> service.calculate(service.parse("UNKNOWN:1"), rates));
        assertThrows(IllegalArgumentException.class, () -> service.parse("CHAIR:-1"));
        assertThrows(IllegalArgumentException.class, () -> service.parse("CHAIR:1;CHAIR:2"));
    }
}
