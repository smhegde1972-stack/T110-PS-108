package in.ps108.service;

import in.ps108.model.Vendor;
import org.junit.jupiter.api.Test;
import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.Map;
import static org.junit.jupiter.api.Assertions.*;

class CancellationServiceTest {
    @Test void capIsPaidAdvancesPlusTwentyFiveThousand() {
        Vendor decor=new Vendor("D","Decor",Vendor.Category.DECOR,Map.of("BASIC",new BigDecimal("100000")),new BigDecimal("0.15"));
        var result=new CancellationService().calculate(new BigDecimal("200000"),new BigDecimal("10000"),
                LocalDate.parse("2026-12-31"),LocalDate.parse("2026-12-01"),Map.of("D",new BigDecimal("100000")),
                Map.of("D",decor),Map.of("D","BASIC"),BigDecimal.ZERO);
        assertEquals(new BigDecimal("35000.00"),result.cap());
        assertEquals(result.cap(),result.cappedForfeiture());
        assertEquals(new BigDecimal("0.00"),result.refundAmount());
        assertEquals(new BigDecimal("25000.00"),result.additionalRecoverableAmount());
    }
    @Test void cancellationAtExactlyNinetyDaysUsesFiftyPercentLadder() {
        Vendor decor=new Vendor("D","Decor",Vendor.Category.DECOR,Map.of("BASIC",new BigDecimal("100000")),new BigDecimal("0.15"));
        var result=new CancellationService().calculate(new BigDecimal("100000"),new BigDecimal("100000"),
                LocalDate.parse("2026-04-01"),LocalDate.parse("2026-01-01"),Map.of("D",new BigDecimal("100000")),
                Map.of("D",decor),Map.of("D","BASIC"),BigDecimal.ZERO);
        assertEquals(new BigDecimal("50000.00"),result.grossForfeiture());
    }

    @Test void cancellationLadderUsesEachExactBandBoundary() {
        Vendor decor=new Vendor("D","Decor",Vendor.Category.DECOR,Map.of("BASIC",new BigDecimal("100000")),new BigDecimal("0.15"));
        CancellationService service=new CancellationService();
        LocalDate event=LocalDate.parse("2027-06-01");
        Map<Integer,String> expectedRates=Map.of(181,"10",180,"25",91,"25",90,"50",31,"50",30,"75",8,"75",7,"100");

        expectedRates.forEach((days,percent)->{
            var result=service.calculate(new BigDecimal("100000"),new BigDecimal("100000"),event,event.minusDays(days),
                    Map.of("D",new BigDecimal("100000")),Map.of("D",decor),Map.of("D","BASIC"),BigDecimal.ZERO);
            assertEquals(days.longValue(),result.daysRemaining());
            assertEquals(new BigDecimal("100000").multiply(new BigDecimal(percent)).movePointLeft(2).setScale(2),
                    result.grossForfeiture(),"Unexpected ladder rate at "+days+" days before the event");
        });
    }

    @Test void refundIsPaidAdvancesLessCappedForfeiture() {
        Vendor decor=new Vendor("D","Decor",Vendor.Category.DECOR,Map.of("BASIC",new BigDecimal("100000")),new BigDecimal("0.15"));
        var result=new CancellationService().calculate(new BigDecimal("100000"),new BigDecimal("80000"),
                LocalDate.parse("2027-06-01"),LocalDate.parse("2027-03-03"),Map.of("D",new BigDecimal("100000")),
                Map.of("D",decor),Map.of("D","BASIC"),BigDecimal.ZERO);
        assertEquals(new BigDecimal("50.00"),result.cancellationPercent());
        assertEquals(new BigDecimal("50000.00"),result.cappedForfeiture());
        assertEquals(new BigDecimal("30000.00"),result.refundAmount());
        assertEquals(new BigDecimal("0.00"),result.additionalRecoverableAmount());
    }

    @Test void noRefundWhenPaidIsBelowCappedForfeitureAndRecoverableIsReported() {
        Vendor decor=new Vendor("D","Decor",Vendor.Category.DECOR,Map.of("BASIC",new BigDecimal("100000")),new BigDecimal("0.15"));
        var result=new CancellationService().calculate(new BigDecimal("100000"),new BigDecimal("10000"),
                LocalDate.parse("2027-06-01"),LocalDate.parse("2027-05-31"),Map.of("D",new BigDecimal("100000")),
                Map.of("D",decor),Map.of("D","BASIC"),BigDecimal.ZERO);
        assertEquals(new BigDecimal("0.00"),result.refundAmount());
        assertEquals(new BigDecimal("25000.00"),result.additionalRecoverableAmount());
        assertEquals(new BigDecimal("35000.00"),result.cappedForfeiture());
    }

