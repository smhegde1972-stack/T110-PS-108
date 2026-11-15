package in.ps108.service;

import in.ps108.model.*;
import java.math.BigDecimal;
import java.time.*;
import java.time.temporal.ChronoUnit;
import java.sql.*;
import java.util.*;
import java.util.function.Supplier;
import java.util.function.Consumer;

/** Core booking state machine; the web adapter can persist state through the SQLite transaction store. */
public class BookingEngine {
    public record Enquiry(String id, String client, String venueId, LocalDate date, Slot slot, int guests, Map<String,String> selectedPackages) {
        public Enquiry(String id,String client,String venueId,LocalDate date,Slot slot,int guests){this(id,client,venueId,date,slot,guests,Map.of());}
        public Enquiry { selectedPackages=selectedPackages==null?Map.of():Map.copyOf(selectedPackages); }
    }
    private final Map<String,Venue> venues;
    private final Map<String,Vendor> vendors;
    private final PricingService pricing;
    private final Map<String,Booking> bookings = new LinkedHashMap<>();
    private final Map<String,Enquiry> enquiries = new LinkedHashMap<>();
    private final Map<String,Deque<String>> queues = new LinkedHashMap<>();
    private final Map<String,LocalDateTime> enquiryCreatedAt = new LinkedHashMap<>();
    private final Map<String,String> enquiryStatuses = new LinkedHashMap<>();
    private final Map<String,String> bookingEnquiryIds = new HashMap<>();
    /** Maps atomic venue/date/slot keys to the booking holding or confirming them. */
    private final Map<String,String> activeHolds = new HashMap<>();
    private final Set<String> confirmedSlots = new HashSet<>();
    private final Map<String,String> confirmedSlotOwners = new HashMap<>();
    private final Set<String> paidConfirmedSlots = new HashSet<>();
    private final Map<String,BigDecimal> paymentsByBooking = new HashMap<>();
    private final Clock clock;
    private final SqliteHoldStore holdStore;

    public BookingEngine(Map<String,Venue> venues, Map<String,Vendor> vendors, PricingService pricing) {
        this(venues,vendors,pricing,Clock.systemDefaultZone(),null);
    }

    public BookingEngine(Map<String,Venue> venues, Map<String,Vendor> vendors, PricingService pricing, Clock clock) {
        this(venues,vendors,pricing,clock,null);
    }

    public BookingEngine(Map<String,Venue> venues, Map<String,Vendor> vendors, PricingService pricing, Clock clock, SqliteHoldStore holdStore) {
        this.venues=Objects.requireNonNull(venues); this.vendors=Objects.requireNonNull(vendors); this.pricing=Objects.requireNonNull(pricing);
        this.clock=Objects.requireNonNull(clock); this.holdStore=holdStore;
        if(holdStore!=null) holdStore.transaction(connection->{loadState(connection);return null;});
    }

    private <T> T mutate(Supplier<T> operation) {
        if(holdStore==null) return operation.get();
        try {
            return holdStore.transaction(connection->{loadState(connection);T result=operation.get();saveState(connection);return result;});
        } catch(RuntimeException e) {
            try { holdStore.transaction(connection->{loadState(connection);return null;}); } catch(RuntimeException ignored) { }
            throw e;
        }
    }

    private void refreshFromStore() {
        if(holdStore!=null) holdStore.transaction(connection->{loadState(connection);return null;});
    }

    private List<String> atomicKeys(String venue, LocalDate date, Slot slot) {
        if (slot == Slot.DAY_AND_NIGHT) return List.of(key(venue,date,Slot.DAY), key(venue,date,Slot.NIGHT));
        return List.of(key(venue,date,slot));
    }
    private String key(String venue, LocalDate date, Slot slot) { return venue + "|" + date + "|" + slot; }
    private boolean overlaps(Slot a,Slot b) {
        return a==Slot.DAY_AND_NIGHT || b==Slot.DAY_AND_NIGHT || a==b;
    }

    public synchronized void enquire(Enquiry e) { enquire(e, LocalDateTime.now(clock)); }
    public synchronized void enquire(Enquiry e, LocalDateTime now) {
        mutate(()->{enquireInternal(e,now);return null;});
    }

