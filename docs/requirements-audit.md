# PS-108 Requirements Audit

## Scope and evidence

This audit covers the full PS-108 brief supplied in the conversation on 2026-10-21, the project design PDF, README, decision log, Java services/models, command workflow, HTTP API, and customer/admin browser code. The project uses self-authored brief-derived expected outputs as its local acceptance baseline; no organizer-specific files are present for a separate comparison.

## Requirements checklist

| Area | Status | Findings |
|---|---|---|
| Java/Maven and project layout | Implemented | Java 17 Maven project, plain Java services, CSV inputs, CLI, and local HTTP entry point. |
| Venue/vendor/damage-rate data loading | Implemented for project CSV format | Loads venues, vendors, auspicious dates, and damage rates. CSV quoting supports quoted commas, doubled quotes, and quoted line breaks. |
| Command-file quoting/output | Implemented | Added `QUOTE|venueId|date|slot|guests|vendorId:package;...`, which prints venue, catering, package, tax, and total lines. Existing schedule/cancellation/settlement output remains. |
| Venue/vendor browsing and quotes | Implemented and end-to-end checked | Customer page loaded, venue/vendor data loaded, valid quote returned, and a demo reservation was created through the API. Admin page loaded and the full booking-to-settlement workflow was exercised through the API. |
| Venue pricing | Implemented and regression-tested | Weekend/auspicious multipliers use the maximum; day+night discounts the second slot by 10%; unsupported venue slots are rejected. |
| Catering guarantee/excess pricing | Implemented and regression-tested | 80%/150-plate guarantee, +10% boundary, 20% premium, and invalid input checks. |
| Enquiries, 72-hour holds, FIFO queues | Implemented and regression-tested | Expiry/promotion work; held booking cancellation releases the slot; confirmation requires the matching live hold. Runtime state is in memory. |
| Package confirmation/swap and date changes | Implemented and regression-tested | One catering vendor per booking; slot support, 30-day swap boundary, free date-change eligibility, destination availability, and schedule rebasing are implemented. |
| Advances and payments | Implemented and regression-tested | 25/50/balance tranches, merged ≤60-day schedule, full due ≤7 days, late-fee calculation, and 21-day auto-cancellation are implemented. CLI schedule totals use the full quoted catering contract value. |
| Cancellation/forfeiture | Implemented and regression-tested | Full contract lines are used for the ladder; photography is 100% within 30 days, catering is limited to its guarantee within 7 days, decor follows the ladder, and the cap is paid advances + ₹25,000 once after aggregation. |
| Damage rate card | Implemented | `ACTUALS` uses `damageCode:quantity` pairs and prices each item from `data/damage-rate-card.csv`; unknown codes and invalid quantities are rejected. The web actuals form uses the same card. |
| Date-change credit | Implemented | A source booking's available 50% capped-forfeiture credit can be applied to a confirmed replacement using `APPLY_CREDIT`; it reduces the replacement's balance and future advance schedule. Application must precede replacement payments. |
| Taxes and settlement | Implemented against supplied bases | Settlement applies 18% GST to venue and packages and 5% to catering (without ITC); damages and tips are outside those GST bases. Switch fees are not taxed because the supplied brief only names venue and packages for 18%. Late fees are printed separately, not added to the specified final-bill formula. |
| Vendor payouts and disputes | Implemented and regression-tested | Venue 12%, catering 8%, decor/photography 15%; payout is eligible at event-close + 7 days; a matching open dispute filed within 48 hours freezes it. Future-filed disputes do not affect earlier evaluations. CLI payees are checked against the booking. |
| API validation/error handling | Implemented and smoke-checked | Invalid dates, wrong-state actuals/settlement, and invalid category commissions return HTTP 400; quote validates venue slots; CLI payout/dispute IDs are checked. A simulated `1.005` payment is recorded as `1.01`. Browser scripts pass Node syntax checks. |
| Persistence/authentication/real payments | Not implemented | The PDF explicitly scopes this as an in-memory local demo without database, authentication, or real payment integration. |
| Local acceptance outputs | Verified against project baseline | Self-authored command/expected-output files are derived from the brief; the packaged sample and auspicious-weekend quote match their expected outputs exactly. Organizer-specific output equivalence is not claimed. |
| UI scope | Project decision | CLI is the required command-file deliverable. The website is retained as a supplemental demo and is not part of terminal acceptance. |
| Submission assets | Partial / pending | README, design PDF, tests, and decision log exist. The capstone email requires a 5-minute demo video link and repository submission; the link and repository URL are not present in the workspace. Organizer fixture comparison remains pending until the files are supplied. |

