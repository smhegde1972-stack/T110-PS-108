import in.ps108.model.*;
import in.ps108.service.*;
import java.math.BigDecimal;
import java.time.*;
import java.util.*;

/** Dependency-free regression checks, useful when Maven Central/JUnit cannot be reached. */
public class StandaloneChecks {
    private static int passed=0;
    public static void main(String[] args) {
        check("weekend + auspicious uses max multiplier", () -> {
            Venue v=new Venue("V","Venue",300,new BigDecimal("100000"),Set.of(Slot.DAY,Slot.NIGHT));
            PricingService p=new PricingService(Set.of(LocalDate.parse("2026-10-24")));
            eq("160000.00",p.venueRental(v,LocalDate.parse("2026-10-24"),Slot.DAY));
        });
        check("day+night discounts second slot", () -> {
            Venue v=new Venue("V","Venue",300,new BigDecimal("100000"),Set.of(Slot.DAY,Slot.NIGHT));
            eq("190000.00",new PricingService(Set.of()).venueRental(v,LocalDate.parse("2026-10-20"),Slot.DAY_AND_NIGHT));
        });
        check("catering guarantee minimum", () -> eq(150,new PricingService(Set.of()).cateringGuarantee(100)));
        check("exact +10 percent uses no premium uplift", () -> eq("210000.00",new PricingService(Set.of()).catering(100,110,new BigDecimal("1400"))));
        check("confirmation at 60 days merges first two tranches", () -> {
            var s=new AdvanceService().schedule(new BigDecimal("100000"),LocalDate.parse("2026-01-01"),LocalDate.parse("2026-03-02"));
            eq(2,s.size());eq("75000.00",s.get(0).amount());
        });
        check("confirmation at 7 days requires full amount", () -> {
            var s=new AdvanceService().schedule(new BigDecimal("100000"),LocalDate.parse("2026-01-01"),LocalDate.parse("2026-01-08"));
            eq(1,s.size());eq("100000.00",s.get(0).amount());
        });
        check("21 days overdue auto-cancels", () -> {if(!new AdvanceService().autoCancel(LocalDate.parse("2026-01-01"),LocalDate.parse("2026-01-22")))fail("expected auto-cancel");});
        check("late fee remains after a late tranche is paid", () -> {
            AdvanceService svc=new AdvanceService();
            AdvanceLedger ledger=new AdvanceLedger(List.of(new AdvanceService.Tranche("A",LocalDate.parse("2026-01-01"),new BigDecimal("100000"))),svc);
            ledger.pay(new BigDecimal("100000"),LocalDate.parse("2026-01-10"));
            eq("1000.00",ledger.status(LocalDate.parse("2026-01-10")).lateFees());
        });
        check("exact hold expiry promotes next queued enquiry", StandaloneChecks::holdQueue);
        check("enquiry at exact hold expiry queues behind promoted party", StandaloneChecks::enquiryAtExactExpiry);
        check("overlapping day+night bookings conflict", StandaloneChecks::overlapConflict);
        check("vendor payout has 15 percent commission", () -> {
            Vendor v=new Vendor("D","Decor",Vendor.Category.DECOR,Map.of("B",new BigDecimal("100000")),new BigDecimal("0.15"));
            var close=LocalDateTime.parse("2026-01-01T12:00:00");
            var out=new PayoutService().calculate("D",new BigDecimal("100000"),v,close,close.plusDays(7),List.of(),"B1");
            eq("15000.00",out.commission());eq("85000.00",out.netPayout());if(!out.payable())fail("payout should be payable");
        });
        check("open dispute within 48h freezes payout", () -> {
            Vendor v=new Vendor("D","Decor",Vendor.Category.DECOR,Map.of("B",new BigDecimal("100000")),new BigDecimal("0.15"));
            var close=LocalDateTime.parse("2026-01-01T12:00:00");
            var d=new PayoutService.Dispute("X","B1","D",close.plusHours(48),"issue",true);
            var out=new PayoutService().calculate("D",new BigDecimal("100000"),v,close,close.plusDays(10),List.of(d),"B1");
            if(!out.frozen()||out.payable())fail("payout was not frozen");
        });
        check("package swap exactly 30 days before is free", StandaloneChecks::swapBoundary);
        check("cancellation cap is paid plus 25000", StandaloneChecks::cancellationCap);
        check("date change to 181 days away is not free", () -> {
            BookingEngine engine=new BookingEngine(Map.of(),Map.of(),new PricingService(Set.of()));
            if(engine.eligibleFreeDateChange(LocalDate.parse("2026-01-01"),LocalDate.parse("2026-07-01"),LocalDate.parse("2026-04-02"))) fail("181-day change incorrectly eligible");
        });
        check("only one free date change is allowed", StandaloneChecks::oneFreeDateChange);
        check("first advance clears queued enquiries", StandaloneChecks::firstPaymentClearsQueue);
        System.out.println("Standalone checks: "+passed+" passed, 0 failed.");
    }

