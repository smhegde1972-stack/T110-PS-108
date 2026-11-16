
package in.ps108;

import in.ps108.model.*;
import in.ps108.service.*;

import java.math.BigDecimal;
import java.nio.file.*;
import java.time.*;
import java.time.format.DateTimeParseException;
import java.util.*;

public class Main {

    private record TimedCommand(int lineNo, String raw, LocalDateTime timestamp) {}

    public static void main(String[] args) throws Exception {

        Path venuesPath = Path.of(args.length > 0 ? args[0] : "data/venues.csv");
        Path vendorsPath = Path.of(args.length > 1 ? args[1] : "data/vendors.csv");
        Path commandsPath = Path.of(args.length > 2 ? args[2] : "data/commands.txt");
        Path auspiciousPath = Path.of(args.length > 3 ? args[3] : "data/auspicious-dates.csv");
        Path damageRatesPath = Path.of(args.length > 4 ? args[4] : "data/damage-rate-card.csv");
        Path packagesPath = Path.of(args.length > 5
                ? args[5]
                : "organizer-input/T110-PS-108-sample-data/vendor_packages.csv");

        Map<String, Venue> venues = CsvLoader.venues(venuesPath);
        Map<String, Vendor> vendors = CsvLoader.vendors(vendorsPath, packagesPath);
        Map<String, BigDecimal> damageRates = CsvLoader.damageRates(damageRatesPath);

        PricingService pricing = new PricingService(CsvLoader.auspiciousDates(auspiciousPath));
        BookingEngine engine = new BookingEngine(venues, vendors, pricing);

        AdvanceService advanceService = new AdvanceService();
        CancellationService cancellationService = new CancellationService();
        SettlementService settlementService = new SettlementService();
        DamageService damageService = new DamageService();
        DisputeLedger disputeLedger = new DisputeLedger();
        PayoutService payoutService = new PayoutService();

        Map<String, AdvanceLedger> advanceLedgers = new LinkedHashMap<>();
        Map<String, LocalDate> confirmationDates = new HashMap<>();
        Set<String> autoCancelled = new HashSet<>();

        List<String> lines = Files.readAllLines(commandsPath);
        List<TimedCommand> commands = new ArrayList<>();

        int lineNo = 0;
        boolean organizerFormat = true;

        for (String raw : lines) {
            lineNo++;
            String line = raw.trim();

            if (line.isEmpty() || line.startsWith("#") || line.contains("arg1|arg2")) {
                continue;
            }

            try {
                String[] p = line.split("\\|", -1);

                if (p.length < 2) {
                    throw new IllegalArgumentException("Invalid command: " + line);
                }

                boolean timestampFirst = isTimestamp(p[0]);

                if (timestampFirst) {
                    organizerFormat = false;
                    String temp = p[0];
                    p[0] = p[1];
                    p[1] = temp;
                }

                LocalDateTime timestamp = parseTimestamp(p[1]);

                commands.add(new TimedCommand(
                        lineNo,
                        String.join("|", p),
                        timestamp
                ));

            } catch (Exception ex) {
                System.err.println("ERROR line " + lineNo + ": " + ex.getMessage());
                throw ex;
            }
        }

        // Legacy command files are timestamp-sorted.
        // Organizer command files are processed in file order.
        if (!organizerFormat) {
            commands.sort(
                    Comparator.comparing(TimedCommand::timestamp)
                            .thenComparingInt(TimedCommand::lineNo)
            );
        }

        for (TimedCommand command : commands) {

            int currentLineNo = command.lineNo();
            String[] p = command.raw().split("\\|", -1);
            LocalDateTime timestamp = command.timestamp();

            try {
                engine.expireHolds(timestamp);

                for (var entry : new ArrayList<>(advanceLedgers.entrySet())) {
                    Booking overdueBooking = engine.bookings().get(entry.getKey());

                    if (overdueBooking != null
                            && overdueBooking.status() == Booking.Status.CONFIRMED
                            && entry.getValue().status(timestamp.toLocalDate()).autoCancel()) {

                        CancellationService.Result cr = cancellationService.calculate(
                                contractValue(overdueBooking, engine),
                                overdueBooking.advancesPaid(),
                                overdueBooking.eventDate(),
                                timestamp.toLocalDate(),
                                cancellationLines(overdueBooking, engine),
                                vendors,
                                overdueBooking.selectedPackages(),
                                cateringGuaranteeValue(overdueBooking, engine)
                        );

                        System.out.println(
                                "AUTO_CANCEL " + overdueBooking.id()
                                        + " reason=ADVANCE_21_DAYS_OVERDUE"
                                        + " forfeiture=" + Money.fmt(cr.cappedForfeiture())
                        );

                        engine.cancel(overdueBooking.id(), timestamp);
                        autoCancelled.add(overdueBooking.id());
                    }
                }

                switch (p[0].toUpperCase(Locale.ROOT)) {

                    case "QUOTE" ->
                            printQuote(p, venues, vendors, pricing, settlementService);

                    case "ENQUIRY", "ENQUIRE" -> {

                        Map<String, String> selected;

                        if (p.length >= 14) {
                            // Organizer format:
                            // ENQUIRY|timestamp|id|customer|venue|date|slot|guests|
                            // cateringPackage|cateringVendor|decorPackage|decorVendor|
                            // photoPackage|photoVendor

                            selected = new LinkedHashMap<>();
                            selected.put(p[9], p[8]);
                            selected.put(p[11], p[10]);
                            selected.put(p[13], p[12]);

                        } else {
                            // Legacy format:
                            // timestamp|ENQUIRE|id|customer|venue|date|slot|guests|packages

                            selected = parsePackages(p.length > 8 ? p[8] : "-");
                        }

                        engine.enquire(
                                new BookingEngine.Enquiry(
                                        p[2],
                                        p[3],
                                        p[4],
                                        LocalDate.parse(p[5]),
                                        Slot.parse(p[6]),
                                        Integer.parseInt(p[7]),
                                        selected
                                ),
                                timestamp
                        );

                        System.out.println(
                                "ENQUIRY " + p[2] + " accepted; hold/queue state updated"
                        );
                    }

                    case "HOLD" ->
                            System.out.println(timestamp + " HOLD request observed for " + p[2]
                                    + " (ENQUIRE creates the temporary hold)");

                    case "CONFIRM" -> {

                        Booking b;

                        if (p.length >= 9) {
                            // Legacy format:
                            // timestamp|CONFIRM|bookingId|enquiryId|venue|date|slot|guests|packages

                            String bookingId = p[2];
                            String enquiryId = p[3];

                            b = engine.confirm(
                                    bookingId,
                                    enquiryId,
                                    p[4],
                                    LocalDate.parse(p[5]),
                                    Slot.parse(p[6]),
                                    Integer.parseInt(p[7]),
                                    parsePackages(p[8]),
                                    timestamp
                            );

                        } else {
                            // Organizer format:
                            // CONFIRM|timestamp|enquiryId

                            String enquiryId = p[2];
                            BookingEngine.Enquiry enquiry =
                                    engine.enquiries().get(enquiryId);

                            if (enquiry == null) {
                                throw new IllegalArgumentException(
                                        "Unknown enquiry: " + enquiryId
                                );
                            }

                            b = engine.confirm(
                                    enquiryId,
                                    enquiryId,
                                    enquiry.venueId(),
                                    enquiry.date(),
                                    enquiry.slot(),
                                    enquiry.guests(),
                                    enquiry.selectedPackages(),
                                    timestamp
                            );
                        }

                        System.out.println(
                                "CONFIRMED " + b.id()
                                        + " | venue=" + Money.fmt(engine.quoteVenue(b))
                        );

                        List<AdvanceService.Tranche> schedule =
                                advanceService.schedule(
                                        paymentContractValue(b, engine),
                                        timestamp.toLocalDate(),
                                        b.eventDate()
                                );

                        advanceLedgers.put(
                                b.id(),
                                new AdvanceLedger(schedule, advanceService)
                        );

                        confirmationDates.put(b.id(), timestamp.toLocalDate());
                        printSchedule(b, schedule);
                    }

                    case "PAY", "PAYMENT" -> {

                        BigDecimal amount = Money.round(new BigDecimal(p[3]));
                        Booking paidBooking = requireBooking(engine, p[2]);

                        if (paidBooking.status() != Booking.Status.CONFIRMED) {
                            throw new IllegalStateException(
                                    "Payments require a confirmed booking; current status="
                                            + paidBooking.status()
                            );
                        }

                        AdvanceLedger ledger = advanceLedgers.get(p[2]);

                        if (ledger != null) {
                            ledger.pay(amount, timestamp.toLocalDate());
                        }

                        engine.pay(p[2], amount);

                        System.out.println(
                                "PAYMENT " + p[2] + " " + Money.fmt(amount)
                        );
                    }

                    case "SCHEDULE" -> {

                        Booking b = requireBooking(engine, p[2]);
                        AdvanceLedger ledger = advanceLedgers.get(p[2]);

                        if (ledger == null) {
                            printSchedule(b, engine, advanceService, LocalDate.parse(p[3]));
                        } else {
                            printSchedule(
                                    b,
                                    ledger.accounts().stream()
                                            .map(AdvanceLedger.Account::tranche)
                                            .toList()
                            );

                            var status = ledger.status(LocalDate.parse(p[3]));

                            System.out.println(
                                    "  outstanding=" + Money.fmt(status.totalOutstanding())
                                            + " lateFees=" + Money.fmt(status.lateFees())
                                            + " autoCancel=" + status.autoCancel()
                            );
                        }
                    }

                    case "APPLY_CREDIT" -> {

                        Booking source = requireBooking(engine, p[2]);
                        Booking replacement = requireBooking(engine, p[3]);

                        if (source.status() != Booking.Status.CANCELLED) {
                            throw new IllegalStateException(
                                    "Credit source booking must be cancelled"
                            );
                        }

                        if (replacement.status() != Booking.Status.CONFIRMED) {
                            throw new IllegalStateException(
                                    "Replacement booking must be confirmed"
                            );
                        }

                        if (replacement.advancesPaid().signum() > 0) {
                            throw new IllegalStateException(
                                    "Apply date-change credit before recording payments on the replacement booking"
                            );
                        }

                        BigDecimal remaining = contractValue(replacement, engine)
                                .subtract(replacement.dateChangeCreditApplied())
                                .max(BigDecimal.ZERO);

                        BigDecimal amount = source.dateChangeCredit()
                                .subtract(source.dateChangeCreditRedeemed())
                                .max(BigDecimal.ZERO)
                                .min(remaining);

                        if (amount.signum() <= 0) {
                            throw new IllegalStateException(
                                    "No usable date-change credit or replacement balance is available"
                            );
                        }

                        source.consumeDateChangeCredit(amount);
                        replacement.applyDateChangeCredit(amount);

                        AdvanceLedger ledger = advanceLedgers.get(replacement.id());

                        if (ledger != null) {
                            ledger.rebase(
                                    advanceService.schedule(
                                            paymentContractValue(replacement, engine),
                                            confirmationDates.get(replacement.id()),
                                            replacement.eventDate()
                                    )
                            );

                            printSchedule(
                                    replacement,
                                    ledger.accounts().stream()
                                            .map(AdvanceLedger.Account::tranche)
                                            .toList()
                            );
                        }

                        System.out.println(
                                "DATE_CHANGE_CREDIT_APPLIED source=" + source.id()
                                        + " replacement=" + replacement.id()
                                        + " amount=" + Money.fmt(amount)
                                        + " remainingSourceCredit="
                                        + Money.fmt(source.dateChangeCredit()
                                        .subtract(source.dateChangeCreditRedeemed()))
                        );
                    }

                    case "CANCEL" -> {

                        Booking b = requireBooking(engine, p[2]);

                        LocalDate cancelDate = p.length > 3 && !p[3].isBlank()
                                ? LocalDate.parse(p[3])
                                : timestamp.toLocalDate();

                        CancellationService.Result result = cancellationService.calculate(
                                contractValue(b, engine),
                                b.advancesPaid(),
                                b.eventDate(),
                                cancelDate,
                                cancellationLines(b, engine),
                                vendors,
                                b.selectedPackages(),
                                cateringGuaranteeValue(b, engine)
                        );

                        System.out.println(
                                "CANCELLATION " + b.id()
                                        + " daysBeforeEvent="
                                        + java.time.temporal.ChronoUnit.DAYS.between(
                                        cancelDate, b.eventDate())
                        );

                        result.lineBreakdown().entrySet().stream()
                                .sorted(Map.Entry.comparingByKey())
                                .forEach(e -> System.out.println(
                                        "  " + e.getKey()
                                                + " forfeiture=" + Money.fmt(e.getValue())
                                ));

                        System.out.println(
                                "  gross=" + Money.fmt(result.grossForfeiture())
                                        + " cap=" + Money.fmt(result.cap())
                                        + " finalForfeiture="
                                        + Money.fmt(result.cappedForfeiture())
                        );

                        engine.cancel(p[2], timestamp);
                    }

                    case "DATE_CHANGE", "DATECHANGE" -> {

                        Booking b = requireBooking(engine, p[2]);

                        LocalDate requestDate;
                        LocalDate newDate;
                        Slot newSlot;

                        if (p.length >= 6) {
                            // Legacy format
                            requestDate = LocalDate.parse(p[3]);
                            newDate = LocalDate.parse(p[4]);
                            newSlot = Slot.parse(p[5]);
                        } else {
                            // Organizer format
                            requestDate = timestamp.toLocalDate();
                            newDate = LocalDate.parse(p[3]);
                            newSlot = b.slot();
                        }

                        boolean free = engine.eligibleFreeDateChange(
                                b, requestDate, newDate
                        );

                        if (free) {
                            engine.dateChange(p[2], requestDate, newDate, newSlot);

                            rebaseLedger(
                                    p[2], b, engine, advanceLedgers,
                                    advanceService,
                                    confirmationDates.getOrDefault(
                                            p[2], timestamp.toLocalDate()
                                    )
                            );

                            System.out.println(
                                    "DATE_CHANGE_FREE " + p[2]
                                            + " newDate=" + newDate
                                            + " slot=" + newSlot
                            );

                        } else {

                            CancellationService.Result result =
                                    cancellationService.calculate(
                                            contractValue(b, engine),
                                            b.advancesPaid(),
                                            b.eventDate(),
                                            requestDate,
                                            cancellationLines(b, engine),
                                            vendors,
                                            b.selectedPackages(),
                                            cateringGuaranteeValue(b, engine)
                                    );

                            BigDecimal credit = Money.pct(
                                    result.cappedForfeiture(), "50"
                            );

                            b.addDateChangeCredit(credit);
                            engine.cancel(p[2], timestamp);

                            System.out.println(
                                    "DATE_CHANGE_REBOOK_REQUIRED " + p[2]
                                            + " forfeiture="
                                            + Money.fmt(result.cappedForfeiture())
                                            + " newBookingCredit=" + Money.fmt(credit)
                                            + "; create a separate enquiry/booking for "
                                            + newDate
                            );
                        }
                    }

                    case "SWAP_PACKAGE", "SWAP" -> {

                        Booking b = requireBooking(engine, p[2]);

                        String vendorId;
                        String packageCode = p[4];

                        if (vendors.containsKey(p[3])) {
                            // Legacy format
                            vendorId = p[3];

                        } else {
                            // Organizer format
                            Vendor.Category category = Vendor.Category.valueOf(
                                    p[3].trim().toUpperCase(Locale.ROOT)
                            );

                            vendorId = null;

                            for (String selectedVendorId : b.selectedPackages().keySet()) {
                                Vendor selectedVendor = vendors.get(selectedVendorId);

                                if (selectedVendor != null
                                        && selectedVendor.category() == category) {

                                    if (vendorId != null) {
                                        throw new IllegalStateException(
                                                "Multiple selected vendors found for category "
                                                        + category
                                        );
                                    }

                                    vendorId = selectedVendorId;
                                }
                            }

                            if (vendorId == null) {
                                throw new IllegalArgumentException(
                                        "No selected vendor found for category " + category
                                );
                            }
                        }

                        BigDecimal fee = engine.swapPackage(
                                p[2],
                                vendorId,
                                packageCode,
                                timestamp.toLocalDate()
                        );

                        b = requireBooking(engine, p[2]);

                        rebaseLedger(
                                p[2], b, engine, advanceLedgers,
                                advanceService,
                                confirmationDates.getOrDefault(
                                        p[2], timestamp.toLocalDate()
                                )
                        );

                        System.out.println(
                                "SWAP_PACKAGE " + p[2]
                                        + " vendor=" + vendorId
                                        + " package=" + packageCode
                                        + " fee=" + Money.fmt(fee)
                        );
                    }

                    case "ACTUALS" -> {

                        Booking b = requireBooking(engine, p[2]);

                        if (b.status() != Booking.Status.CONFIRMED) {
                            throw new IllegalStateException(
                                    "Actuals require a confirmed booking; current status="
                                            + b.status()
                            );
                        }

                        if (timestamp.toLocalDate().isBefore(b.eventDate())) {
                            throw new IllegalStateException(
                                    "Actuals cannot be recorded before the event date"
                            );
                        }

                        DamageService.Charge damage = damageService.calculate(
                                damageService.parse(p.length > 4 ? p[4] : "-"),
                                damageRates
                        );

                        b.actuals(Integer.parseInt(p[3]), damage.total());
                        b.status(Booking.Status.COMPLETED);

                        System.out.println(
                                "ACTUALS " + p[2]
                                        + " guests=" + p[3]
                                        + " damages=" + Money.fmt(damage.total())
                        );

                        damage.lineAmounts().entrySet().stream()
                                .sorted(Map.Entry.comparingByKey())
                                .forEach(e -> System.out.println(
                                        "  DAMAGE " + e.getKey()
                                                + " quantity=" + damage.quantities().get(e.getKey())
                                                + " amount=" + Money.fmt(e.getValue())
                                ));
                    }

                    case "DISPUTE" -> {

                        Booking disputedBooking;
                        String disputeId;
                        String vendorId;
                        LocalDateTime filedAt;
                        LocalDateTime eventClosedAt;
                        String reason;

                        if (p.length >= 8) {
                            // Legacy format
                            disputeId = p[2];
                            disputedBooking = requireBooking(engine, p[3]);
                            vendorId = p[4];
                            filedAt = LocalDateTime.parse(p[5].replace(" ", "T"));
                            eventClosedAt = LocalDateTime.parse(p[6].replace(" ", "T"));
                            reason = p[7];

                        } else {
                            // Organizer format
                            disputedBooking = requireBooking(engine, p[2]);
                            vendorId = p[3];

                            disputeId = "DSP-" + p[2] + "-" + vendorId;
                            filedAt = timestamp;
                            eventClosedAt = disputedBooking.eventDate()
                                    .atTime(23, 59, 59);
                            reason = "Organizer-filed dispute";
                        }

                        if (!vendors.containsKey(vendorId)
                                || !disputedBooking.selectedPackages().containsKey(vendorId)) {
                            throw new IllegalArgumentException(
                                    "Dispute vendor must be selected on the booking"
                            );
                        }

                        var dispute = disputeLedger.file(
                                disputeId,
                                disputedBooking.id(),
                                vendorId,
                                filedAt,
                                eventClosedAt,
                                reason
                        );

                        System.out.println(
                                "DISPUTE " + dispute.id()
                                        + " booking=" + dispute.bookingId()
                                        + " vendor=" + dispute.vendorId()
                                        + " payoutFreeze=" + dispute.open()
                        );
                    }

                    case "RESOLVE_DISPUTE" -> {
                        disputeLedger.resolve(p[2]);
                        System.out.println("DISPUTE_RESOLVED " + p[2]);
                    }

                    case "PAYOUT_VENUE" -> {

                        Booking payoutBooking = requireBooking(engine, p[2]);

                        if (!payoutBooking.venueId().equals(p[3])) {
                            throw new IllegalArgumentException(
                                    "Payout venue must match the booking"
                            );
                        }

                        var payout = payoutService.calculateVenue(
                                p[3],
                                new BigDecimal(p[4]),
                                LocalDateTime.parse(p[5].replace(" ", "T")),
                                timestamp,
                                disputeLedger.all(),
                                p[2]
                        );

                        System.out.println(
                                "PAYOUT " + p[2]
                                        + " venue=" + payout.vendorId()
                                        + " gross=" + Money.fmt(payout.grossLineValue())
                                        + " commission=" + Money.fmt(payout.commission())
                                        + " net=" + Money.fmt(payout.netPayout())
                                        + " status=" + payout.status()
                        );
                    }

                    case "PAYOUT" -> {

                        Booking payoutBooking = requireBooking(engine, p[2]);
                        Vendor v = vendors.get(p[3]);

                        if (v == null) {
                            throw new IllegalArgumentException("Unknown vendor " + p[3]);
                        }

                        if (!payoutBooking.selectedPackages().containsKey(p[3])) {
                            throw new IllegalArgumentException(
                                    "Payout vendor must be selected on the booking"
                            );
                        }

                        var payout = payoutService.calculate(
                                p[3],
                                new BigDecimal(p[4]),
                                v,
                                LocalDateTime.parse(p[5].replace(" ", "T")),
                                timestamp,
                                disputeLedger.all(),
                                p[2]
                        );

                        System.out.println(
                                "PAYOUT " + p[2]
                                        + " vendor=" + payout.vendorId()
                                        + " gross=" + Money.fmt(payout.grossLineValue())
                                        + " commission=" + Money.fmt(payout.commission())
                                        + " net=" + Money.fmt(payout.netPayout())
                                        + " status=" + payout.status()
                        );
                    }

                    case "SETTLE" -> {

                        Booking b = requireBooking(engine, p[2]);

                        if (b.status() != Booking.Status.COMPLETED) {
                            throw new IllegalStateException(
                                    "Settlement requires completed event; current status="
                                            + b.status()
                            );
                        }

                        BigDecimal venue = engine.quoteVenue(b);
                        BigDecimal catering = engine.quoteCatering(b);

                        Map<String, BigDecimal> packageLines = new LinkedHashMap<>();

                        for (var e : b.selectedPackages().entrySet()) {
                            Vendor v = vendors.get(e.getKey());

                            if (v.category() != Vendor.Category.CATERING) {
                                packageLines.put(
                                        e.getKey(),
                                        v.packagePrice(e.getValue())
                                );
                            }
                        }

                        Quote q = settlementService.finalBill(
                                venue,
                                catering,
                                packageLines,
                                b.switchFees(),
                                b.damages(),
                                b.advancesPaid(),
                                b.dateChangeCreditApplied()
                        );

                        AdvanceLedger ledger = advanceLedgers.get(b.id());

                        BigDecimal lateFees = ledger == null
                                ? Money.ZERO
                                : ledger.status(timestamp.toLocalDate()).lateFees();

                        b.status(Booking.Status.SETTLED);

                        System.out.println(
                                "SETTLEMENT " + b.id()
                                        + " venue=" + Money.fmt(q.venue())
                                        + " catering=" + Money.fmt(q.catering())
                                        + " packages=" + Money.fmt(q.packages())
                                        + " switchFees=" + Money.fmt(q.switchFees())
                                        + " damages=" + Money.fmt(q.damages())
                                        + " GST18=" + Money.fmt(q.venueAndPackageTax())
                                        + " GST5=" + Money.fmt(q.cateringTax())
                                        + " advances=" + Money.fmt(b.advancesPaid())
                                        + " dateChangeCredit="
                                        + Money.fmt(q.dateChangeCreditApplied())
                                        + " lateFeesSeparate=" + Money.fmt(lateFees)
                                        + " balance=" + Money.fmt(q.balance())
                        );
                    }

                    case "VENUE_STATEMENT" -> {

                        String venueId = p[2];

                        if (!venues.containsKey(venueId)) {
                            throw new IllegalArgumentException(
                                    "Unknown venue: " + venueId
                            );
                        }

                        BigDecimal totalGross = BigDecimal.ZERO;
                        BigDecimal totalCommission = BigDecimal.ZERO;
                        BigDecimal totalNet = BigDecimal.ZERO;

                        int payableCount = 0;
                        int frozenCount = 0;
                        int pendingCount = 0;

                        System.out.println(
                                "VENUE_STATEMENT " + venueId
                                        + " date=" + timestamp.toLocalDate()
                        );

                        for (Booking b : engine.bookings().values()) {

                            if (!b.venueId().equals(venueId)) {
                                continue;
                            }

                            if (b.eventDate().isAfter(timestamp.toLocalDate())) {
                                continue;
                            }

                            if (b.status() != Booking.Status.COMPLETED
                                    && b.status() != Booking.Status.SETTLED) {
                                continue;
                            }

                            BigDecimal gross = engine.quoteVenue(b);

                            LocalDateTime eventClosedAt =
                                    b.eventDate().atTime(23, 59, 59);

                            var payout = payoutService.calculateVenue(
                                    venueId,
                                    gross,
                                    eventClosedAt,
                                    timestamp,
                                    disputeLedger.all(),
                                    b.id()
                            );

                            totalGross = totalGross.add(payout.grossLineValue());
                            totalCommission = totalCommission.add(payout.commission());
                            totalNet = totalNet.add(payout.netPayout());

                            if (payout.payable()) {
                                payableCount++;
                            } else if (payout.frozen()) {
                                frozenCount++;
                            } else {
                                pendingCount++;
                            }

                            System.out.println(
                                    "  booking=" + b.id()
                                            + " gross=" + Money.fmt(payout.grossLineValue())
                                            + " commission=" + Money.fmt(payout.commission())
                                            + " net=" + Money.fmt(payout.netPayout())
                                            + " status=" + payout.status()
                            );
                        }

                        System.out.println(
                                "TOTAL gross=" + Money.fmt(totalGross)
                                        + " commission=" + Money.fmt(totalCommission)
                                        + " net=" + Money.fmt(totalNet)
                        );

                        System.out.println(
                                "PAYMENT_STATUS payable=" + payableCount
                                        + " frozen=" + frozenCount
                                        + " pending=" + pendingCount
                        );
                    }

                    default ->
                            throw new IllegalArgumentException("Unknown command " + p[0]);
                }

            } catch (Exception ex) {
                System.err.println(
                        "ERROR line " + currentLineNo + ": " + ex.getMessage()
                );
                throw ex;
            }
        }
    }

