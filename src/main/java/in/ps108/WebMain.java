package in.ps108;

import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpServer;
import in.ps108.model.*;
import in.ps108.service.*;

import java.io.IOException;
import java.math.BigDecimal;
import java.net.InetSocketAddress;
import java.net.URLDecoder;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.Clock;
import java.time.DateTimeException;
import java.util.*;
import java.util.concurrent.Executors;
import java.util.concurrent.atomic.AtomicInteger;

/** Local web adapter for PS-108. Booking, hold, and queue state is persisted in SQLite. */
public final class WebMain {
    private final Map<String, Venue> venues;
    private final Map<String, Vendor> vendors;
    private final Map<String, BigDecimal> damageRates;
    private final PricingService pricing;
    private final BookingEngine engine;
    private final Clock clock;
    private final SettlementService settlement = new SettlementService();
    private final AdvanceService advanceService = new AdvanceService();
    private final Map<String, AdvanceLedger> advanceLedgers = new HashMap<>();
    private final CancellationService cancellationService = new CancellationService();
    private final DisputeLedger disputeLedger = new DisputeLedger();
    private final PayoutService payoutService = new PayoutService();
    private final DamageService damageService = new DamageService();
    private final Map<String, BookingEngine.Enquiry> enquiries = new LinkedHashMap<>();
    private final Map<String, LocalDate> confirmationDates = new HashMap<>();
    private final Map<String, LocalDateTime> eventCloseTimes = new HashMap<>();
    private final Map<String, String> enquiryToBooking = new HashMap<>();
    private final Map<String, CancellationRecord> cancellationRecords = new HashMap<>();
    private final AtomicInteger sequence = new AtomicInteger(100);

    private record CancellationRecord(LocalDate cancelDate, String json) {}

    WebMain() throws IOException { this(Path.of("data","ps108-bookings.sqlite"),Clock.systemDefaultZone()); }

    WebMain(Path databasePath,Clock clock) throws IOException {
        this.clock=Objects.requireNonNull(clock);
        Path data = Path.of("data");
        venues = new LinkedHashMap<>(CsvLoader.venues(data.resolve("venues.csv")));
        vendors = new LinkedHashMap<>(CsvLoader.vendors(data.resolve("vendors.csv")));
        damageRates = CsvLoader.damageRates(data.resolve("damage-rate-card.csv"));
        pricing = new PricingService(CsvLoader.auspiciousDates(data.resolve("auspicious-dates.csv")));
        engine = new BookingEngine(venues,vendors,pricing,clock,new SqliteHoldStore(databasePath));
        enquiries.putAll(engine.enquiries());
        int maxId=0;
        for(String id:engine.enquiries().keySet())maxId=Math.max(maxId,numericSuffix(id));
        for(String id:engine.bookings().keySet())maxId=Math.max(maxId,numericSuffix(id));
        sequence.set(Math.max(100,maxId));
        engine.expireHolds(LocalDateTime.now(clock));
    }

    private static int numericSuffix(String id) {
        java.util.regex.Matcher matcher=java.util.regex.Pattern.compile("(\\d+)$").matcher(id);
        return matcher.find()?Integer.parseInt(matcher.group(1)):0;
    }

    public static void main(String[] args) throws Exception {
        int port = args.length > 0 ? Integer.parseInt(args[0]) : 8081;
        Path databasePath=args.length>1?Path.of(args[1]):Path.of("data","ps108-bookings.sqlite");
        WebMain app = new WebMain(databasePath,Clock.systemDefaultZone());
        HttpServer server = HttpServer.create(new InetSocketAddress("127.0.0.1", port), 0);
        server.createContext("/api/", app::api);
        server.createContext("/", app::staticFiles);
        server.setExecutor(Executors.newCachedThreadPool());
        server.start();
        System.out.println("PS-108 website is running at http://localhost:" + port);
        System.out.println("Admin workflow: http://localhost:" + port + "/admin.html");
        System.out.println("Keep this terminal running while using the website. Press Stop to shut it down.");
    }

    private synchronized void api(HttpExchange ex) throws IOException {
        try {
            String path = ex.getRequestURI().getPath();
            String method = ex.getRequestMethod();
            if (path.equals("/api/venues") && method.equals("GET")) { json(ex, 200, venuesJson()); return; }
            if (path.equals("/api/vendors") && method.equals("GET")) { json(ex, 200, vendorsJson()); return; }
            if (path.equals("/api/damage-rates") && method.equals("GET")) { json(ex, 200, damageRatesJson()); return; }
            if (path.equals("/api/availability") && method.equals("GET")) { json(ex,200,availabilityJson(query(ex))); return; }
            if (path.equals("/api/bookings") && method.equals("GET")) { engine.expireHolds(LocalDateTime.now(clock)); json(ex, 200, bookingsJson()); return; }
            if (path.equals("/api/queues") && method.equals("GET")) { engine.expireHolds(LocalDateTime.now(clock)); json(ex, 200, queuesJson()); return; }
            if (path.equals("/api/enquiries") && method.equals("GET")) { engine.expireHolds(LocalDateTime.now(clock)); json(ex,200,enquiriesJson()); return; }
            if (path.startsWith("/api/enquiries/") && method.equals("GET")) { json(ex,200,enquiryStatusJson(URLDecoder.decode(path.substring("/api/enquiries/".length()),StandardCharsets.UTF_8))); return; }
            if (path.equals("/api/enquiries/cancel") && method.equals("POST")) { json(ex,200,cancelEnquiry(form(ex))); return; }
            if (path.equals("/api/quote") && method.equals("POST")) { json(ex, 200, quoteJson(form(ex))); return; }
            if (path.equals("/api/bookings") && method.equals("POST")) { json(ex, 201, createEnquiry(form(ex))); return; }
            if (path.equals("/api/venues") && method.equals("POST")) { json(ex, 201, registerVenue(form(ex))); return; }
            if (path.equals("/api/vendors") && method.equals("POST")) { json(ex, 201, registerVendor(form(ex))); return; }
            if (path.equals("/api/enquiries") && method.equals("POST")) { json(ex, 201, createEnquiry(form(ex))); return; }
            if (path.equals("/api/confirm") && method.equals("POST")) { json(ex, 200, confirmEnquiry(form(ex))); return; }
            if (path.equals("/api/pay") && method.equals("POST")) { json(ex, 200, pay(form(ex))); return; }
            if (path.equals("/api/advance-schedule") && method.equals("POST")) { json(ex, 200, scheduleJson(form(ex))); return; }
            if (path.equals("/api/cancel") && method.equals("POST")) { json(ex, 200, cancel(form(ex))); return; }
            if (path.equals("/api/swap") && method.equals("POST")) { json(ex, 200, swap(form(ex))); return; }
            if (path.equals("/api/date-change") && method.equals("POST")) { json(ex, 200, dateChange(form(ex))); return; }
            if (path.equals("/api/apply-credit") && method.equals("POST")) { json(ex, 200, applyCredit(form(ex))); return; }
            if (path.equals("/api/actuals") && method.equals("POST")) { json(ex, 200, recordActuals(form(ex))); return; }
            if (path.equals("/api/settle") && method.equals("POST")) { json(ex, 200, settle(form(ex))); return; }
            if (path.equals("/api/disputes") && method.equals("GET")) { json(ex, 200, disputesJson()); return; }
            if (path.equals("/api/disputes") && method.equals("POST")) { json(ex, 201, fileDispute(form(ex))); return; }
            if (path.equals("/api/disputes/resolve") && method.equals("POST")) { json(ex, 200, resolveDispute(form(ex))); return; }
            if (path.equals("/api/payouts") && method.equals("POST")) { json(ex, 200, payouts(form(ex))); return; }
            json(ex, 404, "{\"error\":\"API route not found\"}");
        } catch (IllegalArgumentException | IllegalStateException | DateTimeException e) {
            json(ex, 400, "{\"error\":" + q(e.getMessage() == null ? "Invalid request" : e.getMessage()) + "}");
        } catch (Exception e) {
            e.printStackTrace();
            json(ex, 500, "{\"error\":\"Server error. Check the IntelliJ Run console.\"}");
        }
    }

