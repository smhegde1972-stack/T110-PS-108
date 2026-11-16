# PS-108 — Wedding and Event Venue Booking Engine

Java 17 implementation for the T110-PS-108 capstone, including a browser-based customer frontend, an operations dashboard, and a Java HTTP server.

## 1. Requirements

- Java 17 or later
- Maven 3.8 or later
- SQLite JDBC (included through the Maven dependency in `pom.xml`)
- A modern web browser

## 2. Build and test

From the project root (the directory containing `pom.xml`):

```bash
mvn clean test
mvn package
```

The project uses JUnit 5 for automated tests.

## 3. Run the website locally

Build the application and prepare the runtime classpath:

```bash
mvn clean package
mvn dependency:build-classpath "-Dmdep.outputFile=target/runtime-classpath.txt" -DincludeScope=runtime
```

On Windows PowerShell:

```powershell
$runtime = Get-Content target/runtime-classpath.txt -Raw
java -cp "target/classes;$runtime" in.ps108.WebMain 8080 data/ps108-bookings.sqlite
```

On Linux or macOS:

```bash
runtime=$(cat target/runtime-classpath.txt)
java -cp "target/classes:$runtime" in.ps108.WebMain 8080 data/ps108-bookings.sqlite
```

Keep the server running and open:

- Customer website: http://localhost:8080
- Operations dashboard: http://localhost:8080/admin.html

If port 8080 is occupied, use another port, such as 18080, and open the corresponding URL.

Stop the server using `Ctrl+C`.

### Customer frontend

The customer page supports:

- Venue browsing
- Event date, slot and guest selection
- Vendor package selection
- Quote calculation
- Reservation creation
- Quote printing

### Operations dashboard

The admin dashboard supports:

- Venue and vendor registration
- Enquiry and hold creation
- Hold expiry and queue visibility
- Booking confirmation
- Advance schedules and simulated payments
- Late-payment checks
- Cancellation calculations
- Package swaps and date changes
- Post-event actuals and damages
- Final settlement
- Dispute management
- Payout calculations
- Printable reports

## 4. Run the console workflow

Run the standard application tests:

```bash
mvn test
mvn package
```

The command-line entry point is:

```text
in.ps108.Main
```

It accepts input files in this order:

1. Venues CSV
2. Vendors CSV
3. Commands file
4. Auspicious dates CSV
5. Damage rate card CSV
6. Vendor packages CSV

Example organizer sample invocation from the project root:

```powershell
& "C:\Program Files\JetBrains\IntelliJ IDEA Community Edition 2025.2.3\plugins\maven\lib\maven3\bin\mvn.cmd" exec:java "-Dexec.mainClass=in.ps108.Main" "-Dexec.args=organizer-input/T110-PS-108-sample-data/venues.csv organizer-input/T110-PS-108-sample-data/vendors.csv organizer-input/T110-PS-108-sample-data/commands.txt organizer-input/T110-PS-108-sample-data/auspicious_dates.csv organizer-input/T110-PS-108-sample-data/damage_rate_card.csv organizer-input/T110-PS-108-sample-data/vendor_packages.csv"
```

The organizer sample workflow should be considered verified only after it completes successfully and its generated outputs have been checked.

## 5. Included capabilities

- Responsive customer frontend and operations dashboard
- Java HTTP API using `in.ps108.WebMain`
- CSV master-data loading
- Deterministic command-file processing
- Booking holds and FIFO enquiry queues
- SQLite-backed booking and hold persistence
- Venue pricing, seasonal multipliers and second-slot discounts
- Catering minimum guarantee and excess-guest pricing
- Advance schedule generation
- Late fees and automatic cancellation
- Cancellation ladder and forfeiture cap
- Vendor-specific cancellation terms
- Package-switch fee calculation
- Date-change credit calculation and redemption
- Final bill and tax calculations
- Vendor and venue commission calculations
- Dispute ledger and payout eligibility
- Damage-code quantity charges
- Printable quotes and operations reports

## 6. Business-rule interpretations

The current implementation records the following interpretations:

1. Money is calculated using `BigDecimal` and rounded to two decimal places using HALF_UP at line-item boundaries.
2. Catering minimum guarantee is `max(ceil(quotedGuests * 0.80), 150)`.
3. Excess catering pricing applies only above quoted guests plus 10%.
4. Late fees count each started seven-day period after the five-day grace period.
5. General cancellation forfeiture is capped at paid advances plus ₹25,000.
6. At 60 days, the first two advance tranches merge. At seven days, the full amount is due immediately.
7. A free date change requires more than 90 days before the original event and a destination date no more than 180 days after the request date.
8. Date-change credit is tracked separately from cash advances and applied through `APPLY_CREDIT`.
9. Vendor-specific cancellation terms are calculated for their respective lines, with the general cap applied once to aggregate forfeiture.
10. Package swaps exactly 30 days before the event are free; the switch fee applies only when fewer than 30 days remain.
11. GST is calculated according to the implemented venue, catering and package tax rules. Tips are excluded.
12. The self-authored acceptance tests provide an additional local regression baseline.
13. The 48-hour dispute freeze window is measured from the recorded event-close timestamp.

See `decision-log.md` and the design document for the detailed assumptions and rationale.

## 7. Verification

Latest recorded automated test run:

**Date:** 2026-11-16

- `mvn clean test`: 74 tests passed
- Failures: 0
- Errors: 0
- Skipped: 0
- Build result: `BUILD SUCCESS`

This verifies the automated JUnit test suite. It does not, by itself, establish that every organizer sample command or browser workflow has passed end-to-end verification.

Additional checks include JavaScript syntax checks and customer-side money-formatting tests from the project's test files.

## 8. CSV master data

CSV files contain a header row followed by data rows.

The project uses:

- `data/venues.csv`
- `data/vendors.csv`
- `data/commands.txt`
- `data/auspicious-dates.csv`
- `data/damage-rate-card.csv`
- `vendor_packages.csv` or the configured package input file

The organizer sample inputs are located in:

`organizer-input/T110-PS-108-sample-data/`

Dates use ISO format (`yyyy-MM-dd`). Command timestamps use the format expected by the selected command-file format.

## 9. Demo limitations and known scope

- Payments are simulated; no real payment gateway is integrated.
- The application does not currently provide authentication.
- The admin dashboard is intended for local demonstration.
- Date-change credit is applied using the console `APPLY_CREDIT` command.
- Local sample and acceptance tests should not be represented as organizer-certified results unless those results have been independently verified.

See `docs/deferred-work.md` for remaining limitations and follow-up work.

## 10. Repository submission checklist

Before submission:

- Include source code and automated tests.
- Include input data and required sample files.
- Include README, design document and decision log.
- Include test report and deferred-work document.
- Verify the repository does not contain secrets or private customer information.
- Add the required five-minute demonstration video link.
- Confirm repository access permissions with the organizers.

