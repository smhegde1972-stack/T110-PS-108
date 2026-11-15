# Decision Log — PS-108

| ID | Decision / assumption | Reason | Status |
|---|---|---|---|
| DL-01 | Use Java 17, Maven, plain Java domain services, and CSV input | Matches assignment and minimizes dependencies | Initial |
| DL-02 | Use `BigDecimal` with HALF_UP rounding to 2 decimals at line-item boundaries | Deterministic currency math | Confirm with faculty |
| DL-03 | Round catering 80% guarantee upward to a whole plate | Plates are indivisible | Confirm with faculty |
| DL-04 | Apply premium catering rate only to plates strictly above quoted guests + 10% | The brief says “above quote + 10%”; exact boundary has no premium | Confirm with faculty |
| DL-05 | Treat 60-day and 7-day confirmation boundaries as inclusive for nearer payment schedule | Explicit edge-case coverage | Confirm with faculty |
| DL-06 | Charge 1% for each started 7-day period after the 5-day grace; the first fee is due on overdue day 6, and the next period begins on day 13 | Deterministic, conservative late-fee calculation; verified at days 5, 6, 12, and 13 | Project policy; implemented and tested |
| DL-07 | Apply cancellation cap once to total forfeiture, not separately to each vendor line | Avoids multiplying the ₹25,000 allowance | Confirm with faculty |
| DL-08 | Free date change is >90 days before event; destination date is within 180 days of request | Makes the stated boundary test explicit | Confirm with faculty |
| DL-09 | Treat date-change credit as a ledger credit separate from money paid | Avoids misrepresenting refunds and advances | Confirm with faculty |
| DL-10 | Tips are excluded from the bill; damages are taxable only if the supplied rate card/policy says so (default: no GST) | The brief does not define damages taxability | Confirm with faculty |
| DL-11 | Commands are processed in timestamp order, then file order for equal timestamps; expired holds are released before a new hold is granted at that timestamp | Deterministic exact-time queue behavior | Initial |
| DL-12 | Anchor the 48-hour vendor dispute freeze window at the recorded event-close timestamp. Use the close time from command inputs; the web flow records its event-close time. | Gives one explicit timestamp for the dispute and payout rules; tested at the boundary and against event start | Project policy; implemented and tested |
| DL-13 | Use the self-authored brief-derived command and expected-output files as the project's local acceptance baseline | Keeps local output verification actionable without claiming equivalence to unavailable organizer files | Local acceptance baseline |
