
package in.ps108.service;

import in.ps108.model.Slot;
import in.ps108.model.Venue;
import org.junit.jupiter.api.Test;
import in.ps108.model.Booking;
import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;

import static org.junit.jupiter.api.Assertions.*;

class BookingEngineTest {

    private static final LocalDate EVENT_DATE =
            LocalDate.of(2027, 6, 15);

    private static final LocalDateTime NOW =
            LocalDateTime.of(2026, 10, 21, 10, 0);

    private BookingEngine createEngine() {
        Venue venue = new Venue(
                "V1",
                "Test Venue",
                500,
                new BigDecimal("100000"),
                Set.of(Slot.DAY, Slot.NIGHT)
        );

        return new BookingEngine(
                Map.of("V1", venue),
                Map.of(),
                new PricingService(Set.of())
        );
    }

    private BookingEngine.Enquiry enquiry(String id) {
        return new BookingEngine.Enquiry(
                id,
                "Client " + id,
                "V1",
                EVENT_DATE,
                Slot.DAY,
                100
        );
    }

    @Test
    void cancellingHeldBookingShouldPromoteNextEnquiry() {
        BookingEngine engine = createEngine();

        engine.enquire(enquiry("E1"), NOW);
        engine.enquire(enquiry("E2"), NOW);

        engine.cancel("HOLD-E1", NOW.plusHours(1));

        Booking promoted = engine.bookings().get("HOLD-E2");

        assertNotNull(promoted,
                "The queued enquiry should receive a booking hold");

        assertEquals(Booking.Status.HELD, promoted.status());
        assertEquals(NOW.plusHours(73), promoted.holdExpiresAt());
    }

    @Test
    void expiredEnquiryMustNotConfirmWithoutActiveHold() {
        BookingEngine engine = createEngine();

        engine.enquire(enquiry("E1"), NOW);

        LocalDateTime afterExpiry = NOW.plusHours(73);
        engine.expireHolds(afterExpiry);

        assertThrows(IllegalStateException.class, () ->
                engine.confirm(
                        "B1",
                        "E1",
                        "V1",
                        EVENT_DATE,
                        Slot.DAY,
                        100,
                        Map.of(),
                        afterExpiry
                )
        );
    }

    @Test
    void enquiryArrivingAtExactHoldExpiryReceivesPromotedHold() {
        BookingEngine engine = createEngine();
        engine.enquire(enquiry("E1"), NOW);
        engine.enquire(enquiry("E2"), NOW.plusMinutes(1));

        LocalDateTime exactExpiry = NOW.plusHours(72);
        engine.enquire(enquiry("E3"), exactExpiry);

        Booking promoted = engine.bookings().get("HOLD-E2");
        assertNotNull(promoted);
        assertEquals(Booking.Status.HELD, promoted.status());
        assertEquals(exactExpiry.plusHours(72), promoted.holdExpiresAt());
        assertNull(engine.bookings().get("HOLD-E3"),
                "At the exact expiry instant, the queued enquiry receives the released slot first");
        assertEquals(java.util.List.of("E3"), java.util.List.copyOf(
                engine.queues().values().iterator().next()));
    }

    @Test
    void cancellingHeldBookingReleasesActiveHoldForNextEnquiry() {
        BookingEngine engine = createEngine();

        engine.enquire(enquiry("E1"), NOW);
        engine.cancel("HOLD-E1", NOW.plusHours(1));
        engine.enquire(enquiry("E2"), NOW.plusHours(2));

        Booking nextHold = engine.bookings().get("HOLD-E2");
        assertNotNull(nextHold);
        assertEquals(Booking.Status.HELD, nextHold.status());
        assertEquals(NOW.plusHours(74), nextHold.holdExpiresAt());
    }

    @Test
    void confirmationRequiresTheMatchingActiveHoldRecord() {
        BookingEngine engine = createEngine();
        engine.enquire(enquiry("E1"), NOW);
        engine.cancel("HOLD-E1", NOW.plusHours(1));

        assertThrows(IllegalStateException.class, () -> engine.confirm(
                "B1", "E1", "V1", EVENT_DATE, Slot.DAY, 100, Map.of(), NOW.plusHours(2)));
    }

    @Test
    void partialPaymentClearsQueuedEnquiries() {
        BookingEngine engine = createEngine();
        engine.enquire(enquiry("E1"), NOW);
        engine.enquire(enquiry("E2"), NOW.plusMinutes(1));
        engine.confirm("B1", "E1", "V1", EVENT_DATE, Slot.DAY, 100, Map.of(), NOW.plusMinutes(2));

        engine.pay("B1", new BigDecimal("1.00"));

        assertTrue(engine.queues().values().stream().allMatch(java.util.Collection::isEmpty));
    }

    @Test
    void paymentCannotExceedTaxInclusiveOutstandingBalance() {
        BookingEngine engine = createEngine();
        engine.enquire(enquiry("E1"), NOW);
        engine.confirm("B1", "E1", "V1", EVENT_DATE, Slot.DAY, 100, Map.of(), NOW.plusMinutes(1));

        assertThrows(IllegalArgumentException.class, () -> engine.pay("B1", new BigDecimal("200000.00")));
        assertEquals(BigDecimal.ZERO, engine.bookings().get("B1").advancesPaid());
    }

    @Test
    void cancelledBookingCannotBeCancelledAgain() {
        BookingEngine engine = createEngine();
        engine.enquire(enquiry("E1"), NOW);
        engine.cancel("HOLD-E1", NOW.plusHours(1));
        assertThrows(IllegalStateException.class, () -> engine.cancel("HOLD-E1", NOW.plusHours(2)));
    }

    @Test
    void concurrentEnquiriesForSameSlotGrantOnlyOneHold() throws Exception {
        BookingEngine engine=createEngine();
        int parties=24;
        ExecutorService pool=Executors.newFixedThreadPool(parties);
        CountDownLatch ready=new CountDownLatch(parties);
        CountDownLatch start=new CountDownLatch(1);
        try {
            java.util.List<Future<?>> requests=new java.util.ArrayList<>();
            for(int i=0;i<parties;i++) {
                String id="RACE-"+i;
                requests.add(pool.submit(()->{
                    ready.countDown();
                    start.await();
                    engine.enquire(enquiry(id),NOW);
                    return null;
                }));
            }
            assertTrue(ready.await(5,java.util.concurrent.TimeUnit.SECONDS));
            start.countDown();
            for(Future<?> request:requests)request.get(5,java.util.concurrent.TimeUnit.SECONDS);
        } finally {
            pool.shutdownNow();
        }

        long held=engine.bookings().values().stream().filter(b->b.status()==Booking.Status.HELD).count();
        long queued=engine.queues().values().stream().mapToLong(java.util.Collection::size).sum();
        assertEquals(1,held,"Only one concurrent party may hold the same venue/date/slot");
        assertEquals(parties-1,queued,"Every other party must be queued");
    }
}
