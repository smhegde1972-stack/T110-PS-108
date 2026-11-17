# PS-108 — Wedding and Event Venue Booking Engine

Java 17 implementation for the T110‑PS‑108 capstone, including a browser‑based customer frontend, an operations dashboard, and a Java HTTP server.

---

## 1. Requirements

- Java 17 or later
- Maven 3.8 or later
- SQLite JDBC (included through the Maven dependency in `pom.xml`)
- A modern web browser

---

## 2. Build and test

From the project root (the directory containing `pom.xml`):

```bash
mvn clean test   # runs the JUnit 5 test suite
mvn package      # creates a runnable JAR and the compiled classes
```

The test suite currently contains **74 JUnit tests** and all of them pass (`BUILD SUCCESS`). No claims are made about external organizer validation.

---

## 3. Run the website locally

Build the application and prepare the runtime classpath:

```bash
mvn clean package
mvn dependency:build-classpath "-Dmdep.outputFile=target/runtime-classpath.txt" -DincludeScope=runtime
```

### Windows PowerShell

```powershell
$runtime = Get-Content target/runtime-classpath.txt -Raw
java -cp "target/classes;$runtime" in.ps108.WebMain 8080 data/ps108‑bookings.sqlite
```

### Linux / macOS

```bash
runtime=$(cat target/runtime-classpath.txt)
java -cp "target/classes:$runtime" in.ps108.WebMain 8080 data/ps108‑bookings.sqlite
```

Keep the server running and open:

- Customer website: http://localhost:8080
- Operations dashboard: http://localhost:8080/admin.html

If port 8080 is occupied, use another port (e.g., 18080) and open the corresponding URL. Stop the server with **Ctrl+C**.

---

## 4. Command‑Line Interface (CLI)

The console entry point is `in.ps108.Main`. It expects six input files in the order shown below. The CLI processes the command file **strictly in file order** and never re‑orders timestamps.

```text
java -cp "target/classes;$runtime" in.ps108.Main \
    <venues.csv> \
    <vendors.csv> \
    <commands.txt> \
    <auspicious_dates.csv> \
    <damage_rate_card.csv> \
    <vendor_packages.csv>
```

Typical invocation with the organizer‑provided sample data (paths are relative to the project root):

```powershell
& "C:\Program Files\JetBrains\IntelliJ IDEA Community Edition 2025.2.3\plugins\maven\lib\maven3\bin\mvn.cmd" \
    exec:java \
    -Dexec.mainClass=in.ps108.Main \
    -Dexec.args="organizer-input/T110-PS-108-sample-data/venues.csv \
                 organizer-input/T110-PS-108-sample-data/vendors.csv \
                 organizer-input/T110-PS-108-sample-data/commands.txt \
                 organizer-input/T110-PS-108-sample-data/auspicious_dates.csv \
                 organizer-input/T110-PS-108-sample-data/damage_rate_card.csv \
                 organizer-input/T110-PS-108-sample-data/vendor_packages.csv"
```

The application will read the CSV master data, execute the 134 commands in `commands.txt`, and generate the four output CSV files described in the next section.

---

## 5. Organizer Input Files

| File | Description | Important columns / format |
|------|-------------|----------------------------|
| `venues.csv` | List of venue resources. | `venue_id`, `name`, `capacity`, `price_day`, `price_night`, `price_both` |
| `vendors.csv` | Vendor catalogue. | `vendor_id`, `name`, `type` |
| `vendor_packages.csv` | Packages offered by each vendor. | `package_id`, `vendor_id`, `description`, `price`, `slot` (`DAY`, `NIGHT`, `BOTH`) |
| `auspicious_dates.csv` | Dates that trigger a multiplier. | `date` (ISO `yyyy‑MM‑dd`), `multiplier` (decimal) |
| `damage_rate_card.csv` | Damage‑code rates for post‑event billing. | `code`, `description`, `rate_per_item` |
| `commands.txt` | Ordered list of organizer commands (134 lines). Each line follows the pattern `TIMESTAMP|COMMAND|ARG1|ARG2|…`. Timestamps use `yyyy‑MM‑dd HH:mm:ss`. Blank lines and lines starting with `#` are ignored.

All CSV files contain a header row, use commas as separators, and are UTF‑8 encoded.

---

## 6. Output Files (generated automatically)

| File | Purpose | Exact header (comma‑separated) |
|------|---------|--------------------------------|
| `quotes.csv` | Quote generated for every `ENQUIRY`. | `enquiry_id,status,venue_rental,catering,decor,photography,total_contract,tranche1_due,tranche1_amount,tranche2_due,tranche2_amount,tranche3_due,tranche3_amount` |
| `event_log.csv` | Chronological log of every command processed. | `line_no,enquiry_id,command,status,amount,message` |
| `cancellations.csv` | Details of every `CANCEL` command and forfeiture calculation. | `enquiry_id,cancel_date,days_before,venue_forfeit,catering_forfeit,decor_forfeit,photo_forfeit,total_forfeit,paid,cap_applied,refund_or_recoverable` |
| `settlements.csv` | Final settlement information for each event. | `enquiry_id,venue_amount,catering_amount,packages_amount,switch_fees,damages,gst,advances_paid,balance_due,venue_payout,catering_payout,decor_payout,photo_payout,frozen_vendor` |

