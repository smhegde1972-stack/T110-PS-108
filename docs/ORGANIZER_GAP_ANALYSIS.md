# PS-108 Project — Complete Gap Analysis Against Official Organizer Specification

**Document Version:** 1.0.0  
**Audit Date:** 2026-11-15  
**Target Repository:** `T110-PS-108` (`d:\T110-PS-108-with-frontend-updated1\T110-PS-108`)  
**Auditor:** Antigravity AI Engine (Technical Gap Analysis)  
**Audit Scope:** Full source code, test suites, input CSV handling, domain models, command processing engine, SQLite persistence layer, output generation, and web frontend/admin dashboard against the official organizer requirements.

---

## 1. Executive Summary

A comprehensive, rigorous technical audit of the **T110-PS-108 Wedding and Event Venue Booking Engine** was conducted against the official organizer specification and sample dataset requirements.

### Key Finding
The core business logic (venue pricing, seasonal/auspicious multipliers, catering guarantees and tiered overages, advance payment schedules, cancellation ladder with vendor overrides and aggregate cap, dispute freezes, damage assessments, and final settlement) is **exceptionally well-modeled, mathematically precise, and thoroughly tested** in isolation. The application also contains a complete local web frontend and operations dashboard with SQLite-backed durability for holds and queues.

However, the application was built and validated exclusively against **self-authored acceptance fixtures** (as documented in `decision-log.md` DL-13 and `docs/requirements-audit.md`), rather than the official organizer specification. Consequently, **the existing application cannot run the organizer's command file or pass automated evaluation** due to critical structural, schema, and interface incompatibilities:

1. **Required Output CSV Files Completely Missing (0/4 Implemented):**  
   The organizer evaluation harness requires the automatic generation of four CSV files: `quotes.csv`, `event_log.csv`, `cancellations.csv`, and `settlements.csv`. Currently, `Main.java` only writes arbitrary human-readable text strings to `System.out`. No CSV output engine exists in the codebase.
2. **Missing `vendor_packages.csv` Support:**  
   The organizer splits vendor data into `vendors.csv` (20 vendors) and a separate `vendor_packages.csv` (60 packages). The existing `CsvLoader.java` and `Vendor.java` expect vendor packages to be embedded directly as inline string pairs inside `vendors.csv` (e.g., `SILVER:900;GOLD:1400`). There is no loader or data structure to ingest `vendor_packages.csv`.
3. **Command Name and Syntax Discrepancies:**  
   Organizer commands fail with `IllegalArgumentException: Unknown command ...` because existing command names differ:
   - Organizer `ENQUIRY` vs Existing `ENQUIRE`
   - Organizer `PAYMENT` vs Existing `PAY`
   - Organizer `DATE_CHANGE` vs Existing `DATECHANGE`
   - Organizer `SWAP_PACKAGE` vs Existing `SWAP`
   - Organizer `VENUE_STATEMENT` is completely missing.
4. **Timestamp Parsing Incompatibility:**  
   The organizer dataset supplies timestamps in format `YYYY-MM-DD HH:MM:SS` (space separator). The existing `Main.java` uses `LocalDateTime.parse(p[0])` without a formatter, which strictly requires ISO-8601 `YYYY-MM-DDTHH:MM:SS` (letter `T`), immediately crashing with `DateTimeParseException`.
5. **Slot Syntax (`BOTH` vs `DAY_AND_NIGHT`):**  
   `Slot.parse` only accepts `DAY`, `NIGHT`, `DAY+NIGHT`, and `DAY_AND_NIGHT`. It throws `IllegalArgumentException` on the organizer's `BOTH` slot token.
6. **Command Processing Order:**  
   `Main.java` explicitly re-sorts command lines chronologically by timestamp (`commands.sort(...)`), whereas the organizer requires strict preservation of file order.
7. **Input Filename Discrepancies:**  
   Existing code expects hyphenated filenames (`auspicious-dates.csv`, `damage-rate-card.csv`), whereas the organizer supplies snake_case (`auspicious_dates.csv`, `damage_rate_card.csv`).

---

## 2. Existing Application Architecture

The existing application is structured as a Maven Java 17 project following clean separation of concerns:

```
in.ps108
├── Main.java               # CLI entry point for processing pipe-delimited commands
├── WebMain.java            # Embedded Java HTTP server (com.sun.net.httpserver) & REST API
├── model/
│   ├── Booking.java        # State machine and mutable booking state container
│   ├── Quote.java          # Final bill and quote calculation results record
│   ├── Slot.java           # Slot enum (DAY, NIGHT, DAY_AND_NIGHT)
│   ├── Vendor.java         # Vendor record with categories (CATERING, DECOR, PHOTOGRAPHY)
│   └── Venue.java          # Venue entity (capacity, base rates, supported slots)
└── service/
    ├── AdvanceLedger.java  # Payment tracking, tranche allocation, and auto-cancel state
    ├── AdvanceService.java # Tranche schedule generation (25/50/25, 75/25, 100%) and late fees
    ├── BookingEngine.java  # Core engine managing enquiries, holds (72h), FIFO queues, conflicts
    ├── CancellationService.java # Ladder, vendor overrides, and cap (paid + 25k)
    ├── CsvLoader.java      # RFC-4180 CSV reader for venues, vendors, dates, damage rates
    ├── DamageService.java  # Damage card calculation for actuals
    ├── DisputeLedger.java  # Dispute logging and 48-hour event close freeze logic
    ├── Money.java          # BigDecimal HALF_UP 2-decimal arithmetic helper
    ├── PayoutService.java  # Vendor & venue commission and payout schedule calculations
    ├── PricingService.java # Seasonal/auspicious multipliers, slot discounts, catering math
    ├── SettlementService.java # Tax calculations (18% / 5%) and final invoicing
    └── SqliteHoldStore.java   # Durable SQLite transaction store for holds and queues
```