## Changes made during this audit

- Hardened cancellation validation and documented precedence: vendor-specific rules, general ladder on remaining contract value, aggregate cap after the sum.
- Validated/rounded settlement amounts and package line values; made payment rounding consistent between ledger and booking totals; the booking engine rejects overpayment.
- Added negative/null/unknown checks for pricing, schedules, settlement, vendors, and payouts.
- Applied exact timestamp comparison for the 48-hour payout freeze and ignored future-dated disputes until filed.
- Enforced category commission rates in vendor data and made the admin registration form choose the rate by category.
- Tightened web API quote/actuals/settlement validation and CLI payee validation; duplicate cancellation transitions now fail.
- Updated sample command tranche payments to match tax-inclusive totals and refreshed `docs/sample-run-output.txt`.
- Added tests covering these boundaries while retaining existing assertions.
- Added RFC-style CSV quote parsing and regression tests for quoted commas, doubled quotes, multiline fields, and malformed quotes.
- Added damage-rate CSV loading and code/quantity calculations for command and web actuals; updated sample commands to use damage items.
- Added CLI quote printing and date-change credit redemption; credit is reflected in replacement balance and advance schedules.
- Corrected CLI cancellation and advance contract totals to use quoted catering rather than only the minimum guarantee.
- Corrected settlement GST base to exclude switch fees and match the brief's stated taxable categories.
- Made damage-code output deterministic and corrected the web quote GST label to match the implemented taxable base.
- Adopted explicit project policies for partial-week late fees and the event-close dispute window; added boundary regression tests.
- Expanded regression coverage for exact +10% catering, 21-day event-day auto-cancellation, damage-rate actuals, quote output, credit redemption, and credit-adjusted schedules.

## Project policies for details the brief leaves open

- Partial overdue weeks count as started weeks after the grace period: 1% begins on day 6 overdue; the next 1% begins on day 13.
- The 48-hour vendor dispute freeze window begins at event close. CLI input provides the close timestamp; the web flow records it.
- The website remains supplemental. Demonstrate and submit the CLI command-file workflow as the required no-UI engine.
- Local output acceptance uses the self-authored fixtures and expected files. Organizer-specific compatibility can be compared if such files become available, but it is not treated as an implementation blocker.
- Late fees remain separately reported because they are not included in the brief's final-bill formula; no separate late-fee collection path is defined.

Deferred implementation and follow-up tasks are tracked in [docs/deferred-work.md](deferred-work.md).

## Verification results (history through 2026-10-22)

