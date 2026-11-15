package in.ps108.service;

import java.time.Duration;
import java.time.LocalDateTime;
import java.util.*;

/** Small in-memory dispute ledger. Disputes submitted after 48 hours are recorded but do not freeze payout under BR-11. */
public class DisputeLedger {
    private final Map<String,PayoutService.Dispute> disputes = new LinkedHashMap<>();

    public PayoutService.Dispute file(String id, String bookingId, String vendorId, LocalDateTime filedAt,
                                      LocalDateTime eventClosedAt, String reason) {
        if(id==null||id.isBlank()||bookingId==null||bookingId.isBlank()||vendorId==null||vendorId.isBlank()
                ||filedAt==null||eventClosedAt==null||reason==null||reason.isBlank())
            throw new IllegalArgumentException("Dispute ID, booking, vendor, timestamps, and reason are required");
        if(disputes.containsKey(id)) throw new IllegalArgumentException("Duplicate dispute id: " + id);
        if(filedAt.isBefore(eventClosedAt)) throw new IllegalArgumentException("Dispute timestamp cannot precede event close");
        boolean within48=Duration.between(eventClosedAt,filedAt).compareTo(Duration.ofHours(48))<=0;
        PayoutService.Dispute d=new PayoutService.Dispute(id,bookingId,vendorId,filedAt,reason,within48);
        disputes.put(id,d); return d;
    }
    public void resolve(String id) {
        PayoutService.Dispute old=disputes.get(id);
        if(old==null) throw new IllegalArgumentException("Unknown dispute: " + id);
        disputes.put(id,new PayoutService.Dispute(old.id(),old.bookingId(),old.vendorId(),old.filedAt(),old.reason(),false));
    }
    public Collection<PayoutService.Dispute> all(){return List.copyOf(disputes.values());}
}