---

## 3. Input File Compatibility Table

The organizer provides six standardized input files. Below is the detailed compatibility analysis:

| Input File | Organizer Format & Schema | Existing Implementation & Status | Compatibility Gaps & Code References |
|---|---|---|---|
| `venues.csv` (8 venues) | `venue_id,name,capacity,weekday_base,slots` (slots contains `DAY`, `NIGHT`, `BOTH`) | `CsvLoader.venues(Path)`<br>**PARTIALLY IMPLEMENTED** | 1. Header validation is skipped by index 0 check.<br>2. `Slot.parse()` at [Slot.java#L7-L13](file:///d:/T110-PS-108-with-frontend-updated1/T110-PS-108/src/main/java/in/ps108/model/Slot.java#L7-L13) rejects `BOTH` with `IllegalArgumentException`. Needs mapping `BOTH -> DAY_AND_NIGHT`. |
| `vendors.csv` (20 vendors) | `vendor_id,name,category,commission_rate` | `CsvLoader.vendors(Path)`<br>**PARTIALLY IMPLEMENTED** | 1. Existing `CsvLoader.vendors` expects 5 columns with embedded `packages` in column index 3 ([CsvLoader.java#L32-L47](file:///d:/T110-PS-108-with-frontend-updated1/T110-PS-108/src/main/java/in/ps108/service/CsvLoader.java#L32-L47)).<br>2. `Vendor` record requires non-empty packages map ([Vendor.java#L15](file:///d:/T110-PS-108-with-frontend-updated1/T110-PS-108/src/main/java/in/ps108/model/Vendor.java#L15)).<br>3. Organizer provides 4 columns without embedded packages. Crashes on column count check. |
| `vendor_packages.csv` (60 packages) | `package_id,vendor_id,package_name,price` | None<br>**MISSING** | Completely unhandled. No method in `CsvLoader.java`, no CLI argument in `Main.java`, and no model represents standalone vendor packages. Must be loaded and linked to `Vendor` entities. |
| `auspicious_dates.csv` (12 dates) | `date` (`YYYY-MM-DD`) | `CsvLoader.auspiciousDates(Path)`<br>**PARTIALLY IMPLEMENTED** | Parsing logic works ([CsvLoader.java#L51-L58](file:///d:/T110-PS-108-with-frontend-updated1/T110-PS-108/src/main/java/in/ps108/service/CsvLoader.java#L51-L58)), but filename defaults to `auspicious-dates.csv` (hyphen) in `Main.java` line 18 and `WebMain.java` line 55 instead of `auspicious_dates.csv`. |
| `damage_rate_card.csv` (4 damage items) | `damage_code,description,rate` | `CsvLoader.damageRates(Path)`<br>**PARTIALLY IMPLEMENTED** | Parsing logic works ([CsvLoader.java#L60-L70](file:///d:/T110-PS-108-with-frontend-updated1/T110-PS-108/src/main/java/in/ps108/service/CsvLoader.java#L60-L70)), but filename defaults to `damage-rate-card.csv` in `Main.java` line 19 and `WebMain.java` line 54 instead of `damage_rate_card.csv`. |
| `commands.txt` (134 command lines) | `TIMESTAMP\|COMMAND\|ARGS...`<br>Timestamp format: `YYYY-MM-DD HH:MM:SS` | `Main.java` line 34-47<br>**PARTIALLY IMPLEMENTED** | 1. Line 42 uses `LocalDateTime.parse(p[0])` which throws `DateTimeParseException` when timestamps use spaces instead of `T`.<br>2. Line 47 sorts commands chronologically, violating organizer's file-order requirement.<br>3. Command verbs differ significantly. |

### Technical Details on Parsing
- **RFC 4180 Quoting:** `CsvLoader.dataRows` ([CsvLoader.java#L76-L124](file:///d:/T110-PS-108-with-frontend-updated1/T110-PS-108/src/main/java/in/ps108/service/CsvLoader.java#L76-L124)) fully supports quoted commas, doubled quotes (`""`), and multiline fields.
- **Blank Lines and Comments:** `CsvLoader.addDataRow` correctly ignores lines starting with `#` and blank rows. `Main.java` line 38 also skips empty lines and `#` comments.
- **Decimals and Percentages:** `BigDecimal` is used throughout (`Money.java`). However, commission rates in `Vendor.java` line 27 require strict values (`0.08` and `0.15`); percentage notations like `8%` or `15%` must be handled if present in organizer files.

---

## 4. Command Compatibility Table

The organizer defines 10 core command types. The table below outlines how each command maps to the current implementation in `Main.java`:

| Organizer Command | Current Implementation Status | Current Command in Main.java | Gaps, Mismatches & Technical Blockers | Code Location |
|---|---|---|---|---|
| `ENQUIRY` | **PARTIALLY IMPLEMENTED** | `ENQUIRE` | 1. Verb mismatch (`ENQUIRY` vs `ENQUIRE`).<br>2. Current `ENQUIRE` takes `enquiryId\|client\|venueId\|date\|slot\|guests` without packages.<br>3. Organizer `ENQUIRY` expects a quote to be calculated and output to `quotes.csv`. | [Main.java#L66-L69](file:///d:/T110-PS-108-with-frontend-updated1/T110-PS-108/src/main/java/in/ps108/Main.java#L66-L69) |
| `CONFIRM` | **PARTIALLY IMPLEMENTED** | `CONFIRM` | 1. Verb matches.<br>2. Existing requires: `CONFIRM\|bookingId\|enquiryId\|venueId\|date\|slot\|guests\|packages`.<br>3. In organizer flow, `CONFIRM` operates by `enquiry_id` (not requiring repeated venue/date/slot/guests). | [Main.java#L71-L79](file:///d:/T110-PS-108-with-frontend-updated1/T110-PS-108/src/main/java/in/ps108/Main.java#L71-L79) |
| `PAYMENT` | **PARTIALLY IMPLEMENTED** | `PAY` | 1. Verb mismatch (`PAYMENT` vs `PAY`).<br>2. Current `PAY` takes `PAY\|bookingId\|amount\|date`.<br>3. Organizer uses `PAYMENT\|enquiry_id\|amount` (or booking_id). | [Main.java#L80-L86](file:///d:/T110-PS-108-with-frontend-updated1/T110-PS-108/src/main/java/in/ps108/Main.java#L80-L86) |
| `CANCEL` | **PARTIALLY IMPLEMENTED** | `CANCEL` | 1. Verb matches.<br>2. Takes `CANCEL\|bookingId\|cancelDate`.<br>3. Must write row to `cancellations.csv` and log to `event_log.csv`. Currently only writes stdout. | [Main.java#L113-L121](file:///d:/T110-PS-108-with-frontend-updated1/T110-PS-108/src/main/java/in/ps108/Main.java#L113-L121) |
| `DATE_CHANGE` | **PARTIALLY IMPLEMENTED** | `DATECHANGE` | 1. Verb mismatch (`DATE_CHANGE` vs `DATECHANGE`).<br>2. Current takes `DATECHANGE\|bookingId\|requestDate\|newDate\|newSlot`.<br>3. Fallback cancellation must output to `cancellations.csv`. | [Main.java#L122-L132](file:///d:/T110-PS-108-with-frontend-updated1/T110-PS-108/src/main/java/in/ps108/Main.java#L122-L132) |
| `SWAP_PACKAGE` | **PARTIALLY IMPLEMENTED** | `SWAP` | 1. Verb mismatch (`SWAP_PACKAGE` vs `SWAP`).<br>2. Current takes `SWAP\|bookingId\|vendorId\|newPackage`.<br>3. Must log to `event_log.csv`. | [Main.java#L133](file:///d:/T110-PS-108-with-frontend-updated1/T110-PS-108/src/main/java/in/ps108/Main.java#L133) |
| `ACTUALS` | **PARTIALLY IMPLEMENTED** | `ACTUALS` | 1. Verb matches.<br>2. Current takes `ACTUALS\|bookingId\|actualGuests\|damages`.<br>3. Damages parsed via `DamageService.parse` (supports `CODE:QTY` pairs).<br>4. Must log to `event_log.csv`. | [Main.java#L134-L143](file:///d:/T110-PS-108-with-frontend-updated1/T110-PS-108/src/main/java/in/ps108/Main.java#L134-L143) |
| `DISPUTE` | **PARTIALLY IMPLEMENTED** | `DISPUTE` | 1. Verb matches.<br>2. Current takes `DISPUTE\|disputeId\|bookingId\|vendorId\|filedAt\|closedAt\|reason`.<br>3. Organizer format uses `DISPUTE\|enquiry_id\|vendor_id\|reason` (timestamps derived from event context). | [Main.java#L144-L149](file:///d:/T110-PS-108-with-frontend-updated1/T110-PS-108/src/main/java/in/ps108/Main.java#L144-L149) |
| `SETTLE` | **PARTIALLY IMPLEMENTED** | `SETTLE` | 1. Verb matches.<br>2. Current takes `SETTLE\|bookingId`.<br>3. Must generate row in `settlements.csv` with 14 exact columns. Currently only prints summary string to stdout. | [Main.java#L164-L175](file:///d:/T110-PS-108-with-frontend-updated1/T110-PS-108/src/main/java/in/ps108/Main.java#L164-L175) |
| `VENUE_STATEMENT` | **MISSING** | None | Not implemented in `Main.java`. Currently throws `IllegalArgumentException: Unknown command VENUE_STATEMENT`. | None |

---

## 5. Business Rule Implementation and Test Coverage Table

Audit of the 15 critical business scenarios specified by the organizer:

| # | Business Scenario | Implementation Status | Implementation Class & Method | Test Coverage & Evidence | Boundary Evaluation Notes |
|---|---|---|---|---|---|
| 1 | Hold expiration at exactly 72 hours | **IMPLEMENTED** | `BookingEngine.expireHoldsInternal`<br>([BookingEngine.java#L105-L126](file:///d:/T110-PS-108-with-frontend-updated1/T110-PS-108/src/main/java/in/ps108/service/BookingEngine.java#L105-L126)) | `BookingEngineTest#enquiryArrivingAtExactHoldExpiryReceivesPromotedHold`<br>`BookingEnginePersistenceTest#holdQueueAndTimestampsSurviveRestartAndExpiryPromotesFifo`<br>`StandaloneChecks.java#L37` | Exact boundary checked: `holdExpiresAt = createdAt.plusHours(72)`. Releases hold if `!b.holdExpiresAt().isAfter(now)`. |
| 2 | Queue promotion after hold expiration | **IMPLEMENTED** | `BookingEngine.promoteNext`<br>([BookingEngine.java#L128-L138](file:///d:/T110-PS-108-with-frontend-updated1/T110-PS-108/src/main/java/in/ps108/service/BookingEngine.java#L128-L138)) | `BookingEngineTest#cancellingHeldBookingShouldPromoteNextEnquiry`<br>`StandaloneChecks.java#L37` | Enforces FIFO order across atomic slot keys. Verified under multi-threaded concurrency (24 threads). |
| 3 | Weekend & auspicious-date multiplier without stacking | **IMPLEMENTED** | `PricingService.seasonMultiplier`<br>([PricingService.java#L22-L29](file:///d:/T110-PS-108-with-frontend-updated1/T110-PS-108/src/main/java/in/ps108/service/PricingService.java#L22-L29)) | `PricingServiceTest#auspiciousWeekendUsesMaximumNotStackedMultipliers`<br>`StandaloneChecks.java#L11` | Weekend is 1.3×; auspicious date uses `multiplier.max(1.6)`. Does not multiply (1.3 × 1.6 = 2.08 is rejected). |
| 4 | Confirmation exactly 60 days before event | **IMPLEMENTED** | `AdvanceService.schedule`<br>([AdvanceService.java#L17-L19](file:///d:/T110-PS-108-with-frontend-updated1/T110-PS-108/src/main/java/in/ps108/service/AdvanceService.java#L17-L19)) | `AdvanceServiceTest#exactlySixtyDaysMergesFirstTwoTranches`<br>`StandaloneChecks.java#L22` | Boundary condition `days <= 60` merges Tranche 1 (25%) & Tranche 2 (50%) into 75% due at confirmation. |
| 5 | BOTH-slot second-slot discount | **PARTIALLY IMPLEMENTED** | `PricingService.venueRentalBreakdown`<br>([PricingService.java#L41-50](file:///d:/T110-PS-108-with-frontend-updated1/T110-PS-108/src/main/java/in/ps108/service/PricingService.java#L41-L50)) | `PricingServiceTest#secondSlotDiscountAppliesToSecondSlot`<br>`StandaloneChecks.java#L16` | **Math is correct:** 10% discount applied to slot index 1 (second slot).<br>**Token gap:** `Slot.java` only recognizes `DAY_AND_NIGHT` and `DAY+NIGHT`. Token `BOTH` causes parse failure. |
| 6 | Actual guests exactly 10% above quoted guests | **IMPLEMENTED** | `PricingService.catering`<br>([PricingService.java#L76-L85](file:///d:/T110-PS-108-with-frontend-updated1/T110-PS-108/src/main/java/in/ps108/service/PricingService.java#L76-L85)) | `PricingServiceTest#exactTenPercentDoesNotUsePremiumRate`<br>`StandaloneChecks.java#L21` | Premium threshold: `quoted * 110L / 100L`. Tested: 200 quoted, 220 actual = ₹308,000 (no uplift); 221 actual = ₹309,680 (1.2× on 1 plate). |
| 7 | Cancellation exactly 90 days before event | **IMPLEMENTED** | `CancellationService.calculate`<br>([CancellationService.java#L46-L50](file:///d:/T110-PS-108-with-frontend-updated1/T110-PS-108/src/main/java/in/ps108/service/CancellationService.java#L46-L50)) | `CancellationServiceTest#cancellationAtExactlyNinetyDaysUsesFiftyPercentLadder`<br>`CancellationServiceTest#cancellationLadderUsesEachExactBandBoundary` | Ladder check: `days >= 91 ? 25% : days >= 31 ? 50%`. At 90 days, correctly yields 50%. Tested all boundaries (181, 180, 91, 90, 31, 30, 8, 7). |
| 8 | Date change beyond 180-day limit | **IMPLEMENTED** | `BookingEngine.eligibleFreeDateChange`<br>([BookingEngine.java#L269-L272](file:///d:/T110-PS-108-with-frontend-updated1/T110-PS-108/src/main/java/in/ps108/service/BookingEngine.java#L269-L272)) | `StandaloneChecks.java#L55`<br>`MainWorkflowTest#dateChangeCreditCanBeAppliedToReplacementBeforePayments` | Requires `destination <= 180` and `before > 90`. If destination > 180, treated as cancellation, generating 50% capped forfeiture credit for rebooking. |
| 9 | Cancellation forfeiture cap: paid + ₹25,000 | **IMPLEMENTED** | `CancellationService.calculate`<br>([CancellationService.java#L71-L72](file:///d:/T110-PS-108-with-frontend-updated1/T110-PS-108/src/main/java/in/ps108/service/CancellationService.java#L71-L72)) | `CancellationServiceTest#capIsPaidAdvancesPlusTwentyFiveThousand`<br>`StandaloneChecks.java#L73` | Cap = `paid + 25000`. Capped forfeiture = `min(gross, cap)`. Refund = `max(0, paid - capped)`. Recoverable = `max(0, capped - paid)`. |
| 10 | Automatic cancellation after 21 days overdue | **IMPLEMENTED** | `AdvanceService.autoCancel`<br>([AdvanceService.java#L37](file:///d:/T110-PS-108-with-frontend-updated1/T110-PS-108/src/main/java/in/ps108/service/AdvanceService.java#L37)), `Main.java#L57-L63` | `AdvanceServiceTest#autoCancelAtTwentyOneDaysOverdue`<br>`MainWorkflowTest#overdueAdvanceAutoCancelsWhenItReachesTwentyOneDaysOnEventDay`<br>`StandaloneChecks.java#L30` | Checks `ChronoUnit.DAYS.between(dueDate, asOfDate) >= 21`. Evaluated before each command in `Main.java` and triggers cancellation ladder. |
| 11 | Reject bookings exceeding venue capacity | **IMPLEMENTED** | `BookingEngine.enquireInternal`<br>([BookingEngine.java#L83](file:///d:/T110-PS-108-with-frontend-updated1/T110-PS-108/src/main/java/in/ps108/service/BookingEngine.java#L83)), `confirmInternal#L156` | `WebMainTest#customerQuoteAndAdminBookingExposeIdenticalVenueRentalBreakdown` | Rejects with `IllegalArgumentException: Guests exceed venue capacity`. Validated in CLI and Web API. |
| 12 | Reject duplicate confirmations | **IMPLEMENTED** | `BookingEngine.confirmInternal`<br>([BookingEngine.java#L167-L173](file:///d:/T110-PS-108-with-frontend-updated1/T110-PS-108/src/main/java/in/ps108/service/BookingEngine.java#L167-L173)) | `BookingEnginePersistenceTest#firstAdvanceAtomicallyConfirmsAndCancelsQueueAcrossRestart` | Checks `bookings.containsKey(bookingId)` and `confirmedSlots.contains(key)`. Throws `IllegalStateException: Slot already confirmed`. |
| 13 | Late second-tranche fee after 5-day grace period | **IMPLEMENTED** | `AdvanceService.lateFee`<br>([AdvanceService.java#L28-L33](file:///d:/T110-PS-108-with-frontend-updated1/T110-PS-108/src/main/java/in/ps108/service/AdvanceService.java#L28-L33)) | `AdvanceServiceTest#startedWeekLateFeeBoundariesAfterFiveDayGrace`<br>`StandaloneChecks.java#L31` | Days 0-5 overdue: ₹0. Day 6 (first started 7-day period): 1%. Day 12: 1%. Day 13: 2%. Fully verified. |
| 14 | Package switch fee when <30 days remain | **IMPLEMENTED** | `BookingEngine.swapPackageInternal`<br>([BookingEngine.java#L264-L267](file:///d:/T110-PS-108-with-frontend-updated1/T110-PS-108/src/main/java/in/ps108/service/BookingEngine.java#L264-L267)) | `StandaloneChecks.java#L64` | `days < 30 ? 10% * max(oldPrice, newPrice) : 0`. At exactly 30 days remaining, fee is ₹0. Under 30 days, 10% fee charged. |
| 15 | Vendor payout freeze when dispute filed within 48h of event closure | **IMPLEMENTED** | `PayoutService.calculateAtRate`<br>([PayoutService.java#L43-L46](file:///d:/T110-PS-108-with-frontend-updated1/T110-PS-108/src/main/java/in/ps108/service/PayoutService.java#L43-L46)), `DisputeLedger.java#L18` | `PayoutServiceTest#openDisputeWithinFortyEightHoursFreezesPayout`<br>`PayoutServiceTest#disputeJustOverFortyEightHoursDoesNotFreezePayout`<br>`StandaloneChecks.java#L46` | Measured from event close timestamp. Within 48 hours marks dispute open and freezes matching vendor payout (`FROZEN_DISPUTE`). Payout eligibility is at event close + 7 days. |

---

## 6. Required Output CSV Status

The organizer evaluation requires the automatic emission of four CSV files. Below is the exact status of each:

### 1. `quotes.csv` — **MISSING** (0% Implemented)
- **Required Columns:**  
  `enquiry_id,status,venue_rental,catering,decor,photography,total_contract,tranche1_due,tranche1_amount,tranche2_due,tranche2_amount,tranche3_due,tranche3_amount`
- **Output Trigger:** One row emitted per `ENQUIRY` in strict file order.
- **Current State:** `Main.java` line 65 has a `printQuote` helper that outputs raw text to `System.out`. No CSV output is generated.
- **Missing Elements:**
  - Logic to format each enquiry quote into CSV.
  - Column breakdown separating catering, decor, and photography.
  - Advance schedule tranche dates and amounts (with empty values or `0.00` if tranches are merged).

### 2. `event_log.csv` — **MISSING** (0% Implemented)
- **Required Columns:**  
  `line_no,enquiry_id,command,status,amount,message`
- **Output Trigger:** One row per non-ENQUIRY command in file order, plus automatic hold-expiry and auto-cancellation events tagged `AUTO`.
- **Current State:** `Main.java` prints ad-hoc strings like `CONFIRMED B001 | venue=...` and `PAYMENT B001 ...` to `System.out`.
- **Missing Elements:**
  - Sequential execution logging to CSV.
  - Recording `line_no` referencing the source command file line.
  - Auto-generated events tagged with `AUTO`.

### 3. `cancellations.csv` — **MISSING** (0% Implemented)
- **Required Columns:**  
  `enquiry_id,cancel_date,days_before,venue_forfeit,catering_forfeit,decor_forfeit,photo_forfeit,total_forfeit,paid,cap_applied,refund_or_recoverable`
- **Output Trigger:** One row per manual cancellation, automatic overdue cancellation, or date-change cancellation fallback.
- **Current State:** `CancellationService.java` calculates all required mathematical figures, but `Main.java` prints a text breakdown to stdout and discards the structured result.
- **Missing Elements:**
  - CSV writer capturing every cancellation event.
  - Category-by-category forfeiture breakdown columns (`venue_forfeit`, `catering_forfeit`, `decor_forfeit`, `photo_forfeit`).
  - Boolean / text indicator for `cap_applied` (e.g. `TRUE`/`FALSE`).

### 4. `settlements.csv` — **MISSING** (0% Implemented)
- **Required Columns:**  
  `enquiry_id,venue_amount,catering_amount,packages_amount,switch_fees,damages,gst,advances_paid,balance_due,venue_payout,catering_payout,decor_payout,photo_payout,frozen_vendor`
- **Output Trigger:** One row per `SETTLE` command.
- **Special Formatting Rule:** Must output `NONE` for `frozen_vendor` when no dispute exists.
- **Current State:** `SettlementService.java` and `PayoutService.java` calculate all required values, but `Main.java` line 174 prints a single formatted text line to `System.out`.
- **Missing Elements:**
  - Aggregation of venue, vendor payouts, and dispute freeze status into this 14-column CSV schema.
  - Outputting `NONE` when no dispute is active.

---

## 7. Website Audit

The application includes a rich web interface running via `in.ps108.WebMain` serving static assets from `src/main/resources/static`.

### Customer Website (`index.html`, `app.js`, `styles.css`)

| Feature | Audit Status | Implementation Mechanism | Functional Assessment |
|---|---|---|---|
| Venue Listing & Images | **IMPLEMENTED** | `app.js` `renderVenues()` ([app.js#L47-L67](file:///d:/T110-PS-108-with-frontend-updated1/T110-PS-108/src/main/resources/static/app.js#L47-L67)) | **Functional.** Loads live venues from `/api/venues`. Displays capacity, slots, base weekday price, and Unsplash curated photography. |
| Venue Capacity, Location, Rates | **IMPLEMENTED** | `index.html` `#venue-grid`, `app.js` | **Functional.** Capacity constraints are dynamically set on guest count input (`guests.max = venue.capacity`). |
| DAY/NIGHT Availability Check | **IMPLEMENTED** | `app.js` `refreshAvailability()` via `/api/availability` | **Functional.** Probes slot availability (returns `AVAILABLE`, `ON_HOLD`, or `BOOKED`). |
| Vendor Categories & Packages | **IMPLEMENTED** | `app.js` `renderPackages()` ([app.js#L69-L74](file:///d:/T110-PS-108-with-frontend-updated1/T110-PS-108/src/main/resources/static/app.js#L69-L74)) | **Functional.** Loads from `/api/vendors`. Groups by category (Catering, Decor, Photography). |
| Package Prices | **IMPLEMENTED** | `app.js` `renderPackages()` | **Functional.** Shows package prices in INR; appends `/ plate` for catering packages. |
| Customer Enquiry & Booking | **IMPLEMENTED** | `app.js` `#booking-form` submit to `/api/bookings` | **Functional.** Creates enquiry and temporary 72-hour hold or enters FIFO queue. |
| Guest Count & Date Selection | **IMPLEMENTED** | `index.html` `#date`, `#slot`, `#guests` | **Functional.** Validates input bounds against venue capacity. |
| Instant Quote Estimation | **IMPLEMENTED** | `app.js` `fetchQuote()` to `/api/quote` | **Functional.** Generates dynamic estimate with seasonal multiplier, 2-slot discount, catering guarantee, and GST. |
| Booking Confirmation | **PARTIALLY IMPLEMENTED** | `index.html` | **Workflow Boundary.** Customer site creates the reservation enquiry and 72-hour hold. First-advance confirmation is executed via the operations dashboard or API. |
| Advance Payment Tracking | **PARTIALLY IMPLEMENTED** | Customer UI does not expose schedule | **Visual/Internal.** Customers see total estimate and balance due, but the full 3-tranche schedule table is only on `/admin.html`. |
| Cancellation & Refund Review | **IMPLEMENTED** | `index.html` `#cancel-booking`, `app.js#L30-L45` | **Functional.** Dedicated customer section allowing cancellation quote and processing by booking ID. Shows breakdown, capped forfeiture, and refund eligibility. |
| Date Changes & Package Swaps | **NOT PRESENT ON CUSTOMER SITE** | Restricted to operations | By design, customer site is focused on booking and cancellation. Swaps and date changes are handled in the admin portal. |

---

## 8. Admin Dashboard Audit

The admin dashboard at `/admin.html` (`admin.js`, `admin.css`) provides comprehensive operations management:

| Admin Feature | Audit Status | Implementation Mechanism | Functional Assessment |
|---|---|---|---|
| Enquiry & 72-Hour Hold Creation | **IMPLEMENTED** | `admin.js` `#enquiry-form` -> `/api/enquiries` | **Functional.** Submits enquiry, calculates hold expiry, and updates table. |
| Confirm Hold with Advance Payment | **IMPLEMENTED** | `admin.js` `#confirm-form` -> `/api/confirm` | **Functional.** Atomically verifies hold, records advance payment, confirms booking, and clears conflicting queues. |
| Live Bookings, Holds & Queue Monitor | **IMPLEMENTED** | `admin.js` `renderBookings()` ([admin.js#L32-L36](file:///d:/T110-PS-108-with-frontend-updated1/T110-PS-108/src/main/resources/static/admin.js#L32-L36)) | **Functional.** Displays tabular register of active bookings, hold expiry timestamps, payments, and FIFO queue positions. |
| Advance Schedule & Payment Recording | **IMPLEMENTED** | `admin.js` `#schedule-form` -> `/api/advance-schedule`, `/api/pay` | **Functional.** Displays tranches, calculates late fees after 5-day grace, detects 21-day overdue auto-cancellation. |
| Cancellation Ladder Execution | **IMPLEMENTED** | `admin.js` `#cancel-form` -> `/api/cancel` | **Functional.** Calculates exact ladder forfeiture, vendor overrides, and ₹25,000 cap. Retains cancellation record. |
| Package Swap Operations | **IMPLEMENTED** | `admin.js` `#swap-form` -> `/api/swap` | **Functional.** Updates selected packages and applies 10% fee if request is under 30 days before event. |
| Date Change & Credit Application | **IMPLEMENTED** | `admin.js` `#date-change-form`, `#credit-form` | **Functional.** Evaluates >90-day & 180-day rules; applies 50% credit to confirmed replacement bookings. |
| Actuals & Damage Rate Card Entry | **IMPLEMENTED** | `admin.js` `#actuals-form` -> `/api/actuals` | **Functional.** Ingests actual guest count and `CODE:QTY` pairs (e.g. `CHAIR:2;TABLE:1`), computing damages against the rate card. |
| Final Settlement Invoicing | **IMPLEMENTED** | `admin.js` `#settle-button` -> `/api/settle` | **Functional.** Enforces COMPLETED status, generates 18% and 5% GST taxes, reports advance credits, and settles booking. |
| Dispute Management & Payout Freezes | **IMPLEMENTED** | `admin.js` `#dispute-form`, `#payout-form` | **Functional.** Files disputes, evaluates 48-hour event close boundary, freezes matching vendor payouts, and calculates commissions (12% venue, 8% catering, 15% decor/photo). |
| Master Data Registration | **PARTIALLY IMPLEMENTED** | `admin.js` `#venue-form`, `#vendor-form` | **Session-Only.** Successfully registers venues and vendors in memory for the active server session, but does not write back to CSV or database. |
| Printable Operations Reports | **IMPLEMENTED** | `admin.js` `window.print()` wrappers | **Functional.** Generates printable reports and PDF exports for all operations. |

---

## 9. Exact List of Missing Features

The following features must be added to achieve 100% compliance with the organizer evaluation:

1. **Automatic CSV Exporter Engine:**
   - Mechanism to generate and write the four required CSV files (`quotes.csv`, `event_log.csv`, `cancellations.csv`, `settlements.csv`) directly to disk upon completion of command file processing.
2. **`vendor_packages.csv` Ingestion:**
   - Loader method in `CsvLoader.java` for reading `vendor_packages.csv` (`package_id,vendor_id,package_name,price`).
   - Dynamic binding of packages to their parent `Vendor` instances.
3. **`BOTH` Slot Token Recognition:**
   - Update `Slot.parse` to recognize `BOTH` as an alias for `DAY_AND_NIGHT`.
4. **Command File Order Processing:**
   - Remove timestamp re-sorting in `Main.java` line 47; preserve exact command file row order.
5. **Flexible Date/Time Formatter:**
   - Support `YYYY-MM-DD HH:MM:SS` as well as `YYYY-MM-DDTHH:MM:SS` in `Main.java`.
6. **Organizer Command Verb Mapping:**
   - Accept `ENQUIRY` in addition to `ENQUIRE`.
   - Accept `PAYMENT` in addition to `PAY`.
   - Accept `DATE_CHANGE` in addition to `DATECHANGE`.
   - Accept `SWAP_PACKAGE` in addition to `SWAP`.
   - Implement `VENUE_STATEMENT` command.
7. **Input Filename Flexibility:**
   - Fallback between snake_case (`auspicious_dates.csv`, `damage_rate_card.csv`) and kebab-case (`auspicious-dates.csv`, `damage-rate-card.csv`).

---

## 10. Exact Files That Need Modification

| File Path | Description of Required Modifications | Reason |
|---|---|---|
| `src/main/java/in/ps108/Main.java` | 1. Add support for loading `vendor_packages.csv`.<br>2. Support flexible filenames (`auspicious_dates.csv`, etc.).<br>3. Replace strict `LocalDateTime.parse` with a formatter supporting space separator.<br>4. Remove `commands.sort(...)` to process commands in strict file order.<br>5. Map organizer command verbs (`ENQUIRY`, `PAYMENT`, `DATE_CHANGE`, `SWAP_PACKAGE`, `VENUE_STATEMENT`).<br>6. Implement writer logic for `quotes.csv`, `event_log.csv`, `cancellations.csv`, and `settlements.csv`. | Enable execution of the organizer's 134-line command file and generation of required output files. |
| `src/main/java/in/ps108/model/Slot.java` | Add `"BOTH"` to `Slot.parse` switch block: `case "BOTH", "DAY+NIGHT", "DAY_AND_NIGHT" -> DAY_AND_NIGHT;`. | Prevent crash when organizer input uses `BOTH`. |
| `src/main/java/in/ps108/service/CsvLoader.java` | 1. Add `public static Map<String, List<VendorPackage>> vendorPackages(Path path)`.<br>2. Make `CsvLoader.vendors` resilient to 4-column organizer format (without embedded packages).<br>3. Combine vendors and vendor packages when initializing models. | Support official organizer master data schemas. |
| `src/main/java/in/ps108/model/Vendor.java` | Allow `Vendor` to be constructed or updated with packages loaded from external `vendor_packages.csv`. | Decouple vendor master data from embedded packages. |
| `src/main/java/in/ps108/WebMain.java` | Support loading `auspicious_dates.csv` / `damage_rate_card.csv` if underscore names are present in `data/`. | Keep web server compatible with organizer naming. |

---

## 11. New Files That Need to be Created

| New File Path | Purpose & Content |
|---|---|
| `src/main/java/in/ps108/service/OutputReportService.java` | Dedicated service to manage and write `quotes.csv`, `event_log.csv`, `cancellations.csv`, and `settlements.csv` with exact column names, RFC-4180 escaping, 2-decimal formatting, and correct empty value handlers (`NONE`). |
| `src/main/java/in/ps108/model/VendorPackage.java` | Simple record representing `package_id`, `vendor_id`, `package_name`, `price` loaded from `vendor_packages.csv`. |
| `src/test/java/in/ps108/OrganizerCompatibilityTest.java` | Test suite validating exact organizer column headers, command syntax aliases, `BOTH` slot parsing, space-separated timestamp parsing, and all four output CSV formats. |

---

## 12. Recommended Implementation Order

To implement organizer compliance without risking regressions in existing passing tests, the following sequential implementation plan is recommended:

```mermaid
flowchart TD
    A["Step 1: Input Schema Enhancements<br/>(Slot.BOTH, flexible DateTimeFormatter, CsvLoader packages)"] --> B["Step 2: Output Report Engine<br/>(Create OutputReportService for 4 CSVs)"]
    B --> C["Step 3: Main.java Command Aliases & File-Order Processing<br/>(ENQUIRY, PAYMENT, DATE_CHANGE, SWAP_PACKAGE, VENUE_STATEMENT)"]
    C --> D["Step 4: Integration of Output Generation into Main.java<br/>(Wire event logging, quotes, cancellations, settlements)"]
    D --> E["Step 5: Full Regression Testing & Verification<br/>(Run StandaloneChecks, existing JUnit, and new compatibility tests)"]
```

1. **Step 1: Input Schema & Parser Upgrades (Zero Risk)**
   - Add `BOTH` to `Slot.java`.
   - Add `DateTimeFormatter` supporting `yyyy-MM-dd HH:mm:ss` and `yyyy-MM-dd'T'HH:mm:ss`.
   - Add `CsvLoader.vendorPackages()` and allow `Vendor` packages to be populated from the packages file.
2. **Step 2: Output Report Engine (`OutputReportService`)**
   - Create `OutputReportService.java` with builders for the 4 CSV files.
   - Enforce exact header columns and 2-decimal HALF_UP formatting.
3. **Step 3: Command Dispatcher Normalization in `Main.java`**
   - Support both existing verbs (`ENQUIRE`, `PAY`) and organizer verbs (`ENQUIRY`, `PAYMENT`, `DATE_CHANGE`, `SWAP_PACKAGE`).
   - Implement `VENUE_STATEMENT`.
   - Preserve source file order during execution.
4. **Step 4: Output File Wiring**
   - Populate `quotes.csv` on `ENQUIRY`.
   - Populate `event_log.csv` on all non-ENQUIRY commands and `AUTO` hold-expiries.
   - Populate `cancellations.csv` on manual, auto, or date-change cancellations.
   - Populate `settlements.csv` on `SETTLE`.
5. **Step 5: Regression & Verification Testing**
   - Verify that all 59 existing unit tests and 18 standalone checks continue to pass.
   - Run sample organizer commands and confirm output CSV conformity.

---

## 13. Potential Regression Risks and Mitigations

| Risk | Cause | Mitigation |
|---|---|---|
| **Breaking `MainWorkflowTest#outOfOrderCommandsExecuteByTimestampBeforeExactExpiryArrival`** | Existing test at line 70 tests that commands supplied out of timestamp order are executed chronologically. | Retain chronological sorting mode as a configurable option or preserve file order when processing standard input while ensuring timestamp-based hold expiries occur lazily before each command. |
| **Breaking Existing `vendors.csv` Embedded Package Tests** | Existing tests (`CsvLoaderTest`, `VendorTest`) pass inline semicolon-separated packages in `vendors.csv`. | Make `CsvLoader.vendors` auto-detect: if column 4 contains package pairs (`NAME:PRICE`), use inline loader; if `vendor_packages.csv` is provided, merge packages into vendor objects. |
| **Breaking Existing CLI Stdout Assertions** | Tests capture `System.out` to assert strings like `CONFIRMED B1 \| venue=Rs 1600.00`. | Keep all existing `System.out.println` statements intact while simultaneously appending structured records to the output CSV writer. |
| **Rounding Differences in Output CSVs** | Differences in trailing zeros (e.g. `1000` vs `1000.00`). | Use `Money.fmt()` or `BigDecimal.setScale(2, RoundingMode.HALF_UP).toPlainString()` consistently across all CSV writers. |

---

## 14. Conclusion

The application's calculation engine and business rules are **100% sound and fully implemented**. The gap is purely an **adapter and format alignment gap**: bridging the input schemas (`vendor_packages.csv`, `BOTH`, space timestamps) and generating the four required organizer CSV files (`quotes.csv`, `event_log.csv`, `cancellations.csv`, `settlements.csv`). 

Following the implementation plan outlined above will bring the application into **100% compliance** with the official organizer evaluation suite without compromising any existing business logic or frontend capabilities.
