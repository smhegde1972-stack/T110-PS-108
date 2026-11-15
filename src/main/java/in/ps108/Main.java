package in.ps108;

import in.ps108.model.*;
import in.ps108.service.*;
import java.math.BigDecimal;
import java.nio.file.*;
import java.time.*;
import java.util.*;

/** CLI entry point. Input is a deterministic pipe-delimited event file. */
public class Main {
    private record TimedCommand(int lineNo, String raw, LocalDateTime timestamp) {}

    public static void main(String[] args) throws Exception {
        Path venuesPath=Path.of(args.length>0?args[0]:"data/venues.csv");
        Path vendorsPath=Path.of(args.length>1?args[1]:"data/vendors.csv");
        Path commandsPath=Path.of(args.length>2?args[2]:"data/commands.txt");
        Path auspiciousPath=Path.of(args.length>3?args[3]:"data/auspicious-dates.csv");
        Path damageRatesPath=Path.of(args.length>4?args[4]:"data/damage-rate-card.csv");
        Map<String,Venue> venues=CsvLoader.venues(venuesPath);
        Map<String,Vendor> vendors=CsvLoader.vendors(vendorsPath);
        Map<String,BigDecimal> damageRates=CsvLoader.damageRates(damageRatesPath);
        PricingService pricing=new PricingService(CsvLoader.auspiciousDates(auspiciousPath));
        BookingEngine engine=new BookingEngine(venues,vendors,pricing);
        AdvanceService advanceService=new AdvanceService();
        CancellationService cancellationService=new CancellationService();
        SettlementService settlementService=new SettlementService();
        DamageService damageService=new DamageService();
        DisputeLedger disputeLedger=new DisputeLedger();
        PayoutService payoutService=new PayoutService();
        Map<String,AdvanceLedger> advanceLedgers=new LinkedHashMap<>();
        Map<String,LocalDate> confirmationDates=new HashMap<>();
        Set<String> autoCancelled=new HashSet<>();
        List<String> lines=Files.readAllLines(commandsPath);
        List<TimedCommand> commands=new ArrayList<>();
        int lineNo=0;
        for(String raw:lines) {
            lineNo++; String line=raw.trim(); if(line.isEmpty()||line.startsWith("#"))continue;
            try {
                String[] p=line.split("\\|",-1);
                if(p.length<2) throw new IllegalArgumentException("Expected TIMESTAMP|COMMAND|...");
                commands.add(new TimedCommand(lineNo,line,LocalDateTime.parse(p[0])));
            } catch(Exception ex) {
                System.err.println("ERROR line "+lineNo+": "+ex.getMessage()); throw ex;
            }
        }
        commands.sort(Comparator.comparing(TimedCommand::timestamp).thenComparingInt(TimedCommand::lineNo));
        for(TimedCommand command:commands) {
            int currentLineNo=command.lineNo();
            String[] p=command.raw().split("\\|",-1);
            LocalDateTime timestamp=command.timestamp();
            try {
                engine.expireHolds(timestamp);
                // The 21-day overdue rule is evaluated before each incoming event, including on event day.
                for(var entry:new ArrayList<>(advanceLedgers.entrySet())) {
                    Booking overdueBooking=engine.bookings().get(entry.getKey());
                    if(overdueBooking!=null && overdueBooking.status()==Booking.Status.CONFIRMED && entry.getValue().status(timestamp.toLocalDate()).autoCancel()) {
                        CancellationService.Result cr=cancellationService.calculate(contractValue(overdueBooking,engine),overdueBooking.advancesPaid(),overdueBooking.eventDate(),timestamp.toLocalDate(),
                                cancellationLines(overdueBooking,engine),vendors,overdueBooking.selectedPackages(),cateringGuaranteeValue(overdueBooking,engine));
                        System.out.println("AUTO_CANCEL "+overdueBooking.id()+" reason=ADVANCE_21_DAYS_OVERDUE forfeiture="+Money.fmt(cr.cappedForfeiture()));
                        engine.cancel(overdueBooking.id(),timestamp); autoCancelled.add(overdueBooking.id());
                    }
                }
                switch(p[1].toUpperCase(Locale.ROOT)) {
                    case "QUOTE" -> printQuote(p,venues,vendors,pricing,settlementService);
                    case "ENQUIRE" -> {
                        engine.enquire(new BookingEngine.Enquiry(p[2],p[3],p[4],LocalDate.parse(p[5]),Slot.parse(p[6]),Integer.parseInt(p[7])),timestamp);
                        System.out.println("ENQUIRY "+p[2]+" accepted; hold/queue state updated");
                    }
                    case "HOLD" -> System.out.println(timestamp+" HOLD request observed for "+p[2]+" (ENQUIRE creates the temporary hold)");
                    case "CONFIRM" -> {
                        Map<String,String> selected=parsePackages(p.length>8?p[8]:"");
                        Booking b=engine.confirm(p[2],p[3],p[4],LocalDate.parse(p[5]),Slot.parse(p[6]),Integer.parseInt(p[7]),selected,timestamp);
                        System.out.println("CONFIRMED "+b.id()+" | venue="+Money.fmt(engine.quoteVenue(b)));
                        List<AdvanceService.Tranche> schedule=advanceService.schedule(paymentContractValue(b,engine),timestamp.toLocalDate(),b.eventDate());
                        advanceLedgers.put(b.id(),new AdvanceLedger(schedule,advanceService));
                        confirmationDates.put(b.id(),timestamp.toLocalDate());
                        printSchedule(b,schedule);
                    }
                    case "PAY" -> {
                        BigDecimal amount=Money.round(new BigDecimal(p[3])); Booking paidBooking=requireBooking(engine,p[2]);
                        if(paidBooking.status()!=Booking.Status.CONFIRMED)throw new IllegalStateException("Payments require a confirmed booking; current status="+paidBooking.status());
                        AdvanceLedger ledger=advanceLedgers.get(p[2]);
                        if(ledger!=null)ledger.pay(amount,timestamp.toLocalDate());
                        engine.pay(p[2],amount); System.out.println("PAYMENT "+p[2]+" "+Money.fmt(amount));
                    }
                    case "SCHEDULE" -> {
                        Booking b=requireBooking(engine,p[2]);
                        AdvanceLedger ledger=advanceLedgers.get(p[2]);
                        if(ledger==null)printSchedule(b,engine,advanceService,LocalDate.parse(p[3]));
                        else {
                            printSchedule(b,ledger.accounts().stream().map(AdvanceLedger.Account::tranche).toList());
                            var status=ledger.status(LocalDate.parse(p[3]));
                            System.out.println("  outstanding="+Money.fmt(status.totalOutstanding())+" lateFees="+Money.fmt(status.lateFees())+" autoCancel="+status.autoCancel());
                        }
                    }
                    case "APPLY_CREDIT" -> {
                        Booking source=requireBooking(engine,p[2]), replacement=requireBooking(engine,p[3]);
                        if(source.status()!=Booking.Status.CANCELLED) throw new IllegalStateException("Credit source booking must be cancelled");
                        if(replacement.status()!=Booking.Status.CONFIRMED) throw new IllegalStateException("Replacement booking must be confirmed");
                        if(replacement.advancesPaid().signum()>0) throw new IllegalStateException("Apply date-change credit before recording payments on the replacement booking");
                        BigDecimal remaining=contractValue(replacement,engine).subtract(replacement.dateChangeCreditApplied()).max(BigDecimal.ZERO);
                        BigDecimal amount=source.dateChangeCredit().subtract(source.dateChangeCreditRedeemed()).max(BigDecimal.ZERO).min(remaining);
                        if(amount.signum()<=0) throw new IllegalStateException("No usable date-change credit or replacement balance is available");
                        source.consumeDateChangeCredit(amount); replacement.applyDateChangeCredit(amount);
                        AdvanceLedger ledger=advanceLedgers.get(replacement.id());
                        if(ledger!=null) {
                            ledger.rebase(advanceService.schedule(paymentContractValue(replacement,engine),confirmationDates.get(replacement.id()),replacement.eventDate()));
                            printSchedule(replacement,ledger.accounts().stream().map(AdvanceLedger.Account::tranche).toList());
                        }
                        System.out.println("DATE_CHANGE_CREDIT_APPLIED source="+source.id()+" replacement="+replacement.id()+" amount="+Money.fmt(amount)+" remainingSourceCredit="+Money.fmt(source.dateChangeCredit().subtract(source.dateChangeCreditRedeemed())));
                    }
                    case "CANCEL" -> {
                        Booking b=requireBooking(engine,p[2]); LocalDate cancelDate=p.length>3&&!p[3].isBlank()?LocalDate.parse(p[3]):timestamp.toLocalDate();
                        CancellationService.Result result=cancellationService.calculate(contractValue(b,engine),b.advancesPaid(),b.eventDate(),cancelDate,
                                cancellationLines(b,engine),vendors,b.selectedPackages(),cateringGuaranteeValue(b,engine));
                        System.out.println("CANCELLATION "+b.id()+" daysBeforeEvent="+java.time.temporal.ChronoUnit.DAYS.between(cancelDate,b.eventDate()));
                        result.lineBreakdown().entrySet().stream().sorted(Map.Entry.comparingByKey()).forEach(e->System.out.println("  "+e.getKey()+" forfeiture="+Money.fmt(e.getValue())));
                        System.out.println("  gross="+Money.fmt(result.grossForfeiture())+" cap="+Money.fmt(result.cap())+" finalForfeiture="+Money.fmt(result.cappedForfeiture()));
                        engine.cancel(p[2],timestamp);
                    }
                    case "DATECHANGE" -> {
                        Booking b=requireBooking(engine,p[2]); LocalDate request=LocalDate.parse(p[3]),newDate=LocalDate.parse(p[4]);Slot newSlot=Slot.parse(p[5]);
                        boolean free=engine.eligibleFreeDateChange(b,request,newDate);
                        if(free) {engine.dateChange(p[2],request,newDate,newSlot);rebaseLedger(p[2],b,engine,advanceLedgers,advanceService,confirmationDates.getOrDefault(p[2],timestamp.toLocalDate()));System.out.println("DATE_CHANGE_FREE "+p[2]+" newDate="+newDate+" slot="+newSlot);}
                        else {
                            CancellationService.Result result=cancellationService.calculate(contractValue(b,engine),b.advancesPaid(),b.eventDate(),request,
                                    cancellationLines(b,engine),vendors,b.selectedPackages(),cateringGuaranteeValue(b,engine));
                            BigDecimal credit=Money.pct(result.cappedForfeiture(),"50"); b.addDateChangeCredit(credit); engine.cancel(p[2],timestamp);
                            System.out.println("DATE_CHANGE_REBOOK_REQUIRED "+p[2]+" forfeiture="+Money.fmt(result.cappedForfeiture())+" newBookingCredit="+Money.fmt(credit)+"; create a separate enquiry/booking for "+newDate);
                        }
                    }
                    case "SWAP" -> {BigDecimal fee=engine.swapPackage(p[2],p[3],p[4],timestamp.toLocalDate());Booking b=requireBooking(engine,p[2]);rebaseLedger(p[2],b,engine,advanceLedgers,advanceService,confirmationDates.getOrDefault(p[2],timestamp.toLocalDate()));System.out.println("SWAP "+p[2]+" fee="+Money.fmt(fee));}
                    case "ACTUALS" -> {
                        Booking b=requireBooking(engine,p[2]);
                        if(b.status()!=Booking.Status.CONFIRMED)throw new IllegalStateException("Actuals require a confirmed booking; current status="+b.status());
                        if(timestamp.toLocalDate().isBefore(b.eventDate()))throw new IllegalStateException("Actuals cannot be recorded before the event date");
                        DamageService.Charge damage=damageService.calculate(damageService.parse(p.length>4?p[4]:"-"),damageRates);
                        b.actuals(Integer.parseInt(p[3]),damage.total());b.status(Booking.Status.COMPLETED);
                        System.out.println("ACTUALS "+p[2]+" guests="+p[3]+" damages="+Money.fmt(damage.total()));
                        damage.lineAmounts().entrySet().stream().sorted(Map.Entry.comparingByKey())
                                .forEach(e->System.out.println("  DAMAGE "+e.getKey()+" quantity="+damage.quantities().get(e.getKey())+" amount="+Money.fmt(e.getValue())));
                    }
                    case "DISPUTE" -> {
                        Booking disputedBooking=requireBooking(engine,p[3]);
                        if(!vendors.containsKey(p[4])||!disputedBooking.selectedPackages().containsKey(p[4])) throw new IllegalArgumentException("Dispute vendor must be selected on the booking");
                        var d=disputeLedger.file(p[2],p[3],p[4],LocalDateTime.parse(p[5]),LocalDateTime.parse(p[6]),p.length>7?p[7]:"Client dispute");
                        System.out.println("DISPUTE "+d.id()+" vendor="+d.vendorId()+" payoutFreeze="+d.open());
                    }
                    case "RESOLVE_DISPUTE" -> {disputeLedger.resolve(p[2]);System.out.println("DISPUTE_RESOLVED "+p[2]);}
                    case "PAYOUT_VENUE" -> {
                        Booking payoutBooking=requireBooking(engine,p[2]);
                        if(!payoutBooking.venueId().equals(p[3])) throw new IllegalArgumentException("Payout venue must match the booking");
                        var payout=payoutService.calculateVenue(p[3],new BigDecimal(p[4]),LocalDateTime.parse(p[5]),timestamp,disputeLedger.all(),p[2]);
                        System.out.println("PAYOUT "+p[2]+" venue="+payout.vendorId()+" gross="+Money.fmt(payout.grossLineValue())+" commission="+Money.fmt(payout.commission())+" net="+Money.fmt(payout.netPayout())+" status="+payout.status());
                    }
                    case "PAYOUT" -> {
                        Booking payoutBooking=requireBooking(engine,p[2]);
                        Vendor v=vendors.get(p[3]);if(v==null)throw new IllegalArgumentException("Unknown vendor "+p[3]);
                        if(!payoutBooking.selectedPackages().containsKey(p[3])) throw new IllegalArgumentException("Payout vendor must be selected on the booking");
                        var payout=payoutService.calculate(p[3],new BigDecimal(p[4]),v,LocalDateTime.parse(p[5]),timestamp,disputeLedger.all(),p[2]);
                        System.out.println("PAYOUT "+p[2]+" vendor="+payout.vendorId()+" gross="+Money.fmt(payout.grossLineValue())+" commission="+Money.fmt(payout.commission())+" net="+Money.fmt(payout.netPayout())+" status="+payout.status());
                    }
                    case "SETTLE" -> {
                        Booking b=requireBooking(engine,p[2]);
                        if(b.status()!=Booking.Status.COMPLETED)throw new IllegalStateException("Settlement requires completed event; current status="+b.status());
                        BigDecimal venue=engine.quoteVenue(b), catering=engine.quoteCatering(b);
                        Map<String,BigDecimal> packageLines=new LinkedHashMap<>();
                        for(var e:b.selectedPackages().entrySet()){Vendor v=vendors.get(e.getKey());if(v.category()!=Vendor.Category.CATERING)packageLines.put(e.getKey(),v.packagePrice(e.getValue()));}
                        Quote q=settlementService.finalBill(venue,catering,packageLines,b.switchFees(),b.damages(),b.advancesPaid(),b.dateChangeCreditApplied());
                        AdvanceLedger ledger=advanceLedgers.get(b.id());
                        BigDecimal lateFees=ledger==null?Money.ZERO:ledger.status(timestamp.toLocalDate()).lateFees();
                        b.status(Booking.Status.SETTLED);
                        System.out.println("SETTLEMENT "+b.id()+" venue="+Money.fmt(q.venue())+" catering="+Money.fmt(q.catering())+" packages="+Money.fmt(q.packages())+" switchFees="+Money.fmt(q.switchFees())+" damages="+Money.fmt(q.damages())+" GST18="+Money.fmt(q.venueAndPackageTax())+" GST5="+Money.fmt(q.cateringTax())+" advances="+Money.fmt(b.advancesPaid())+" dateChangeCredit="+Money.fmt(q.dateChangeCreditApplied())+" lateFeesSeparate="+Money.fmt(lateFees)+" balance="+Money.fmt(q.balance()));
                    }
                    default -> throw new IllegalArgumentException("Unknown command "+p[1]);
                }
            } catch(Exception ex) {
                System.err.println("ERROR line "+currentLineNo+": "+ex.getMessage()); throw ex;
            }
        }
    }

