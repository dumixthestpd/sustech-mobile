# What's new since v0.3.11

One note for the next release, covering everything that has landed since v0.3.11 —
including the work that was published and unpublished in between as 0.3.12–0.3.27.
Paste the section below the line as the release body; set the version number yourself.

The per-version history stays in [CHANGELOG.md](CHANGELOG.md); this file is the one
consolidated note, so removing intermediate releases loses nothing.

---

## New screens

- **Electronic campus card (一卡通)** — balance and recent bills read with your
  stored account, plus a top-up hand-off that opens WeChat with the amount filled
  in (the payment itself has to complete in WeChat, by university rule).
- **Course reviews (牛娃社区)** — search a course, code or teacher and read the
  community's ratings. The catalog used to list this as "planned".
- **Faculty directory, offline** — a bilingual snapshot of the public staff
  directory ships in the app, so name / title / department search works with no
  network. Opening a person loads only that public profile.
- **My borrowed books** — due dates and one-tap renew. Renewing shows the exact
  request first and sends only after you confirm: the renewal quota is limited and
  a wasted one cannot be refunded.
- **Two launcher widgets** — one shows campus-bus ETAs, Blackboard deadlines, the
  next class or the weather (your choice when you add it); the other is a 2×2 grid
  of four campus shortcuts you configure, starting with the e-card, its QR code,
  printing and the bus. Both preview their real layout in the picker.

## Services that used to hand you a browser

- **Exchange programmes.** Opening Exchange used to show the platform's own
  SID/password form even though the app already holds your account — the hand-off
  was wrapped in `runCatching` and failed silently, so you were asked to sign in a
  second time. The listing is ours now: 170 programmes with region, school, length
  and status. Tapping one opens its official page **already signed in**, which is
  where the application form belongs.
- **Venue booking.** Two tabs replace the E-Hall page: the bookable rooms (name,
  type, building, capacity, how far ahead you can book, whether approval is needed)
  and your own reservations.
- **Language tutoring.** What you have booked with the language centre, and how much
  of the semester's allowance is used.
- Booking, cancelling, renewing and recharging still happen on the official pages —
  those change a real room calendar, a real quota or real money.
- Wi-Fi, the e-card, printing and staff profiles open their official pages inside
  the app with your stored session.

## Interface

- **Chinese and Russian**, the whole interface — including the names the campus-bus
  and library APIs return, instead of surfacing their English.

## Reliability

- **Sessions are shared and honest.** CAS accepts an existing SSO session when it
  issues a ticket straight away, one cookie store serves every service, and a
  service is reported unavailable only after its API actually says so.
- **My borrowed books used to fail entirely**: the login hop's response body was
  read twice, which throws in OkHttp and surfaced as a network error.
- **Exchange's deep link opened nothing**: the module carried the id `ws` while
  everything else called it `exchange`, and an unknown id closes the screen. A test
  now pins the whole service-id list.
- **Language tutoring read correctly only while a session was live**, then failed
  confusingly once it expired — the session was handed to the page by host, and
  matching by host drops any cookie scoped to a path, which is exactly what the
  service uses. It also spins up its own page session now, so a stale cookie can no
  longer send the page and the sign-in service in circles.
- **The app notices its own updates.** The Account tab checks GitHub Releases when
  it opens and, when a newer build exists, offers a card that opens the release
  page. Current builds and offline moments stay silent.
- **Release APKs are signed with a real release key**, so a new version installs
  over the previous one instead of demanding an uninstall first.

## Known limits

- Campus-only services (printing, venue booking, the e-card, the Wi-Fi hand-off)
  need campus Wi-Fi or wired; off campus they say so instead of failing silently.
- Android 8.0 and newer (`minSdk 26`).
- Not exercised end-to-end yet: Blackboard, course reviews, the campus-code widget's
  live render, and the top-up hand-off (no real payment was ever submitted).

## Upgrading

- Coming from **0.3.23 or later**: installs straight over the top — same signing key.
- Coming from **v0.3.11 or earlier** (debug-signed): Android refuses the install.
  Uninstall the old app first; that clears the saved account, so you sign in once
  more.