- `mvn test`: **50 passed, 0 failed, 0 errors, 0 skipped**; `mvn package`: **BUILD SUCCESS**.
- Standalone regression checks: **18 passed, 0 failed**; current Java sources compiled with `javac --release 17`, and the sample command file completed.
- Added exact hold-expiry arrival coverage and tightened the date-change workflow to exactly 91 days before the original event with a replacement 181 days out.
- Added [self-authored acceptance checks](self-authored-acceptance-checks.md) with expected values derived from the brief; these are clearly distinguished from organizer fixtures.
- A persistent self-authored auspicious-weekend quote input produced output identical to its independently calculated expected-output file.
- Live HTTP smoke verified both pages (200), quote/booking, rate-card actuals, settlement, dispute freeze/release, admin hold/queue, confirmation, schedule, and first-payment queue clearing.
- Further endpoint smoke covered venue/vendor registration, 30-day package swap, free date change, 91-day/181-day fallback, credit application, and cancellation.
- Corrected and rechecked the browser quote label: it now reports GST on venue and packages, excluding switch fees; invalid slot input returns HTTP 400.
- Sorted damage line items by rate code in both output surfaces after exact sample comparison exposed nondeterministic order.
- Rechecked the web actuals endpoint with reversed input order; its JSON lines return sorted damage codes.
- Node syntax checks passed for customer `app.js` and admin `admin.js`.
- Live local HTTP workflow: customer venue browse/quote/reservation passed. Admin enquiry/hold, confirmation, advance schedule, payment, actuals, settlement, dispute filing, and matching payout freeze passed. Both `/` and `/admin.html` returned HTTP 200.
- Updated API smoke: all 3 damage rate codes loaded, `CHAIR:2;TABLE:1` calculated ₹8,000, 91-day/181-day date-change fallback produced a cancelled source, and ₹12,500 credit applied to a confirmed replacement. Both customer and admin pages returned 200.
- Earlier smoke checks remain: invalid date/state/commission requests returned HTTP 400 and a `1.005` payment was recorded as `1.01`.
- The `.sh` wrapper could not run because Bash resolves to WSL and no WSL distribution is installed; its Java compile, checks, and sample workflow were executed directly with the installed JDK.
- Organizer-specific output comparison is unavailable because those files are absent; the local self-authored baseline is verified.


## Audit recheck (2026-10-28)

- Maven suite: 54 passed, 0 failed, 0 errors, 0 skipped, including timestamp ordering, 24-way concurrent hold integrity, JSON control-character escaping, and exact cancellation-band regression coverage.
- Customer formatter regression: 1 Node test passed; both browser scripts passed `node --check`.
- Standalone regression runner: 18 passed, 0 failed; sample command-file workflow completed.
- CLI now orders commands by timestamp and preserves source order at equal timestamps, as promised by the design document.
- A live HTTP smoke could not be run in this environment because Java's loopback bind failed with `Permission denied`; prior smoke results remain the recorded 2026-10-22 results above.
- Official organizer fixture comparison and the required demo video link/repository submission remain pending; exact organizer-output compliance is not claimed.

## Documentation and verification recheck (2026-11-01)

- `mvn clean test`: 54 passed, 0 failed, 0 errors, 0 skipped. `mvn package`: BUILD SUCCESS and 54 tests passed in the package lifecycle.
- Direct Java execution of the standalone checks: 18 passed; sample command-file run completed. The Bash wrapper could not start in this environment (`E_ACCESSDENIED`).
- Customer money test: 1 passed. Customer and admin scripts both passed `node --check`.
- Packaged JAR output matched `docs/sample-run-output.txt` exactly.
- Added CSV schemas, class/component and booking lifecycle diagrams, direct local server run instructions, and a five-minute recording run sheet. No new implementation defect was confirmed in this documentation pass.
- A fresh HTTP check remains unavailable because Java loopback access was denied in this environment; the previous successful browser/API smoke remains the latest live check. No persistence or background scheduler was added: neither is documented as a required assignment deliverable, and the runtime is explicitly described as in-memory.
- Official organizer input/expected-output fixtures and the actual demo video URL remain pending. Exact fixture compliance is not claimed.

## Cancellation refund fix (2026-11-01)

- The cancellation service now returns the applicable ladder percentage, vendor-specific override amount, capped forfeiture, refund `max(0, paid - capped forfeiture)`, and recoverable amount `max(0, capped forfeiture - paid)`.
- The web API returns contract amount, paid amount, cancellation date, days remaining, ladder percentage, vendor override details and line breakdown, cap, refund eligibility and transfer state. A cancellation result is retained for the server session; retrying the same cancellation returns that same result instead of attempting a second state transition. No payment gateway or cash refund is represented as completed.
- The customer page now has a cancellation form and displays the breakdown. The admin page presents the same API values instead of raw JSON.
- Full clean JUnit suite: 59 passed, 0 failed, 0 errors, 0 skipped. Maven package succeeded with the same suite; customer Node test passed and both browser scripts passed syntax checks.