    private String venuesJson() {
        StringJoiner out = new StringJoiner(",", "[", "]");
        for (Venue v : venues.values()) {
            StringJoiner slots = new StringJoiner(",", "[", "]");
            v.slots().stream().map(Enum::name).sorted().forEach(s -> slots.add(q(s)));
            out.add("{\"id\":"+q(v.id())+",\"name\":"+q(v.name())+",\"capacity\":"+v.capacity()+",\"weekdayBase\":"+v.weekdayBase().toPlainString()+",\"slots\":"+slots+"}");
        }
        return out.toString();
    }

    private String vendorsJson() {
        StringJoiner out = new StringJoiner(",", "[", "]");
        for (Vendor v : vendors.values()) {
            StringJoiner packages = new StringJoiner(",", "{", "}");
            v.packages().entrySet().stream().sorted(Map.Entry.comparingByKey()).forEach(p -> packages.add(q(p.getKey())+":"+p.getValue().toPlainString()));
            out.add("{\"id\":"+q(v.id())+",\"name\":"+q(v.name())+",\"category\":"+q(v.category().name())+",\"commissionRate\":"+v.commissionRate().toPlainString()+",\"packages\":"+packages+"}");
        }
        return out.toString();
    }

    private String damageRatesJson() {
        StringJoiner out=new StringJoiner(",","[","]");
        damageRates.forEach((code,rate)->out.add("{\"code\":"+q(code)+",\"rate\":"+rate.toPlainString()+"}"));
        return out.toString();
    }

    String bookingsJson() {
        StringJoiner out = new StringJoiner(",", "[", "]");
        for (Booking b : engine.bookings().values()) {
            StringJoiner packages = new StringJoiner(",", "[", "]");
            b.selectedPackages().forEach((vendorId, pkg) -> packages.add("{\"vendorId\":"+q(vendorId)+",\"vendorName\":"+q(vendors.get(vendorId).name())+",\"package\":"+q(pkg)+"}"));
            String created = b.holdCreatedAt() == null ? "null" : q(b.holdCreatedAt().toString());
            String expiry = b.holdExpiresAt() == null ? "null" : q(b.holdExpiresAt().toString());
            String venuePricing=venuePricingJson(requireVenue(b.venueId()),b.eventDate(),b.slot());
            out.add("{\"id\":"+q(b.id())+",\"client\":"+q(b.client())+",\"venueId\":"+q(b.venueId())+",\"venueName\":"+q(venues.get(b.venueId()).name())+",\"eventDate\":"+q(b.eventDate().toString())+",\"slot\":"+q(b.slot().name())+",\"guests\":"+b.quotedGuests()+",\"actualGuests\":"+b.actualGuests()+",\"actualsRecorded\":"+b.actualsRecorded()+",\"advancesPaid\":"+b.advancesPaid().toPlainString()+",\"status\":"+q(b.status().name())+",\"holdCreatedAt\":"+created+",\"holdExpiresAt\":"+expiry+",\"venuePricing\":"+venuePricing+",\"switchFees\":"+b.switchFees().toPlainString()+",\"damages\":"+b.damages().toPlainString()+",\"dateChangeCredit\":"+b.dateChangeCredit().toPlainString()+",\"dateChangeCreditRedeemed\":"+b.dateChangeCreditRedeemed().toPlainString()+",\"dateChangeCreditApplied\":"+b.dateChangeCreditApplied().toPlainString()+",\"packages\":"+packages+"}");
        }
        return out.toString();
    }

    private String queuesJson() {
        StringJoiner out = new StringJoiner(",", "[", "]");
        engine.queues().forEach((key, queue) -> {
            StringJoiner ids = new StringJoiner(",", "[", "]");
            StringJoiner entries = new StringJoiner(",", "[", "]"); int[] position={0};
            queue.forEach(id -> {ids.add(q(id));entries.add("{\"enquiryId\":"+q(id)+",\"position\":"+(++position[0])+",\"status\":\"QUEUED\"}");});
            out.add("{\"key\":"+q(key)+",\"enquiries\":"+ids+",\"entries\":"+entries+"}");
        });
        return out.toString();
    }

    private String enquiriesJson() {
        StringJoiner out=new StringJoiner(",","[","]");
        Map<String,BookingEngine.Enquiry> all=engine.enquiries();Map<String,String> statuses=engine.enquiryStatuses();Map<String,Booking> bookings=engine.bookings();
        for(BookingEngine.Enquiry e:all.values())out.add(enquiryJson(e,statuses.get(e.id()),bookings.get("HOLD-"+e.id())));
        return out.toString();
    }