    private static void swapBoundary() {
        Vendor decor=new Vendor("D","Decor",Vendor.Category.DECOR,Map.of("BASIC",new BigDecimal("50000"),"PREMIUM",new BigDecimal("100000")),new BigDecimal("0.15"));
        Venue venue=new Venue("V","Venue",300,new BigDecimal("100000"),Set.of(Slot.DAY));
        BookingEngine engine=new BookingEngine(Map.of("V",venue),Map.of("D",decor),new PricingService(Set.of()));
        LocalDate event=LocalDate.parse("2026-04-01"); LocalDate request=event.minusDays(30);
        engine.enquire(new BookingEngine.Enquiry("E","Client","V",event,Slot.DAY,100),request.atStartOfDay());
        Booking b=engine.confirm("B","E","V",event,Slot.DAY,100,Map.of("D","BASIC"),request.atStartOfDay());
        eq("0.00",engine.swapPackage("B","D","PREMIUM",request));
    }
    private static void cancellationCap() {
        Vendor decor=new Vendor("D","Decor",Vendor.Category.DECOR,Map.of("BASIC",new BigDecimal("100000")),new BigDecimal("0.15"));
        var result=new CancellationService().calculate(new BigDecimal("200000"),new BigDecimal("10000"),
                LocalDate.parse("2026-12-31"),LocalDate.parse("2026-12-01"),Map.of("D",new BigDecimal("100000")),
                Map.of("D",decor),Map.of("D","BASIC"),BigDecimal.ZERO);
        eq("35000.00",result.cappedForfeiture());
    }

