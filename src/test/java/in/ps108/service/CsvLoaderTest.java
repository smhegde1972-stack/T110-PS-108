package in.ps108.service;

import in.ps108.model.Vendor;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Files;
import java.nio.file.Path;

import static org.junit.jupiter.api.Assertions.*;

class CsvLoaderTest {
    @TempDir Path tempDir;

    @Test void venueLoaderPreservesQuotedCommasAndDoubledQuotes() throws Exception {
        Path csv = tempDir.resolve("venues.csv");
        Files.writeString(csv, "venue_id,name,capacity,weekday_base,slots\nV1,\"Hall, East\",300,1000.00,DAY;NIGHT\nV2,\"The \"\"Grand\"\" Hall\",400,2000.00,DAY\n");

        var venues = CsvLoader.venues(csv);

        assertEquals("Hall, East", venues.get("V1").name());
        assertEquals("The \"Grand\" Hall", venues.get("V2").name());
    }

    @Test void vendorLoaderSupportsQuotedNamesAndQuotedMultilineFields() throws Exception {
        Path csv = tempDir.resolve("vendors.csv");
        Files.writeString(csv, "vendor_id,name,category,packages,commission_rate\r\nC1,\"Silver, Spoon\",CATERING,\"SILVER:900;GOLD:1400\",0.08\r\nD1,\"Petal\r\nDecor\",DECOR,CLASSIC:45000,0.15\r\n");

        var vendors = CsvLoader.vendors(csv);

        assertEquals("Silver, Spoon", vendors.get("C1").name());
        assertEquals("Petal\r\nDecor", vendors.get("D1").name());
        assertEquals(2, vendors.get("C1").packages().size());
        assertEquals(Vendor.Category.CATERING, vendors.get("C1").category());
    }

    @Test void malformedCsvQuotesFailClearly() throws Exception {
        Path csv = tempDir.resolve("venues.csv");
        Files.writeString(csv, "venue_id,name,capacity,weekday_base,slots\nV1,\"unfinished,300,1000,DAY\n");

        IllegalArgumentException error = assertThrows(IllegalArgumentException.class, () -> CsvLoader.venues(csv));
        assertTrue(error.getMessage().contains("Unterminated"));
    }

    @Test void loadsDamageRateCardAndRejectsDuplicateCodes() throws Exception {
        Path csv = tempDir.resolve("damage-rates.csv");
        Files.writeString(csv, "damage_code,description,rate\nCHAIR,Chair damage,1500.00\n");
        assertEquals("1500.00", CsvLoader.damageRates(csv).get("CHAIR").toPlainString());

        Files.writeString(csv, "damage_code,description,rate\nCHAIR,Chair damage,1500.00\nCHAIR,Duplicate,1600.00\n");
        assertThrows(IllegalArgumentException.class, () -> CsvLoader.damageRates(csv));
    }

    @Test void auspiciousDateConfigurationCanBeEmptyButInvalidDatesAreRejected() throws Exception {
        Path csv=tempDir.resolve("auspicious-dates.csv");
        Files.writeString(csv,"date\n");
        assertTrue(CsvLoader.auspiciousDates(csv).isEmpty());
        Files.writeString(csv,"date\nnot-a-date\n");
        assertThrows(java.time.format.DateTimeParseException.class,()->CsvLoader.auspiciousDates(csv));
    }
}