    @Test void refundIsZeroWhenPaidExactlyEqualsForfeiture() {
        Vendor decor=new Vendor("D","Decor",Vendor.Category.DECOR,Map.of("BASIC",new BigDecimal("100000")),new BigDecimal("0.15"));
        var result=new CancellationService().calculate(new BigDecimal("100000"),new BigDecimal("50000"),
                LocalDate.parse("2027-06-01"),LocalDate.parse("2027-03-03"),Map.of("D",new BigDecimal("100000")),
                Map.of("D",decor),Map.of("D","BASIC"),BigDecimal.ZERO);
        assertEquals(new BigDecimal("0.00"),result.refundAmount());
        assertEquals(new BigDecimal("0.00"),result.additionalRecoverableAmount());
    }

    @Test void briefRefundExamplesNeverRefundUnpaidContractValue() {
        Vendor decor=new Vendor("D","Decor",Vendor.Category.DECOR,Map.of("BASIC",new BigDecimal("10000")),new BigDecimal("0.15"));
        var paidMoreThanForfeit=new CancellationService().calculate(new BigDecimal("10000"),new BigDecimal("10000"),
                LocalDate.parse("2027-06-01"),LocalDate.parse("2027-03-03"),Map.of("D",new BigDecimal("10000")),
                Map.of("D",decor),Map.of("D","BASIC"),BigDecimal.ZERO);
        assertEquals(new BigDecimal("5000.00"),paidMoreThanForfeit.cappedForfeiture());
        assertEquals(new BigDecimal("5000.00"),paidMoreThanForfeit.refundAmount());

        Vendor catering=new Vendor("C","Catering",Vendor.Category.CATERING,Map.of("BASIC",new BigDecimal("1")),new BigDecimal("0.08"));
        var paidLessThanForfeit=new CancellationService().calculate(new BigDecimal("15000"),new BigDecimal("10000"),
                LocalDate.parse("2027-06-01"),LocalDate.parse("2027-05-25"),Map.of("C",new BigDecimal("5000")),
                Map.of("C",catering),Map.of("C","BASIC"),new BigDecimal("5000"));
        assertEquals(new BigDecimal("15000.00"),paidLessThanForfeit.cappedForfeiture());
        assertEquals(new BigDecimal("0.00"),paidLessThanForfeit.refundAmount());
        assertEquals(new BigDecimal("5000.00"),paidLessThanForfeit.additionalRecoverableAmount());
    }
    @Test void photographyOverridesLadderWithinThirtyDays() {
        Vendor photo=new Vendor("P","Photo",Vendor.Category.PHOTOGRAPHY,Map.of("BASIC",new BigDecimal("50000")),new BigDecimal("0.15"));
        var result=new CancellationService().calculate(new BigDecimal("50000"),new BigDecimal("100000"),
                LocalDate.parse("2026-01-31"),LocalDate.parse("2026-01-10"),Map.of("P",new BigDecimal("50000")),
                Map.of("P",photo),Map.of("P","BASIC"),BigDecimal.ZERO);
        assertEquals(new BigDecimal("50000.00"),result.grossForfeiture());
    }

    @Test void photographyOverrideAppliesAtThirtyDaysButNotAtThirtyOne() {
        Vendor photo=new Vendor("P","Photo",Vendor.Category.PHOTOGRAPHY,Map.of("BASIC",new BigDecimal("50000")),new BigDecimal("0.15"));
        CancellationService service=new CancellationService();
        LocalDate event=LocalDate.parse("2026-06-01");
        var atThirty=service.calculate(new BigDecimal("50000"),new BigDecimal("100000"),event,event.minusDays(30),Map.of("P",new BigDecimal("50000")),Map.of("P",photo),Map.of("P","BASIC"),BigDecimal.ZERO);
        var atThirtyOne=service.calculate(new BigDecimal("50000"),new BigDecimal("100000"),event,event.minusDays(31),Map.of("P",new BigDecimal("50000")),Map.of("P",photo),Map.of("P","BASIC"),BigDecimal.ZERO);
        assertEquals(new BigDecimal("50000.00"),atThirty.grossForfeiture());
        assertEquals(new BigDecimal("25000.00"),atThirtyOne.grossForfeiture());
        assertEquals(new BigDecimal("50000.00"),atThirty.vendorOverrideBreakdown().get("P"));
        assertTrue(atThirtyOne.vendorOverrideBreakdown().isEmpty());
    }