    private String enquiryStatusJson(String enquiryId) {
        engine.expireHolds(LocalDateTime.now(clock));
        BookingEngine.Enquiry e=engine.enquiries().get(enquiryId);if(e==null)throw new IllegalArgumentException("Unknown enquiry: "+enquiryId);
        Booking hold=engine.bookings().get("HOLD-"+enquiryId);String status=engine.enquiryStatuses().get(enquiryId);
        String view=enquiryJson(e,status,hold);
        String slotStatus=engine.availability(e.venueId(),e.date(),e.slot(),LocalDateTime.now(clock));
        String bookingId=engine.bookingIdForEnquiry(enquiryId);
        return view.substring(0,view.length()-1)+",\"slotStatus\":"+q(slotStatus)+",\"bookingId\":"+q(bookingId)+"}";
    }

    private String enquiryJson(BookingEngine.Enquiry e,String status,Booking hold) {
        int position="QUEUED".equals(status)?engine.queuePosition(e.id()):0;
        BigDecimal requiredAdvance="HELD".equals(status)&&hold!=null?firstAdvanceDue(hold,LocalDateTime.now(clock).toLocalDate()):BigDecimal.ZERO;
        return "{\"enquiryId\":"+q(e.id())+",\"client\":"+q(e.client())+",\"venueId\":"+q(e.venueId())+",\"venueName\":"+q(venues.get(e.venueId()).name())+",\"date\":"+q(e.date().toString())+",\"slot\":"+q(e.slot().name())+",\"guests\":"+e.guests()+",\"createdAt\":"+q(engine.enquiryCreatedAt().get(e.id()).toString())+",\"status\":"+q(status)+",\"queuePosition\":"+position+",\"holdCreatedAt\":"+(hold==null||hold.holdCreatedAt()==null?"null":q(hold.holdCreatedAt().toString()))+",\"holdExpiresAt\":"+(hold==null||hold.holdExpiresAt()==null?"null":q(hold.holdExpiresAt().toString()))+",\"requiredFirstAdvance\":"+requiredAdvance.toPlainString()+"}";
    }

    private String availabilityJson(Map<String,String> params) {
        String venueId=required(params,"venueId");LocalDate date=LocalDate.parse(required(params,"date"));Slot slot=Slot.parse(required(params,"slot"));
        String status=engine.availability(venueId,date,slot,LocalDateTime.now(clock));
        return "{\"venueId\":"+q(venueId)+",\"date\":"+q(date.toString())+",\"slot\":"+q(slot.name())+",\"status\":"+q(status)+"}";
    }

    private String cancelEnquiry(Map<String,String> form) {
        String enquiryId=required(form,"enquiryId");engine.cancelEnquiry(enquiryId,LocalDateTime.now(clock));
        return enquiryStatusJson(enquiryId);
    }

    String quoteJson(Map<String,String> f) {
        Venue venue = requireVenue(required(f,"venueId"));
        LocalDate date = LocalDate.parse(required(f,"date"));
        Slot slot = Slot.parse(required(f,"slot"));
        validateVenueSlot(venue,slot);
        int guests = Integer.parseInt(required(f,"guests"));
        if (guests < 1 || guests > venue.capacity()) throw new IllegalArgumentException("Guest count must be between 1 and the venue capacity ("+venue.capacity()+")");
        Map<String,String> selected = selectedPackages(f); validatePackages(selected);
        PricingService.VenueRentalBreakdown venuePricing=pricing.venueRentalBreakdown(venue,date,slot);
        BigDecimal venueCost = venuePricing.total();
        Map<String,BigDecimal> packageLines = new LinkedHashMap<>();
        BigDecimal catering = BigDecimal.ZERO;
        for (var entry : selected.entrySet()) {
            Vendor v = vendors.get(entry.getKey()); BigDecimal price = v.packagePrice(entry.getValue());
            if (v.category() == Vendor.Category.CATERING) catering = pricing.catering(guests, guests, price);
            else packageLines.put(v.id(), price);
        }
        Quote quote = settlement.finalBill(venueCost, catering, packageLines, BigDecimal.ZERO, BigDecimal.ZERO, BigDecimal.ZERO);
        return quoteToJson(quote, venue.id(), venue.name(), date, slot, guests, venuePricing);
    }

    private String quoteToJson(Quote quote, String venueId, String venueName, LocalDate date, Slot slot, int guests, PricingService.VenueRentalBreakdown venuePricing) {
        StringJoiner lines = new StringJoiner(",", "[", "]");
        lines.add(line("Venue rental", quote.venue()));
        if (quote.catering().signum()>0) lines.add(line("Catering", quote.catering()));
        for (var e : quote.packageLines().entrySet()) lines.add(line(vendors.get(e.getKey()).name()+" package", e.getValue()));
        if (quote.switchFees().signum()>0) lines.add(line("Package switch fees", quote.switchFees()));
        if (quote.damages().signum()>0) lines.add(line("Damages", quote.damages()));
        lines.add(line("GST (18% venue + packages)", quote.venueAndPackageTax()));
        lines.add(line("GST (5% catering)", quote.cateringTax()));
        if (quote.totalBeforeAdvances().subtract(quote.balance()).signum()>0) lines.add(line("Advances paid", quote.totalBeforeAdvances().subtract(quote.balance()).negate()));
        lines.add(line("Balance due", quote.balance()));
        return "{\"venueId\":"+q(venueId)+",\"venueName\":"+q(venueName)+",\"date\":"+q(date.toString())+",\"slot\":"+q(slot.name())+",\"guests\":"+guests+",\"seasonMultiplier\":"+venuePricing.multiplier().toPlainString()+",\"venuePricing\":"+venuePricingJson(venuePricing)+",\"lines\":"+lines+",\"total\":"+quote.totalBeforeAdvances().toPlainString()+",\"balanceDue\":"+quote.balance().toPlainString()+"}";
    }

    private String venuePricingJson(Venue venue,LocalDate date,Slot slot) {
        return venuePricingJson(pricing.venueRentalBreakdown(venue,date,slot));
    }

    private String venuePricingJson(PricingService.VenueRentalBreakdown breakdown) {
        StringJoiner slots=new StringJoiner(",","[","]");
        for(PricingService.SlotRental line:breakdown.slotRentals())slots.add("{\"slot\":"+q(line.slot().name())+",\"undiscountedAmount\":"+line.undiscountedAmount().toPlainString()+",\"discount\":"+line.discount().toPlainString()+",\"amount\":"+line.amount().toPlainString()+"}");
        return "{\"baseWeekdayRental\":"+breakdown.weekdayBaseRental().toPlainString()+",\"dateCategory\":"+q(breakdown.dateCategory())+",\"multiplier\":"+breakdown.multiplier().toPlainString()+",\"slotRentals\":"+slots+",\"secondSlotDiscount\":"+breakdown.secondSlotDiscount().toPlainString()+",\"finalVenueRental\":"+breakdown.total().toPlainString()+"}";
    }

