package in.ps108.service;

import in.ps108.model.*;
import java.math.BigDecimal;
import java.util.*;

public class SettlementService {
    public Quote finalBill(BigDecimal venue, BigDecimal catering, Map<String,BigDecimal> packageLines,
                           BigDecimal switchFees, BigDecimal damages, BigDecimal advancesPaid) {
        return finalBill(venue, catering, packageLines, switchFees, damages, advancesPaid, BigDecimal.ZERO);
    }

    public Quote finalBill(BigDecimal venue, BigDecimal catering, Map<String,BigDecimal> packageLines,
                           BigDecimal switchFees, BigDecimal damages, BigDecimal advancesPaid,
                           BigDecimal dateChangeCreditApplied) {
        requireNonNegative(venue,"Venue charge");
        requireNonNegative(catering,"Catering charge");
        requireNonNegative(switchFees,"Switch fees");
        requireNonNegative(damages,"Damages");
        requireNonNegative(advancesPaid,"Advances paid");
        requireNonNegative(dateChangeCreditApplied,"Date-change credit applied");
        if(packageLines==null) throw new IllegalArgumentException("Package lines are required");
        Map<String,BigDecimal> roundedLines=new LinkedHashMap<>();
        for(var e:packageLines.entrySet()) {
            if(e.getKey()==null||e.getKey().isBlank()) throw new IllegalArgumentException("Package line name is required");
            requireNonNegative(e.getValue(),"Package line "+e.getKey());
            roundedLines.put(e.getKey(),Money.round(e.getValue()));
        }
        venue=Money.round(venue); catering=Money.round(catering); switchFees=Money.round(switchFees); damages=Money.round(damages); advancesPaid=Money.round(advancesPaid); dateChangeCreditApplied=Money.round(dateChangeCreditApplied);
        BigDecimal packages=roundedLines.values().stream().reduce(BigDecimal.ZERO,BigDecimal::add);
        BigDecimal taxableVenuePackages=Money.round(venue.add(packages));
        BigDecimal tax18=Money.pct(taxableVenuePackages,"18");
        BigDecimal tax5=Money.pct(catering,"5");
        BigDecimal total=Money.round(venue.add(catering).add(packages).add(switchFees).add(damages).add(tax18).add(tax5));
        return new Quote(Money.round(venue),Money.round(catering),Money.round(packages),Money.round(switchFees),
                Money.round(damages),tax18,tax5,total,Money.round(total.subtract(advancesPaid).subtract(dateChangeCreditApplied)),
                dateChangeCreditApplied,roundedLines);
    }

    public Map<String,BigDecimal> vendorCommissions(Map<String,BigDecimal> lineValues, Map<String,Vendor> vendors) {
        if(lineValues==null||vendors==null) throw new IllegalArgumentException("Commission line values and vendor data are required");
        Map<String,BigDecimal> result=new LinkedHashMap<>();
        for(var e:lineValues.entrySet()) {
            Vendor vendor=vendors.get(e.getKey());
            if(vendor==null) throw new IllegalArgumentException("Unknown vendor: "+e.getKey());
            requireNonNegative(e.getValue(),"Commission line value for "+e.getKey());
            result.put(e.getKey(),Money.pct(e.getValue(),vendor.commissionRate().multiply(new BigDecimal("100")).toPlainString()));
        }
        return result;
    }

    private static void requireNonNegative(BigDecimal value,String label) {
        if(value==null||value.signum()<0) throw new IllegalArgumentException(label+" must be zero or greater");
    }
}