    private void enquireInternal(Enquiry e,LocalDateTime now) {
        expireHoldsInternal(now);
        Venue venue = requireVenue(e.venueId());
        if (e.id()==null || e.id().isBlank() || e.client()==null || e.client().isBlank()) throw new IllegalArgumentException("Enquiry id and client are required");
        if (e.guests()<1) throw new IllegalArgumentException("Guests must be positive");
        if (e.guests()>venue.capacity()) throw new IllegalArgumentException("Guests exceed venue capacity");
        if (e.date()==null || e.slot()==null) throw new IllegalArgumentException("Enquiry date and slot are required");
        for(Slot atomic: (e.slot()==Slot.DAY_AND_NIGHT?List.of(Slot.DAY,Slot.NIGHT):List.of(e.slot())))
            if(!venue.slots().contains(atomic)) throw new IllegalArgumentException("Venue "+venue.id()+" does not support slot "+atomic);
        if (enquiries.containsKey(e.id())) throw new IllegalArgumentException("Duplicate enquiry " + e.id());
        for (String k: atomicKeys(e.venueId(),e.date(),e.slot())) {
            if (paidConfirmedSlots.contains(k)) throw new IllegalStateException("Slot already confirmed by a booking with an advance paid");
        }
        enquiries.put(e.id(),e);
        enquiryCreatedAt.put(e.id(),now);
        for (String k: atomicKeys(e.venueId(),e.date(),e.slot())) {
            if (confirmedSlots.contains(k) || activeHolds.containsKey(k)) {
                queues.computeIfAbsent(key(e.venueId(),e.date(),e.slot()),x->new ArrayDeque<>()).addLast(e.id());
                enquiryStatuses.put(e.id(),"QUEUED");
                return;
            }
        }
        grantHold(e.id(), now);
    }

    public synchronized void expireHolds(LocalDateTime now) { mutate(()->{expireHoldsInternal(now);return null;}); }

    private void expireHoldsInternal(LocalDateTime now) {
        // Release every expired hold first; then promote at most one queued enquiry per released slot.
        Set<String> expiredBookingIds = new LinkedHashSet<>();
        for (var entry: bookings.entrySet()) {
            Booking b=entry.getValue();
            if (b.status()==Booking.Status.HELD && b.holdExpiresAt()!=null && !b.holdExpiresAt().isAfter(now)) expiredBookingIds.add(entry.getKey());
        }
        Set<String> releasedQueueKeys = new LinkedHashSet<>();
        for (String id:expiredBookingIds) {
            Booking b=bookings.get(id); b.status(Booking.Status.EXPIRED);
            String enquiryId=bookingEnquiryIds.get(id);
            if(enquiryId!=null) enquiryStatuses.put(enquiryId,"EXPIRED");
            for(String k:atomicKeys(b.venueId(),b.eventDate(),b.slot())) activeHolds.remove(k,id);
            String prefix=b.venueId()+"|"+b.eventDate()+"|";
            for(String qk:queues.keySet()) if(qk.startsWith(prefix)) {
                String queuedSlotName=qk.substring(prefix.length());
                Slot queuedSlot=Slot.parse(queuedSlotName);
                if(overlaps(b.slot(),queuedSlot)) releasedQueueKeys.add(qk);
            }
        }
        for(String qk:releasedQueueKeys) promoteNext(qk,now);
    }

    private void promoteNext(String queueKey, LocalDateTime now) {
        Deque<String> q=queues.get(queueKey);
        while(q!=null && !q.isEmpty()) {
            String enquiryId=q.removeFirst(); Enquiry e=enquiries.get(enquiryId);
            if(e==null) continue;
            boolean free=true;
            for(String k:atomicKeys(e.venueId(),e.date(),e.slot())) if(activeHolds.containsKey(k)||confirmedSlots.contains(k)) free=false;
            if(!free) { q.addFirst(enquiryId); return; }
            grantHold(enquiryId,now); return;
        }
    }

    private void grantHold(String enquiryId, LocalDateTime createdAt) {
        Enquiry e=enquiries.get(enquiryId);
        String bookingId="HOLD-"+enquiryId;
        Booking b=new Booking(bookingId,e.client(),e.venueId(),e.date(),e.slot(),e.guests());
        e.selectedPackages().forEach(b::selectPackage);
        b.status(Booking.Status.HELD); b.holdCreatedAt(createdAt); b.holdExpiresAt(createdAt.plusHours(72)); bookings.put(bookingId,b);
        bookingEnquiryIds.put(bookingId,enquiryId); enquiryStatuses.put(enquiryId,"HELD");
        for(String k:atomicKeys(e.venueId(),e.date(),e.slot())) activeHolds.put(k,bookingId);
    }

    public synchronized Booking confirm(String bookingId,String enquiryId,String venueId,LocalDate date,Slot slot,int guests,Map<String,String> selected,LocalDateTime now) {
        return mutate(()->confirmInternal(bookingId,enquiryId,venueId,date,slot,guests,selected,now));
    }

