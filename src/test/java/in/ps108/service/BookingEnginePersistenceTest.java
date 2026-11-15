package in.ps108.service;

import in.ps108.model.Booking;
import in.ps108.model.Slot;
import in.ps108.model.Venue;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.math.BigDecimal;
import java.nio.file.Path;
import java.time.Clock;
import java.time.Instant;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.ZoneOffset;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.Executors;

import static org.junit.jupiter.api.Assertions.*;

class BookingEnginePersistenceTest {
    @TempDir Path temp;
    private static final LocalDate EVENT = LocalDate.of(2027, 6, 15);
    private static final LocalDateTime NOW = LocalDateTime.of(2026, 10, 21, 10, 0);
    private static final Clock CLOCK = Clock.fixed(Instant.parse("2026-10-21T10:00:00Z"), ZoneOffset.UTC);

    private BookingEngine engine(Path db) {
        Venue venue = new Venue("V1", "Test Venue", 500, new BigDecimal("100000"), Set.of(Slot.DAY, Slot.NIGHT));
        Venue venue2 = new Venue("V2", "Second Venue", 300, new BigDecimal("80000"), Set.of(Slot.DAY, Slot.NIGHT));
        return new BookingEngine(Map.of("V1", venue, "V2", venue2), Map.of(), new PricingService(Set.of()), CLOCK, new SqliteHoldStore(db));
    }

    private BookingEngine.Enquiry enquiry(String id) {
        return new BookingEngine.Enquiry(id, "Client " + id, "V1", EVENT, Slot.DAY, 100);
    }

    @Test void holdQueueAndTimestampsSurviveRestartAndExpiryPromotesFifo() {
        Path db = temp.resolve("holds.sqlite");
        BookingEngine first = engine(db);
        first.enquire(enquiry("E1"), NOW);
        first.enquire(enquiry("E2"), NOW.plusMinutes(1));
        first.enquire(enquiry("E3"), NOW.plusMinutes(2));

        BookingEngine restarted = engine(db);
        assertEquals("HELD", restarted.enquiryStatuses().get("E1"));
        assertEquals(NOW, restarted.bookings().get("HOLD-E1").holdCreatedAt());
        assertEquals(List.of("E2", "E3"), List.copyOf(restarted.queues().values().iterator().next()));

        restarted.expireHolds(NOW.plusHours(72));
        assertEquals(Booking.Status.EXPIRED, restarted.bookings().get("HOLD-E1").status());
        assertEquals(Booking.Status.HELD, restarted.bookings().get("HOLD-E2").status());
        assertEquals(NOW.plusHours(72).plusHours(72), restarted.bookings().get("HOLD-E2").holdExpiresAt());
        assertEquals(List.of("E3"), List.copyOf(restarted.queues().values().iterator().next()));
        LocalDateTime secondExpiry=NOW.plusHours(144);
        restarted.expireHolds(secondExpiry);
        restarted.expireHolds(secondExpiry); // Processing the same deadline twice must not duplicate promotion.
        assertEquals(Booking.Status.HELD,restarted.bookings().get("HOLD-E3").status());
        assertEquals(secondExpiry.plusHours(72),restarted.bookings().get("HOLD-E3").holdExpiresAt());
        assertTrue(restarted.queues().values().stream().allMatch(queue->queue.isEmpty()));
    }

    @Test void firstAdvanceAtomicallyConfirmsAndCancelsQueueAcrossRestart() {
        Path db = temp.resolve("confirm.sqlite");
        BookingEngine engine = engine(db);
        engine.enquire(enquiry("E1"), NOW);
        engine.enquire(enquiry("E2"), NOW.plusMinutes(1));

        Booking confirmed = engine.confirmAndPay("B1", "E1", "V1", EVENT, Slot.DAY, 100,
                Map.of(), new BigDecimal("29500"), NOW.plusMinutes(2));
        assertEquals(Booking.Status.CONFIRMED, confirmed.status());
        assertEquals(new BigDecimal("29500.00"), confirmed.advancesPaid());
        assertEquals("CANCELLED", engine.enquiryStatuses().get("E2"));
        assertTrue(engine.queues().values().stream().allMatch(queue -> queue.isEmpty()));

        BookingEngine restarted = engine(db);
        assertEquals("BOOKED", restarted.availability("V1", EVENT, Slot.DAY, NOW.plusMinutes(3)));
        assertEquals(Booking.Status.CONFIRMED, restarted.bookings().get("B1").status());
        assertEquals("CANCELLED", restarted.enquiryStatuses().get("E2"));
    }

