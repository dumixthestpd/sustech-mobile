# Changelog

## 0.3.27 — 2026-10-06

Fixes language tutoring, which shipped in 0.3.26 working only while an E-Hall
session was live — and failing confusingly the moment it expired.

- 🔴 The session was being handed to the page **by host**, and matching cookies by
  host **drops any cookie scoped to a path**. E-Hall's session cookie is exactly
  that, so the page loaded signed out, its own queries bounced to CAS, and the
  screen showed a network error (or spun on "preparing") instead of the list.
  Cookies are now matched against the full URL, path included.
- The page's cookie store is cleared before the bootstrap: a stale entry there made
  the app page and CAS bounce off each other until the load died with
  `ERR_TOO_MANY_REDIRECTS`.
- When CAS does ask for a sign-in, the screen answers it **on the page** — filling
  CAS's own form with the stored account — instead of assuming a silent hand-off.
  If that does not take, it says so plainly rather than spinning.
- A booking's status now reads the display field, so a cancelled reservation shows
  `取消预约` instead of a raw code, and the reservation's own topic reads with it.

## 0.3.26 — 2026-10-06

The last of the three "open the official page" services is read natively, and it
was the one that could not be done the way the other two were.

- **Language tutoring.** It now lists what you have booked with the language
  centre and how much of the semester's allowance is used (1/3 on the account this
  was built against). Opening it used to show the E-Hall page and ask you to sign
  in again. Booking stays on the official page on purpose: a reservation posts the
  form's entire control set and consumes one of the three slots.
- 🔴 E-Hall's app APIs refuse a plain HTTP request **even with valid CAS cookies**,
  and its session does not survive being copied into the app's own HTTP client —
  the same query replayed from the jar is refused. So the query is issued **by the
  page itself**, over a WebView that is a session rather than a screen, and the
  answer comes back through a JavaScript bridge.
- Two bugs came out of testing that path rather than from reading the code: two
  page loads shared a single answer slot, so answers were handed to the wrong
  question (it looked like the endpoints were refusing us); and the allowance
  arrives as a JSON number, which read as `0` through a string parse.
- Cookie marshalling now lives in one place (`core/WebCookies.kt`), shared with the
  in-app page hand-off instead of duplicated in the portal activity.

## 0.3.25 — 2026-10-05

Two of the three "open the official page" services are now read natively. Neither
was a matter of wrapping an API: each one's session had to be understood first.

- **Exchange programmes, read natively.** Opening Exchange used to show the
  platform's own SID/password form even though the app already holds your CAS
  account — the WebView hand-off was wrapped in `runCatching` and failed
  silently, so you were asked to sign in a second time. The listing is ours now:
  170 programmes with region, school, length and status, read from the service's
  JSON behind the app's session. Tapping a programme opens its official page
  **already signed in**, which is where the application form belongs.
- **Venue booking, read natively.** The E-Hall venue page is replaced by two
  tabs: the bookable rooms (name, type, building, capacity, how far ahead you can
  book, whether it needs approval) and your own reservations. Creating and
  cancelling a booking still happen on the official page — they change a real
  room calendar.

Language tutoring is the one that is *not* a port: E-Hall's app APIs refuse plain
HTTP even with a valid CAS session, because its JavaScript adapter has to run
first. Making that one native needs an in-app bootstrap, so it stays a page for
now.

## 0.3.24 — 2026-10-05

- **Exchange opened nothing when reached by deep link.** The module carried the id
  `ws` — with its string resources still named `service_ws` — while everything
  else called it "exchange". `Services.byId("exchange")` therefore returned null,
  and `ServiceActivity` silently `finish()`es on an unknown id, so
  `--es service exchange` closed the screen instead of opening the exchange
  platform. The widget's Exchange shortcut kept working only because it repeated
  the same wrong id. The id is now `exchange`, the strings are named for it, and a
  test pins the whole service-id list so a rename cannot pass unnoticed again.
- **The widget previews were blank.** Neither widget declared a preview, so the
  launcher's picker showed two empty white rectangles where a preview belongs.
  Both now preview their real layout.

## 0.3.23 — 2026-10-05

First release since v0.3.11. It bundles everything merged from the community
branch (0.3.14–0.3.18) with the library-loans and update-detection work below —
the intermediate versions were never published on their own.