    private Booking confirmInternal(String bookingId,String enquiryId,String venueId,LocalDate date,Slot slot,int guests,Map<String,String> selected,LocalDateTime now) {
        expireHoldsInternal(now); Venue venue=requireVenue(venueId);
        if(guests>venue.capacity()) throw new IllegalArgumentException("Guests exceed venue capacity");
        if(guests<1) throw new IllegalArgumentException("Guests must be positive");
        Enquiry enquiry=enquiries.get(enquiryId);
        if(enquiry==null) throw new IllegalArgumentException("Unknown enquiry: "+enquiryId);
        if(!enquiry.venueId().equals(venueId)||!enquiry.date().equals(date)||enquiry.slot()!=slot||enquiry.guests()!=guests)
            throw new IllegalArgumentException("Confirmation details do not match enquiry "+enquiryId);
        Booking enquiryHold=bookings.get("HOLD-"+enquiryId);
        if(enquiryHold==null||enquiryHold.status()!=Booking.Status.HELD||enquiryHold.holdExpiresAt()==null||!enquiryHold.holdExpiresAt().isAfter(now))
            throw new IllegalStateException("Enquiry does not have an active hold: "+enquiryId);
        for(Slot atomic: (slot==Slot.DAY_AND_NIGHT?List.of(Slot.DAY,Slot.NIGHT):List.of(slot)))
            if(!venue.slots().contains(atomic)) throw new IllegalArgumentException("Venue "+venue.id()+" does not support slot "+atomic);
        if(bookings.containsKey(bookingId)) throw new IllegalArgumentException("Duplicate booking id: "+bookingId);
        List<String> keys=atomicKeys(venueId,date,slot);
        for(String k:keys) {
            String active=activeHolds.get(k);
            if(!enquiryHold.id().equals(active)) throw new IllegalStateException("Enquiry does not hold every requested slot");
            if(confirmedSlots.contains(k)) throw new IllegalStateException("Slot already confirmed");
        }
        long cateringCount=selected.keySet().stream().map(vendors::get).filter(Objects::nonNull)
                .filter(v->v.category()==Vendor.Category.CATERING).count();
        if(cateringCount>1) throw new IllegalArgumentException("Choose only one catering package");
        Booking b=new Booking(bookingId,enquiry.client(),venueId,date,slot,guests);
        for(var e:selected.entrySet()) {
            Vendor vendor=vendors.get(e.getKey()); if(vendor==null) throw new IllegalArgumentException("Unknown vendor "+e.getKey());
            vendor.packagePrice(e.getValue()); b.selectPackage(e.getKey(),e.getValue());
        }
        b.status(Booking.Status.CONFIRMED); bookings.put(bookingId,b); bookingEnquiryIds.put(bookingId,enquiryId);
        for(String k:keys) { String hold=activeHolds.remove(k); if(hold!=null) {Booking held=bookings.get(hold);if(held!=null)held.status(Booking.Status.CONVERTED);} confirmedSlots.add(k); confirmedSlotOwners.put(k,bookingId); }
        enquiryStatuses.put(enquiryId,"CONFIRMED");
        // Queue remains until first advance is paid, as specified in BR-01.
        return b;
    }

    public synchronized Booking confirmAndPay(String bookingId,String enquiryId,String venueId,LocalDate date,Slot slot,int guests,Map<String,String> selected,BigDecimal firstAdvance,LocalDateTime now) {
        expireHolds(now);
        return mutate(()->{
            LocalDateTime clockNow=LocalDateTime.now(clock);
            LocalDateTime effectiveNow=now==null||clockNow.isAfter(now)?clockNow:now;
            Booking b=confirmInternal(bookingId,enquiryId,venueId,date,slot,guests,selected,effectiveNow);
            BigDecimal required=new AdvanceService().schedule(contractBalance(b),effectiveNow.toLocalDate(),b.eventDate()).get(0).amount();
            if(firstAdvance==null||Money.round(firstAdvance).compareTo(required)<0)throw new IllegalArgumentException("First advance must be at least "+required.toPlainString());
            payInternal(bookingId,firstAdvance);
            return b;
        });
    }

    public synchronized BigDecimal quoteVenue(Booking b) { return pricing.venueRental(requireVenue(b.venueId()),b.eventDate(),b.slot()); }
    public synchronized BigDecimal quoteCatering(Booking b) {
        for(var e:b.selectedPackages().entrySet()) { Vendor v=vendors.get(e.getKey()); if(v.category()==Vendor.Category.CATERING)
            return pricing.catering(b.quotedGuests(),b.actualsRecorded()?b.actualGuests():b.quotedGuests(),v.packagePrice(e.getValue())); }
        return BigDecimal.ZERO;
    }
    public synchronized void pay(String bookingId,BigDecimal amount) {
        mutate(()->{payInternal(bookingId,amount);return null;});
    }