    @Test void cateringGuaranteeOverrideAppliesThroughSevenDaysOnly() {
        Vendor catering=new Vendor("C","Catering",Vendor.Category.CATERING,Map.of("BASIC",new BigDecimal("100000")),new BigDecimal("0.08"));
        CancellationService service=new CancellationService();
        LocalDate event=LocalDate.parse("2026-06-01");
        var atSeven=service.calculate(new BigDecimal("100000"),new BigDecimal("200000"),event,event.minusDays(7),Map.of("C",new BigDecimal("100000")),Map.of("C",catering),Map.of("C","BASIC"),new BigDecimal("20000"));
        var atEight=service.calculate(new BigDecimal("100000"),new BigDecimal("200000"),event,event.minusDays(8),Map.of("C",new BigDecimal("100000")),Map.of("C",catering),Map.of("C","BASIC"),new BigDecimal("20000"));
        assertEquals(new BigDecimal("20000.00"),atSeven.grossForfeiture());
        assertEquals(new BigDecimal("75000.00"),atEight.grossForfeiture());
        assertEquals(new BigDecimal("20000.00"),atSeven.vendorOverrideBreakdown().get("C"));
        assertTrue(atEight.vendorOverrideBreakdown().isEmpty());
    }

    @Test void vendorOverridesPrecedeGeneralLadderAndCapAppliesOnceAfterSum() {
        Vendor photo=new Vendor("P","Photo",Vendor.Category.PHOTOGRAPHY,Map.of("BASIC",new BigDecimal("50000")),new BigDecimal("0.15"));
        Vendor catering=new Vendor("C","Catering",Vendor.Category.CATERING,Map.of("BASIC",new BigDecimal("50000")),new BigDecimal("0.08"));
        Vendor decor=new Vendor("D","Decor",Vendor.Category.DECOR,Map.of("BASIC",new BigDecimal("50000")),new BigDecimal("0.15"));
        Map<String,BigDecimal> lines=Map.of("P",new BigDecimal("50000"),"C",new BigDecimal("50000"),"D",new BigDecimal("50000"));
        var result=new CancellationService().calculate(new BigDecimal("200000"),new BigDecimal("100000"),LocalDate.parse("2026-06-08"),LocalDate.parse("2026-06-01"),lines,Map.of("P",photo,"C",catering,"D",decor),Map.of("P","BASIC","C","BASIC","D","BASIC"),new BigDecimal("20000"));
        assertEquals(new BigDecimal("170000.00"),result.grossForfeiture());
        assertEquals(new BigDecimal("70000.00"),result.vendorSpecificForfeiture());
        assertEquals(new BigDecimal("125000.00"),result.cap());
        assertEquals(new BigDecimal("125000.00"),result.cappedForfeiture());
    }

    @Test void nullInputsFailWithClearValidationError() {
        CancellationService service=new CancellationService();
        assertThrows(IllegalArgumentException.class,()->service.calculate(null,BigDecimal.ZERO,LocalDate.now(),LocalDate.now(),Map.of(),Map.of(),Map.of(),BigDecimal.ZERO));
        assertThrows(IllegalArgumentException.class,()->service.calculate(BigDecimal.ZERO,null,LocalDate.now(),LocalDate.now(),Map.of(),Map.of(),Map.of(),BigDecimal.ZERO));
        assertThrows(IllegalArgumentException.class,()->service.calculate(BigDecimal.ZERO,BigDecimal.ZERO,LocalDate.now(),LocalDate.now(),null,Map.of(),Map.of(),BigDecimal.ZERO));
        assertThrows(IllegalArgumentException.class,()->service.calculate(BigDecimal.ZERO,BigDecimal.ZERO,LocalDate.now(),LocalDate.now(),Map.of(),Map.of(),Map.of(),null));
    }

    @Test void unknownCancellationVendorIsRejected() {
        assertThrows(IllegalArgumentException.class,()->new CancellationService().calculate(new BigDecimal("10"),BigDecimal.ZERO,LocalDate.now(),LocalDate.now(),Map.of("missing",BigDecimal.ONE),Map.of(),Map.of("missing","BASIC"),BigDecimal.ZERO));
    }

    @Test void selectedVendorWithoutCancellationLineIsRejected() {
        Vendor decor=new Vendor("D","Decor",Vendor.Category.DECOR,Map.of("BASIC",BigDecimal.TEN),new BigDecimal("0.15"));
        assertThrows(IllegalArgumentException.class,()->new CancellationService().calculate(new BigDecimal("10"),BigDecimal.ZERO,LocalDate.now(),LocalDate.now(),Map.of(),Map.of("D",decor),Map.of("D","BASIC"),BigDecimal.ZERO));
    }
}