- **Electronic campus card (一卡通) is a service.** Balance and recent bills read
  from the campus-card API with the stored account, plus a top-up hand-off that
  opens WeChat with the amount prefilled (payment completes in WeChat).
  <br><sub>Contributed by [@Shirakawa-Kotone](https://github.com/Shirakawa-Kotone).</sub>
- **Two launcher widgets.** One shows the campus-bus ETAs, Blackboard deadlines,
  the next class or the weather — you choose which when you add it. The other is
  a 2×2 grid of four campus shortcuts you configure yourself; it starts out as
  the electronic card, the campus-card QR, printing and the campus bus.
  <br><sub>Contributed by [@Shirakawa-Kotone](https://github.com/Shirakawa-Kotone).</sub>
- **Course reviews (牛娃社区).** Search a course, code or teacher and read the
  community's evaluation ratings — a service the catalog previously listed as
  planned.
  <br><sub>Contributed by [@Shirakawa-Kotone](https://github.com/Shirakawa-Kotone).</sub>
- **Faculty directory, offline.** A bilingual snapshot of the official public
  staff directory ships in the app, so name/title/department search works with no
  network; opening a person loads only that public profile.
  <br><sub>Contributed by [@Shirakawa-Kotone](https://github.com/Shirakawa-Kotone).</sub>
- **Official portals open in-app with the saved account.** Venue booking, exchange
  programmes, language tutoring and Wi-Fi open their real pages inside the app,
  with the stored CAS cookies handed to those pages so CAS-based ones start
  signed in. The exchange platform is the exception — it still shows its own
  login form.
  <br><sub>Contributed by [@Shirakawa-Kotone](https://github.com/Shirakawa-Kotone).</sub>
- **Chinese and Russian.** The whole interface is translated, including the names
  the campus-bus and library APIs return, instead of surfacing their English.
  <br><sub>Contributed by [@Shirakawa-Kotone](https://github.com/Shirakawa-Kotone).</sub>
- **Sessions are shared and honest.** CAS now accepts an existing SSO session when
  it issues a ticket immediately, one cookie store serves every service, and a
  service is reported unavailable only after its API actually says so.
  <br><sub>Contributed by [@Shirakawa-Kotone](https://github.com/Shirakawa-Kotone).</sub>
- **My borrowed books, with due dates and one-tap renew.** The library gains a
  Loans tab: everything you have out, when each book is due, and its renewal
  state. Renewing shows the exact request first and sends only after you confirm
  — renewal quota is limited and a wasted renewal cannot be refunded.
- **The app notices its own updates.** The Account tab checks GitHub Releases when
  it opens; when a newer build is published, a card appears with the version and a
  button that opens the release page. Current builds and offline moments stay
  silent.

## 0.3.22 — 2026-10-05

- **My borrowed books, with due dates and one-tap renew.** The library gains a
  Loans tab: everything you have out, when each book is due, and its renewal
  state. Renewing shows the exact request first and sends only after you
  confirm — renewal quota is limited and a wasted renewal cannot be refunded.
- **The app now notices its own updates.** The Account tab checks GitHub
  Releases when it opens; when a newer build is published, a card appears with
  the version and a button that opens the release page. Current builds and
  offline moments stay silent — an update check must not nag louder than the
  network deserves.
- **Loans strings follow the app language** (English, Chinese, Russian).

## 0.3.18 — 2026-10-02

- **Campus QR is now a separate 2×2 widget.** It has its own launcher entry and
  refresh control; the general 2×3 widget no longer offers the QR code. Existing
  2×3 QR cards show how to replace them.

## 0.3.17 — 2026-10-02

- **Campus card QR has its own 2×2 widget.** It is a separate launcher item
  with a compact code display and immediate refresh button. Old configurable
  QR cards show instructions to replace them with the new widget.

## 0.3.16 — 2026-10-02

- **The electronic campus card is easier to reach.** The campus card page opens
  inside the app using the existing campus-card CAS session.
- **A live campus-code widget is available.** It requests a fresh code about
  every 30 seconds, can be refreshed immediately, and hides the code on failure
  instead of showing a stale one. The QR payload is not written to app storage.
- **Language tutoring API investigation continues.** The authenticated booking
  page loads in-app, but no stable appointment API schema has been verified, so
  native appointment data is not shown yet.

## 0.3.15 — 2026-10-02

- **Library labels follow the app language.** Chinese and Russian now localize the
  catalogue credit and shelf availability, instead of exposing the API's English
  status values.
- **Faculty lookup is local.** A compact bilingual snapshot of the official
  public directory is bundled in the app; name, title and department search works
  offline, and opening a person loads only that public profile. The crawl script
  refreshes the snapshot from the school's directory.
- **Exchange opens the platform first.** Project information is available from a
  top-right toolbar action, and the app requests the platform's own CAS ticket
  using the stored school account.
- **Language tutoring opens its booking app directly.** The saved account is used
  to establish the E-Hall service session first. The scheduler's actual read API
  is loaded dynamically and is not present in its published page/scripts, so a
  native appointment list is not included until its live request schema can be
  verified.
- **CAS supports an existing SSO session.** When CAS immediately issues a service
  ticket, the client now captures and exchanges it instead of expecting a login
  form every time.

## 0.3.14 — 2026-10-02

- **Service status is now accurate.** The catalog labels implemented pages as
  implemented; Printing also probes PMS when the Services screen opens and shows
  unavailable when its public API returns HTTP 405 or cannot be reached.
- **Venue booking, faculty, exchange, language help, and Wi-Fi now open useful
  service pages.** Official SUSTech pages load inside the app; portal pages reuse
  the app's saved CAS session. Wi-Fi opens Android's network settings. Course
  reviews / 牛娃社区 remains planned as requested.
- **Campus bus language follows the app.** Chinese uses the API's Chinese stop,
  route, and direction names; English and Russian use English names, and arrival
  states, distances, direction labels, and widget copy are localized.

## 0.3.11 — 2026-10-01

- **Stops are places now, not berths.** The API serves 33 stops, but they are 18
  landmarks: "Hui Yuan Uphill" and "Hui Yuan Downhill", "Gate 1 (1)" and
  "Gate 1 (2)". The four busiest stops listed four route names in a row ("Gate 2
  Short-turn B · RSB Short-turn A · Line 1 · Line 2"). Berths of one landmark are
  now one row and its service is merged to one token per family —
  **"1/2 · Uphill/Downhill"**. Requested as "merge uphill/downhill, 1 and 2".
- **The direction is a choice, made after tapping in.** Tapping a place opens the
  Buses tab with a chooser — **CW / CCW / Uphill / Downhill**, only the ones that
  actually leave from there — and the list shows that direction alone. One berth
  can carry several directions (Hui Yuan Uphill serves Uphill *and* CW *and* CCW),
  which is why all four used to be mixed into one list. A place with a single
  direction has nothing to ask and picks it silently. Requested as "it ask you for
  directions. display as 'cw/ccw' 'uphill/downhill'".
- **"Board" is now "Buses"** — buses on the way, which is what the list is.
  Requested as "in the BOARD section, it actually means BUSES".
- Direction words are shortened at the edge, never in the data: `Clockwise` →
  `CW`, `Counter-Clockwise` → `CCW`; `Uphill`/`Downhill` were already the words
  riders use. Ring lines are labelled by number, derived from the route name
  (`Line 2` → `2`) rather than a hardcoded id, so a new or renamed line still reads
  right.
- All of it is pure data over `/stops`, which already carries each berth's route
  *and* direction — so `stops()` no longer needs `routes()` to name things, and
  no extra request is made. `TransitBusesFragment`'s refresh prefix was also wrong
  (`bus.board.` never matched the `bus.arrivals.*` entries), so pull-to-refresh did
  not actually re-ask the API; it does now.

## 0.3.10 — 2026-10-01

- **Papers is gone from the catalog.** It was a roadmap card with no
  implementation behind it, and the school's paper search is not something this
  app can do better than a browser. Requested as "drop papers feature".
- **New: Library — how full it is, and where a book is.** Two questions, three
  tabs, and neither question needs an account, so the screens work on the way to
  the library rather than after arriving.
  - **Inside** reads the library's own homepage (`lib.sustech.edu.cn/main.htm`),
    which server-renders a live headcount (`在馆人数`) and the per-building
    split — 1777 inside, 一丹 865, 琳恩 780 when this shipped. There is no JSON
    behind it, so the number is read out of the HTML. Entries the library hides
    behind an HTML comment (涵泳) are stripped before counting, so a building the
    library has stopped advertising cannot reappear. There is no seat or capacity
    figure anywhere, so this reports people, not a percentage.
  - **Books** searches Primo's public record API (`/primaws/rest/pub/pnxs`), the
    same one the Discovery SPA calls, and **Where** lists every copy from
    `delivery.holding`: building, collection, call number and availability —
    "Lynn Library · 3rd Floor / Shelf O62 /E 4:2 第111排A面 / Available", verified
    on device. A record with no physical copy says so instead of showing an
    empty list.
  - Not wired yet, though verified live: the IC booking system's room
    availability (`booking.lib.sustech.edu.cn/ic-web/home/page/room/idle` — 讨论间
    9/13 and 6/11 free at the time). It needs a CAS booking session, so it is a
    separate piece of work from the two public reads above.
  - 🔴 Primo's TLS needs **unsafe legacy renegotiation**, which Python's SSL stack
    refuses outright (`UNSAFE_LEGACY_RENEGOTIATION_DISABLED`) while curl and
    OkHttp both complete the handshake — verified from a JVM OkHttp 4.12 probe
    and again on the device. Any future work on this host must probe with curl or
    OkHttp, not urllib, or it will read as an outage.
- **Transit: the board now says where the bus actually is.** `/arrivals` and
  `/vehicles` share a `trip_id`, and the vehicle's own 1-based stop index
  (`current_position.next_stop_num`, checked against the direction's stop order)
  subtracts from the rider's stop index to give **"8 stops to go · next 学生宿舍北"**.
  A countdown alone cannot tell a rider whether the bus is one stop away or stuck
  at the far end of the loop. Cross-checked live: 8 stops against an 8-minute
  ETA. Timetable rows have no vehicle and show no such line.

## 0.3.9 — 2026-10-01

- **The app has an academic calendar now, bundled in the APK.** The timetable TIS
  serves is a *pattern* — "weeks 2, 4, 6 … on Thursday" — and it makes no mention
  of holidays, so on 2026-10-01 the Today card announced a 19:00 lecture during
  国庆节. `assets/calendar/<year>/` now carries verbatim copies of the
  `sustech-calendar` repo (the same files `sustech_survival.calendar` parses), and
  `edu.sustech.mobile.calendar.Term` turns a pattern into real dates: week numbers
  anchored on the Monday on or before `teaching_start`, holidays, makeup days,
  extra breaks and final weeks. Reported as "according to sustech survival,
  sustech mobile and sustech cli, what class do i have today?" — the other two
  clients said no class, the app did not.
- **"Next up" and the "Next class" widget now share one rule.** They each carried
  a copy of the selection logic, which is how the two could drift; both call
  `tis.NextClass`. A meeting already finished today no longer counts, and a
  holiday-flushed meeting counts on its makeup day — or not at all.
- **The week line names the holiday** when today is one (`Thu · 2026Fall · 国庆节`),
  so an empty "Next up" says why. The holiday name is calendar data, shown
  verbatim, like the room names already on the card.
- **This week drops classes a holiday flushed.** A row with no real meeting left
  in the week is gone; a row a holiday moved stays, in the week it moved to.
- **Without a calendar the app degrades, it does not guess.** A date no bundled
  year covers falls back to the pattern-only reading — what the card did before —
  so an unfilled 2028 says too much rather than too little.
- **Every file in the bundle is the repo's, proven byte-for-byte.** `assets/calendar/`
  is `dumixthestpd/sustech-calendar` at pinned commit `e5e102c7`: the repo's root
  `README.md` (as `upstream-README.md`) and `2026/{general,graduate,undergraduate}.json`.
  Each file's git blob sha1 is checked against the repo's own tree listing, so a
  wrong calendar cannot slip in unquestioned. The repo's
  `2026/academic-calendar-2026.pdf` is deliberately **not** packaged — nothing
  reads it — and it is the one omission, named in the README so its absence is
  not mistaken for a foreign copy.
- Bundling is deliberate on this host: `raw.githubusercontent.com` is unreliable
  from Python here (hangs, `RemoteDisconnected`) while curl works, and the copy
  in `~/.sustech_survival/cache` on 2026-10-01 did **not** match upstream — it
  had dropped five of the six holidays, both makeup days and the extra break, and
  reported `total_teaching_weeks` 17 instead of 16. Refresh instructions and the
  blob hashes are in `app/src/main/assets/calendar/README.md`.

## 0.3.8 — 2026-10-01

- **Sign-in stopped waiting on the print server.** Fixing the order in 0.3.7
  was not enough: the print probe still ran on every sign-in, and it is the
  half that cannot answer off campus — it costs a CAS handshake plus the print
  system's RSA password login, and it can only ever change the verdict when CAS
  itself answered nothing. Courses are asked first now and settle the account on
  their own; printing is probed only when the course probe could not answer at
  all. `ensurePrint()` still runs it lazily where it is needed. Reported as
  "i need to speed up the login verification".
- **A saved account no longer holds the app shut.** `LoginActivity` is the
  launcher and ran the whole two-service verification before opening anything,
  so every launch began on a spinner. With credentials already stored there is
  nothing to ask: the app opens immediately, the verification runs alongside it
  on an app-level scope, and its verdict lands in the sign-in note the Account
  tab shows. Reported as "it's faster to first continue without credentials,
  then refresh to get the course info".
- **Fixed: two screens signing in at once could each report a dead session.**
  `Cache` remembered a value only once its load had finished, so the background
  verification and the Today tab both walked the CAS re-login path; the second
  found the re-login already in flight, gave up, and cached the failure — a
  spurious "session expired" on a session that was fine. Loads are single-flight
  per key now.
- Sign-in logs its verdict with per-probe timings under the `SustechSignIn` tag,
  so the cost of each half is measured rather than assumed.

## 0.3.7 — 2026-09-14

- **Licensing settled: PolyForm Noncommercial only, no commercial offering.**
  The earlier draft dual-license was rolled back: the school name and torch
  mark, plus reverse-engineered campus endpoints, make selling commercial
  rights a liability with no realistic buyers. The author retains full rights
  as sole copyright holder (all commits by one person) and may price their
  own distribution; README now carries public funding commitments (annual
  transparency, threshold-then-free, contributor share). README rewritten
  for a public audience in the `sustech_survival` README's shape.
- **Fixed: the credential check ran against the print server first.** Sign-in
  probed PMS before CAS, so off campus — where the print host 403s or drops
  packets — every sign-in ground through dead print timeouts before CAS was
  even tried, which read as "stations search timed out forever". CAS/TIS now
  goes first: it is the credential verdict, it answers from any network, and
  the campus-only print probe runs last. Reported as "you don't test creds by
  using pms service".
- **The test-server card is gone from the Account tab** unless a server
  override is actually configured. The shipping UI no longer shows any print
  server address; the field remains reachable only by pointing the app at a
  mock first. Reported as "i will kill you for keeping the test server as pms".
- **Session rows are labeled by service** — "Courses (CAS)" and "Printing
  (campus only)" — and the raw sign-in note line ("courses: … · printing: …")
  is gone: it restated the two rows above it in transport vocabulary. No
  print-server hostname appears anywhere on the Account tab now.
- **New Network row on the Account tab** telling the three states apart:
  campus network (print host answers normally), online off campus (print host
  answers its campus-only 403, CAS answers), and no network (nothing answers).
  Verified in all three states on the emulator: airplane-mode style shutoff →
  "No network"; this Mac's off-campus network → "Online, off campus".
- **Funding stance: the Android app ships free.** The upstream `sustech-cli`
  developer approved this app as a new project and reminded that people don't
  like charged public services — so the APK carries no price and the LICENSE
  file is untouched. A paid iOS edition is only a maybe: considered after the
  Android release, only with massive demand, license revisited then. Final
  README pass by the maintainer: shorter, first-person, funding commitments
  off the public page, iOS section just "We will consider iOS releases in the
  future."
- **CONTRIBUTING.md added**, stating up front that the license may change —
  the budget problems for iOS publishing are the public reason.

## 0.3.6 — 2026-09-13

- **Fixed: every clock time was wrong.** The app carried the exam-hall period
  table (10:00-10:50, 13:00-13:50 …) instead of the teaching grid. It now reads
  the real grid from `component/queryKbjg` (period 1-2 = 08:00-09:50, 3-4 =
  10:20-12:10, 5-6 = 14:00-15:50, 9-10 = 19:00-20:50 …), caches it, and falls
  back to those values rather than the old ones.
- **Fixed: the `ZC` week bitmap was read one week off.** It is zero-padded, so
  index *i* is week *i*; reading it as *i+1* shifted every course a week and made
  a "1-15单周" lab show up in week 2 — reported as "amse still in this week".
  Odd/even labs now appear only in the weeks they run.
- **"This week" is built from the whole-term timetable**, filtered by that bitmap.
  The single-week endpoint answers with the week's rows whether or not the course
  runs in it, which is why the lab kept appearing.
- **Less fetching, so the screens stop feeling slow**: week/term timetables,
  grades, exams, the period grid, print points and scan/queue lists are cached
  in memory (20 s – 30 min by kind). Opening a screen or resuming one shows what
  the session already knows; pull-to-refresh and the toolbar button still fetch
  immediately, and a delete or an upload drops the affected entry. Sign-out
  clears everything.
- **A way in without an account**: "Continue without signing in" on the sign-in
  screen opens the shell; the catalog and campus weather work without a session,
  and each service screen says it needs one.
- The server override is now `-PserverUrl=` (the old `-PpmsBaseUrl=` still
  works), the harness defaults to the CAS-backed `tis-live` scenario instead of a
  print one, and a `no-signin` scenario covers the new button.

## 0.3.5 — 2026-09-13

- **Printing signs in through CAS first, like the website does.** Reported as
  "only pms don't work": the print back end links the CAS identity to the print
  account (creating one on first visit), which is the route the Python client's
  refresh takes as well. The site's own RSA password login stays as the fallback,
  and a local test server skips CAS entirely. One retry now covers a dropped TLS
  handshake ("connection closed"), which campus networks produce routinely.
- **"无效会话，未登录" no longer reaches the screen.** TIS and the print API
  answer a dead session with that phrase inside an otherwise normal error
  envelope; every such marker now triggers the silent re-login instead of
  surfacing as a Chinese "Network error".
- **The dashboard refreshes when pulled.** It had no pull-to-refresh container at
  all (only the list screens did), so the gesture did nothing. New `refresh`
  scenario drags for real and asserts the data reloads on both the dashboard and
  a list.
- **The dashboard shows one meeting — "Next up"** — instead of the whole day:
  time and course on the first line, teacher, room and weeks on the second.
- **Odd and even weeks are now visible everywhere**: "Weeks 3-17 (odd weeks)",
  "Weeks 2-15 (even weeks)", and rows that carry no week bitmap (the single-week
  endpoint) fall back to parsing the range out of the schedule text instead of
  counting as every week. Because the dashboard filters by the bitmap, a
  biweekly lab no longer appears in a week it does not run.
- Harness: sign-in waits for the shell *or* the sign-in screen, so a slow cold
  start no longer looks like a missing bottom bar.

## 0.3.4 — 2026-09-13

- **Fixed: an upload could still fail with a cleartext error although the server
  address was a correct https one.** Reported from campus with the message
  showing an https address; the only way both can be true is that the server
  answered the upload with a redirect to an `http://` page (the 云打印 result
  page), OkHttp chased it, and the platform blocked cleartext — surfacing as a
  misleading "plain HTTP was attempted" failure on a healthy server.
  API calls no longer follow redirects at all: a 302 to CAS now means "session
  gone" (silent re-login), a 302 at an `http://` address is reported as the
  server's own mismatch, and anything else is reported as an unexpected
  redirect. The sign-in handshake keeps its own redirect-following client — its
  ticket exchange *is* a redirect chain.
- **Uploads now treat the queue as the verdict on every answer**, including
  redirects and cleartext blocks: if a new job with that file name is in the
  queue, it worked and the screen returns to the queue. Only a genuinely
  unqueued upload is reported as a failure.
- Harness: `--upload-redirect-http` mock behaviour plus a
  `pms-upload-redirect-http` scenario (accept the job, then bounce to an
  `http://` result page — the reported case); the driver now waits out the app's
  silent sign-in instead of pressing BACK on the sign-in screen, and a parked
  sign-in screen reports what it says instead of a raw "no node" error.

## 0.3.3 — 2026-09-13

- **Fixed: an accepted upload was reported as a failure, so the screen never came
  back to the queue.** Reported from campus: the job did reach the printer queue,
  the app said otherwise. Cause: the 云打印 form posts with `BackURL=result.html`,
  so an accepted upload can answer with the HTML result page instead of the JSON
  envelope, which the app read as "not an envelope → failed". The queue is now the
  source of truth: the app snapshots the job ids before the upload and reports
  success once a new job with that file name is in the queue, whatever the body
  said. A server that answers 200 without queueing anything is still reported as a
  failure, and the message now carries the HTTP status and a body snippet.
- Harness: `pms-upload-html` and `pms-upload-dropped` scenarios with two mock
  behaviours (`--upload-html`, `--upload-drop`) so both answers stay covered.
- `inject_session.py --mock-port` points the app at a mock on any port without
  putting an address on the command line.

## 0.3.2 — 2026-09-13

- **Fixed: a plain-`http://` server address could be configured and then fail
  with OkHttp's raw "CLEARTEXT communication … not permitted by network security
  policy".** The address is now normalised: a bare host gets `https://`, and
  plain HTTP is upgraded to HTTPS unless the host is a local test server
  (emulator host alias, localhost, private address), where the network policy
  allows it. The stored value is rewritten on startup so a hand-edited address
  cannot linger. Reported from a real install.
- **Fixed: "Session expired — sign in again" was shown for every print-session
  failure**, including failures that had nothing to do with the session. The
  banner now reports the real cause — campus-only 403 becomes "Printing needs the
  campus network", a configuration or transport failure keeps its own wording,
  and only an explicit refusal offers the sign-in action. Same for the courses
  banner, which no longer claims a session problem when the network is at fault.
- Clarified in the docs and the harness comments: the print tests run against the
  local mock through the emulator host alias, so they need neither the campus
  network nor a VPN; the only campus-dependent observation is the 403 the real
  host returns off campus.

## 0.3.1 — 2026-09-13

- **Fixed: the bottom bar was invisible in dark mode.** The app used a DayNight
  parent theme while every color in it is a fixed light palette, so on a device
  in dark mode the bar painted white icons and labels on its white background —
  nothing showed until an item was selected and picked up the brand color. The
  theme is now Light-only, dark mode is explicitly forced off, and the bar's
  icon/label tints are an explicit color state list (brand when selected, muted
  ink otherwise) instead of theme defaults. (Reported by the user.)
- **Fixed: an off-campus failure can no longer look like a bad account.**
  `Session.signIn()` now classifies the outcome — `ACCEPTED`, `REFUSED` or
  `UNREACHABLE` — and only an explicit refusal from CAS or the print login is
  treated as wrong credentials. Printing is campus-only, so its 403 (or any
  timeout, DNS failure or 5xx) is "unreachable": the user is let into the app
  with an explanation instead of being told their password is wrong.
  `ApiException` gained a `refused` flag to carry that distinction.
- Harness: new `theme` scenario asserts, from the pixels, that the bottom bar
  actually paints in both device themes (`cmd uimode night` yes/no) — uiautomator
  reports an invisible item as present, so a row count can never catch this.

## 0.3.0 — 2026-09-13

- **Credentials-only sign-in: one account, entered once.** The app stores the
  school account on first use and reuses it for every service; sessions renew
  themselves silently. Approval: user directive — one cred, auto-login forever,
  like the Python client.
- **The browser sign-in is gone.** `WebLoginActivity` and its layout are
  deleted, and no screen opens a WebView. TIS refuses mobile browser sign-ins,
  and the native client needs no browser, so CAS is now implemented directly in
  `sso/CasLogin.kt` (execution token → credential POST → ticket → cookie
  exchange, desktop user agent + XHR header) — approach adopted from
  `sustech_survival`'s `CASAuthorizer`.
- **Invisible expiry.** `PmsApi` and `TisApi` retry a call once after
  re-authenticating from the stored account (`withRelogin`), so an expired
  session never surfaces as a prompt.
- **The sign-in screen mentions no service.** It asks for a student ID and a
  password, says the account is stored on the phone and reused, and nothing
  else; the per-service copy and the print-server field moved out.
- Account tab reworked around the stored account: identity, per-service session
  state, **Forget account** (credentials + sessions), and the test-server
  override.
- Harness: `inject_session.py --creds` copies the school account in (never
  printed) so the app signs in by itself; `drive_ui.py` waits for that silent
  sign-in instead of treating "the login screen is up" as "unconfigured".
- Icon: the torch mark is scaled to 67% of its previous size, in both the
  launcher icon and the sign-in mark. Approval: user request.

## 0.2.0 — 2026-09-13

- **Multi-service shell.** The app is no longer a print client: a service
  catalog (`service/Services.kt`) plus a generic `ServiceActivity` host means a
  new SUSTech service is one entry and one root fragment. The bottom bar is
  fixed at Today / Services / Account, so the shell does not change as the
  catalog grows.
- **New service: courses & grades (TIS).** This week's timetable (week number
  from TIS itself), the term's enrolled courses grouped one row per course, all
  posted grades with the credit-weighted GPA, and the exam schedule. Sign-in is
  the school page in a WebView; writes (selection, bidding, evaluation) are not
  in the app.
- **New screen: Today.** Week number, today's classes, campus weather and air
  quality, next exam. Weather/AQI are public APIs and work off campus; the rest
  says so when TIS is not signed in.
- **App icon is the project's own torch mark.** `res/drawable/ic_torch.xml` and
  `ic_torch_mark.xml` are generated from `sustech_survival/resources/logo.svg`
  (the artwork the Electron app and the web UI already ship) with the fill
  changed from `#ed7005` to the wordmark green `#004851`; the placeholder
  printer glyph is gone. Approval: user request to use their torch, recoloured.
- **English-only UI.** The Chinese default strings file is gone, the app is
  named SUSTech Mobile (no Chinese name), and every label comes from
  `res/values/strings.xml`. Derived labels (idle/busy/fault, duplex, usage
  type, settle type) moved out of the wire layer into resources, so `pms/` and
  `tis/` now speak codes only.
- One WebView sign-in for every service (`WebLoginActivity`), parameterized by
  entry URL and the cookie that proves the landing; the print-only RSA account
  login stays in `PmsAuth`.
- Session probing is per service: the launcher advances if any stored session
  still works, and the Account tab reports the printing and TIS sessions
  separately.
- `tools/inject_session.py`: puts the mock server URL or a live TIS session into
  the installed app, so screens can be verified without typing credentials into
  a WebView. Cookie values are never printed.
- `tools/drive_ui.py` rewritten for the new navigation with four scenarios
  (shell, pms-smoke, pms-upload, tis-live), row-count assertions instead of
  "the screen looked right", and a wait for network-backed lists.
- Fixed two real bugs found by running against the live service: the timetable
  room was read out of the teacher's bracket, and the single-week endpoint's
  missing `ZC` bitmap left the week range blank. Teachers are now comma-spaced
  and de-duplicated, and repeated lab meetings collapse to one.
- Service catalog driven from the same submodule list the Python and TypeScript
  clients use — approach adopted from sustech-cli.

## 0.1.0 — 2026-09-13

- New project: Android client for SUSTech campus services, first subsystem
  PMS 联创云打印.
- Feature parity with the print website: cloud-print upload with all five
  print options, queued print documents list + per-job delete, scanned
  documents list + per-document delete, paginated usage report with date
  range and type filter, print-point list with server-group filter, account
  and session state.
- Two sign-in paths: CAS in a WebView (cookie only, no password) and the
  site's own RSA login flow (`Auth/GetAuthToken` → `Auth/PublicKey` →
  `PKCS1v15(password;nonce)` → `Auth/Login`).
- Session cookies persisted; off-campus `403 Access forbidden` reported as a
  campus-network message instead of a JSON parse failure.
- `tools/mock_pms.py`: offline API double with a real RSA handshake, so the
  app is testable without the campus network.
- Endpoint set and field semantics ported from `sustech_survival.pms` (Python)
  and `sustech-cli` (TypeScript) — approach adopted from sustech-cli.
- Debug build-time server override (`-PpmsBaseUrl=...`) so a test APK can point
  at a LAN or mock server without a code change.
- `tools/drive_ui.py`: uiautomator-driven emulator harness with three scenarios
  (WebView sign-in, password sign-in + all tabs + delete, upload round trip).
- Verified on an Android 14 emulator against the mock: CAS sign-in, password
  sign-in, all five tabs, job deletion (queue count asserted), and a full file
  upload that came back listed in the print queue.