    private static boolean isTimestamp(String value) {
        try {
            parseTimestamp(value);
            return true;
        } catch (DateTimeParseException e) {
            return false;
        }
    }

    private static LocalDateTime parseTimestamp(String value) {
        String cleaned = value.trim().replace(" ", "T");

        try {
            return LocalDateTime.parse(cleaned);
        } catch (DateTimeParseException e) {
            return LocalDate.parse(cleaned).atStartOfDay();
        }
    }

    private static void printQuote(
            String[] p,
            Map<String, Venue> venues,
            Map<String, Vendor> vendors,
            PricingService pricing,
            SettlementService settlement
    ) {
        if (p.length < 6) {
            throw new IllegalArgumentException(
                    "QUOTE format: timestamp|QUOTE|venueId|date|slot|guests|vendorId:package;..."
            );
        }

        Venue venue = venues.get(p[2]);

        if (venue == null) {
            throw new IllegalArgumentException("Unknown venue: " + p[2]);
        }

        LocalDate date = LocalDate.parse(p[3]);
        Slot slot = Slot.parse(p[4]);
        int guests = Integer.parseInt(p[5]);

        if (guests < 1 || guests > venue.capacity()) {
            throw new IllegalArgumentException(
                    "Guest count must be between 1 and venue capacity"
            );
        }

        BigDecimal venueAmount = pricing.venueRental(venue, date, slot);
        BigDecimal catering = BigDecimal.ZERO;

        Map<String, BigDecimal> packageLines = new LinkedHashMap<>();
        Map<String, String> selected = p.length > 6
                ? parsePackages(p[6])
                : Map.of();

        long cateringCount = selected.keySet().stream()
                .map(vendors::get)
                .filter(Objects::nonNull)
                .filter(v -> v.category() == Vendor.Category.CATERING)
                .count();

        if (cateringCount > 1) {
            throw new IllegalArgumentException("Choose only one catering package");
        }

        for (var entry : selected.entrySet()) {
            Vendor vendor = vendors.get(entry.getKey());

            if (vendor == null) {
                throw new IllegalArgumentException(
                        "Unknown vendor: " + entry.getKey()
                );
            }

            BigDecimal rate = vendor.packagePrice(entry.getValue());

            if (vendor.category() == Vendor.Category.CATERING) {
                catering = pricing.catering(guests, guests, rate);
            } else {
                packageLines.put(vendor.id(), rate);
            }
        }

        Quote quote = settlement.finalBill(
                venueAmount,
                catering,
                packageLines,
                BigDecimal.ZERO,
                BigDecimal.ZERO,
                BigDecimal.ZERO
        );

        System.out.println(
                "QUOTE venue=" + venue.id()
                        + " date=" + date
                        + " slot=" + slot
                        + " guests=" + guests
                        + " seasonMultiplier=" + pricing.seasonMultiplier(date)
        );

        System.out.println("  venue=" + Money.fmt(quote.venue()));

        if (quote.catering().signum() > 0) {
            System.out.println("  catering=" + Money.fmt(quote.catering()));
        }

        for (var entry : quote.packageLines().entrySet()) {
            System.out.println(
                    "  package " + vendors.get(entry.getKey()).name()
                            + "=" + Money.fmt(entry.getValue())
            );
        }

        System.out.println(
                "  GST18 venue+packages=" + Money.fmt(quote.venueAndPackageTax())
        );

        System.out.println("  GST5 catering=" + Money.fmt(quote.cateringTax()));
        System.out.println("  total=" + Money.fmt(quote.totalBeforeAdvances()));
    }