    private static void oneFreeDateChange() {
        Venue venue=new Venue("V","Venue",300,new BigDecimal("100000"),Set.of(Slot.DAY));
        BookingEngine engine=new BookingEngine(Map.of("V",venue),Map.of(),new PricingService(Set.of()));
        LocalDate request=LocalDate.parse("2026-01-01"), original=LocalDate.parse("2026-05-01");
        engine.enquire(new BookingEngine.Enquiry("E","Client","V",original,Slot.DAY,100),request.atStartOfDay());
        Booking b=engine.confirm("B","E","V",original,Slot.DAY,100,Map.of(),request.atStartOfDay());
        if(!engine.eligibleFreeDateChange(b,request,LocalDate.parse("2026-06-01")))fail("first free change should qualify");
        engine.dateChange("B",request,LocalDate.parse("2026-06-01"),Slot.DAY);
        if(engine.eligibleFreeDateChange(b,request,LocalDate.parse("2026-04-01")))fail("second free change should be denied");
    }
    private static void firstPaymentClearsQueue() {
        Venue venue=new Venue("V","Venue",300,new BigDecimal("100000"),Set.of(Slot.DAY));
        BookingEngine engine=new BookingEngine(Map.of("V",venue),Map.of(),new PricingService(Set.of()));
        LocalDate date=LocalDate.parse("2026-06-01");LocalDateTime t=LocalDateTime.parse("2026-01-01T10:00:00");
        engine.enquire(new BookingEngine.Enquiry("E1","A","V",date,Slot.DAY,100),t);
        engine.enquire(new BookingEngine.Enquiry("E2","B","V",date,Slot.DAY,100),t.plusMinutes(1));
        engine.confirm("B1","E1","V",date,Slot.DAY,100,Map.of(),t.plusMinutes(2));
        engine.pay("B1",new BigDecimal("1.00"));
        if(engine.queues().values().stream().anyMatch(q->!q.isEmpty()))fail("queue was not cleared after first advance");
        try {engine.enquire(new BookingEngine.Enquiry("E3","C","V",date,Slot.DAY,100),t.plusMinutes(3));fail("new enquiry should conflict after advance paid");}
        catch(IllegalStateException expected) { }
    }
    private static void holdQueue() {
        var venue=new Venue("V","Venue",300,new BigDecimal("100000"),Set.of(Slot.DAY));
        var engine=new BookingEngine(Map.of("V",venue),Map.of(),new PricingService(Set.of()));
        var t=LocalDateTime.parse("2026-01-01T10:00:00");
        engine.enquire(new BookingEngine.Enquiry("E1","A","V",LocalDate.parse("2026-06-01"),Slot.DAY,100),t);
        engine.enquire(new BookingEngine.Enquiry("E2","B","V",LocalDate.parse("2026-06-01"),Slot.DAY,100),t.plusMinutes(1));
        engine.expireHolds(t.plusHours(72));
        eq(Booking.Status.HELD,engine.bookings().get("HOLD-E2").status());
    }
    private static void enquiryAtExactExpiry() {
        var venue=new Venue("V","Venue",300,new BigDecimal("100000"),Set.of(Slot.DAY));
        var engine=new BookingEngine(Map.of("V",venue),Map.of(),new PricingService(Set.of()));
        var date=LocalDate.parse("2026-06-01");var t=LocalDateTime.parse("2026-01-01T10:00:00");
        engine.enquire(new BookingEngine.Enquiry("E1","A","V",date,Slot.DAY,100),t);
        engine.enquire(new BookingEngine.Enquiry("E2","B","V",date,Slot.DAY,100),t.plusMinutes(1));
        var expiry=t.plusHours(72);
        engine.enquire(new BookingEngine.Enquiry("E3","C","V",date,Slot.DAY,100),expiry);
        eq(Booking.Status.HELD,engine.bookings().get("HOLD-E2").status());
        eq(expiry.plusHours(72),engine.bookings().get("HOLD-E2").holdExpiresAt());
        if(engine.bookings().containsKey("HOLD-E3"))fail("new arrival bypassed the queued enquiry");
        if(engine.queues().values().stream().noneMatch(q->q.contains("E3")))fail("new arrival was not queued");
    }
    private static void overlapConflict() {
        var venue=new Venue("V","Venue",300,new BigDecimal("100000"),Set.of(Slot.DAY,Slot.NIGHT,Slot.DAY_AND_NIGHT));
        var engine=new BookingEngine(Map.of("V",venue),Map.of(),new PricingService(Set.of()));
        var date=LocalDate.parse("2026-06-01");var t=LocalDateTime.parse("2026-01-01T10:00:00");
        engine.enquire(new BookingEngine.Enquiry("E1","A","V",date,Slot.DAY,100),t);
        engine.enquire(new BookingEngine.Enquiry("E2","B","V",date,Slot.DAY_AND_NIGHT,100),t.plusMinutes(1));
        if(!engine.queues().values().stream().anyMatch(q->q.contains("E2")))fail("overlapping enquiry was not queued");
    }
    private static void check(String name,Runnable test) {try{test.run();passed++;System.out.println("PASS: "+name);}catch(Throwable e){System.err.println("FAIL: "+name+" — "+e.getMessage());System.exit(1);}}
    private static void eq(Object expected,Object actual){if(!Objects.equals(expected,actual))fail("expected "+expected+" but got "+actual);}
    private static void eq(String expected,BigDecimal actual){eq(expected,actual.setScale(2).toPlainString());}
    private static void fail(String message){throw new AssertionError(message);}
}
