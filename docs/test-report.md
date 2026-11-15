# PS-108 Verification Report

## Clean verification on 2026-11-01

- `mvn clean test`: **59 passed, 0 failed, 0 errors, 0 skipped**.
- `mvn package`: **BUILD SUCCESS** and repeated the 59 passing JUnit tests while creating `target/wedding-venue-engine-1.0.0.jar`.
- These commands used IntelliJ IDEA's bundled Maven because Maven is not available as a shell command in this environment. Maven printed an `Access is denied.` line before its normal output, but both commands exited with code 0 and reported `BUILD SUCCESS`.
- Dependency-free runner: **18 passed, 0 failed**; Java 17 compilation and the sample command file both completed. The bundled `.sh` wrapper could not execute because Bash returned `E_ACCESSDENIED`, so its Java steps were run directly from PowerShell.
- `node --test src/test/js/customer-money.test.cjs`: **1 passed, 0 failed**.
- `node --check src/main/resources/static/app.js` and `node --check src/main/resources/static/admin.js`: both passed.
- Running the packaged JAR and normalizing line endings matched `docs/sample-run-output.txt` exactly.
- A fresh live HTTP smoke was not possible in this environment: the earlier server start failed with a loopback `Permission denied`. The last successful HTTP smoke remains the historical 2026-10-22 run below.
- The 14 cancellation service tests cover every ladder boundary, photography and catering overrides, aggregate cap, refund above/equal/below forfeiture, BR-07 example arithmetic, and vendor-specific amount reporting. The web adapter regression verifies the detailed response, days remaining, and idempotent retry.

## Verification performed on 2026-10-22

- `mvn test`: **50 passed, 0 failed, 0 errors, 0 skipped** using IntelliJ's bundled Maven.
- `mvn package`: **BUILD SUCCESS**; executable JAR created.
- Standalone regression checks: **18 passed, 0 failed**.
- Added a regression test for a new enquiry arriving at the exact 72-hour hold expiry: the queued party is promoted first, and the new arrival queues behind it.
- The date-change workflow test now uses exactly 91 days before the original event and a replacement date exactly 181 days out.
- Late-fee tests cover day 5 grace expiry, first fee at day 6, unchanged fee through day 12, and second started-week fee at day 13.
- Payout tests establish event close as the dispute-window anchor, including a report filed 51 hours after event start but 42 hours after close.
- The current source and test trees also compiled successfully with `javac --release 17`.
- The sample command file completed with quote printing, full-contract advance schedules, damage-rate calculations, cancellation, and date-change credit redemption.
- Running the packaged JAR reproduced `docs/sample-run-output.txt` exactly.
- Deterministic damage-code sorting now makes the sample output stable; a workflow assertion verifies `CHAIR` appears before `TABLE` regardless of map iteration order.
- A persistent self-authored weekend/auspicious quote fixture matched its manually calculated expected output line-for-line.
- HTTP smoke: customer and admin pages returned 200; quote and booking routes worked; rate-card actuals returned ₹8,000; settlement completed; an on-time dispute froze the decor payout and resolving it made the payout payable; the admin hold/queue, confirmation, advance schedule, and first-payment queue-clear flow passed.
- Additional API workflow: venue/vendor registration, exactly-30-day free package swap, eligible free date change, 91-day/181-day cancellation fallback, ₹12,500 credit redemption, and cancellation all passed.
- Follow-up web quote check verified the GST label says “18% venue + packages”; invalid slot input returned HTTP 400. The misleading previous label that included switch fees was corrected.
- Reversed-order damage input to the web actuals API returned damage lines in stable `CHAIR`, `TABLE` order.
- Focused workflow tests cover a quote output, damage-code rates, replacement credit application and adjusted advance schedule, an advance reaching 21 days overdue on the event date, exact 72-hour hold expiry, and the 91-day/181-day date-change boundary.
- Pricing tests verify exactly quoted guests + 10% uses the normal catering price and a guest count above that threshold applies the 1.2× rate.
- Settlement regression checks verify GST applies to venue/packages and catering, not the switch-fee line.
- CSV tests cover quoted commas, doubled quotes, quoted multiline fields, malformed quotes, and damage-rate loading.
- `node --check` passed for both `app.js` and `admin.js`.
- Live HTTP smoke loaded all three rate-card entries, calculated ₹8,000 for `CHAIR:2;TABLE:1`, canceled a source booking under the 91-day/181-day rule, and applied its ₹12,500 credit to a confirmed replacement. Customer and admin pages both returned HTTP 200.

## External comparison note and project policies

- Organizer-provided venue/vendor/enquiry/payment fixtures and expected-output files are not present. The project uses its self-authored expected-output baseline instead and does not claim organizer-specific format equivalence.
- The project resolves underspecified rules as documented policies: each started 7-day late-fee period after grace, and dispute window from recorded event close.
- Late fees are reported separately; the final-bill formula does not include them, and there is no independent collection payment path.
- The CLI is the required command-file deliverable; the website remains a supplemental demo.


## Audit recheck performed on 2026-10-28

- IntelliJ-bundled Maven `test`: **54 passed, 0 failed, 0 errors, 0 skipped**.
- IntelliJ-bundled Maven `package`: **BUILD SUCCESS**; the packaged JAR matched `docs/sample-run-output.txt` exactly.
- Dependency-free regression runner: **18 passed, 0 failed**.
- Node customer-money regression: **1 passed**; `node --check` passed for `app.js` and `admin.js`.
- New regression coverage verifies chronological command processing across an out-of-order exact-expiry event, single-hold behavior under 24 simultaneous enquiries, JSON escaping for all control characters, and all general cancellation ladder boundaries (181, 180, 91, 90, 31, 30, 8, and 7 days).
- A live HTTP smoke could not be run in this environment because Java's loopback bind failed with `Permission denied`; see the earlier 2026-10-22 smoke report for the last successful API smoke.