    String createEnquiry(Map<String,String> f) {
        String id = "WEB-E"+sequence.incrementAndGet();
        Map<String,String> selected=selectedPackages(f);validatePackages(selected);
        BookingEngine.Enquiry e = new BookingEngine.Enquiry(id, required(f,"client"), required(f,"venueId"), LocalDate.parse(required(f,"date")), Slot.parse(required(f,"slot")), Integer.parseInt(required(f,"guests")),selected);
        LocalDateTime now=LocalDateTime.now(clock);
        engine.enquire(e,now); enquiries.put(id,e);
        Booking held = engine.bookings().get("HOLD-"+id);
        boolean queued = held == null;
        int position=queued?engine.queuePosition(id):0;
        BigDecimal firstAdvance=queued?BigDecimal.ZERO:firstAdvanceDue(held,now.toLocalDate());
        return "{\"enquiryId\":"+q(id)+",\"status\":"+q(queued?"QUEUED":"HELD")+",\"queuePosition\":"+position+",\"holdCreatedAt\":"+(held==null?"null":q(held.holdCreatedAt().toString()))+",\"holdExpiresAt\":"+(held==null?"null":q(held.holdExpiresAt().toString()))+",\"requiredFirstAdvance\":"+firstAdvance.toPlainString()+",\"message\":"+q(queued?"Enquiry added to the FIFO queue":"Temporary hold created for 72 hours")+"}";
    }

    String confirmEnquiry(Map<String,String> f) {
        String enquiryId = required(f,"enquiryId"); BookingEngine.Enquiry e = engine.enquiries().get(enquiryId);
        if (e == null) throw new IllegalArgumentException("Unknown enquiry: " + enquiryId);
        String holdId = "HOLD-"+enquiryId; Booking hold = engine.bookings().get(holdId);
        if (hold == null || hold.status()!=Booking.Status.HELD) throw new IllegalStateException("This enquiry does not currently have an active hold. It may be queued or expired.");
        Map<String,String> selected = selectedPackages(f); if(selected.isEmpty())selected=e.selectedPackages(); validatePackages(selected);
        String bookingId = "WEB-B"+sequence.incrementAndGet();
        LocalDateTime now=LocalDateTime.now(clock);BigDecimal amount=new BigDecimal(required(f,"amount"));
        Booking b = engine.confirmAndPay(bookingId,enquiryId,e.venueId(),e.date(),e.slot(),e.guests(),selected,amount,now);
        enquiryToBooking.put(enquiryId,bookingId);confirmationDates.put(bookingId,now.toLocalDate());
        AdvanceLedger ledger=ledgerFor(b);ledger.pay(amount,now.toLocalDate());
        return "{\"bookingId\":"+q(b.id())+",\"status\":"+q(b.status().name())+",\"message\":\"First advance recorded and booking confirmed.\",\"total\":"+bookingQuote(b).totalBeforeAdvances().toPlainString()+",\"amountPaid\":"+b.advancesPaid().toPlainString()+",\"queueCleared\":true}";
    }

    private BigDecimal firstAdvanceDue(Booking booking,LocalDate onDate) {
        return advanceService.schedule(bookingQuote(booking).totalBeforeAdvances(),onDate,booking.eventDate()).get(0).amount();
    }

    String pay(Map<String,String> f) {
        Booking b = requireBooking(required(f,"bookingId")); BigDecimal amount = new BigDecimal(required(f,"amount"));
        amount = Money.round(amount);
        Quote q = bookingQuote(b);
        if (amount.signum()<=0) throw new IllegalArgumentException("Payment amount must be positive");
        if (b.advancesPaid().add(amount).compareTo(q.totalBeforeAdvances())>0) throw new IllegalArgumentException("Payment exceeds the outstanding contract balance");
        if (b.status()!=Booking.Status.CONFIRMED) throw new IllegalStateException("Only confirmed bookings can be paid");
        LocalDate paymentDate = f.containsKey("paymentDate") && !f.get("paymentDate").isBlank() ? LocalDate.parse(f.get("paymentDate")) : LocalDate.now();
        AdvanceLedger ledger = ledgerFor(b);
        if (ledger.status(paymentDate).autoCancel()) {
            CancellationRecord cancellation = cancelAndRecord(b, paymentDate, "Auto-cancelled for overdue advance");
            return "{\"status\":\"AUTO_CANCELLED\",\"message\":\"An advance was at least 21 days overdue. Booking auto-cancelled under the cancellation ladder.\",\"cancellation\":"+cancellation.json()+"}";
        }
        ledger.pay(amount, paymentDate);
        engine.pay(b.id(), amount);
        b=requireBooking(b.id());
        return "{\"message\":\"Simulated payment recorded\",\"bookingId\":"+q(b.id())+",\"paidTotal\":"+b.advancesPaid().toPlainString()+",\"balanceDue\":"+bookingQuote(b).balance().toPlainString()+",\"queueCleared\":"+(b.advancesPaid().signum()>0)+"}";
    }

    private String scheduleJson(Map<String,String> f) {
        Booking b = requireBooking(required(f,"bookingId"));
        AdvanceLedger ledger = ledgerFor(b);
        LocalDate asOf = LocalDate.now();
        AdvanceLedger.Status ledgerStatus = ledger.status(asOf);
        StringJoiner rows = new StringJoiner(",", "[", "]");
        for (AdvanceLedger.Account account : ledger.accounts()) {
            AdvanceService.Tranche t = account.tranche();
            long overdue = java.time.temporal.ChronoUnit.DAYS.between(t.dueDate(), asOf);
            BigDecimal fee = account.outstanding().signum()>0 && overdue > 5 ? advanceService.lateFee(account.outstanding(), t.dueDate(), asOf) : BigDecimal.ZERO;
            String status = account.outstanding().signum()==0 ? "PAID" : overdue>=21 ? "AUTO-CANCEL THRESHOLD" : overdue>5 ? "OVERDUE" : "SCHEDULED";
            rows.add("{\"name\":"+q(t.name())+",\"dueDate\":"+q(t.dueDate().toString())+",\"amount\":"+t.amount().toPlainString()+",\"paid\":"+account.paid().toPlainString()+",\"outstanding\":"+account.outstanding().toPlainString()+",\"overdueDays\":"+Math.max(0,overdue)+",\"lateFeeEstimate\":"+fee.toPlainString()+",\"status\":"+q(status)+"}");
        }
        if (ledgerStatus.autoCancel() && b.status()==Booking.Status.CONFIRMED) {
            CancellationRecord cancellation = cancelAndRecord(b, asOf, "Auto-cancelled for overdue advance");
            return "{\"bookingId\":"+q(b.id())+",\"autoCancelled\":true,\"message\":\"An outstanding advance reached 21 days overdue. Booking auto-cancelled.\",\"tranches\":"+rows+",\"cancellation\":"+cancellation.json()+"}";
        }
        return "{\"bookingId\":"+q(b.id())+",\"autoCancelled\":false,\"contractTotal\":"+bookingQuote(b).totalBeforeAdvances().toPlainString()+",\"paid\":"+b.advancesPaid().toPlainString()+",\"balance\":"+bookingQuote(b).balance().toPlainString()+",\"totalOutstanding\":"+ledgerStatus.totalOutstanding().toPlainString()+",\"lateFeesEstimate\":"+ledgerStatus.lateFees().toPlainString()+",\"tranches\":"+rows+"}";
    }

