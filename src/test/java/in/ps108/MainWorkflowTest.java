package in.ps108;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.ByteArrayOutputStream;
import java.io.PrintStream;
import java.nio.file.Files;
import java.nio.file.Path;

import static org.junit.jupiter.api.Assertions.*;

class MainWorkflowTest {
    @TempDir Path tempDir;

    @Test void quoteCommandPrintsASeasonPricedQuote() throws Exception {
        Path[] files=inputs("2026-10-24\n", "2026-10-24T10:00:00|QUOTE|V1|2026-10-24|DAY|100|-\n");

        String output=run(files);

        assertTrue(output.contains("QUOTE venue=V1 date=2026-10-24 slot=DAY guests=100 seasonMultiplier=1.6"));
        assertTrue(output.contains("venue=Rs 1600.00"));
        assertTrue(output.contains("total=Rs 1888.00"));
    }

    @Test void dateChangeCreditCanBeAppliedToReplacementBeforePayments() throws Exception {
        String commands="""
                2026-10-02T10:00:00|ENQUIRE|E1|Client|V1|2027-01-01|DAY|100
                2026-10-02T10:01:00|CONFIRM|B1|E1|V1|2027-01-01|DAY|100|-
                2026-10-02T10:02:00|DATECHANGE|B1|2026-10-02|2027-04-01|DAY
                2026-10-02T10:03:00|ENQUIRE|E2|Client|V1|2027-06-01|DAY|100
                2026-10-02T10:04:00|CONFIRM|B2|E2|V1|2027-06-01|DAY|100|-
                2026-10-02T10:05:00|APPLY_CREDIT|B1|B2
                """;
        String output=run(inputs("",commands));

        assertTrue(output.contains("DATE_CHANGE_REBOOK_REQUIRED B1"));
        assertTrue(output.contains("DATE_CHANGE_CREDIT_APPLIED source=B1 replacement=B2 amount=Rs 147.50"));
        assertTrue(output.contains("remainingSourceCredit=Rs 0.00"));
        assertTrue(output.contains("CONFIRMATION_25_PERCENT due=2026-10-02 amount=Rs 258.13"));
    }

    @Test void overdueAdvanceAutoCancelsWhenItReachesTwentyOneDaysOnEventDay() throws Exception {
        String commands="""
                2026-04-01T10:00:00|ENQUIRE|E1|Client|V1|2026-04-22|DAY|100
                2026-04-01T10:01:00|CONFIRM|B1|E1|V1|2026-04-22|DAY|100|-
                2026-04-22T10:00:00|SCHEDULE|B1|2026-04-22
                """;
        String output=run(inputs("",commands));

        assertTrue(output.contains("AUTO_CANCEL B1 reason=ADVANCE_21_DAYS_OVERDUE"));
        assertTrue(output.contains("autoCancel=true"));
    }

    @Test void actualsCommandPricesDamageCodesFromTheRateCard() throws Exception {
        String commands="""
                2026-04-01T10:00:00|ENQUIRE|E1|Client|V1|2026-04-01|DAY|100
                2026-04-01T10:01:00|CONFIRM|B1|E1|V1|2026-04-01|DAY|100|-
                2026-04-01T10:02:00|ACTUALS|B1|100|CHAIR:2;TABLE:1
                """;
        String output=run(inputs("",commands));

        assertTrue(output.contains("ACTUALS B1 guests=100 damages=Rs 8000.00"));
        assertTrue(output.contains("DAMAGE CHAIR quantity=2 amount=Rs 3000.00"));
        assertTrue(output.contains("DAMAGE TABLE quantity=1 amount=Rs 5000.00"));
        assertTrue(output.indexOf("DAMAGE CHAIR") < output.indexOf("DAMAGE TABLE"),
                "Damage lines should print in deterministic code order");
    }

    @Test void outOfOrderCommandsExecuteByTimestampBeforeExactExpiryArrival() throws Exception {
        String commands="""
                2026-10-24T10:01:00|CONFIRM|B2|E2|V1|2027-06-15|DAY|100|-
                2026-10-24T10:00:00|ENQUIRE|E3|Third|V1|2027-06-15|DAY|100
                2026-10-21T10:01:00|ENQUIRE|E2|Second|V1|2027-06-15|DAY|100
                2026-10-21T10:00:00|ENQUIRE|E1|First|V1|2027-06-15|DAY|100
                """;

        String output=run(inputs("",commands));

        assertTrue(output.contains("CONFIRMED B2"),
                "At E1's exact expiry, the earlier queued E2 must be promoted before E3 arrives");
        assertFalse(output.contains("ERROR line"));
    }

    private Path[] inputs(String auspiciousDates,String commands) throws Exception {
        Path venues=tempDir.resolve("venues.csv"), vendors=tempDir.resolve("vendors.csv"), commandFile=tempDir.resolve("commands.txt");
        Path dates=tempDir.resolve("dates.csv"), damageRates=tempDir.resolve("damage-rates.csv");
        Files.writeString(venues,"venue_id,name,capacity,weekday_base,slots\nV1,Test Venue,500,1000.00,DAY;NIGHT\n");
        Files.writeString(vendors,"vendor_id,name,category,packages,commission_rate\n");
        Files.writeString(commandFile,commands); Files.writeString(dates,"date\n"+auspiciousDates);
        Files.writeString(damageRates,"damage_code,description,rate\nCHAIR,Chair damage,1500.00\nTABLE,Table damage,5000.00\nLIGHT,Lighting damage,8000.00\n");
        return new Path[]{venues,vendors,commandFile,dates,damageRates};
    }

    private String run(Path[] files) throws Exception {
        ByteArrayOutputStream bytes=new ByteArrayOutputStream();
        PrintStream previous=System.out;
        try(PrintStream capture=new PrintStream(bytes,true)) {
            System.setOut(capture);
            Main.main(java.util.Arrays.stream(files).map(Path::toString).toArray(String[]::new));
        } finally {
            System.setOut(previous);
        }
        return bytes.toString();
    }
}
