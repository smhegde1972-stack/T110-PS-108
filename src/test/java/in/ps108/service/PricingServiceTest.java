package in.ps108.service;

import in.ps108.model.*;
import org.junit.jupiter.api.Test;
import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.Set;
import static org.junit.jupiter.api.Assertions.*;

class PricingServiceTest {
    private static final LocalDate WEEKDAY=LocalDate.parse("2026-10-20");
    private static final LocalDate WEEKEND=LocalDate.parse("2026-10-24");

    private Venue venue(String id,BigDecimal base) {
        return new Venue(id,"Venue "+id,500,base,Set.of(Slot.DAY,Slot.NIGHT));
    }


    @Test
    void weekdayUsesBaseAndLabelsDateCategory() {
        var result = new PricingService(Set.of())
                .venueRentalBreakdown(
                        venue("V1", new BigDecimal("50000")),
                        WEEKDAY,
                        Slot.DAY
                );

        assertEquals("WEEKDAY", result.dateCategory());
        assertEquals(0, new BigDecimal("1.0").compareTo(result.multiplier()));
        assertEquals(new BigDecimal("50000.00"), result.total());
    }


    @Test void weekendUsesThirteenTimesBase() {
        var result=new PricingService(Set.of()).venueRentalBreakdown(venue("V1",new BigDecimal("50000")),WEEKEND,Slot.DAY);
        assertEquals("WEEKEND",result.dateCategory());assertEquals(new BigDecimal("1.3"),result.multiplier());
        assertEquals(new BigDecimal("65000.00"),result.total());
    }

    @Test void auspiciousWeekdayUsesSixteenTimesBase() {
        var result=new PricingService(Set.of(WEEKDAY)).venueRentalBreakdown(venue("V1",new BigDecimal("50000")),WEEKDAY,Slot.DAY);
        assertEquals("AUSPICIOUS",result.dateCategory());assertEquals(new BigDecimal("1.6"),result.multiplier());
        assertEquals(new BigDecimal("80000.00"),result.total());
    }

    @Test void auspiciousWeekendUsesMaximumNotStackedMultipliers() {
        var p=new PricingService(Set.of(WEEKEND));
        var result=p.venueRentalBreakdown(venue("V1",new BigDecimal("100000")),WEEKEND,Slot.DAY);
        assertEquals("AUSPICIOUS",result.dateCategory());assertEquals(new BigDecimal("1.6"),result.multiplier());
        assertEquals(new BigDecimal("160000.00"),result.total());
        assertEquals(new BigDecimal("160000.00"),p.venueRental(venue("V1",new BigDecimal("100000")),WEEKEND,Slot.DAY));
    }
    @Test void secondSlotDiscountAppliesToSecondSlot() {
        Venue v=new Venue("V","Venue",500,new BigDecimal("100000"),Set.of(Slot.DAY,Slot.NIGHT));
        PricingService p=new PricingService(Set.of());
        assertEquals(new BigDecimal("190000.00"),p.venueRental(v,LocalDate.parse("2026-10-20"),Slot.DAY_AND_NIGHT));
        var breakdown=p.venueRentalBreakdown(v,WEEKDAY,Slot.DAY_AND_NIGHT);
        assertEquals(Slot.DAY,breakdown.slotRentals().get(0).slot());
        assertEquals(new BigDecimal("100000.00"),breakdown.slotRentals().get(0).amount());
        assertEquals(BigDecimal.ZERO.setScale(2),breakdown.slotRentals().get(0).discount());
        assertEquals(Slot.NIGHT,breakdown.slotRentals().get(1).slot());
        assertEquals(new BigDecimal("10000.00"),breakdown.slotRentals().get(1).discount());
        assertEquals(new BigDecimal("90000.00"),breakdown.slotRentals().get(1).amount());
        assertEquals(new BigDecimal("10000.00"),breakdown.secondSlotDiscount());
    }

