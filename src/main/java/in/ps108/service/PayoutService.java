    package in.ps108.service;

    import in.ps108.model.Vendor;
    import java.math.BigDecimal;
    import java.time.Duration;
    import java.time.LocalDateTime;
    import java.util.Collection;

    /** Computes platform commission and enforces event-close/dispute payout gates. */
    public class PayoutService {
        public record Dispute(String id, String bookingId, String vendorId, LocalDateTime filedAt, String reason, boolean open) {}
        public record Payout(String vendorId, BigDecimal grossLineValue, BigDecimal commission, BigDecimal netPayout,
                             LocalDateTime eligibleAt, boolean frozen, boolean payable, String status) {}

        public Payout calculate(String vendorId, BigDecimal grossLineValue, Vendor vendor,
                                LocalDateTime eventClosedAt, LocalDateTime now, Collection<Dispute> disputes,
                                String bookingId) {
            if(vendor==null) throw new IllegalArgumentException("Vendor is required");
            if(vendorId==null||!vendorId.equals(vendor.id())) throw new IllegalArgumentException("Payout vendor ID does not match vendor");
            return calculateAtRate(vendorId,grossLineValue,vendor.commissionRate(),eventClosedAt,now,disputes,bookingId);
        }

        /** Venue commission is specified separately from vendor CSV rates: 12%. */
        public Payout calculateVenue(String venueId, BigDecimal grossLineValue, LocalDateTime eventClosedAt,
                                     LocalDateTime now, Collection<Dispute> disputes, String bookingId) {
            if(venueId==null||venueId.isBlank()) throw new IllegalArgumentException("Venue ID is required");
            return calculateAtRate(venueId,grossLineValue,new BigDecimal("0.12"),eventClosedAt,now,disputes,bookingId);
        }

        private Payout calculateAtRate(String payeeId, BigDecimal grossLineValue, BigDecimal rate,
                                       LocalDateTime eventClosedAt, LocalDateTime now,
                                       Collection<Dispute> disputes, String bookingId) {
            if(payeeId==null||payeeId.isBlank()||bookingId==null||bookingId.isBlank()||eventClosedAt==null||now==null||disputes==null)
                throw new IllegalArgumentException("Payout IDs, timestamps, and disputes are required");
            if(grossLineValue==null||grossLineValue.signum()<0) throw new IllegalArgumentException("Payout gross must be zero or greater");
            BigDecimal gross=Money.round(grossLineValue);
            BigDecimal commission=Money.round(gross.multiply(rate));
            BigDecimal net=Money.round(gross.subtract(commission));
            LocalDateTime eligibleAt=eventClosedAt.plusDays(7);
            boolean frozen=false;
            for(Dispute d:disputes) {
                if(d==null||d.bookingId()==null||d.vendorId()==null||d.filedAt()==null) throw new IllegalArgumentException("Payout dispute record is invalid");
                if(d.open() && d.bookingId().equals(bookingId) && d.vendorId().equals(payeeId)) {
                    Duration elapsed=Duration.between(eventClosedAt,d.filedAt());
                    if(!d.filedAt().isAfter(now) && !elapsed.isNegative() && elapsed.compareTo(Duration.ofHours(48))<=0) frozen=true;
                }
            }
            boolean payable=!frozen && !now.isBefore(eligibleAt);
            String status=frozen?"FROZEN_DISPUTE":payable?"PAYABLE":"PENDING_7_DAY_HOLD";
            return new Payout(payeeId,gross,commission,net,eligibleAt,frozen,payable,status);
        }
    }