    private static void printQuote(String[] p,Map<String,Venue> venues,Map<String,Vendor> vendors,
                                   PricingService pricing,SettlementService settlement) {
        if(p.length<6) throw new IllegalArgumentException("QUOTE format: QUOTE|venueId|date|slot|guests|vendorId:package;...");
        Venue venue=venues.get(p[2]);
        if(venue==null)throw new IllegalArgumentException("Unknown venue: "+p[2]);
        LocalDate date=LocalDate.parse(p[3]); Slot slot=Slot.parse(p[4]); int guests=Integer.parseInt(p[5]);
        if(guests<1||guests>venue.capacity())throw new IllegalArgumentException("Guest count must be between 1 and venue capacity");
        BigDecimal venueAmount=pricing.venueRental(venue,date,slot), catering=BigDecimal.ZERO;
        Map<String,BigDecimal> packageLines=new LinkedHashMap<>();
        Map<String,String> selected=p.length>6?parsePackages(p[6]):Map.of();
        long cateringCount=selected.keySet().stream().map(vendors::get).filter(Objects::nonNull).filter(v->v.category()==Vendor.Category.CATERING).count();
        if(cateringCount>1)throw new IllegalArgumentException("Choose only one catering package");
        for(var entry:selected.entrySet()) {
            Vendor vendor=vendors.get(entry.getKey());
            if(vendor==null)throw new IllegalArgumentException("Unknown vendor: "+entry.getKey());
            BigDecimal rate=vendor.packagePrice(entry.getValue());
            if(vendor.category()==Vendor.Category.CATERING)catering=pricing.catering(guests,guests,rate);
            else packageLines.put(vendor.id(),rate);
        }
        Quote quote=settlement.finalBill(venueAmount,catering,packageLines,BigDecimal.ZERO,BigDecimal.ZERO,BigDecimal.ZERO);
        System.out.println("QUOTE venue="+venue.id()+" date="+date+" slot="+slot+" guests="+guests+" seasonMultiplier="+pricing.seasonMultiplier(date));
        System.out.println("  venue="+Money.fmt(quote.venue()));
        if(quote.catering().signum()>0)System.out.println("  catering="+Money.fmt(quote.catering()));
        for(var entry:quote.packageLines().entrySet())System.out.println("  package "+vendors.get(entry.getKey()).name()+"="+Money.fmt(entry.getValue()));
        System.out.println("  GST18 venue+packages="+Money.fmt(quote.venueAndPackageTax()));
        System.out.println("  GST5 catering="+Money.fmt(quote.cateringTax()));
        System.out.println("  total="+Money.fmt(quote.totalBeforeAdvances()));
    }