    @Test void singleSlotsHaveNoDiscountAndRecalculationDoesNotCompoundIt() {
        PricingService pricing=new PricingService(Set.of());Venue venue=venue("V1",new BigDecimal("50000"));
        var day=pricing.venueRentalBreakdown(venue,WEEKDAY,Slot.DAY);
        var night=pricing.venueRentalBreakdown(venue,WEEKDAY,Slot.NIGHT);
        assertEquals(BigDecimal.ZERO.setScale(2),day.secondSlotDiscount());
        assertEquals(BigDecimal.ZERO.setScale(2),night.secondSlotDiscount());
        var pair=pricing.venueRentalBreakdown(venue,WEEKDAY,Slot.DAY_AND_NIGHT);
        assertEquals(pair,pricing.venueRentalBreakdown(venue,WEEKDAY,Slot.DAY_AND_NIGHT));
        assertEquals(new BigDecimal("95000.00"),pair.total());
    }

    @Test void venueBasePriceAndPerSlotRoundingAreAppliedOnce() {
        PricingService pricing=new PricingService(Set.of());
        var smaller=pricing.venueRentalBreakdown(venue("A",new BigDecimal("25000")),WEEKDAY,Slot.DAY_AND_NIGHT);
        var larger=pricing.venueRentalBreakdown(venue("B",new BigDecimal("50000")),WEEKDAY,Slot.DAY_AND_NIGHT);
        assertEquals(new BigDecimal("47500.00"),smaller.total());assertEquals(new BigDecimal("95000.00"),larger.total());
        var decimals=pricing.venueRentalBreakdown(venue("C",new BigDecimal("1000.005")),WEEKDAY,Slot.DAY_AND_NIGHT);
        assertEquals(new BigDecimal("1000.01"),decimals.slotRentals().get(0).amount());
        assertEquals(new BigDecimal("900.01"),decimals.slotRentals().get(1).amount());
        assertEquals(new BigDecimal("1900.02"),decimals.total());
    }

    @Test void noConfiguredAuspiciousDatesDoNotInventAnyAndNullConfigurationIsRejected() {
        PricingService noDates=new PricingService(Set.of());
        assertEquals("WEEKDAY",noDates.venueRentalBreakdown(venue("V1",new BigDecimal("1000")),WEEKDAY,Slot.DAY).dateCategory());
        assertEquals("WEEKEND",noDates.venueRentalBreakdown(venue("V1",new BigDecimal("1000")),WEEKEND,Slot.DAY).dateCategory());
        assertThrows(IllegalArgumentException.class,()->new PricingService(null));
    }

    @Test void advanceScheduleUsesFinalTwoSlotRentalIncludingTax() {
        PricingService pricing=new PricingService(Set.of());
        BigDecimal rental=pricing.venueRental(venue("V1",new BigDecimal("1000")),WEEKDAY,Slot.DAY_AND_NIGHT);
        BigDecimal venueWithTax=Money.round(rental.multiply(new BigDecimal("1.18")));
        var first=new AdvanceService().schedule(venueWithTax,WEEKDAY,WEEKDAY.plusDays(100)).get(0);
        assertEquals(new BigDecimal("560.50"),first.amount());
    }
    @Test void cateringGuaranteeIsAtLeast150() {
        PricingService p=new PricingService(Set.of());
        assertEquals(150,p.cateringGuarantee(100));
        assertEquals(160,p.cateringGuarantee(200));
    }
    @Test void exactTenPercentDoesNotUsePremiumRate() {
        PricingService p=new PricingService(Set.of());
        assertEquals(new BigDecimal("308000.00"),p.catering(200,220,new BigDecimal("1400")));
        assertEquals(new BigDecimal("309680.00"),p.catering(200,221,new BigDecimal("1400")));
    }

    @Test void unsupportedVenueSlotIsRejected() {
        Venue v=new Venue("V","Venue",500,new BigDecimal("100000"),Set.of(Slot.DAY));
        assertThrows(IllegalArgumentException.class,()->new PricingService(Set.of()).venueRental(v,LocalDate.parse("2026-10-20"),Slot.NIGHT));
    }

    @Test void invalidCateringInputsAreRejected() {
        PricingService p=new PricingService(Set.of());
        assertThrows(IllegalArgumentException.class,()->p.catering(0,10,new BigDecimal("10")));
        assertThrows(IllegalArgumentException.class,()->p.catering(10,10,null));
    }
}
