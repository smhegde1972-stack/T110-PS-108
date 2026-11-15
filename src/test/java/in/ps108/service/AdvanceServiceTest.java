package in.ps108.service;

import org.junit.jupiter.api.Test;
import java.math.BigDecimal;
import java.time.LocalDate;
import static org.junit.jupiter.api.Assertions.*;

class AdvanceServiceTest {
    @Test void exactlySixtyDaysMergesFirstTwoTranches() {
        AdvanceService a=new AdvanceService();
        var s=a.schedule(new BigDecimal("100000"),LocalDate.parse("2026-01-01"),LocalDate.parse("2026-03-02"));
        assertEquals(2,s.size());
        assertEquals(new BigDecimal("75000.00"),s.get(0).amount());
    }
    @Test void exactlySevenDaysMakesFullAmountDueAtConfirmation() {
        AdvanceService a=new AdvanceService();
        var s=a.schedule(new BigDecimal("100000"),LocalDate.parse("2026-01-01"),LocalDate.parse("2026-01-08"));
        assertEquals(1,s.size());
        assertEquals(new BigDecimal("100000.00"),s.get(0).amount());
    }
    @Test void autoCancelAtTwentyOneDaysOverdue() {
        AdvanceService a=new AdvanceService();
        assertTrue(a.autoCancel(LocalDate.parse("2026-01-01"),LocalDate.parse("2026-01-22")));
    }

    @Test void startedWeekLateFeeBoundariesAfterFiveDayGrace() {
        AdvanceService service=new AdvanceService();
        LocalDate due=LocalDate.parse("2026-01-01");
        BigDecimal tranche=new BigDecimal("100000");
        assertEquals(new BigDecimal("0.00"),service.lateFee(tranche,due,due.plusDays(5)));
        assertEquals(new BigDecimal("1000.00"),service.lateFee(tranche,due,due.plusDays(6)));
        assertEquals(new BigDecimal("1000.00"),service.lateFee(tranche,due,due.plusDays(12)));
        assertEquals(new BigDecimal("2000.00"),service.lateFee(tranche,due,due.plusDays(13)));
    }

    @Test void paymentRoundsOnceToCentsAndRejectsRoundedZero() {
        AdvanceService service=new AdvanceService();
        AdvanceLedger ledger=new AdvanceLedger(service.schedule(new BigDecimal("10"),LocalDate.parse("2026-01-01"),LocalDate.parse("2026-05-01")),service);
        ledger.pay(new BigDecimal("0.005"),LocalDate.parse("2026-01-01"));
        assertEquals(new BigDecimal("0.01"),ledger.accounts().get(0).paid());
        assertThrows(IllegalArgumentException.class,()->ledger.pay(new BigDecimal("0.001"),LocalDate.parse("2026-01-01")));
    }

    @Test void invalidScheduleNullAndNegativeAmountsAreRejected() {
        AdvanceService service=new AdvanceService();
        assertThrows(IllegalArgumentException.class,()->service.schedule(null,LocalDate.now(),LocalDate.now()));
        assertThrows(IllegalArgumentException.class,()->service.schedule(new BigDecimal("-1"),LocalDate.now(),LocalDate.now()));
    }
}
