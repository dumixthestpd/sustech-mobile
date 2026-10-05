# SUSTech Mobile

A clean Android app for SUSTech campus services. Anywhere, anytime.

## Features

- **Printing** (`pms`) — 联创 cloud print: upload with all five options, queue + delete, scans, usage report, stations
- **Courses & grades** (`tis`) — this week's timetable on its real period grid, enrolled courses, grades + GPA, exams
- **Course reviews** (牛娃社区) — search a course, code or teacher and read the community's evaluation ratings
- **Blackboard** (`bb`) — enrolled courses and the assignments coming due
- **Campus card** (一卡通) — balance and recent bills, with in-app recharge
- **Library** — how many people are inside each library, where a book is (floor, shelf, availability), and **your borrowed books with due dates and renewal**
- **Transit** — the campus shuttle: every stop as a *place*, nearest first; pick a direction and see the buses on the way, live
- **Today** — week number, next class, campus weather, AQI, next exam
- **Widgets** — one shows the next class, the next bus, campus weather or Blackboard deadlines (you pick); the other is a 2×2 grid of four campus shortcuts you configure
- **Account** — stored school account, per-service session state, network state (campus / off campus / offline). It also tells you when a new release is out.

The Services tab opens official school portals for venue booking, exchange programs, and language
tutoring; those flows reuse the saved CAS account. Exchange opens its application platform first,
with project information in the top-right menu. Language tutoring opens the E-Hall booking system
directly. Faculty search uses a bilingual snapshot of the public roster, so name, title, and
department searches work offline; selecting a person opens their official profile. The tutoring
system loads its schedule through dynamic requests that are not exposed by its public page, so a
native appointment list still needs a verified request/response schema.

Course selection, bidding and evaluation are intentionally **not** in the app.

## Install

Grab the APK from [Releases](../../releases) (Android 8.0+), open it, allow "install unknown apps"
once, sign in with your school account.

The APK is a sideload build — not a Play Store app. Release v0.3.23 and later install over your
existing copy, so you will not have to uninstall anything to update.

## Sign-in

Your CAS credentials are **stored on your device**. Sessions renew themselves silently.

**Forget account** on the Account tab clears credentials and sessions.

Printing needs the campus network (or a VPN back to campus) — PMS answers 403 from outside. You can
check your account state in the Account tab.

## Build and test

Building, testing and releasing are covered in [CONTRIBUTING.md](./CONTRIBUTING.md).

## iOS

We will consider iOS releases in the future.

## Related projects

- **[sustech_survival](https://github.com/dumixthestpd/sustech_survival)** — the Python client this app's TIS wire semantics come from
- **[sustech-cli](https://github.com/wormforce/sustech-cli)** — the TypeScript client the print wire semantics come from

## Data & credits

The app is a client. These are the services and datasets it talks to, and the terms they come with:

- **Campus shuttle** — [sustech.online](https://sustech.online) (`buseta.sustcra.com`), CC BY-SA 4.0.
  API only; none of its data is redistributed in this repo.
- **Library occupancy and catalogue** — `lib.sustech.edu.cn` and SUSTech's Ex Libris Primo.
- **Faculty directory** — public names, titles, departments, and profile links from the official
  [Chinese roster](https://www.sustech.edu.cn/zh/letter/) and its English alphabet pages. The
  compact snapshot is bundled in the APK for local search; individual profiles open on demand.
  Refresh it with `python3 tools/crawl_faculty_directory.py`.
- **Academic calendar** — mirrored from
  [`dumixthestpd/sustech-calendar`](https://github.com/dumixthestpd/sustech-calendar) at a pinned
  commit and bundled in the APK, so the app can tell a holiday from a teaching day with no network.
  See `app/src/main/assets/calendar/README.md` for the pin.
- **Weather** — `api.sustech.online/weather`. **Air quality** —
  [Open-Meteo](https://open-meteo.com) (CC BY 4.0).

Your courses, grades, print jobs and Blackboard data are your own: fetched with your own credentials
and sent nowhere else.

## About the dev

I am [dumixthestpd](https://github.com/dumixthestpd), same maintainer as `sustech_survival` — a
non-CS undergrad at SUSTech. This repo is also Agent-written code, known and accepted. Issues and PRs
welcome.

## License & funding

[PolyForm Noncommercial License 1.0.0](./LICENSE), inherited from
[`sustech_survival`](https://github.com/dumixthestpd/sustech_survival).
Non-commercial use only.

**The Android app is free** and stays free.

Note for contributors: the license may change in the future since we are trying to solve the budget
problems for iOS publishing — see
[CONTRIBUTING.md](./CONTRIBUTING.md).