    private void payInternal(String bookingId,BigDecimal amount) {
        Booking b=requireBooking(bookingId);
        if(b.status()!=Booking.Status.CONFIRMED) throw new IllegalStateException("Only confirmed bookings can be paid");
        if(amount==null) throw new IllegalArgumentException("Payment is required");
        amount=Money.round(amount);
        if(amount.signum()<=0) throw new IllegalArgumentException("Payment must be positive after rounding to cents");
        if(amount.compareTo(contractBalance(b))>0) throw new IllegalArgumentException("Payment exceeds the outstanding contract balance");
        b.addPayment(amount); paymentsByBooking.merge(bookingId,amount,BigDecimal::add);
        if(amount.signum()>0) {
            for(String k:atomicKeys(b.venueId(),b.eventDate(),b.slot())) paidConfirmedSlots.add(k);
            String prefix=b.venueId()+"|"+b.eventDate()+"|";
            for(var entry:queues.entrySet()) if(entry.getKey().startsWith(prefix)) {
                String slotName=entry.getKey().substring(prefix.length());
                Slot queuedSlot=Slot.parse(slotName);
                if(overlaps(b.slot(),queuedSlot)) {
                    for(String enquiryId:entry.getValue()) enquiryStatuses.put(enquiryId,"CANCELLED");
                    entry.getValue().clear();
                }
            }
        }
    }
    public synchronized void cancel(String bookingId) { cancel(bookingId, LocalDateTime.now(clock)); }
    public synchronized void cancel(String bookingId, LocalDateTime now) {
        mutate(()->{cancelInternal(bookingId,now);return null;});
    }

    private void cancelInternal(String bookingId,LocalDateTime now) {
        Booking b=requireBooking(bookingId);
        if(b.status()!=Booking.Status.HELD&&b.status()!=Booking.Status.CONFIRMED) throw new IllegalStateException("Cannot cancel booking in status "+b.status());
        if(now==null) throw new IllegalArgumentException("Cancellation timestamp is required");
        b.status(Booking.Status.CANCELLED);
        String enquiryId=bookingEnquiryIds.get(bookingId);
        if(enquiryId!=null) enquiryStatuses.put(enquiryId,"CANCELLED");
        for(String k:atomicKeys(b.venueId(),b.eventDate(),b.slot())) activeHolds.remove(k,b.id());
        String datePrefix=b.venueId()+"|"+b.eventDate()+"|";
        for(String k:atomicKeys(b.venueId(),b.eventDate(),b.slot())) { confirmedSlots.remove(k); confirmedSlotOwners.remove(k); paidConfirmedSlots.remove(k); }
        for(String qk:new ArrayList<>(queues.keySet())) if(qk.startsWith(datePrefix)) {
            Slot queuedSlot=Slot.parse(qk.substring(datePrefix.length()));
            if(overlaps(b.slot(),queuedSlot)) promoteNext(qk,now);
        }
    }
    public synchronized BigDecimal swapPackage(String bookingId,String vendorId,String newPackage,LocalDate requestDate) {
        return mutate(()->swapPackageInternal(bookingId,vendorId,newPackage,requestDate));
    }

    private BigDecimal swapPackageInternal(String bookingId,String vendorId,String newPackage,LocalDate requestDate) {
        Booking b=requireBooking(bookingId);
        if(b.status()!=Booking.Status.CONFIRMED) throw new IllegalStateException("Package swaps require a confirmed booking");
        Vendor v=vendors.get(vendorId); if(v==null)throw new IllegalArgumentException("Unknown vendor "+vendorId);
        BigDecimal newPrice=v.packagePrice(newPackage); String old=b.selectedPackages().get(vendorId);
        if(old==null)throw new IllegalArgumentException("Vendor not selected: "+vendorId);
        BigDecimal oldPrice=v.packagePrice(old); BigDecimal fee=BigDecimal.ZERO;
        long days=ChronoUnit.DAYS.between(requestDate,b.eventDate());
        if(days<0) throw new IllegalStateException("Cannot swap a package after the event date");
        if(days<30) fee=Money.pct(oldPrice.max(newPrice),"10");
        b.swapPackage(vendorId,newPackage); b.addSwitchFee(fee); return Money.round(fee);
    }
    public boolean eligibleFreeDateChange(LocalDate requestDate,LocalDate newDate,LocalDate originalDate) {
        long before=ChronoUnit.DAYS.between(requestDate,originalDate), destination=ChronoUnit.DAYS.between(requestDate,newDate);
        return before>90&&destination>=0&&destination<=180;
    }
    public boolean eligibleFreeDateChange(Booking booking,LocalDate requestDate,LocalDate newDate) {
        return !booking.freeDateChangeUsed() && eligibleFreeDateChange(requestDate,newDate,booking.eventDate());
    }
    public synchronized void dateChange(String bookingId,LocalDate requestDate,LocalDate newDate,Slot newSlot) {
        mutate(()->{dateChangeInternal(bookingId,requestDate,newDate,newSlot);return null;});
    }

