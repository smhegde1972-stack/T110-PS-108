# PS-108 Project Decisions and External Comparison

The core implementation is checked against the supplied written rules and a self-authored acceptance baseline. Organizer-specific output comparison remains pending until those fixtures are supplied; final repository/video submission is also still pending. The project uses the explicit decisions below in the meantime.

## Locked project decisions

- **Late-fee rounding:** after the 5-day grace, charge 1% of the outstanding tranche for each started 7-day period. The first 1% begins on overdue day 6; the second begins on day 13. Tests cover days 5, 6, 12, and 13.
- **Dispute window:** measure 48 hours from the recorded event-close timestamp. CLI commands provide that timestamp; the web workflow records the event close. A dispute filed at or before close + 48 hours freezes the matching open vendor payout, subject to the evaluation time. Tests cover the exact boundary, just beyond it, and the distinction from event start.
- **Output acceptance baseline:** use the project’s sample command file and the self-authored fixture under `data/self-authored-acceptance/` as local golden outputs. Their expected values are derived from the supplied rules. This is a project baseline, not a claim that organizer output has been matched.
- **Website scope:** retain the website as a supplemental demo. The CLI command-file workflow is the required assignment path and does not need to mirror browser actions in the terminal.

## External comparison, if materials arrive

Organizer-provided command/data fixtures and expected-output files are not present. If provided later, run them as an additional compatibility comparison; the absence of those materials does not block local implementation or verification.

## Financial handling

- Late fees are calculated and reported separately from the final bill because the stated final-bill formula omits them. The brief does not define a separate late-fee collection path, so none is added.
- Damages remain a final-bill line item. The supplied GST bases name venue/packages and catering, so the implementation does not add GST to damages.
- Date-change credit is redeemable against a confirmed replacement booking before its first payment. Any leftover credit after the replacement contract has been fully offset remains unused on the source booking.

## Submission note

The capstone email requires the Week 4 repository to include the README, design document (PDF), test suite, decision log, and a 5-minute demo video link. The CLI is the required command-file deliverable; the website may be shown as an additional demo surface. Organizer-specific command/data fixtures and expected outputs are not in the project yet, so exact compatibility comparison remains pending until they are provided.
