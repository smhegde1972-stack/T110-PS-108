package in.ps108.service;

import in.ps108.model.*;
import java.math.BigDecimal;
import java.time.DayOfWeek;
import java.time.LocalDate;
import java.util.*;

public class PricingService {
    public record SlotRental(Slot slot, BigDecimal undiscountedAmount, BigDecimal discount, BigDecimal amount) {}
    public record VenueRentalBreakdown(BigDecimal weekdayBaseRental, String dateCategory, BigDecimal multiplier,
                                       List<SlotRental> slotRentals, BigDecimal secondSlotDiscount, BigDecimal total) {
        public VenueRentalBreakdown { slotRentals=List.copyOf(slotRentals); }
    }
    private final Set<LocalDate> auspiciousDates;
    public PricingService(Set<LocalDate> auspiciousDates) {
        if(auspiciousDates==null) throw new IllegalArgumentException("Auspicious date set is required");
        for(LocalDate date:auspiciousDates) if(date==null) throw new IllegalArgumentException("Auspicious dates cannot contain null");
        this.auspiciousDates = Set.copyOf(auspiciousDates);
    }

    public BigDecimal seasonMultiplier(LocalDate date) {
        if(date==null) throw new IllegalArgumentException("Pricing date is required");
        BigDecimal multiplier = BigDecimal.ONE;
        DayOfWeek day = date.getDayOfWeek();
        if (day == DayOfWeek.SATURDAY || day == DayOfWeek.SUNDAY) multiplier = new BigDecimal("1.3");
        if (auspiciousDates.contains(date)) multiplier = multiplier.max(new BigDecimal("1.6"));
        return multiplier;
    }

    public BigDecimal venueRental(Venue venue, LocalDate date, Slot slot) {
        return venueRentalBreakdown(venue,date,slot).total();
    }

    public VenueRentalBreakdown venueRentalBreakdown(Venue venue,LocalDate date,Slot slot) {
        if(venue==null||date==null||slot==null) throw new IllegalArgumentException("Venue, date, and slot are required");
        for(Slot atomic:(slot==Slot.DAY_AND_NIGHT?List.of(Slot.DAY,Slot.NIGHT):List.of(slot)))
            if(!venue.slots().contains(atomic)) throw new IllegalArgumentException("Venue "+venue.id()+" does not support slot "+atomic);
        BigDecimal multiplier = seasonMultiplier(date);
        String category=auspiciousDates.contains(date)?"AUSPICIOUS":isWeekend(date)?"WEEKEND":"WEEKDAY";
        BigDecimal regularSlot=Money.round(venue.weekdayBase().multiply(multiplier));
        List<Slot> requestedSlots=slot==Slot.DAY_AND_NIGHT?List.of(Slot.DAY,Slot.NIGHT):List.of(slot);
        List<SlotRental> slotRentals=new ArrayList<>();BigDecimal total=Money.ZERO,discountTotal=Money.ZERO;
        for(int index=0;index<requestedSlots.size();index++) {
            BigDecimal discount=index==1?Money.pct(regularSlot,"10"):Money.ZERO;
            BigDecimal amount=Money.round(regularSlot.subtract(discount));
            slotRentals.add(new SlotRental(requestedSlots.get(index),regularSlot,discount,amount));
            total=total.add(amount);discountTotal=discountTotal.add(discount);
        }
        return new VenueRentalBreakdown(Money.round(venue.weekdayBase()),category,multiplier,slotRentals,Money.round(discountTotal),Money.round(total));
    }

    private static boolean isWeekend(LocalDate date) {
        DayOfWeek day=date.getDayOfWeek();return day==DayOfWeek.SATURDAY||day==DayOfWeek.SUNDAY;
    }

    public int cateringGuarantee(int quotedGuests) {
        if (quotedGuests < 1) {
            throw new IllegalArgumentException("Quoted guest count must be positive");
        }

        // Round 80% upward, then enforce the 150-plate minimum.
        long eightyPercent = (quotedGuests * 80L + 99L) / 100L;
        return (int) Math.max(eightyPercent, 150L);
    }

    public BigDecimal catering(int quotedGuests, int actualGuests, BigDecimal rate) {
        if (quotedGuests < 1 || actualGuests < 0 || rate == null || rate.signum() < 0) {
            throw new IllegalArgumentException("Invalid catering input");
        }

        int guarantee = cateringGuarantee(quotedGuests);
        int billedPlates = Math.max(actualGuests, guarantee);

        // Premium applies only to actual guests exceeding the quoted count by 10%.
        long premiumThreshold = quotedGuests * 110L / 100L;
        long premiumPlates = Math.max(0L, actualGuests - premiumThreshold);
        long normalPlates = billedPlates - premiumPlates;

        BigDecimal normalAmount = rate.multiply(BigDecimal.valueOf(normalPlates));
        BigDecimal premiumAmount = rate
                .multiply(new BigDecimal("1.2"))
                .multiply(BigDecimal.valueOf(premiumPlates));

        return Money.round(normalAmount.add(premiumAmount));
    }
    public BigDecimal packageTotal(Map<String,String> selected, Map<String,Vendor> vendors, Map<String,BigDecimal> lines) {
        BigDecimal total = BigDecimal.ZERO;
        for (var e : selected.entrySet()) {
            Vendor vendor = requireVendor(vendors, e.getKey());
            BigDecimal price = vendor.packagePrice(e.getValue());
            lines.put(e.getKey(), Money.round(price));
            if (vendor.category() != Vendor.Category.CATERING) total = total.add(price);
        }
        return Money.round(total);
    }

    private Vendor requireVendor(Map<String,Vendor> vendors,String id) {
        Vendor v=vendors.get(id); if(v==null) throw new IllegalArgumentException("Unknown vendor: "+id); return v;
    }
}