The files are written to the working directory in the order shown above and use the exact column order required by the organizer.

---

## 7. SQLite persistence

State is persisted with SQLite via the `SqliteHoldStore` implementation. The store creates a single file (e.g., `data/ps108‑bookings.sqlite`) that holds tables for enquiries, holds, queues, payments, and settlements. All mutations (hold creation, confirmation, payment, cancellation, etc.) are written to this database, allowing the application to be stopped and restarted without losing business state.

---

## 8. Customer frontend

The customer page supports:

- Venue browsing
- Event date, slot and guest selection
- Vendor package selection
- Quote calculation
- Reservation creation
- Quote printing

---

## 9. Operations dashboard

The admin dashboard supports:

- Venue and vendor registration
- Enquiry and hold creation
- Hold expiry and queue visibility
- Booking confirmation
- Advance schedules and simulated payments
- Late‑payment checks
- Cancellation calculations
- Package swaps and date changes
- Post‑event actuals and damages
- Final settlement
- Dispute management
- Payout calculations
- Printable reports

---

## 10. Included capabilities

- Responsive customer frontend and operations dashboard
- Java HTTP API using `in.ps108.WebMain`
- CSV master‑data loading
- Deterministic command‑file processing
- Booking holds and FIFO enquiry queues
- SQLite‑backed booking and hold persistence
- Venue pricing, seasonal multipliers and second‑slot discounts
- Catering minimum guarantee and excess‑guest pricing
- Advance schedule generation
- Late fees and automatic cancellation
- Cancellation ladder and forfeiture cap
- Vendor‑specific cancellation terms
- Package‑switch fee calculation
- Date‑change credit calculation and redemption
- Final bill and tax calculations
- Vendor and venue commission calculations
- Dispute ledger and payout eligibility
- Damage‑code quantity charges
- Printable quotes and operations reports

---

## 11. Business‑rule interpretations

The current implementation records the following interpretations (see source code for exact formulas):

1. Money is calculated using `BigDecimal` and rounded to two decimal places using `HALF_UP` at line‑item boundaries.
2. Catering minimum guarantee is `max(ceil(quotedGuests * 0.80), 150)`.
3. Excess catering pricing applies only above quoted guests plus **10 %**.
4. Late fees count each started seven‑day period after the five‑day grace period.
5. General cancellation forfeiture is capped at paid advances plus ₹25,000.
6. At 60 days, the first two advance tranches merge; at seven days the full amount is due immediately.
7. A free date change requires more than 90 days before the original event and a destination date no more than 180 days after the request date.
8. Date‑change credit is tracked separately from cash advances and applied through the `APPLY_CREDIT` command.
9. Vendor‑specific cancellation terms are calculated for their respective lines, with the general cap applied once to aggregate forfeiture.
10. Package swaps exactly 30 days before the event are free; the swap fee applies only when fewer than 30 days remain.
11. GST is calculated according to the implemented venue, catering and package tax rules. Tips are excluded.
12. The self‑authored acceptance tests provide an additional local regression baseline.
13. The 48‑hour dispute freeze window is measured from the recorded event‑close timestamp.

---

## 12. Verification

**Latest recorded automated test run (2026‑11‑16):**

- `mvn clean test`: 74 tests passed, 0 failures, 0 errors, 0 skipped.
- Build result: `BUILD SUCCESS`

These results verify the internal JUnit test suite. They do **not** constitute a full organizer‑sample validation.

---

## 13. CSV master data

CSV files contain a header row followed by data rows. The project uses:

- `data/venues.csv`
- `data/vendors.csv`
- `data/commands.txt`
- `data/auspicious‑dates.csv`
- `data/damage‑rate‑card.csv`
- `vendor_packages.csv` (or the configured package input file)

The organizer sample inputs are located in `organizer-input/T110-PS-108-sample-data/`.

Dates use ISO format (`yyyy‑MM‑dd`). Command timestamps use the format expected by the selected command‑file format.

---

## 14. Demo limitations and known scope

- Payments are simulated; no real payment gateway is integrated.
- The application does not currently provide authentication.
- The admin dashboard is intended for local demonstration.
- Date‑change credit is applied using the console `APPLY_CREDIT` command.
- Local sample and acceptance tests should not be represented as organizer‑certified results unless those results have been independently verified.

---

## 15. Repository submission checklist

Before submission:

- Include source code and automated tests.
- Include input data and required sample files.
- Include README, design document and decision log.
- Include test report and deferred‑work document.
- Verify the repository does not contain secrets or private customer information.
- Add the required five‑minute demonstration video link.
- Confirm repository access permissions with the organizers.