    String cancel(Map<String,String> f) {
        Booking b = requireBooking(required(f,"bookingId")); LocalDate cancelDate = f.containsKey("cancelDate")&&!f.get("cancelDate").isBlank()?LocalDate.parse(f.get("cancelDate")):LocalDate.now();
        CancellationRecord prior=cancellationRecords.get(b.id());
        if(prior!=null) return prior.json();
        return cancelAndRecord(b,cancelDate,"Cancellation recorded. Any refund shown is eligible/pending and has not been transferred.").json();
    }

    private String swap(Map<String,String> f) {
        Booking b = requireBooking(required(f,"bookingId")); String vendorId = required(f,"vendorId"), pkg = required(f,"packageName");
        LocalDate requestDate = f.containsKey("requestDate")&&!f.get("requestDate").isBlank()?LocalDate.parse(f.get("requestDate")):LocalDate.now();
        BigDecimal fee = engine.swapPackage(b.id(), vendorId, pkg, requestDate);
        b=requireBooking(b.id());
        rebaseLedgerIfCreated(b);
        return "{\"bookingId\":"+q(b.id())+",\"vendor\":"+q(vendors.get(vendorId).name())+",\"newPackage\":"+q(pkg)+",\"switchFee\":"+fee.toPlainString()+",\"newContractTotal\":"+bookingQuote(b).totalBeforeAdvances().toPlainString()+"}";
    }

    private String dateChange(Map<String,String> f) {
        Booking b = requireBooking(required(f,"bookingId")); LocalDate requestDate = LocalDate.parse(required(f,"requestDate"));
        LocalDate newDate = LocalDate.parse(required(f,"newDate")); Slot newSlot = Slot.parse(required(f,"newSlot"));
        boolean free = engine.eligibleFreeDateChange(b, requestDate, newDate);
        if (free) {
            engine.dateChange(b.id(), requestDate, newDate, newSlot);
            b=requireBooking(b.id());
            rebaseLedgerIfCreated(b);
            return "{\"bookingId\":"+q(b.id())+",\"status\":\"CONFIRMED\",\"freeChange\":true,\"newDate\":"+q(newDate.toString())+",\"message\":\"One-time free date change completed.\"}";
        }
        CancellationRecord prior=cancellationRecords.get(b.id());
        if(prior!=null) throw new IllegalStateException("Booking cancellation is already recorded; use its existing result");
        CancellationService.Result result = cancellationFor(b, requestDate);
        BigDecimal credit = result.cappedForfeiture().multiply(new BigDecimal("0.50")).setScale(2, java.math.RoundingMode.HALF_UP);
        engine.dateChange(b.id(), requestDate, newDate, newSlot);
        b=engine.updateBooking(b.id(),booking->booking.addDateChangeCredit(credit));
        CancellationRecord record=storeCancellation(b,requestDate,result,"Cancelled under date-change fallback. Any cash refund is only eligible/pending; no transfer was made.");
        return "{\"bookingId\":"+q(b.id())+",\"status\":\"CANCELLED\",\"freeChange\":false,\"forfeiture\":"+result.cappedForfeiture().toPlainString()+",\"rebookingCredit\":"+credit.toPlainString()+",\"cancellation\":"+record.json()+",\"message\":\"Original booking cancelled under the fallback rule. Create a separate new booking and apply this credit manually.\"}";
    }

    private String applyCredit(Map<String,String> f) {
        String sourceId=required(f,"sourceBookingId"),replacementId=required(f,"replacementBookingId");
        BigDecimal amount=engine.applyDateChangeCredit(sourceId,replacementId);
        Booking source=requireBooking(sourceId),replacement=requireBooking(replacementId);
        rebaseLedgerIfCreated(replacement);
        return "{\"sourceBookingId\":"+q(source.id())+",\"replacementBookingId\":"+q(replacement.id())+",\"creditApplied\":"+amount.toPlainString()+",\"remainingSourceCredit\":"+source.dateChangeCredit().subtract(source.dateChangeCreditRedeemed()).toPlainString()+",\"balanceDue\":"+bookingQuote(replacement).balance().toPlainString()+"}";
    }

    private String recordActuals(Map<String,String> f) {
        Booking b = requireBooking(required(f,"bookingId")); int guests = Integer.parseInt(required(f,"actualGuests"));
        if (b.status()!=Booking.Status.CONFIRMED) throw new IllegalStateException("Actuals require a confirmed booking; current status="+b.status());
        if (LocalDate.now().isBefore(b.eventDate())) throw new IllegalStateException("Post-event actuals can only be recorded on or after the event date");
        DamageService.Charge damage=damageService.calculate(damageService.parse(f.getOrDefault("damageItems","-")),damageRates);
        BigDecimal damages=damage.total();
        b=engine.updateBooking(b.id(),booking->{
            if(booking.status()!=Booking.Status.CONFIRMED)throw new IllegalStateException("Actuals require a confirmed booking; current status="+booking.status());
            booking.actuals(guests,damages);booking.status(Booking.Status.COMPLETED);
        });
        eventCloseTimes.putIfAbsent(b.id(), b.eventDate().atTime(18,0));
        Quote q = bookingQuote(b);
        StringJoiner damageLines=new StringJoiner(",","[","]");
        damage.lineAmounts().entrySet().stream().sorted(Map.Entry.comparingByKey())
                .forEach(e->damageLines.add("{\"code\":"+q(e.getKey())+",\"quantity\":"+damage.quantities().get(e.getKey())+",\"amount\":"+e.getValue().toPlainString()+"}"));
        return "{\"bookingId\":"+q(b.id())+",\"status\":\"COMPLETED\",\"actualGuests\":"+guests+",\"damageLines\":"+damageLines+",\"damages\":"+damages.toPlainString()+",\"finalTotal\":"+q.totalBeforeAdvances().toPlainString()+",\"balanceDue\":"+q.balance().toPlainString()+"}";
    }