    @Test void separateEngineInstancesSerializeConflictingEnquiries() throws Exception {
        Path db = temp.resolve("race.sqlite");
        engine(db); // Initialize the schema before concurrent connections are opened.
        int parties = 8;
        var pool = Executors.newFixedThreadPool(parties);
        var ready = new CountDownLatch(parties);
        var start = new CountDownLatch(1);
        try {
            var futures = new java.util.ArrayList<java.util.concurrent.Future<?>>();
            for (int i = 0; i < parties; i++) {
                int index = i;
                futures.add(pool.submit(() -> {
                    ready.countDown();
                    start.await();
                    engine(db).enquire(enquiry("R" + index), NOW);
                    return null;
                }));
            }
            ready.await(); start.countDown();
            for (var future : futures) future.get();
        } finally { pool.shutdownNow(); }
        BookingEngine result = engine(db);
        long held = result.enquiryStatuses().values().stream().filter("HELD"::equals).count();
        long queued = result.enquiryStatuses().values().stream().filter("QUEUED"::equals).count();
        assertEquals(1, held);
        assertEquals(parties - 1, queued);
    }

    @Test void postEventBookingUpdatesPersistWithoutReleasingTheReservedSlot() {
        Path db = temp.resolve("post-event.sqlite");
        BookingEngine engine = engine(db);
        engine.enquire(enquiry("E1"), NOW);
        engine.confirm("B1", "E1", "V1", EVENT, Slot.DAY, 100, Map.of(), NOW.plusMinutes(1));
        engine.updateBooking("B1", booking -> {
            booking.actuals(105, new BigDecimal("500"));
            booking.status(Booking.Status.COMPLETED);
        });

        BookingEngine restarted = engine(db);
        Booking persisted = restarted.bookings().get("B1");
        assertEquals(Booking.Status.COMPLETED, persisted.status());
        assertEquals(105, persisted.actualGuests());
        assertEquals(new BigDecimal("500.00"), persisted.damages());
        assertEquals("BOOKED", restarted.availability("V1", EVENT, Slot.DAY, NOW.plusDays(1)));
    }

    @Test void cancelledQueueEntryIsSkippedAndOtherVenueOrSlotCanHoldIndependently() {
        Path db=temp.resolve("independent-slots.sqlite");
        BookingEngine engine=engine(db);
        engine.enquire(enquiry("A"),NOW);
        engine.enquire(enquiry("B"),NOW.plusMinutes(1));
        engine.enquire(enquiry("C"),NOW.plusMinutes(2));
        engine.cancelEnquiry("B",NOW.plusMinutes(3));
        engine.enquire(new BookingEngine.Enquiry("NIGHT","Night Client","V1",EVENT,Slot.NIGHT,100),NOW.plusMinutes(4));
        engine.enquire(new BookingEngine.Enquiry("OTHER","Other Venue Client","V2",EVENT,Slot.DAY,100),NOW.plusMinutes(5));
        assertEquals("CANCELLED",engine.enquiryStatuses().get("B"));
        assertEquals(Booking.Status.HELD,engine.bookings().get("HOLD-NIGHT").status());
        assertEquals(Booking.Status.HELD,engine.bookings().get("HOLD-OTHER").status());
        engine.expireHolds(NOW.plusHours(72));
        assertEquals(Booking.Status.HELD,engine.bookings().get("HOLD-C").status());
        assertFalse(engine.bookings().containsKey("HOLD-B"));
    }

    @Test void expiredHoldCannotBeConfirmedAndPaymentExpiryRaceProducesOnlyValidState() throws Exception {
        Path expiredDb=temp.resolve("expired-confirm.sqlite");
        BookingEngine expired=engine(expiredDb);expired.enquire(enquiry("LATE"),NOW);
        assertThrows(IllegalStateException.class,()->expired.confirmAndPay("B-LATE","LATE","V1",EVENT,Slot.DAY,100,
                Map.of(),new BigDecimal("29500"),NOW.plusHours(72)));
        assertEquals("EXPIRED",expired.enquiryStatuses().get("LATE"));
        assertFalse(expired.bookings().containsKey("B-LATE"));

        Path raceDb=temp.resolve("expiry-payment-race.sqlite");
        BookingEngine seed=engine(raceDb);seed.enquire(enquiry("R1"),NOW);seed.enquire(enquiry("R2"),NOW.plusMinutes(1));
        BookingEngine expiryEngine=engine(raceDb),paymentEngine=engine(raceDb);
        var gate=new CountDownLatch(1);var pool=Executors.newFixedThreadPool(2);
        try {
            var expiry=pool.submit(()->{gate.await();expiryEngine.expireHolds(NOW.plusHours(72));return null;});
            var payment=pool.submit(()->{gate.await();try{paymentEngine.confirmAndPay("B-R1","R1","V1",EVENT,Slot.DAY,100,Map.of(),new BigDecimal("29500"),NOW.plusHours(72).minusSeconds(1));return "PAID";}catch(IllegalStateException rejected){return "EXPIRED";}});
            gate.countDown();expiry.get();String outcome=payment.get();
            BookingEngine finalState=engine(raceDb);
            if("PAID".equals(outcome)) {
                assertEquals(Booking.Status.CONFIRMED,finalState.bookings().get("B-R1").status());
                assertEquals("CANCELLED",finalState.enquiryStatuses().get("R2"));
            } else {
                assertEquals("EXPIRED",finalState.enquiryStatuses().get("R1"));
                assertEquals(Booking.Status.HELD,finalState.bookings().get("HOLD-R2").status());
            }
        } finally {pool.shutdownNow();}
    }
}
