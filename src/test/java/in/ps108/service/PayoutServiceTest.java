package in.ps108.service;

import in.ps108.model.Vendor;
import org.junit.jupiter.api.Test;
import java.math.BigDecimal;
import java.time.LocalDateTime;
import java.util.List;
import java.util.Map;
import static org.junit.jupiter.api.Assertions.*;

class PayoutServiceTest {
    @Test void payoutWaitsSevenDaysAfterEventClose() {
        Vendor decor=new Vendor("D","Decor",Vendor.Category.DECOR,Map.of("BASIC",new BigDecimal("100000")),new BigDecimal("0.15"));
        var payout=new PayoutService().calculate("D",new BigDecimal("100000"),decor,
                LocalDateTime.parse("2026-01-01T12:00:00"),LocalDateTime.parse("2026-01-08T12:00:00"),List.of(),"B1");
        assertTrue(payout.payable());
        assertEquals(new BigDecimal("85000.00"),payout.netPayout());
    }
    @Test void openDisputeWithinFortyEightHoursFreezesPayout() {
        Vendor decor=new Vendor("D","Decor",Vendor.Category.DECOR,Map.of("BASIC",new BigDecimal("100000")),new BigDecimal("0.15"));
        var close=LocalDateTime.parse("2026-01-01T12:00:00");
        var dispute=new PayoutService.Dispute("X","B1","D",close.plusHours(48),"damage",true);
        var payout=new PayoutService().calculate("D",new BigDecimal("100000"),decor,close,close.plusDays(10),List.of(dispute),"B1");
        assertTrue(payout.frozen()); assertFalse(payout.payable());
    }

    @Test void disputeJustOverFortyEightHoursDoesNotFreezePayout() {
        Vendor decor=new Vendor("D","Decor",Vendor.Category.DECOR,Map.of("BASIC",new BigDecimal("100000")),new BigDecimal("0.15"));
        var close=LocalDateTime.parse("2026-01-01T12:00:00");
        var dispute=new PayoutService.Dispute("X","B1","D",close.plusHours(48).plusMinutes(1),"damage",true);
        var payout=new PayoutService().calculate("D",new BigDecimal("100000"),decor,close,close.plusDays(10),List.of(dispute),"B1");
        assertFalse(payout.frozen()); assertTrue(payout.payable());
    }

    @Test void disputeWindowUsesRecordedEventCloseNotEarlierEventStart() {
        Vendor decor=new Vendor("D","Decor",Vendor.Category.DECOR,Map.of("BASIC",new BigDecimal("100000")),new BigDecimal("0.15"));
        var eventStart=LocalDateTime.parse("2026-01-01T09:00:00");
        var eventClose=LocalDateTime.parse("2026-01-01T18:00:00");
        var filed=LocalDateTime.parse("2026-01-03T12:00:00"); // 51h after start, 42h after close
        assertTrue(java.time.Duration.between(eventStart,filed).compareTo(java.time.Duration.ofHours(48))>0);
        var dispute=new PayoutService.Dispute("X","B1","D",filed,"damage",true);
        var payout=new PayoutService().calculate("D",new BigDecimal("100000"),decor,eventClose,
                LocalDateTime.parse("2026-01-10T12:00:00"),List.of(dispute),"B1");
        assertTrue(payout.frozen(),"The project policy measures the 48-hour window from event close");
    }

    @Test void negativeOrNullPayoutValuesAreRejected() {
        Vendor decor=new Vendor("D","Decor",Vendor.Category.DECOR,Map.of("BASIC",BigDecimal.ONE),new BigDecimal("0.15"));
        var service=new PayoutService();
        assertThrows(IllegalArgumentException.class,()->service.calculate("D",new BigDecimal("-1"),decor,LocalDateTime.now(),LocalDateTime.now(),List.of(),"B1"));
        assertThrows(IllegalArgumentException.class,()->service.calculate("D",BigDecimal.ONE,decor,null,LocalDateTime.now(),List.of(),"B1"));
    }

    @Test void futureDatedDisputeDoesNotFreezeAnEarlierPayoutEvaluation() {
        Vendor decor=new Vendor("D","Decor",Vendor.Category.DECOR,Map.of("BASIC",new BigDecimal("100000")),new BigDecimal("0.15"));
        var close=LocalDateTime.parse("2026-01-01T12:00:00");
        var filed=close.plusHours(24);
        var dispute=new PayoutService.Dispute("X","B1","D",filed,"damage",true);
        var payout=new PayoutService().calculate("D",new BigDecimal("100000"),decor,close,close.plusHours(12),List.of(dispute),"B1");
        assertFalse(payout.frozen()); assertFalse(payout.payable());
    }
}
