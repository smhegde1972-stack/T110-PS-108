package in.ps108.service;

import in.ps108.model.Vendor;
import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.temporal.ChronoUnit;
import java.util.Map;

public class CancellationService {
    public record Result(long daysRemaining, BigDecimal grossForfeiture, BigDecimal cappedForfeiture, BigDecimal cap,
                         BigDecimal cancellationPercent, BigDecimal vendorSpecificForfeiture,
                         BigDecimal refundAmount, BigDecimal additionalRecoverableAmount,
                         Map<String,BigDecimal> lineBreakdown, Map<String,BigDecimal> vendorOverrideBreakdown) {
        public Result { lineBreakdown=Map.copyOf(lineBreakdown); vendorOverrideBreakdown=Map.copyOf(vendorOverrideBreakdown); }
    }

    public Result calculate(BigDecimal totalContract, BigDecimal paid, LocalDate eventDate, LocalDate cancelDate,
                            Map<String,BigDecimal> lineValues, Map<String,Vendor> vendors, Map<String,String> selectedPackages,
                            BigDecimal cateringGuaranteeValue) {
        requireNonNegative(totalContract,"Contract total");
        requireNonNegative(paid,"Paid advances");
        requireNonNegative(cateringGuaranteeValue,"Catering guarantee");
        requirePresent(eventDate,"Event date");
        requirePresent(cancelDate,"Cancellation date");
        requirePresent(lineValues,"Cancellation line values");
        requirePresent(vendors,"Vendor data");
        requirePresent(selectedPackages,"Selected packages");
        BigDecimal lineTotal=BigDecimal.ZERO;
        for(var e:selectedPackages.entrySet()) {
            if(e.getKey()==null||!vendors.containsKey(e.getKey())||vendors.get(e.getKey())==null)
                throw new IllegalArgumentException("Unknown selected vendor: "+e.getKey());
            vendors.get(e.getKey()).packagePrice(e.getValue());
            if(!lineValues.containsKey(e.getKey())) throw new IllegalArgumentException("Missing cancellation line for selected vendor "+e.getKey());
        }
        for(var e:lineValues.entrySet()) {
            if(e.getKey()==null||!vendors.containsKey(e.getKey())) throw new IllegalArgumentException("Unknown vendor in cancellation lines: "+e.getKey());
            Vendor vendor=vendors.get(e.getKey());
            if(vendor==null) throw new IllegalArgumentException("Unknown vendor in cancellation lines: "+e.getKey());
            String packageName=selectedPackages.get(e.getKey());
            if(packageName==null) throw new IllegalArgumentException("No selected package for vendor "+e.getKey());
            vendor.packagePrice(packageName);
            requireNonNegative(e.getValue(),"Cancellation line value for "+e.getKey());
            lineTotal=lineTotal.add(e.getValue());
        }
        if(lineTotal.compareTo(totalContract)>0) throw new IllegalArgumentException("Cancellation lines exceed contract total");
        long days=ChronoUnit.DAYS.between(cancelDate,eventDate);
        BigDecimal ladder = days > 180 ? new BigDecimal("0.10")
                : days >= 91 ? new BigDecimal("0.25")
                : days >= 31 ? new BigDecimal("0.50")
                : days >= 8 ? new BigDecimal("0.75") : BigDecimal.ONE;
        BigDecimal gross=BigDecimal.ZERO;
        Map<String,BigDecimal> breakdown=new java.util.LinkedHashMap<>();
        BigDecimal vendorLineTotal=BigDecimal.ZERO;
        BigDecimal vendorOverride=BigDecimal.ZERO;
        Map<String,BigDecimal> overrideBreakdown=new java.util.LinkedHashMap<>();
        for(var e:lineValues.entrySet()) {
            Vendor v=vendors.get(e.getKey());
            if(v==null) continue;
            BigDecimal rate=ladder;
            if(v.category()==Vendor.Category.PHOTOGRAPHY && days<=30) { rate=BigDecimal.ONE; vendorOverride=vendorOverride.add(e.getValue()); overrideBreakdown.put(e.getKey(),Money.round(e.getValue())); }
            if(v.category()==Vendor.Category.CATERING && days<=7) {
                BigDecimal val=cateringGuaranteeValue.min(e.getValue());
                breakdown.put(e.getKey(),Money.round(val)); vendorLineTotal=vendorLineTotal.add(val); vendorOverride=vendorOverride.add(val); overrideBreakdown.put(e.getKey(),Money.round(val)); continue;
            }
            BigDecimal line=Money.round(e.getValue().multiply(rate));
            breakdown.put(e.getKey(),line); vendorLineTotal=vendorLineTotal.add(line);
        }
        BigDecimal venueAndOther=Money.round(totalContract.subtract(lineTotal).multiply(ladder));
        breakdown.put("VENUE_AND_OTHER",venueAndOther);
        gross=Money.round(vendorLineTotal.add(venueAndOther));
        BigDecimal cap=Money.round(paid.add(new BigDecimal("25000")));
        BigDecimal capped=Money.round(gross.min(cap));
        BigDecimal refund=Money.round(paid.subtract(capped).max(BigDecimal.ZERO));
        BigDecimal recoverable=Money.round(capped.subtract(paid).max(BigDecimal.ZERO));
        BigDecimal percent=Money.round(ladder.movePointRight(2));
        return new Result(days,gross,capped,cap,percent,Money.round(vendorOverride),refund,recoverable,breakdown,overrideBreakdown);
    }

    private static void requireNonNegative(BigDecimal value,String label) {
        if(value==null||value.signum()<0) throw new IllegalArgumentException(label+" must be zero or greater");
    }
    private static void requirePresent(Object value,String label) {
        if(value==null) throw new IllegalArgumentException(label+" is required");
    }
}