    private static Map<String,String> parsePackages(String source) {
        Map<String,String> selected=new LinkedHashMap<>(); if(source==null||source.isBlank()||source.trim().equals("-"))return selected;
        for(String pair:source.split(";")){String[] kv=pair.split(":",2);if(kv.length!=2)throw new IllegalArgumentException("Invalid vendor package pair: "+pair);if(selected.putIfAbsent(kv[0],kv[1])!=null)throw new IllegalArgumentException("Duplicate vendor package selection: "+kv[0]);}
        return selected;
    }
    private static Booking requireBooking(BookingEngine engine,String id) {Booking b=engine.bookings().get(id);if(b==null)throw new IllegalArgumentException("Unknown booking: "+id);return b;}
    private static BigDecimal cateringGuaranteeValue(Booking b,BookingEngine engine) {
        for(var e:b.selectedPackages().entrySet()){Vendor v=engine.vendors().get(e.getKey());if(v.category()==Vendor.Category.CATERING)return v.packagePrice(e.getValue()).multiply(BigDecimal.valueOf(new PricingService(Set.of()).cateringGuarantee(b.quotedGuests())));}
        return BigDecimal.ZERO;
    }
    private static BigDecimal contractValue(Booking b,BookingEngine engine) {
        BigDecimal catering=engine.quoteCatering(b);
        Map<String,BigDecimal> packages=new LinkedHashMap<>();
        for(var e:b.selectedPackages().entrySet()) {
            Vendor v=engine.vendors().get(e.getKey());
            if(v.category()!=Vendor.Category.CATERING) packages.put(e.getKey(),v.packagePrice(e.getValue()));
        }
        return new SettlementService().finalBill(engine.quoteVenue(b),catering,packages,b.switchFees(),BigDecimal.ZERO,BigDecimal.ZERO).totalBeforeAdvances();
    }
    private static BigDecimal paymentContractValue(Booking b,BookingEngine engine) {
        return contractValue(b,engine).subtract(b.dateChangeCreditApplied()).max(BigDecimal.ZERO);
    }
    private static void rebaseLedger(String bookingId,Booking booking,BookingEngine engine,Map<String,AdvanceLedger> ledgers,
                                     AdvanceService service,LocalDate confirmationDate) {
        AdvanceLedger ledger=ledgers.get(bookingId);
        if(ledger!=null) ledger.rebase(service.schedule(paymentContractValue(booking,engine),confirmationDate,booking.eventDate()));
    }
    private static Map<String,BigDecimal> cancellationLines(Booking b,BookingEngine engine) {
        Map<String,BigDecimal> lines=new LinkedHashMap<>();
        for(var e:b.selectedPackages().entrySet()){Vendor v=engine.vendors().get(e.getKey());BigDecimal val=v.category()==Vendor.Category.CATERING?engine.quoteCatering(b):v.packagePrice(e.getValue());lines.put(e.getKey(),val);}
        return lines;
    }
    private static void printSchedule(Booking b,BookingEngine engine,AdvanceService service,LocalDate confirmationDate) {
        printSchedule(b,service.schedule(contractValue(b,engine),confirmationDate,b.eventDate()));
    }
    private static void printSchedule(Booking b,List<AdvanceService.Tranche> tranches) {
        System.out.println("ADVANCE_SCHEDULE "+b.id());
        for(var t:tranches)System.out.println("  "+t.name()+" due="+t.dueDate()+" amount="+Money.fmt(t.amount()));
    }
}