    private void dateChangeInternal(String bookingId,LocalDate requestDate,LocalDate newDate,Slot newSlot) {
        Booking b=requireBooking(bookingId);
        if(b.status()!=Booking.Status.CONFIRMED) throw new IllegalStateException("Date changes require a confirmed booking");
        if(!eligibleFreeDateChange(b,requestDate,newDate)) { cancelInternal(bookingId,requestDate.atStartOfDay()); return; }
        Venue venue=requireVenue(b.venueId());
        for(Slot atomic: (newSlot==Slot.DAY_AND_NIGHT?List.of(Slot.DAY,Slot.NIGHT):List.of(newSlot)))
            if(!venue.slots().contains(atomic)) throw new IllegalArgumentException("Venue "+venue.id()+" does not support slot "+atomic);
        List<String> newKeys=atomicKeys(b.venueId(),newDate,newSlot);
        Set<String> currentKeys=new HashSet<>(atomicKeys(b.venueId(),b.eventDate(),b.slot()));
        for(String k:newKeys) if((confirmedSlots.contains(k)&&!currentKeys.contains(k))||activeHolds.containsKey(k))throw new IllegalStateException("New date/slot unavailable");
        for(String k:atomicKeys(b.venueId(),b.eventDate(),b.slot())) { confirmedSlots.remove(k); confirmedSlotOwners.remove(k); }
        b.changeDate(newDate,newSlot); b.markFreeDateChangeUsed(); for(String k:newKeys) { confirmedSlots.add(k); confirmedSlotOwners.put(k,b.id()); }
    }
    public synchronized Map<String,Booking> bookings(){refreshFromStore();return Collections.unmodifiableMap(new LinkedHashMap<>(bookings));}
    public synchronized Booking updateBooking(String bookingId,Consumer<Booking> update) {
        Objects.requireNonNull(update,"Booking update is required");
        return mutate(()->{Booking booking=requireBooking(bookingId);update.accept(booking);return booking;});
    }
    public synchronized BigDecimal applyDateChangeCredit(String sourceBookingId,String replacementBookingId) {
        return mutate(()->{
            Booking source=requireBooking(sourceBookingId),replacement=requireBooking(replacementBookingId);
            if(source.status()!=Booking.Status.CANCELLED)throw new IllegalStateException("Credit source booking must be cancelled");
            if(replacement.status()!=Booking.Status.CONFIRMED)throw new IllegalStateException("Replacement booking must be confirmed");
            if(replacement.advancesPaid().signum()>0)throw new IllegalStateException("Apply date-change credit before recording payments on the replacement booking");
            BigDecimal available=source.dateChangeCredit().subtract(source.dateChangeCreditRedeemed()).max(BigDecimal.ZERO);
            BigDecimal amount=available.min(contractBalance(replacement));
            if(amount.signum()<=0)throw new IllegalStateException("No usable date-change credit or replacement balance is available");
            source.consumeDateChangeCredit(amount);replacement.applyDateChangeCredit(amount);return Money.round(amount);
        });
    }
    public synchronized Map<String,Enquiry> enquiries(){refreshFromStore();return Collections.unmodifiableMap(new LinkedHashMap<>(enquiries));}
    public synchronized Map<String,String> enquiryStatuses(){refreshFromStore();return Collections.unmodifiableMap(new LinkedHashMap<>(enquiryStatuses));}
    public synchronized Map<String,LocalDateTime> enquiryCreatedAt(){refreshFromStore();return Collections.unmodifiableMap(new LinkedHashMap<>(enquiryCreatedAt));}
    public synchronized String bookingIdForEnquiry(String enquiryId){refreshFromStore();return bookingEnquiryIds.entrySet().stream().filter(e->e.getValue().equals(enquiryId)&&!e.getKey().startsWith("HOLD-")).map(Map.Entry::getKey).findFirst().orElse(null);}
    public synchronized int queuePosition(String enquiryId) {
        refreshFromStore();
        for(Deque<String> queue:queues.values()){int position=1;for(String id:queue){if(id.equals(enquiryId))return position;position++;}}
        return 0;
    }
    public synchronized String availability(String venueId,LocalDate date,Slot slot,LocalDateTime now) {
        expireHolds(now);
        Venue venue=requireVenue(venueId);
        for(Slot atomic:(slot==Slot.DAY_AND_NIGHT?List.of(Slot.DAY,Slot.NIGHT):List.of(slot)))if(!venue.slots().contains(atomic))throw new IllegalArgumentException("Venue "+venue.id()+" does not support slot "+atomic);
        for(String k:atomicKeys(venueId,date,slot))if(confirmedSlots.contains(k))return "BOOKED";
        for(String k:atomicKeys(venueId,date,slot))if(activeHolds.containsKey(k))return "ON_HOLD";
        return "AVAILABLE";
    }
    public synchronized void cancelEnquiry(String enquiryId,LocalDateTime now) {
        mutate(()->{
            expireHoldsInternal(now);
            String status=enquiryStatuses.get(enquiryId);
            if(status==null)throw new IllegalArgumentException("Unknown enquiry: "+enquiryId);
            if("HELD".equals(status)){cancelInternal("HOLD-"+enquiryId,now);return null;}
            if(!"QUEUED".equals(status))throw new IllegalStateException("Cannot cancel enquiry in status "+status);
            for(Deque<String> queue:queues.values())queue.remove(enquiryId);
            enquiryStatuses.put(enquiryId,"CANCELLED");
            return null;
        });
    }
    public synchronized Map<String,Vendor> vendors(){return Collections.unmodifiableMap(new LinkedHashMap<>(vendors));}
    public synchronized Map<String,Deque<String>> queues(){refreshFromStore();Map<String,Deque<String>> copy=new LinkedHashMap<>();queues.forEach((k,v)->copy.put(k,new ArrayDeque<>(v)));return Collections.unmodifiableMap(copy);}