    private String settle(Map<String,String> f) {
        Booking b = requireBooking(required(f,"bookingId"));
        if (b.status()!=Booking.Status.COMPLETED || !b.actualsRecorded()) throw new IllegalStateException("Settlement requires a completed event with recorded actuals");
        Quote q = bookingQuote(b); b=engine.updateBooking(b.id(),booking->{
            if(booking.status()!=Booking.Status.COMPLETED||!booking.actualsRecorded())throw new IllegalStateException("Settlement requires a completed event with recorded actuals");
            booking.status(Booking.Status.SETTLED);
        });
        return "{\"bookingId\":"+q(b.id())+",\"status\":\"SETTLED\",\"venue\":"+q.venue().toPlainString()+",\"catering\":"+q.catering().toPlainString()+",\"packages\":"+q.packages().toPlainString()+",\"switchFees\":"+q.switchFees().toPlainString()+",\"damages\":"+q.damages().toPlainString()+",\"gst18\":"+q.venueAndPackageTax().toPlainString()+",\"gst5\":"+q.cateringTax().toPlainString()+",\"total\":"+q.totalBeforeAdvances().toPlainString()+",\"advancesPaid\":"+b.advancesPaid().toPlainString()+",\"dateChangeCreditApplied\":"+q.dateChangeCreditApplied().toPlainString()+",\"balanceDue\":"+q.balance().toPlainString()+"}";
    }

    private String fileDispute(Map<String,String> f) {
        Booking b = requireBooking(required(f,"bookingId")); String vendorId = required(f,"vendorId");
        if (!vendors.containsKey(vendorId) || !b.selectedPackages().containsKey(vendorId)) throw new IllegalArgumentException("Choose a vendor selected on this booking");
        LocalDateTime closed = eventCloseTimes.getOrDefault(b.id(), b.eventDate().atTime(18,0));
        LocalDateTime filed = f.containsKey("filedAt")&&!f.get("filedAt").isBlank()?LocalDateTime.parse(f.get("filedAt")):LocalDateTime.now();
        String id = "DSP-"+sequence.incrementAndGet();
        PayoutService.Dispute d = disputeLedger.file(id, b.id(), vendorId, filed, closed, required(f,"reason"));
        return "{\"id\":"+q(d.id())+",\"bookingId\":"+q(d.bookingId())+",\"vendorId\":"+q(d.vendorId())+",\"within48Hours\":"+d.open()+",\"status\":"+q(d.open()?"OPEN — PAYOUT FROZEN":"OPEN — LATE DISPUTE, DOES NOT FREEZE")+"}";
    }

    private String disputesJson() {
        StringJoiner out = new StringJoiner(",", "[", "]");
        for (PayoutService.Dispute d : disputeLedger.all()) out.add("{\"id\":"+q(d.id())+",\"bookingId\":"+q(d.bookingId())+",\"vendorId\":"+q(d.vendorId())+",\"filedAt\":"+q(d.filedAt().toString())+",\"reason\":"+q(d.reason())+",\"open\":"+d.open()+"}");
        return out.toString();
    }

    private String resolveDispute(Map<String,String> f) {
        String id = required(f,"disputeId"); disputeLedger.resolve(id);
        return "{\"id\":"+q(id)+",\"status\":\"RESOLVED — PAYOUT CAN BE RECHECKED\"}";
    }

    private String payouts(Map<String,String> f) {
        Booking b = requireBooking(required(f,"bookingId"));
        LocalDateTime closed = eventCloseTimes.getOrDefault(b.id(), b.eventDate().atTime(18,0));
        LocalDateTime now = f.containsKey("asOf")&&!f.get("asOf").isBlank()?LocalDateTime.parse(f.get("asOf")):LocalDateTime.now();
        StringJoiner rows = new StringJoiner(",", "[", "]");
        BigDecimal venueGross = pricing.venueRental(requireVenue(b.venueId()), b.eventDate(), b.slot());
        PayoutService.Payout venuePayout = payoutService.calculateVenue(b.venueId(), venueGross, closed, now, disputeLedger.all(), b.id());
        rows.add(payoutJson("Venue", venuePayout));
        for (var e : b.selectedPackages().entrySet()) {
            Vendor v = vendors.get(e.getKey());
            BigDecimal gross = v.category()==Vendor.Category.CATERING ? pricing.catering(b.quotedGuests(), b.actualsRecorded()?b.actualGuests():b.quotedGuests(), v.packagePrice(e.getValue())) : v.packagePrice(e.getValue());
            rows.add(payoutJson(v.name(), payoutService.calculate(v.id(), gross, v, closed, now, disputeLedger.all(), b.id())));
        }
        return "{\"bookingId\":"+q(b.id())+",\"eventClosedAt\":"+q(closed.toString())+",\"payouts\":"+rows+"}";
    }

    private String payoutJson(String name, PayoutService.Payout p) {
        return "{\"payee\":"+q(name)+",\"payeeId\":"+q(p.vendorId())+",\"gross\":"+p.grossLineValue().toPlainString()+",\"commission\":"+p.commission().toPlainString()+",\"net\":"+p.netPayout().toPlainString()+",\"eligibleAt\":"+q(p.eligibleAt().toString())+",\"frozen\":"+p.frozen()+",\"payable\":"+p.payable()+",\"status\":"+q(p.status())+"}";
    }

    private String registerVenue(Map<String,String> f) {
        String id = required(f,"id"); if (venues.containsKey(id)) throw new IllegalArgumentException("Venue ID already exists");
        Set<Slot> slots = new LinkedHashSet<>();
        for (String s : required(f,"slots").split("[,;]")) { Slot slot = Slot.parse(s.trim()); if (slot==Slot.DAY_AND_NIGHT) { slots.add(Slot.DAY); slots.add(Slot.NIGHT); } else slots.add(slot); }
        Venue v = new Venue(id, required(f,"name"), Integer.parseInt(required(f,"capacity")), new BigDecimal(required(f,"weekdayBase")), slots);
        venues.put(id,v); return "{\"message\":\"Venue registered for this server session\",\"id\":"+q(id)+"}";
    }

