# Self-authored PS-108 acceptance checks

These cases were derived from the PS-108 brief supplied in the conversation. They are **not organizer fixtures or official expected outputs**. Expected values below are calculated from the written rules, then checked against JUnit regressions, the standalone regression runner, and the sample CLI run where applicable.

The persistent self-authored quote fixture is in `data/self-authored-acceptance/`. It uses an auspicious Saturday, a ₹1,000 weekday base, and 100 guests. The expected CLI output is stored in `expected-quote-output.txt` and was compared line-for-line with the program output; the comparison passed.

## Deterministic rule cases

| Rule case | Expected result from the brief | Verification |
|---|---|---|
| Weekend and auspicious date together, weekday base ₹100,000 | Use max multiplier 1.6 once; rental ₹160,000 | `PricingServiceTest.auspiciousWeekendUsesMaximumNotStackedMultipliers`; standalone regression check |
| Day and night slots, weekday base ₹100,000 | Second slot at 90%; total ₹190,000 | `PricingServiceTest.secondSlotDiscountAppliesToSecondSlot`; standalone regression check |
| Confirmation exactly 60 days before event, ₹100,000 contract | First two tranches merge to ₹75,000; remaining ₹25,000 later | `AdvanceServiceTest.exactlySixtyDaysMergesFirstTwoTranches`; standalone regression check |
| Catering quote 200 guests at ₹1,400/plate; actual exactly 220 | No 1.2x uplift; 220 × ₹1,400 = ₹308,000 | `PricingServiceTest.exactTenPercentDoesNotUsePremiumRate`; standalone regression check |
| Same quote; actual 221 | 220 plates at ₹1,400 plus 1 at ₹1,680 = ₹309,680 | Same pricing regression test |
| Cancellation exactly 90 days before event | 50% general ladder rate | `CancellationServiceTest.cancellationAtExactlyNinetyDaysUsesFiftyPercentLadder` |
| Date-change request 91 days before original event; replacement 181 days away | Free-change rule fails because replacement is beyond 180 days; rebook path applies. In the small test contract, forfeiture is ₹295 and credit is ₹147.50 | `MainWorkflowTest.dateChangeCreditCanBeAppliedToReplacementBeforePayments` |
| Forfeiture above paid amount by more than ₹25,000 | Cap equals paid amount + ₹25,000; tested with ₹10,000 paid, producing ₹35,000 cap | `CancellationServiceTest.capIsPaidAdvancesPlusTwentyFiveThousand`; standalone regression check |
| Tranche reaches 21 days overdue on event day | Booking auto-cancels before processing that day's command | `MainWorkflowTest.overdueAdvanceAutoCancelsWhenItReachesTwentyOneDaysOnEventDay`; standalone regression check |
| Late-fee periods after 5-day grace on ₹100,000 tranche | Day 5: ₹0; day 6: ₹1,000; day 12: ₹1,000; day 13: ₹2,000 | `AdvanceServiceTest.startedWeekLateFeeBoundariesAfterFiveDayGrace` |
| New enquiry arrives at the exact 72-hour hold expiry while another enquiry is queued | Expired hold is released, queued enquiry receives the hold first, new arrival queues behind it | `BookingEngineTest.enquiryArrivingAtExactHoldExpiryReceivesPromotedHold`; standalone regression check |
| Dispute filed 51 hours after event start but 42 hours after event close | Matching open payout is frozen because event close is the chosen 48-hour anchor | `PayoutServiceTest.disputeWindowUsesRecordedEventCloseNotEarlierEventStart` |

| General cancellation ladder band boundaries | 181/180, 91/90, 31/30, and 8/7 days | `CancellationServiceTest.cancellationLadderUsesEachExactBandBoundary` |
| Command file lines supplied out of timestamp order | Process in ascending timestamp order, preserving file order for equal timestamps | `MainWorkflowTest.outOfOrderCommandsExecuteByTimestampBeforeExactExpiryArrival` |

## Local run results

- JUnit: **59 passed, 0 failed, 0 errors, 0 skipped** on 2026-11-01 using IntelliJ's bundled Maven (`mvn clean test`; `mvn package` also passed).
- Dependency-free regression runner: **18 passed, 0 failed** on 2026-11-01. Its Bash wrapper was blocked by environment `E_ACCESSDENIED`; the Java compile and runner steps were executed directly in PowerShell.
- Java application and test source compilation with `javac --release 17`: passed.
- Sample command-file execution: completed and printed quote, schedules, damage charges, cancellation, settlement, and payout results.
- Self-authored quote fixture: exact CLI output matched `data/self-authored-acceptance/expected-quote-output.txt`.

The packaged JAR matched `docs/sample-run-output.txt` exactly on 2026-11-01. The self-authored quote expected output matched line-for-line in the previous verification run.

## Project decisions for unspecified details

- Partial overdue weeks count as started weeks: 1% on overdue day 6 and each additional 1% at 7-day intervals (day 13, day 20, etc.). Tests cover the grace and both fee transitions.
- The 48-hour dispute window starts at recorded event close, not event start. Tests cover exact close + 48 hours, one minute beyond, and a dispute beyond 48 hours from event start but within 48 hours from close.
- The website is supplemental; the terminal command-file workflow is the required no-UI deliverable.

Organizer-specific fixture comparison is unavailable, but it is not a project blocker. The self-authored fixture files and `docs/sample-run-output.txt` define the local expected-output baseline.
