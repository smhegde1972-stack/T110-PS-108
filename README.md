# PS-108 — Wedding and Event Venue Booking Engine

Java 17 implementation for the T110-PS-108 capstone, including a local browser-based frontend and a lightweight Java HTTP server.

## Requirements
- Java 17+
- Maven 3.8+ (for JUnit tests). Booking holds and enquiry queues use the Xerial SQLite JDBC runtime dependency declared in `pom.xml`.

## Run the website locally

From a terminal in the project root (the folder containing `pom.xml` and `data`):
```bash
mvn clean package
mvn dependency:build-classpath "-Dmdep.outputFile=target/runtime-classpath.txt" -DincludeScope=runtime
$runtime = Get-Content target/runtime-classpath.txt -Raw
java -cp "target/classes;$runtime" in.ps108.WebMain 8080 data/ps108-bookings.sqlite
```
Keep the server process running, then open **http://localhost:8080** in your browser. Open **http://localhost:8080/admin.html** for the booking-operations dashboard. In IntelliJ, the same server can be started with an Application run configuration for `in.ps108.WebMain`, using the project root as working directory.

The customer page supports venue browsing, date/slot/guest selection, vendor package selection, quote calculation, reservation creation and quote printing. The operations dashboard (`/admin.html`) exposes venue/vendor registration, enquiry and 72-hour hold creation, hold expiry and queue visibility, confirmation, advance schedules, simulated payments, late-payment checks, cancellation calculations, package swaps, date changes, post-event actuals, final settlement, disputes, payout calculations, and printable reports.

**Important demo limits:** booking holds, queue positions and booking states are stored in `data/ps108-bookings.sqlite`, so they survive Java server restarts. Payments, disputes, cancellation result records and newly registered master data remain demo/session state. Payments are simulated; there is no authentication or real payment gateway. Date-change credit can be applied to a confirmed replacement using the CLI `APPLY_CREDIT` command. Local CLI output is checked against self-authored brief-based fixtures. Stop the Java process to shut the local site down.

If port 8080 is already in use, add `18080` to the Application run configuration's **Program arguments** and open `http://localhost:18080` instead.

## Build and run the console workflow
```bash
mvn test
mvn package
java -jar target/wedding-venue-engine-1.0.0.jar
```
The command-file entry point can also be run with:
```bash
java -cp target/classes in.ps108.Main data/venues.csv data/vendors.csv data/commands.txt
```

## Included capabilities
- Responsive customer frontend and operations dashboard (`src/main/resources/static`) with local Java HTTP API (`in.ps108.WebMain`)
- Browser forms for venue/vendor registration, enquiries/holds/queues, confirmation, simulated payments, cancellation, swaps, date changes, post-event actuals, settlement, disputes and payout calculation
- Printable quote and operations reports via browser print / Save as PDF
- Venue/vendor CSV loading with quoted commas, doubled quotes, and quoted multiline fields
- Deterministic command-file processing
- Hold expiry and FIFO queue promotion
- Venue pricing with max season multiplier and second-slot discount
- Catering minimum guarantee and excess-guest pricing
- Advance schedule generation
- Cancellation ladder, vendor line overrides, and forfeiture cap
- Package-switch fee calculation
- Final bill tax/advance calculations and vendor commission calculation
- Basic JUnit tests plus a dependency-free regression runner (`tools/StandaloneChecks.java`) for environments where Maven Central is unavailable
- Dispute ledger, payout eligibility after 7 days, and vendor/venue commission calculation
- Advance tranche ledger, late fees, and automatic cancellation after 21 days overdue
- Damage-code quantity charges loaded from `data/damage-rate-card.csv`
- Command-line quote output and date-change credit redemption against an unpaid replacement booking
- Commission rates from the design document: venue 12%, catering 8%, decor/photography 15%

## Project rule interpretations (recorded in `decision-log.md`)
1. All money is rounded to 2 decimals using HALF_UP at each final line-item boundary.
2. Catering minimum guarantee is `max(ceil(quotedGuests * 0.80), 150)`.
3. Excess pricing applies only to plates above quoted guests + 10%; the exact +10% boundary has no premium. The guaranteed quantity is still a minimum billed quantity.
4. Late fees count each started 7-day period after the 5-day grace: 1% starts on overdue day 6; the next 1% starts on day 13.
5. The general cancellation cap is `min(calculated forfeiture, paid advances + ₹25,000)`.
6. The 60-day and 7-day advance schedule boundaries are inclusive in the nearer-deadline rule: confirmation at 60 days merges the first two tranches; confirmation at 7 days makes the full amount due immediately.
7. A free date change requires more than 90 days before the original event and a new date no more than 180 days after the request date. Otherwise, cancellation applies and 50% of capped forfeiture becomes credit toward a replacement booking.
8. Date-change credit is tracked separately from cash advances and applied using `APPLY_CREDIT` before replacement-booking payments.
9. Vendor-specific cancellation terms are calculated for their lines; the general ladder applies to remaining contract value and the cap applies once to the aggregate.
10. Package swaps exactly 30 days before the event are free; a switch fee applies only when fewer than 30 days remain.
11. GST is 18% on venue and packages, 5% on catering without ITC; tips are excluded. Rate-card damages are added as a separate line.
12. The self-authored acceptance fixture and sample output are the local expected-output baseline; the project does not claim equivalence to organizer files that were not supplied.
13. Measure the 48-hour dispute freeze window from the recorded event-close timestamp.