    private void loadState(Connection connection) throws SQLException {
        bookings.clear(); enquiries.clear(); queues.clear(); enquiryCreatedAt.clear(); enquiryStatuses.clear(); bookingEnquiryIds.clear();
        activeHolds.clear(); confirmedSlots.clear(); confirmedSlotOwners.clear(); paidConfirmedSlots.clear(); paymentsByBooking.clear();
        try(Statement statement=connection.createStatement();ResultSet rs=statement.executeQuery("SELECT id,client,venue_id,event_date,slot,guests,created_at,status FROM enquiries ORDER BY created_at,id")) {
            while(rs.next()) {
                Enquiry e=new Enquiry(rs.getString("id"),rs.getString("client"),rs.getString("venue_id"),LocalDate.parse(rs.getString("event_date")),Slot.parse(rs.getString("slot")),rs.getInt("guests"));
                enquiries.put(e.id(),e); enquiryCreatedAt.put(e.id(),LocalDateTime.parse(rs.getString("created_at")));
                enquiryStatuses.put(e.id(),rs.getString("status"));
            }
        }
        Map<String,Map<String,String>> enquiryPackages=new HashMap<>();
        try(Statement statement=connection.createStatement();ResultSet rs=statement.executeQuery("SELECT enquiry_id,vendor_id,package_name FROM enquiry_packages ORDER BY rowid")) {
            while(rs.next())enquiryPackages.computeIfAbsent(rs.getString("enquiry_id"),ignored->new LinkedHashMap<>()).put(rs.getString("vendor_id"),rs.getString("package_name"));
        }
        enquiries.replaceAll((id,e)->new Enquiry(e.id(),e.client(),e.venueId(),e.date(),e.slot(),e.guests(),enquiryPackages.getOrDefault(id,Map.of())));
        try(Statement statement=connection.createStatement();ResultSet rs=statement.executeQuery("SELECT id,venue_id,event_date,slot FROM enquiries WHERE status='QUEUED' ORDER BY venue_id,event_date,slot,queue_position")) {
            while(rs.next())queues.computeIfAbsent(key(rs.getString("venue_id"),LocalDate.parse(rs.getString("event_date")),Slot.parse(rs.getString("slot"))),ignored->new ArrayDeque<>()).addLast(rs.getString("id"));
        }
        try(Statement statement=connection.createStatement();ResultSet rs=statement.executeQuery("SELECT * FROM bookings ORDER BY rowid")) {
            while(rs.next()) {
                Booking b=new Booking(rs.getString("id"),rs.getString("client"),rs.getString("venue_id"),LocalDate.parse(rs.getString("event_date")),Slot.parse(rs.getString("slot")),rs.getInt("guests"));
                b.status(Booking.Status.valueOf(rs.getString("status")));
                b.restorePersistedState(rs.getInt("actual_guests"),rs.getInt("actuals_recorded")!=0,new BigDecimal(rs.getString("advances_paid")),new BigDecimal(rs.getString("damages")),new BigDecimal(rs.getString("switch_fees")),new BigDecimal(rs.getString("date_change_credit")),new BigDecimal(rs.getString("date_change_credit_redeemed")),new BigDecimal(rs.getString("date_change_credit_applied")),rs.getInt("free_date_change_used")!=0);
                String created=rs.getString("hold_created_at"),expiry=rs.getString("hold_expires_at");
                if(created!=null)b.holdCreatedAt(LocalDateTime.parse(created)); if(expiry!=null)b.holdExpiresAt(LocalDateTime.parse(expiry));
                String enquiryId=rs.getString("enquiry_id"); if(enquiryId!=null)bookingEnquiryIds.put(b.id(),enquiryId);
                bookings.put(b.id(),b); paymentsByBooking.put(b.id(),b.advancesPaid());
            }
        }
        try(Statement statement=connection.createStatement();ResultSet rs=statement.executeQuery("SELECT booking_id,vendor_id,package_name FROM booking_packages ORDER BY rowid")) {
            while(rs.next()){Booking b=bookings.get(rs.getString("booking_id"));if(b!=null)b.selectPackage(rs.getString("vendor_id"),rs.getString("package_name"));}
        }
        for(var entry:bookings.entrySet()) {
            Booking b=entry.getValue();
            if(b.status()==Booking.Status.HELD) for(String slotKey:atomicKeys(b.venueId(),b.eventDate(),b.slot()))activeHolds.put(slotKey,b.id());
            boolean reserved=b.status()==Booking.Status.CONFIRMED||b.status()==Booking.Status.COMPLETED||b.status()==Booking.Status.SETTLED;
            if(reserved) for(String slotKey:atomicKeys(b.venueId(),b.eventDate(),b.slot()))confirmedSlots.add(slotKey);
            if(reserved&&b.advancesPaid().signum()>0) for(String slotKey:atomicKeys(b.venueId(),b.eventDate(),b.slot()))paidConfirmedSlots.add(slotKey);
        }
        try(Statement statement=connection.createStatement();ResultSet rs=statement.executeQuery("SELECT venue_id,event_date,slot,booking_id FROM confirmed_slots")) {
            while(rs.next()) {String slotKey=key(rs.getString("venue_id"),LocalDate.parse(rs.getString("event_date")),Slot.parse(rs.getString("slot")));confirmedSlots.add(slotKey);confirmedSlotOwners.put(slotKey,rs.getString("booking_id"));}
        }
    }

