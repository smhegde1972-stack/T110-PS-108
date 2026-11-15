package in.ps108.service;

import in.ps108.model.Quote;
import java.io.IOException;
import java.math.BigDecimal;
import java.math.RoundingMode;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.List;

/**
 * Generates the four required official organizer CSV output files:
 * 1. quotes.csv
 * 2. event_log.csv
 * 3. cancellations.csv
 * 4. settlements.csv
 */
public class OutputReportService {

    public record QuoteRow(
            String enquiryId,
            String status,
            BigDecimal venueRental,
            BigDecimal catering,
            BigDecimal decor,
            BigDecimal photography,
            BigDecimal totalContract,
            String tranche1Due,
            BigDecimal tranche1Amount,
            String tranche2Due,
            BigDecimal tranche2Amount,
            String tranche3Due,
            BigDecimal tranche3Amount
    ) {
        public String toCsvRow() {
            return String.join(",",
                    enquiryId,
                    status,
                    fmt(venueRental),
                    fmt(catering),
                    fmt(decor),
                    fmt(photography),
                    fmt(totalContract),
                    tranche1Due,
                    fmt(tranche1Amount),
                    tranche2Due,
                    fmt(tranche2Amount),
                    tranche3Due,
                    fmt(tranche3Amount)
            );
        }
    }

    public record EventLogRow(
            int lineNo,
            String enquiryId,
            String command,
            String status,
            BigDecimal amount,
            String message
    ) {
        public String toCsvRow() {
            return String.join(",",
                    String.valueOf(lineNo),
                    enquiryId,
                    command,
                    status,
                    fmt(amount),
                    escape(message)
            );
        }
    }

    public record CancellationRow(
            String enquiryId,
            LocalDate cancelDate,
            long daysBefore,
            BigDecimal venueForfeit,
            BigDecimal cateringForfeit,
            BigDecimal decorForfeit,
            BigDecimal photoForfeit,
            BigDecimal totalForfeit,
            BigDecimal paid,
            boolean capApplied,
            BigDecimal refundOrRecoverable
    ) {
        public String toCsvRow() {
            return String.join(",",
                    enquiryId,
                    cancelDate != null ? cancelDate.toString() : "",
                    String.valueOf(daysBefore),
                    fmt(venueForfeit),
                    fmt(cateringForfeit),
                    fmt(decorForfeit),
                    fmt(photoForfeit),
                    fmt(totalForfeit),
                    fmt(paid),
                    String.valueOf(capApplied),
                    fmt(refundOrRecoverable)
            );
        }
    }

    public record SettlementRow(
            String enquiryId,
            BigDecimal venueAmount,
            BigDecimal cateringAmount,
            BigDecimal packagesAmount,
            BigDecimal switchFees,
            BigDecimal damages,
            BigDecimal gst,
            BigDecimal advancesPaid,
            BigDecimal balanceDue,
            BigDecimal venuePayout,
            BigDecimal cateringPayout,
            BigDecimal decorPayout,
            BigDecimal photoPayout,
            String frozenVendor
    ) {
        public String toCsvRow() {
            return String.join(",",
                    enquiryId,
                    fmt(venueAmount),
                    fmt(cateringAmount),
                    fmt(packagesAmount),
                    fmt(switchFees),
                    fmt(damages),
                    fmt(gst),
                    fmt(advancesPaid),
                    fmt(balanceDue),
                    fmt(venuePayout),
                    fmt(cateringPayout),
                    fmt(decorPayout),
                    fmt(photoPayout),
                    frozenVendor != null && !frozenVendor.isBlank() ? frozenVendor : "NONE"
            );
        }
    }

    private final List<QuoteRow> quoteRows = new ArrayList<>();
    private final List<EventLogRow> eventLogRows = new ArrayList<>();
    private final List<CancellationRow> cancellationRows = new ArrayList<>();
    private final List<SettlementRow> settlementRows = new ArrayList<>();

    public void addQuote(QuoteRow row) { quoteRows.add(row); }
    public void addEventLog(EventLogRow row) { eventLogRows.add(row); }
    public void addCancellation(CancellationRow row) { cancellationRows.add(row); }
    public void addSettlement(SettlementRow row) { settlementRows.add(row); }

    public List<QuoteRow> quoteRows() { return List.copyOf(quoteRows); }
    public List<EventLogRow> eventLogRows() { return List.copyOf(eventLogRows); }
    public List<CancellationRow> cancellationRows() { return List.copyOf(cancellationRows); }
    public List<SettlementRow> settlementRows() { return List.copyOf(settlementRows); }

    public void writeQuotes(Path destination) throws IOException {
        List<String> lines = new ArrayList<>();
        lines.add("enquiry_id,status,venue_rental,catering,decor,photography,total_contract,tranche1_due,tranche1_amount,tranche2_due,tranche2_amount,tranche3_due,tranche3_amount");
        for (QuoteRow r : quoteRows) lines.add(r.toCsvRow());
        writeLines(destination, lines);
    }

    public void writeEventLog(Path destination) throws IOException {
        List<String> lines = new ArrayList<>();
        lines.add("line_no,enquiry_id,command,status,amount,message");
        for (EventLogRow r : eventLogRows) lines.add(r.toCsvRow());
        writeLines(destination, lines);
    }

    public void writeCancellations(Path destination) throws IOException {
        List<String> lines = new ArrayList<>();
        lines.add("enquiry_id,cancel_date,days_before,venue_forfeit,catering_forfeit,decor_forfeit,photo_forfeit,total_forfeit,paid,cap_applied,refund_or_recoverable");
        for (CancellationRow r : cancellationRows) lines.add(r.toCsvRow());
        writeLines(destination, lines);
    }

    public void writeSettlements(Path destination) throws IOException {
        List<String> lines = new ArrayList<>();
        lines.add("enquiry_id,venue_amount,catering_amount,packages_amount,switch_fees,damages,gst,advances_paid,balance_due,venue_payout,catering_payout,decor_payout,photo_payout,frozen_vendor");
        for (SettlementRow r : settlementRows) lines.add(r.toCsvRow());
        writeLines(destination, lines);
    }

    public void writeAll(Path directory) throws IOException {
        if (!Files.exists(directory)) Files.createDirectories(directory);
        writeQuotes(directory.resolve("quotes.csv"));
        writeEventLog(directory.resolve("event_log.csv"));
        writeCancellations(directory.resolve("cancellations.csv"));
        writeSettlements(directory.resolve("settlements.csv"));
    }

    public static String fmt(BigDecimal value) {
        if (value == null) return "0.00";
        return value.setScale(2, RoundingMode.HALF_UP).toPlainString();
    }

    private static String escape(String s) {
        if (s == null) return "";
        if (s.contains(",") || s.contains("\"") || s.contains("\n") || s.contains("\r")) {
            return "\"" + s.replace("\"", "\"\"") + "\"";
        }
        return s;
    }

    private static void writeLines(Path destination, List<String> lines) throws IOException {
        if (destination.getParent() != null && !Files.exists(destination.getParent())) {
            Files.createDirectories(destination.getParent());
        }
        Files.write(destination, lines, StandardCharsets.UTF_8);
    }
}
