package in.ps108;

import in.ps108.service.Money;
import org.junit.jupiter.api.Test;

import java.time.LocalDate;
import java.time.Clock;
import java.time.ZoneId;
import java.time.LocalDateTime;
import java.util.Map;
import java.util.stream.Collectors;
import java.util.stream.IntStream;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import org.junit.jupiter.api.io.TempDir;
import java.nio.file.Path;

class WebMainTest {
    @TempDir Path temp;
    private static String jsonObject(String json,String property) {
        int start=json.indexOf("\""+property+"\":{");assertTrue(start>=0,"Missing JSON object: "+property);
        start=json.indexOf('{',start);int depth=0;
        for(int i=start;i<json.length();i++){if(json.charAt(i)=='{')depth++;else if(json.charAt(i)=='}'&&--depth==0)return json.substring(start,i+1);}
        throw new AssertionError("Unclosed JSON object: "+property);
    }
    @Test void jsonStringsEscapeEveryControlCharacter() {
        assertEquals("\"tab\\tbackspace\\bformfeed\\funit\\u0001line\\nreturn\\rslash\\\\quote\\\"\"",
                WebMain.q("tab\tbackspace\bformfeed\funit\u0001line\nreturn\rslash\\quote\""));
        String controls=IntStream.range(0,0x20).mapToObj(i->String.valueOf((char)i)).collect(Collectors.joining());
        String encoded=WebMain.q(controls);
        assertFalse(encoded.chars().anyMatch(c->c<0x20),"Encoded JSON must not contain raw control characters");
        assertTrue(encoded.startsWith("\"\\u0000"));
        assertTrue(encoded.endsWith("\\u001f\""));
        assertEquals("null",WebMain.q(null));
    }

    @Test void cancellationResponseIncludesRefundBreakdownAndRetriesDoNotRepeatCancellation() throws Exception {
        LocalDate today=LocalDate.now();
        LocalDate event=today.plusDays(300);
        Clock clock=Clock.fixed(LocalDateTime.now().atZone(ZoneId.systemDefault()).toInstant(),ZoneId.systemDefault());
        WebMain app=new WebMain(temp.resolve("web.sqlite"),clock);
        Map<String,String> form=Map.of("client","Refund Test","venueId","V001","date",event.toString(),"slot","DAY","guests","150");
        String quote=app.quoteJson(form);
        String quoteTotal=quote.replaceFirst(".*\\\"total\\\":([0-9.]+).*","$1");
        String created=app.createEnquiry(form);
        String enquiryId=created.replaceFirst(".*\\\"enquiryId\\\":\\\"([^\\\"]+)\\\".*","$1");
        String required=created.replaceFirst(".*\\\"requiredFirstAdvance\\\":([0-9.]+).*","$1");
        assertEquals(Money.pct(new java.math.BigDecimal(quoteTotal),"25").toPlainString(),required);
        String confirmed=app.confirmEnquiry(Map.of("enquiryId",enquiryId,"amount",required));
        String bookingId=confirmed.replaceFirst(".*\\\"bookingId\\\":\\\"([^\\\"]+)\\\".*","$1");

        String first=app.cancel(Map.of("bookingId",bookingId,"cancelDate",today.toString()));
        String retry=app.cancel(Map.of("bookingId",bookingId,"cancelDate",event.minusDays(1).toString()));

        assertEquals(first,retry,"An API retry must return the saved cancellation result unchanged");
        assertTrue(first.contains("\"totalBookingAmount\":"));
        assertTrue(first.contains("\"amountPaid\":"+required));
        assertTrue(first.contains("\"totalBookingAmount\":"+quoteTotal),"Cancellation must use the quoted rental amount");
        assertTrue(first.contains("\"daysRemaining\":300"));
        assertTrue(first.contains("\"cancellationPercentage\":10.00"));
        assertTrue(first.contains("\"refundAmount\":"));
        assertTrue(first.contains("\"additionalRecoverableAmount\":0.00"));
        assertTrue(first.contains("\"refundStatus\":\"PENDING_ELIGIBILITY\""));
        assertTrue(first.contains("\"refundTransferStatus\":\"NOT_PROCESSED\""));
        assertTrue(first.contains("\"vendorSpecificBreakdown\":[]"));
    }

    @Test void customerQuoteAndAdminBookingExposeIdenticalVenueRentalBreakdown() throws Exception {
        LocalDate event=LocalDate.parse("2027-03-05");
        Clock clock=Clock.fixed(LocalDateTime.parse("2026-10-01T10:00:00").atZone(ZoneId.systemDefault()).toInstant(),ZoneId.systemDefault());
        WebMain app=new WebMain(temp.resolve("pricing-view.sqlite"),clock);
        Map<String,String> form=Map.of("client","Pricing View","venueId","V001","date",event.toString(),"slot","DAY_AND_NIGHT","guests","200");
        String customerQuote=app.quoteJson(form);
        String created=app.createEnquiry(form);
        String bookings=app.bookingsJson();
        String customerBreakdown=jsonObject(customerQuote,"venuePricing");
        String adminBreakdown=jsonObject(bookings,"venuePricing");
        assertEquals(customerBreakdown,adminBreakdown);
        assertTrue(customerBreakdown.contains("\"baseWeekdayRental\":150000.00"));
        assertTrue(customerBreakdown.contains("\"slot\":\"DAY\""));
        assertTrue(customerBreakdown.contains("\"slot\":\"NIGHT\""));
        assertTrue(customerBreakdown.contains("\"secondSlotDiscount\":15000.00"));
        assertTrue(customerBreakdown.contains("\"finalVenueRental\":"));
        assertTrue(created.contains("\"status\":\"HELD\""));
    }
}