    private void saveState(Connection connection) throws SQLException {
        try(Statement statement=connection.createStatement()) {
            statement.executeUpdate("DELETE FROM active_hold_slots"); statement.executeUpdate("DELETE FROM confirmed_slots");
            statement.executeUpdate("DELETE FROM booking_packages"); statement.executeUpdate("DELETE FROM bookings"); statement.executeUpdate("DELETE FROM enquiry_packages"); statement.executeUpdate("DELETE FROM enquiries");
        }
        Map<String,Integer> positions=new HashMap<>();
        queues.forEach((queueKey,queue)->{int index=0;for(String id:queue)positions.put(id,++index);});
        try(PreparedStatement ps=connection.prepareStatement("INSERT INTO enquiries(id,client,venue_id,event_date,slot,guests,created_at,status,queue_position) VALUES(?,?,?,?,?,?,?,?,?)")) {
            for(var entry:enquiries.entrySet()) {
                Enquiry e=entry.getValue(); ps.setString(1,e.id());ps.setString(2,e.client());ps.setString(3,e.venueId());ps.setString(4,e.date().toString());ps.setString(5,e.slot().name());ps.setInt(6,e.guests());ps.setString(7,enquiryCreatedAt.getOrDefault(e.id(),LocalDateTime.now(clock)).toString());ps.setString(8,enquiryStatuses.getOrDefault(e.id(),"QUEUED"));
                Integer position=positions.get(e.id());if(position==null)ps.setNull(9,Types.INTEGER);else ps.setInt(9,position);ps.addBatch();
            }
            ps.executeBatch();
        }
        try(PreparedStatement ps=connection.prepareStatement("INSERT INTO enquiry_packages(enquiry_id,vendor_id,package_name) VALUES(?,?,?)")) {
            for(Enquiry e:enquiries.values())for(var pkg:e.selectedPackages().entrySet()){ps.setString(1,e.id());ps.setString(2,pkg.getKey());ps.setString(3,pkg.getValue());ps.addBatch();}ps.executeBatch();
        }
        try(PreparedStatement ps=connection.prepareStatement("INSERT INTO bookings(id,client,venue_id,event_date,slot,guests,status,actual_guests,actuals_recorded,advances_paid,damages,switch_fees,date_change_credit,date_change_credit_redeemed,date_change_credit_applied,free_date_change_used,hold_created_at,hold_expires_at,enquiry_id) VALUES(?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?)")) {
            for(Booking b:bookings.values()) {
                ps.setString(1,b.id());ps.setString(2,b.client());ps.setString(3,b.venueId());ps.setString(4,b.eventDate().toString());ps.setString(5,b.slot().name());ps.setInt(6,b.quotedGuests());ps.setString(7,b.status().name());ps.setInt(8,b.actualGuests());ps.setInt(9,b.actualsRecorded()?1:0);ps.setString(10,b.advancesPaid().toPlainString());ps.setString(11,b.damages().toPlainString());ps.setString(12,b.switchFees().toPlainString());ps.setString(13,b.dateChangeCredit().toPlainString());ps.setString(14,b.dateChangeCreditRedeemed().toPlainString());ps.setString(15,b.dateChangeCreditApplied().toPlainString());ps.setInt(16,b.freeDateChangeUsed()?1:0);ps.setString(17,b.holdCreatedAt()==null?null:b.holdCreatedAt().toString());ps.setString(18,b.holdExpiresAt()==null?null:b.holdExpiresAt().toString());ps.setString(19,bookingEnquiryIds.get(b.id()));ps.addBatch();
            }
            ps.executeBatch();
        }
        try(PreparedStatement ps=connection.prepareStatement("INSERT INTO booking_packages(booking_id,vendor_id,package_name) VALUES(?,?,?)")) {
            for(Booking b:bookings.values())for(var pkg:b.selectedPackages().entrySet()){ps.setString(1,b.id());ps.setString(2,pkg.getKey());ps.setString(3,pkg.getValue());ps.addBatch();}ps.executeBatch();
        }
        try(PreparedStatement ps=connection.prepareStatement("INSERT INTO active_hold_slots(venue_id,event_date,slot,enquiry_id) VALUES(?,?,?,?)")) {
            for(var entry:activeHolds.entrySet()) {String[] parts=entry.getKey().split("\\|",3);String enquiryId=bookingEnquiryIds.get(entry.getValue());if(enquiryId==null)continue;ps.setString(1,parts[0]);ps.setString(2,parts[1]);ps.setString(3,parts[2]);ps.setString(4,enquiryId);ps.addBatch();}ps.executeBatch();
        }
        try(PreparedStatement ps=connection.prepareStatement("INSERT INTO confirmed_slots(venue_id,event_date,slot,booking_id) VALUES(?,?,?,?)")) {
            for(String slotKey:confirmedSlots) {String[] parts=slotKey.split("\\|",3);ps.setString(1,parts[0]);ps.setString(2,parts[1]);ps.setString(3,parts[2]);ps.setString(4,confirmedSlotOwners.getOrDefault(slotKey,"unknown"));ps.addBatch();}ps.executeBatch();
        }
    }

    private Venue requireVenue(String id){Venue v=venues.get(id);if(v==null)throw new IllegalArgumentException("Unknown venue: "+id);return v;}
    private Booking requireBooking(String id){Booking b=bookings.get(id);if(b==null)throw new IllegalArgumentException("Unknown booking: "+id);return b;}
    private BigDecimal contractBalance(Booking booking) {
        Map<String,BigDecimal> packages=new LinkedHashMap<>();
        for(var e:booking.selectedPackages().entrySet()) {
            Vendor vendor=vendors.get(e.getKey());
            if(vendor==null) throw new IllegalArgumentException("Unknown vendor: "+e.getKey());
            if(vendor.category()!=Vendor.Category.CATERING) packages.put(e.getKey(),vendor.packagePrice(e.getValue()));
        }
        Quote quote=new SettlementService().finalBill(quoteVenue(booking),quoteCatering(booking),packages,
                booking.switchFees(),booking.damages(),booking.advancesPaid(),booking.dateChangeCreditApplied());
        return quote.balance();
    }
}
