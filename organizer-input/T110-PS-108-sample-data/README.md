# PS-108 – Wedding and Event Venue Booking with Vendor Packages, Advances and Cancellation Ladder Engine — Sample Data Pack
Prepared by 10 SECONDS for Team T110.

## Files
| File | What it holds | Rows |
|---|---|---|
| venues.csv | Venues with capacity and weekday base rental | 8 |
| vendors.csv | Vendors by category | 20 |
| vendor_packages.csv | Package/menu prices per vendor | 60 |
| auspicious_dates.csv | Auspicious dates (1.6x) | 12 |
| damage_rate_card.csv | Post-event damage items | 4 |
| commands.txt | 30 enquiries + ~100 hold/confirm/payment/cancel/date-change events + actuals/settlements | 134 |

## Column reference
Conventions: dates `YYYY-MM-DD`; timestamps `YYYY-MM-DD HH:MM:SS` (24h, IST, no timezone suffix); money in Rs as plain decimals with 2 places (no commas, no symbol); percentages as plain numbers (12.5 = 12.5%); booleans `true`/`false`; IDs uppercase with a fixed prefix. Row counts in the Files table exclude the header row (and comment lines in command files).

### venues.csv
| Column | Type | Units/format | Allowed values | Notes |
|---|---|---|---|---|
| venue_id | string | VEN-NN |  | PK |
| venue_name | string |  |  |  |
| city | string |  |  |  |
| capacity | integer | guests |  | max guests |
| weekday_base_rental | money | Rs per slot |  | weekday base; weekend 1.3x, auspicious 1.6x |
| slots | list |  | DAY;NIGHT |  |

### vendors.csv
| Column | Type | Units/format | Allowed values | Notes |
|---|---|---|---|---|
| vendor_id | string | VND-NN |  | PK |
| vendor_name | string |  |  |  |
| category | string |  | CATERING/DECOR/PHOTOGRAPHY |  |
| city | string |  |  |  |

### vendor_packages.csv
| Column | Type | Units/format | Allowed values | Notes |
|---|---|---|---|---|
| vendor_id | string |  | vendors.vendor_id |  |
| package_code | string |  | SILVER/GOLD/PLATINUM (catering per plate); BASIC/CLASSIC/GRAND (decor); STILLS/STILLS_VIDEO/CINEMATIC (photo) |  |
| package_name | string |  |  |  |
| price | money | Rs |  | per plate for catering, fixed otherwise |

### auspicious_dates.csv
| Column | Type | Units/format | Allowed values | Notes |
|---|---|---|---|---|
| date | date | YYYY-MM-DD |  |  |

### damage_rate_card.csv
| Column | Type | Units/format | Allowed values | Notes |
|---|---|---|---|---|
| item_code | string | DMG-NN |  | PK |
| description | string |  |  |  |
| amount | money | Rs |  |  |

### commands.txt
| Column | Type | Units/format | Allowed values | Notes |
|---|---|---|---|---|
| line | text | COMMAND/args | see below |  |

## Command file format
`commands.txt` holds one command per line in the form `COMMAND_NAME|arg1|arg2|...`. Commands MUST be processed in file order (top to bottom). Lines starting with `#` are comments and blank lines are ignored. Argument order per command:

| Command | Arguments (in order) | Meaning |
|---|---|---|
| ENQUIRY | ts/enquiry_id/client_name/venue_id/event_date/slot/guests/menu_tier/catering_vendor/decor_package/decor_vendor/photo_package/photo_vendor | creates a 72 h hold or queues; slot DAY/NIGHT/BOTH |
| CONFIRM | ts/enquiry_id | client confirms; booking is CONFIRMED once the first tranche is paid |
| PAYMENT | ts/enquiry_id/amount | advance payment |
| CANCEL | ts/enquiry_id | client cancellation |
| DATE_CHANGE | ts/enquiry_id/new_date | date change request (same slot) |
| SWAP_PACKAGE | ts/enquiry_id/category/new_package | category DECOR/PHOTOGRAPHY |
| ACTUALS | ts/enquiry_id/actual_guests/damage_items | post-event; damage_items semicolon list or NONE |
| DISPUTE | ts/enquiry_id/vendor_id | client dispute against a vendor |
| SETTLE | ts/enquiry_id | final bill and vendor payouts |
| VENUE_STATEMENT | ts/venue_id | print venue bookings and payouts |