    private static Map<String, String> parsePackages(String source) {
        Map<String, String> selected = new LinkedHashMap<>();

        if (source == null || source.isBlank() || source.trim().equals("-")) {
            return selected;
        }

        for (String pair : source.split(";")) {
            String[] kv = pair.split(":", 2);

            if (kv.length != 2) {
                throw new IllegalArgumentException(
                        "Invalid vendor package pair: " + pair
                );
            }

            if (selected.putIfAbsent(kv[0], kv[1]) != null) {
                throw new IllegalArgumentException(
                        "Duplicate vendor package selection: " + kv[0]
                );
            }
        }

        return selected;
    }

    private static Booking requireBooking(BookingEngine engine, String id) {
        Booking b = engine.bookings().get(id);

        if (b == null) {
            throw new IllegalArgumentException("Unknown booking: " + id);
        }

        return b;
    }

    private static BigDecimal cateringGuaranteeValue(
            Booking b,
            BookingEngine engine
    ) {
        for (var e : b.selectedPackages().entrySet()) {
            Vendor v = engine.vendors().get(e.getKey());

            if (v.category() == Vendor.Category.CATERING) {
                return v.packagePrice(e.getValue()).multiply(
                        BigDecimal.valueOf(
                                new PricingService(Set.of())
                                        .cateringGuarantee(b.quotedGuests())
                        )
                );
            }
        }

        return BigDecimal.ZERO;
    }