    private String registerVendor(Map<String,String> f) {
        String id = required(f,"id"); if (vendors.containsKey(id)) throw new IllegalArgumentException("Vendor ID already exists");
        Vendor.Category category = Vendor.Category.valueOf(required(f,"category").toUpperCase(Locale.ROOT));
        Map<String,BigDecimal> packages = new LinkedHashMap<>();
        for (String item : required(f,"packages").split("[,;]")) {
            String[] pair = item.trim().split(":",2); if(pair.length!=2) throw new IllegalArgumentException("Packages must use NAME:PRICE pairs separated by commas");
            String packageName=pair[0].trim().toUpperCase(Locale.ROOT);
            if(packages.putIfAbsent(packageName,new BigDecimal(pair[1].trim()))!=null) throw new IllegalArgumentException("Duplicate package name: "+packageName);
        }
        BigDecimal commission = new BigDecimal(required(f,"commissionPercent")).divide(new BigDecimal("100"), 4, java.math.RoundingMode.HALF_UP);
        Vendor v = new Vendor(id, required(f,"name"), category, packages, commission); vendors.put(id,v);
        return "{\"message\":\"Vendor registered for this server session\",\"id\":"+q(id)+"}";
    }

    private CancellationService.Result cancellationFor(Booking b, LocalDate cancelDate) {
        Quote q = bookingQuote(b); Map<String,BigDecimal> lineValues = new LinkedHashMap<>();
        BigDecimal cateringGuarantee = BigDecimal.ZERO;
        for (var e : b.selectedPackages().entrySet()) if(vendors.get(e.getKey()).category()==Vendor.Category.CATERING) {
            int guarantee = pricing.cateringGuarantee(b.quotedGuests());
            cateringGuarantee = vendors.get(e.getKey()).packagePrice(e.getValue()).multiply(BigDecimal.valueOf(guarantee));
        }
        for (var e : b.selectedPackages().entrySet()) {
            Vendor v = vendors.get(e.getKey());
            BigDecimal lineValue = v.category()==Vendor.Category.CATERING
                    ? pricing.catering(b.quotedGuests(), b.quotedGuests(), v.packagePrice(e.getValue()))
                    : v.packagePrice(e.getValue());
            lineValues.put(e.getKey(), lineValue);
        }
        return cancellationService.calculate(q.totalBeforeAdvances(), b.advancesPaid(), b.eventDate(), cancelDate, lineValues, vendors, b.selectedPackages(), cateringGuarantee);
    }

    private CancellationRecord cancelAndRecord(Booking b, LocalDate cancelDate, String message) {
        CancellationRecord existing=cancellationRecords.get(b.id());
        if(existing!=null) return existing;
        CancellationService.Result result=cancellationFor(b,cancelDate);
        engine.cancel(b.id(),cancelDate.atStartOfDay());
        return storeCancellation(b,cancelDate,result,message);
    }

    private CancellationRecord storeCancellation(Booking b, LocalDate cancelDate, CancellationService.Result result, String message) {
        CancellationRecord existing=cancellationRecords.get(b.id());
        if(existing!=null) return existing;
        CancellationRecord record=new CancellationRecord(cancelDate,cancellationJson(b,cancelDate,result,message));
        cancellationRecords.put(b.id(),record);
        return record;
    }

    private String cancellationJson(Booking b, LocalDate cancelDate, CancellationService.Result r, String message) {
        StringJoiner lines = new StringJoiner(",", "[", "]");
        r.lineBreakdown().forEach((k,v)->lines.add("{\"line\":"+q(k)+",\"forfeiture\":"+v.toPlainString()+"}"));
        StringJoiner vendorOverrides = new StringJoiner(",", "[", "]");
        r.vendorOverrideBreakdown().forEach((id,value)->{
            Vendor vendor=vendors.get(id);
            vendorOverrides.add("{\"vendorId\":"+q(id)+",\"vendorName\":"+q(vendor.name())+",\"category\":"+q(vendor.category().name())+",\"forfeiture\":"+value.toPlainString()+"}");
        });
        BigDecimal contractTotal=bookingQuote(b).totalBeforeAdvances();
        String refundStatus=r.refundAmount().signum()>0?"PENDING_ELIGIBILITY":"NOT_APPLICABLE";
        return "{\"bookingId\":"+q(b.id())+",\"cancelDate\":"+q(cancelDate.toString())+",\"status\":\"CANCELLED\",\"message\":"+q(message)+
                ",\"totalBookingAmount\":"+contractTotal.toPlainString()+",\"amountPaid\":"+b.advancesPaid().toPlainString()+
                ",\"daysRemaining\":"+r.daysRemaining()+
                ",\"cancellationPercentage\":"+r.cancellationPercent().toPlainString()+
                ",\"vendorSpecificForfeiture\":"+r.vendorSpecificForfeiture().toPlainString()+
                ",\"grossForfeiture\":"+r.grossForfeiture().toPlainString()+",\"cap\":"+r.cap().toPlainString()+
                ",\"totalForfeiture\":"+r.cappedForfeiture().toPlainString()+",\"forfeiture\":"+r.cappedForfeiture().toPlainString()+
                ",\"refundAmount\":"+r.refundAmount().toPlainString()+",\"refundEstimate\":"+r.refundAmount().toPlainString()+
                ",\"refundStatus\":"+q(refundStatus)+",\"refundTransferStatus\":\"NOT_PROCESSED\",\"additionalRecoverableAmount\":"+r.additionalRecoverableAmount().toPlainString()+",\"recoverableStatus\":\"NOT_COLLECTED\""+
                ",\"lines\":"+lines+",\"vendorSpecificBreakdown\":"+vendorOverrides+"}";
    }

    private void rebaseLedgerIfCreated(Booking b) {
        AdvanceLedger ledger = advanceLedgers.get(b.id());
        if (ledger != null) {
            LocalDate confirmation = confirmationDates.getOrDefault(b.id(), LocalDate.now());
            ledger.rebase(advanceService.schedule(bookingQuote(b).totalBeforeAdvances().subtract(b.dateChangeCreditApplied()).max(BigDecimal.ZERO), confirmation, b.eventDate()));
        }
    }

