package in.ps108.service;

import org.junit.jupiter.api.Test;

import java.math.BigDecimal;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;

class SettlementServiceTest {
    @Test void finalBillAppliesDocumentedTaxesAndReportsAdvanceCreditAsNegativeBalance() {
        var quote=new SettlementService().finalBill(new BigDecimal("100"),new BigDecimal("200"),
                Map.of("D",new BigDecimal("50")),new BigDecimal("10"),new BigDecimal("30"),new BigDecimal("500"));
        assertEquals(new BigDecimal("27.00"),quote.venueAndPackageTax());
        assertEquals(new BigDecimal("10.00"),quote.cateringTax());
        assertEquals(new BigDecimal("427.00"),quote.totalBeforeAdvances());
        assertEquals(new BigDecimal("-73.00"),quote.balance());
    }

    @Test void finalBillRejectsNullOrNegativeMoneyInputs() {
        var service=new SettlementService();
        assertThrows(IllegalArgumentException.class,()->service.finalBill(null,BigDecimal.ZERO,Map.of(),BigDecimal.ZERO,BigDecimal.ZERO,BigDecimal.ZERO));
        assertThrows(IllegalArgumentException.class,()->service.finalBill(BigDecimal.ZERO,BigDecimal.ZERO,Map.of(),BigDecimal.ZERO,new BigDecimal("-1"),BigDecimal.ZERO));
        assertThrows(IllegalArgumentException.class,()->service.finalBill(BigDecimal.ZERO,BigDecimal.ZERO,null,BigDecimal.ZERO,BigDecimal.ZERO,BigDecimal.ZERO));
    }

    @Test void dateChangeCreditReducesBalanceSeparatelyFromCashAdvances() {
        var quote=new SettlementService().finalBill(new BigDecimal("100"),BigDecimal.ZERO,Map.of(),BigDecimal.ZERO,
                BigDecimal.ZERO,new BigDecimal("20"),new BigDecimal("30"));
        assertEquals(new BigDecimal("118.00"),quote.totalBeforeAdvances());
        assertEquals(new BigDecimal("68.00"),quote.balance());
        assertEquals(new BigDecimal("30.00"),quote.dateChangeCreditApplied());
    }
}