    private static BigDecimal contractValue(
            Booking b,
            BookingEngine engine
    ) {
        BigDecimal catering = engine.quoteCatering(b);
        Map<String, BigDecimal> packages = new LinkedHashMap<>();

        for (var e : b.selectedPackages().entrySet()) {
            Vendor v = engine.vendors().get(e.getKey());

            if (v.category() != Vendor.Category.CATERING) {
                packages.put(e.getKey(), v.packagePrice(e.getValue()));
            }
        }

        return new SettlementService().finalBill(
                engine.quoteVenue(b),
                catering,
                packages,
                b.switchFees(),
                BigDecimal.ZERO,
                BigDecimal.ZERO
        ).totalBeforeAdvances();
    }

    private static BigDecimal paymentContractValue(
            Booking b,
            BookingEngine engine
    ) {
        return contractValue(b, engine)
                .subtract(b.dateChangeCreditApplied())
                .max(BigDecimal.ZERO);
    }

    private static void rebaseLedger(
            String bookingId,
            Booking booking,
            BookingEngine engine,
            Map<String, AdvanceLedger> ledgers,
            AdvanceService service,
            LocalDate confirmationDate
    ) {
        AdvanceLedger ledger = ledgers.get(bookingId);

        if (ledger != null) {
            ledger.rebase(
                    service.schedule(
                            paymentContractValue(booking, engine),
                            confirmationDate,
                            booking.eventDate()
                    )
            );
        }
    }

    private static Map<String, BigDecimal> cancellationLines(
            Booking b,
            BookingEngine engine
    ) {
        Map<String, BigDecimal> lines = new LinkedHashMap<>();

        for (var e : b.selectedPackages().entrySet()) {
            Vendor v = engine.vendors().get(e.getKey());

            BigDecimal value = v.category() == Vendor.Category.CATERING
                    ? engine.quoteCatering(b)
                    : v.packagePrice(e.getValue());

            lines.put(e.getKey(), value);
        }

        return lines;
    }

    private static void printSchedule(
            Booking b,
            BookingEngine engine,
            AdvanceService service,
            LocalDate confirmationDate
    ) {
        printSchedule(
                b,
                service.schedule(
                        contractValue(b, engine),
                        confirmationDate,
                        b.eventDate()
                )
        );
    }

    private static void printSchedule(
            Booking b,
            List<AdvanceService.Tranche> tranches
    ) {
        System.out.println("ADVANCE_SCHEDULE " + b.id());

        for (var t : tranches) {
            System.out.println(
                    "  " + t.name()
                            + " due=" + t.dueDate()
                            + " amount=" + Money.fmt(t.amount())
            );
        }
    }
}
