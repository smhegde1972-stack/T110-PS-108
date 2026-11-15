# PS-108 Code Changes Note

This is a record of changes made directly in the project during our work, separate from the tasks still deferred in `docs/deferred-work.md`.

## Booking and business-rule handling

- Fixed stale active holds after cancellation and required confirmation to use the matching, unexpired `HOLD-<enquiryId>` record.
- Clear queued enquiries for the overlapping slot after the first positive advance payment.
- Added cancellation validation for null, negative, unknown, and mismatched inputs. Applied photography and catering overrides before the general ladder, then applied the ₹25,000-plus-advances cap once to the aggregate.
- Added pricing validation for dates, venue slots, guests, and catering inputs.
- Rounded payment amounts consistently and prevented payment beyond the tax-inclusive booking contract.
- Added settlement validation and consistent currency rounding. Damages are included in the final amount but are not included in GST calculations by the current implementation.
- Validated advance schedules, payment ledgers, vendor commissions, payout inputs, and dispute records.
- Applied exact timestamp checks for the 48-hour payout dispute window and retained fixed commission rates by vendor category.
- Corrected CLI advance and cancellation totals to use the full quoted catering charge; the guarantee is used only for the ≤7-day catering cancellation override.
- Applied the supplied GST bases exactly: 18% on venue and packages, 5% on catering, excluding switch fees and damages.
- Added CLI quote output and `APPLY_CREDIT` to transfer available date-change credit to a confirmed replacement before its payments.
- Integrated the damage rate card into command-file and web actuals using `CODE:QUANTITY` items.
- Corrected the web quote's GST label to match the actual 18% tax base (venue and packages; switch fees are excluded).
- Sorted damage line items by rate code in CLI and web output so printed reports are deterministic.

## API, command workflow, and frontend

- Tightened API validation and HTTP error responses for invalid dates, booking states, unsupported slots, and registration data.
- Validated that CLI disputes and payouts refer to vendors or venues associated with the selected booking.
- Updated sample command payments to reflect tax-inclusive advance totals and refreshed the sample output report.
- Added a customer-site section showing all 12 business rules and a notice that unresolved financial details are demo behavior pending confirmation.
- Added a customer cancellation form and an admin cancellation breakdown backed by the Java cancellation service. Responses include contract amount, paid amount, ladder rate, vendor overrides, capped forfeiture, refund eligibility, and additional recoverable amount.
- Refunds are calculated as `max(0, paid - capped forfeiture)`; recoverable amounts are `max(0, capped forfeiture - paid)`. Cancellation results are retained in the running server and repeated requests return the same result. No bank transfer is claimed.

## CSV support and regression tests

- Updated the CSV loader to support quoted commas, doubled quote escapes, and line breaks within quoted fields; malformed quoting produces an error.
- Added CSV regression tests for quoted commas, doubled quotes, quoted multiline values, and malformed quotes.
- Added and expanded service regression tests for cancellation, settlement, pricing, payouts, payment rounding, and vendor validation.
- Added cancellation tests for all ladder boundaries, vendor overrides, refund cases, the ₹25,000 cap, and web cancellation retry safety.
- Added workflow regressions for the quote command, rate-card damages, credit redemption and adjusted schedule, and event-day 21-day auto-cancellation; strengthened the exact +10% catering threshold test.

## Documentation and verification

- Updated `README.md`, `docs/requirements-audit.md`, `docs/test-report.md`, and `FILE-MANIFEST.md` to describe the implementation and its limitations.
- Recorded verification results: 39 JUnit tests passed, Maven package succeeded, 17 standalone checks passed, JavaScript syntax checks passed, and representative customer/admin HTTP workflows completed.

## Current known unverified items

The project uses a self-authored acceptance baseline because organizer fixtures/expected outputs were not supplied. The CLI is the required terminal deliverable; the website remains supplemental. The project's late-fee and event-close dispute policies are recorded in `decision-log.md`.