    private AdvanceLedger ledgerFor(Booking b) {
        return advanceLedgers.computeIfAbsent(b.id(), id -> {
            LocalDate confirmation = confirmationDates.getOrDefault(id, LocalDate.now());
            return new AdvanceLedger(advanceService.schedule(bookingQuote(b).totalBeforeAdvances().subtract(b.dateChangeCreditApplied()).max(BigDecimal.ZERO), confirmation, b.eventDate()), advanceService);
        });
    }

    private Quote bookingQuote(Booking b) {
        BigDecimal venue = pricing.venueRental(requireVenue(b.venueId()), b.eventDate(), b.slot());
        Map<String,BigDecimal> packageLines = new LinkedHashMap<>(); BigDecimal catering = BigDecimal.ZERO;
        for (var e : b.selectedPackages().entrySet()) {
            Vendor v = vendors.get(e.getKey()); BigDecimal price = v.packagePrice(e.getValue());
            if (v.category()==Vendor.Category.CATERING) catering = pricing.catering(b.quotedGuests(), b.actualsRecorded()?b.actualGuests():b.quotedGuests(), price);
            else packageLines.put(e.getKey(), price);
        }
        return settlement.finalBill(venue,catering,packageLines,b.switchFees(),b.damages(),b.advancesPaid(),b.dateChangeCreditApplied());
    }

    private String createBookingQuoteForForm(Map<String,String> f) { return quoteJson(f); }
    private Map<String,String> selectedPackages(Map<String,String> f) {
        Map<String,String> selected = new LinkedHashMap<>();
        for (Vendor v : vendors.values()) { String packageName = f.get("package_"+v.id()); if (packageName != null && !packageName.isBlank()) selected.put(v.id(), packageName.toUpperCase(Locale.ROOT)); }
        return selected;
    }
    private void validatePackages(Map<String,String> selected) {
        for (var e : selected.entrySet()) { Vendor v=vendors.get(e.getKey()); if(v==null)throw new IllegalArgumentException("Unknown vendor: "+e.getKey()); v.packagePrice(e.getValue()); }
        long cateringCount = selected.keySet().stream().map(vendors::get).filter(v -> v.category()==Vendor.Category.CATERING).count();
        if (cateringCount > 1) throw new IllegalArgumentException("Choose only one catering package");
    }
    private Booking requireBooking(String id) { Booking b=engine.bookings().get(id); if(b==null)throw new IllegalArgumentException("Unknown booking ID: "+id); return b; }
    private Venue requireVenue(String id) { Venue v=venues.get(id); if(v==null)throw new IllegalArgumentException("Unknown venue: "+id); return v; }
    private static void validateVenueSlot(Venue venue,Slot slot) {
        for(Slot atomic:(slot==Slot.DAY_AND_NIGHT?List.of(Slot.DAY,Slot.NIGHT):List.of(slot)))
            if(!venue.slots().contains(atomic)) throw new IllegalArgumentException("Venue "+venue.id()+" does not support slot "+atomic);
    }
    private static String line(String label, BigDecimal value) { return "{\"label\":"+q(label)+",\"amount\":"+value.toPlainString()+"}"; }
    private static String required(Map<String,String> f, String key) { String v=f.get(key); if(v==null||v.isBlank())throw new IllegalArgumentException("Missing required field: "+key); return v; }
    private static Map<String,String> form(HttpExchange ex) throws IOException {
        String body = new String(ex.getRequestBody().readAllBytes(), StandardCharsets.UTF_8); Map<String,String> result = new LinkedHashMap<>();
        for(String pair : body.split("&")) { if(pair.isEmpty())continue; String[] p=pair.split("=",2); result.put(decode(p[0]), p.length>1?decode(p[1]):""); }
        return result;
    }
    private static Map<String,String> query(HttpExchange ex) {
        Map<String,String> result=new LinkedHashMap<>();String raw=ex.getRequestURI().getRawQuery();if(raw==null||raw.isBlank())return result;
        for(String pair:raw.split("&")){if(pair.isEmpty())continue;String[] p=pair.split("=",2);result.put(decode(p[0]),p.length>1?decode(p[1]):"");}
        return result;
    }
    private static String decode(String s) { return URLDecoder.decode(s, StandardCharsets.UTF_8); }
    static String q(String s) {
        if(s==null)return "null";
        StringBuilder escaped=new StringBuilder(s.length()+2).append('"');
        for(int i=0;i<s.length();i++) {
            char c=s.charAt(i);
            switch(c) {
                case '"' -> escaped.append("\\\"");
                case '\\' -> escaped.append("\\\\");
                case '\b' -> escaped.append("\\b");
                case '\f' -> escaped.append("\\f");
                case '\n' -> escaped.append("\\n");
                case '\r' -> escaped.append("\\r");
                case '\t' -> escaped.append("\\t");
                default -> {
                    if(c<0x20) escaped.append(String.format(Locale.ROOT,"\\u%04x",(int)c));
                    else escaped.append(c);
                }
            }
        }
        return escaped.append('"').toString();
    }
    private static void json(HttpExchange ex, int status, String body) throws IOException {
        byte[] bytes=body.getBytes(StandardCharsets.UTF_8); ex.getResponseHeaders().set("Content-Type","application/json; charset=utf-8");
        ex.getResponseHeaders().set("Cache-Control","no-store"); ex.sendResponseHeaders(status,bytes.length); ex.getResponseBody().write(bytes); ex.close();
    }
    private void staticFiles(HttpExchange ex) throws IOException {
        String path=ex.getRequestURI().getPath(); if(path.equals("/"))path="/index.html";
        if(path.contains("..") || path.contains("\\")) { ex.sendResponseHeaders(400,-1);ex.close();return; }
        Path file=Path.of("src","main","resources","static").resolve(path.substring(1)).normalize();
        Path root=Path.of("src","main","resources","static").toAbsolutePath().normalize();
        if(!file.toAbsolutePath().normalize().startsWith(root)||!Files.isRegularFile(file)) { byte[] b="Not found".getBytes(StandardCharsets.UTF_8);ex.sendResponseHeaders(404,b.length);ex.getResponseBody().write(b);ex.close();return; }
        String name=file.getFileName().toString(); String type=name.endsWith(".html")?"text/html; charset=utf-8":name.endsWith(".css")?"text/css; charset=utf-8":name.endsWith(".js")?"application/javascript; charset=utf-8":"application/octet-stream";
        byte[] bytes=Files.readAllBytes(file);ex.getResponseHeaders().set("Content-Type",type);ex.sendResponseHeaders(200,bytes.length);ex.getResponseBody().write(bytes);ex.close();
    }
}