## Verification without downloading JUnit
```bash
./tools/run-standalone-checks.sh
```
This compiles the application with `javac --release 17`, runs 18 dependency-free regression checks, and runs the sample command file. The JUnit 5 suite is also included for `mvn test` when dependencies are available.

## Latest verification

Latest prior verification: 2026-11-01, using IntelliJ's bundled Maven:

- `mvn clean test`: 59 tests passed, 0 failed, 0 errors, 0 skipped (includes timestamp ordering, concurrent hold integrity, JSON escaping, cancellation/refund boundaries, and retry safety).
- `mvn package`: successful; created `target/wedding-venue-engine-1.0.0.jar`; the same 59 tests passed during the package lifecycle.
- Java source/test compilation with `javac --release 17` succeeded.
- Standalone regression checks: 18 passed; the sample command workflow completed with tax-inclusive advance amounts.
- The packaged JAR output exactly matched `docs/sample-run-output.txt` (last verified 2026-10-28).
- `node --test src/test/js/customer-money.test.cjs`: 1 passed; the customer formatter displays API paise.
- `node --check` passed for both customer and operations scripts.
- Previously recorded live HTTP smoke (2026-10-22): customer/admin pages returned 200; quote, booking, rate-card actuals, settlement, dispute freeze/release, admin enquiry/hold, confirmation, schedule/payment, and first-payment queue clearing passed.
- Earlier HTTP smoke checks verified invalid date/state/commission inputs return 400 and a `1.005` payment is recorded as `1.01`.

Organizer-specific expected-output files are unavailable; the project uses its self-authored acceptance baseline instead. Late fees remain separately reported because the supplied final-bill formula excludes them. See [docs/requirements-audit.md](docs/requirements-audit.md) and [docs/deferred-work.md](docs/deferred-work.md).

Hold persistence update verification: `mvn package dependency:build-classpath "-Dmdep.outputFile=target/runtime-classpath.txt" -DincludeScope=runtime` on 2026-11-05 passed 65 tests and packaged the application. Coverage includes SQLite persistence, restart, FIFO promotion, post-event-state persistence, separate-slot behavior, cancelled queue entries and payment/expiry races. Customer and admin JavaScript syntax checks passed with Node.js.

## Commands
Each line in `data/commands.txt` is:
`TIMESTAMP|COMMAND|ARGUMENTS...`

Supported commands:
- `QUOTE|venueId|date|slot|guests|vendorId:package;vendorId:package`
- `ENQUIRE|enquiryId|client|venueId|date|slot|guests`
- `HOLD|enquiryId`
- `CONFIRM|bookingId|enquiryId|venueId|date|slot|guests|vendorId:package;vendorId:package`
- `PAY|bookingId|amount|paymentDate` (paymentDate is retained in the command file; command timestamp controls processing)
- `CANCEL|bookingId|cancelDate` (date is optional; defaults to command timestamp date)
- `SWAP|bookingId|vendorId|newPackage`
- `SETTLE|bookingId`
- `SCHEDULE|bookingId|asOfDate`
- `DATECHANGE|bookingId|requestDate|newDate|slot`
- `APPLY_CREDIT|cancelledSourceBookingId|confirmedReplacementBookingId` (apply available 50% date-change credit before payments on the replacement)
- `ACTUALS|bookingId|actualGuests|damageCode:quantity;damageCode:quantity` (`-` means no damages; codes/rates come from `data/damage-rate-card.csv`)
- `DISPUTE|disputeId|bookingId|vendorId|filedAt|eventClosedAt|reason`
- `RESOLVE_DISPUTE|disputeId`
- `PAYOUT|bookingId|vendorId|grossLineValue|eventClosedAt` (command timestamp is payout evaluation time)
- `PAYOUT_VENUE|bookingId|venueId|grossLineValue|eventClosedAt` (command timestamp is payout evaluation time)

Slots are `DAY`, `NIGHT`, or `DAY+NIGHT`. Vendors are referenced by their CSV IDs. Dates use ISO format (`yyyy-MM-dd`), timestamps use `yyyy-MM-dd'T'HH:mm:ss`.

### CSV master data
CSV files use a header followed by data rows. The exact ordered columns and examples are documented in [docs/design-document.md](docs/design-document.md#41-csv-input-formats). Package lists and venue slots use semicolons inside their field; quote such a CSV field if it contains a comma. The loader accepts standard quoted commas, doubled quotes, and quoted multiline fields. `data/auspicious-dates.csv` and `data/damage-rate-card.csv` are loaded from the project data directory.

## Repository submission checklist
- Keep source, tests, data, README, design PDF, and decision log in the repository.
- Never commit secrets or private customer data.
- Check whether organizers accept a private repository and grant access if needed.

The capstone email requires a 5-minute demo video link in the final repository. The project uses self-authored brief-based checks as its local acceptance baseline; organizer-specific fixtures were not supplied and are not claimed to have been matched. See [docs/self-authored-acceptance-checks.md](docs/self-authored-acceptance-checks.md) and [docs/requirements-audit.md](docs/requirements-audit.md).

See [docs/requirements-audit.md](docs/requirements-audit.md) for the current requirements traceability, known gaps, and items awaiting the official organizer brief.