## Planted scenarios
Every edge case from the brief is planted at a known location, followed by extra traps. Line numbers refer to `commands.txt` counting from line 1 including comment lines.
- **Hold expiring at the exact moment the next enquiry arrives** — ENQ-001 holds VEN-01 / 2027-01-16 / DAY from 2026-10-05 10:00:00 (line 3); ENQ-002 for the same date and slot arrives at exactly 2026-10-08 10:00:00 = hold + 72 h (line 12) → the hold has expired at that instant and ENQ-002 gets a fresh hold.
- Trap — ENQ-004 (line 6) enquires for a date held by ENQ-003 (line 4) → queued; ENQ-003 tries to CONFIRM 73 h after its hold (line 18) → expired, the hold has passed to ENQ-004.
- **Event on a weekend auspicious date (no stacking)** — ENQ-005 (line 8) is on 2026-11-22, a Sunday that is also in auspicious_dates.csv → 1.6x only (no 1.3 × 1.6 stacking).
- **Confirmation exactly 60 days before the event** — ENQ-006 event 2026-12-13; CONFIRM on 2026-10-14 (line 44) = exactly 60 days before → decide whether the 25% and 50% tranches merge; this booking also spans BOTH slots (10% off the second).
- **Actual guests at exactly quote + 10%** — ENQ-005 quoted 600 guests; ACTUALS 660 at line 94 = exactly quote + 10% → no 1.2x surcharge plates.
- **Cancellation at exactly 90 days** — ENQ-007 event 2027-03-20; CANCEL on 2026-12-20 (line 117) = exactly 90 days → 31–90 band (50%).
- **Date change requested at 91 days to a date 181 days out** — ENQ-008 event 2027-02-10; DATE_CHANGE on 2026-11-11 (line 89) = 91 days before, to 2027-05-11 = 181 days after the request → fails the 180-day test → cancellation + new booking with 50% forfeiture credit.
- **Forfeiture exceeding paid advances by more than Rs 25,000** — ENQ-009: 1200 guests PLATINUM/GRAND/CINEMATIC on BOTH slots at VEN-07; only the 25% tranche paid; CANCEL 4 days before (line 87) → 100% forfeiture far exceeds paid + 25,000 → cap applies.
- **Advance overdue reaching 21 days on the event day** — ENQ-010 confirmed 2026-10-20 (line 54), 21 days before the 2026-11-10 event, with no payment; on the event day the first tranche is 21 days overdue → auto-cancel as of that date; the PAYMENT at line 86 arrives after auto-cancellation.
- Trap — enquiry with 1600 guests at a 1500-capacity venue (line 34, reject); duplicate CONFIRM (line 39; deliberately out of timestamp order with its neighbour — process in file order).
- Trap — second tranche of the booking at line 37 paid 9 days late (line 105) → 5-day grace then 1% per week.
- Trap — SWAP_PACKAGE 23 days before the event (line 91) → 10% switch fee on the higher package; DISPUTE within 48 h of the event (line 110) freezes that vendor's payout.

## What is NOT in this pack
Expected outputs are not provided for this set. Validate your engine with your own test suite against the rules in your brief (BR-01 … BR-nn). A separate hidden input set with the same schema will be used at final evaluation; outputs are compared field-for-field, so follow the column formats above exactly in your own output.

## Output format expected at evaluation
Produce the following CSV files (header row, same conventions as above, rows in the order stated). Column names are case-sensitive.

### `quotes.csv`
One row per ENQUIRY in file order; status HELD/QUEUED/REJECTED; due dates NONE where tranches merge.

Columns, in order: `enquiry_id`, `status`, `venue_rental`, `catering`, `decor`, `photography`, `total_contract`, `tranche1_due`, `tranche1_amount`, `tranche2_due`, `tranche2_amount`, `tranche3_due`, `tranche3_amount`

### `event_log.csv`
One row per non-ENQUIRY command in file order (plus one row per automatic hold-expiry/auto-cancel, tagged AUTO in command).

Columns, in order: `line_no`, `enquiry_id`, `command`, `status`, `amount`, `message`

### `cancellations.csv`
One row per cancellation (manual, auto or date-change-as-cancellation).

Columns, in order: `enquiry_id`, `cancel_date`, `days_before`, `venue_forfeit`, `catering_forfeit`, `decor_forfeit`, `photo_forfeit`, `total_forfeit`, `paid`, `cap_applied`, `refund_or_recoverable`

### `settlements.csv`
One row per SETTLE in file order; frozen_vendor NONE if no dispute.

Columns, in order: `enquiry_id`, `venue_amount`, `catering_amount`, `packages_amount`, `switch_fees`, `damages`, `gst`, `advances_paid`, `balance_due`, `venue_payout`, `catering_payout`, `decor_payout`, `photo_payout`, `frozen_vendor`

## Notes and assumptions
- Weekend = Saturday or Sunday event date.
- Catering vendor minimum guarantee uses quoted guests from the ENQUIRY.
- The date-change 180-day test is measured from the request date; state your reading in your BR if you choose otherwise.
