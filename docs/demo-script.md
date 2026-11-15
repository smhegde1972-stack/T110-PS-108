# Five-minute project demo script

This is a run sheet for recording the required short demonstration. It is not a video link or a claim that a video has been recorded. Use a clean checkout and the included sample data. Start the server from the project root before recording:

```bash
mvn package
java -cp target/classes in.ps108.WebMain
```

## Run of show

| Time | Screen and action | Narration |
|---|---|---|
| 0:00–0:25 | Show the project title, README, and repository folders. | “This is PS-108, a Java 17 booking engine with a local customer website and operations dashboard. The command-file workflow is the core deliverable; the browser UI is an additional demonstration surface.” |
| 0:25–1:10 | Open `http://localhost:8080`. Browse venues, select date, slot, guests and packages, and request a quote. | “The customer page gets venue, package, and quote information from the Java API, so the displayed amount is calculated by the backend.” |
| 1:10–2:05 | Open `http://localhost:8080/admin.html`. Create an enquiry and hold for an available slot. Show the hold expiry/queue area. If practical, create a second enquiry for the same slot and point out its queued state. | “Operations can create enquiries and 72-hour holds. When a hold expires, the next queued enquiry is promoted; the rule also has exact-expiry regression coverage.” |
| 2:05–3:00 | In a second terminal, run `java -cp target/classes in.ps108.Main data/venues.csv data/vendors.csv data/commands.txt`; show the quote, payment schedule, settlement and payout/dispute lines. | “The supplied timestamped command file demonstrates deterministic processing of the booking lifecycle. Master data comes from CSV and command events are ordered by timestamp.” |
| 3:00–4:10 | Show `docs/design-document.md` rule traceability and diagrams, then `docs/self-authored-acceptance-checks.md`. | “The rule table maps each business rule to its Java service and test focus. Boundary tests cover pricing, advances, cancellation, date-change credit, overdue cancellation, disputes, and holds. These are self-authored checks, not organizer fixtures.” |
| 4:10–5:00 | Show the Maven test summary and the data limitations in README. | “The latest clean test run passes 59 tests, and Maven packaging succeeds. Runtime bookings and payments are in memory and reset on server shutdown; there is no database or real payment gateway. Official organizer expected-output fixtures were not provided, so exact fixture matching is not claimed.” |

## Recording checklist

- Keep the server console visible only when needed; do not show private data or secrets.
- Use the included sample files and sample names only.
- If creating a second hold in the browser, use a date and slot not already occupied by a confirmed booking in that server session.
- Show the actual test summary from `mvn clean test`; do not edit or fabricate the result.
- Add the recorded video link to the repository only after uploading the actual video to the organizer-approved destination.
