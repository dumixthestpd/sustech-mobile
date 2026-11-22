# v0.3.31 — Home layout, timetables and campus search

Arrange the home screen in Account, swipe through outstanding Blackboard assignments, and find
campus services and future discussion-room availability more directly.

- **Home layout:** drag cards and service shortcuts into order and choose which ones appear. The
  first visible card receives optional green emphasis. Settings save automatically and live only
  in Account; Home starts with a compact date followed by content. Existing card choices migrate
  without losing their effective order. Choose Home, Blackboard deadlines or campus bus on launch.
- **Blackboard work:** hide an assignment from pending work once a submitted attempt is confirmed,
  including attempts awaiting grading. Swipe between pending assignments and open the exact task.
  A course-scoped Submitted work page shows published grades, individual attempt scores and teacher
  feedback. Drafts, unreadable states, unpublished grades and exemptions are distinguished.
- **Timetable and exams:** Home previews today's complete timetable with a current-time marker.
  Courses provides day/week grids, overlapping events, classroom locations and TIS exam arrangements;
  exams without published clock times remain listed separately.
- **Bus directions:** switch directions or nearby stops directly, with immediate next-stop labels.
  The optional home bus card remembers the chosen stop and direction and uses the existing
  live/planned data, including explicit non-operating states.
- **Exchange projects and discussion rooms:** search/filter the complete WS catalog by university,
  region, year, project type and eligibility. Search rooms by floor/text and a future continuous
  time interval; retain the selected date/time when opening a room's calendar and booking sheet.
- **Presentation:** compact Blackboard announcements, a grouped service catalog and service filters,
  paper-colored surfaces and responsive card layouts. Air-quality data explicitly states modeled
  US AQI and its data time, with invalid/stale values rejected.

## Updating

Install the release-signed APK over v0.3.23 or later to keep the stored account and local settings.
Version code 40 also installs over the locally tested 0.3.31 development builds through dev.6 (39).
The signing certificate is unchanged. Blackboard material preview/sharing and the Upload to PMS
share target from v0.3.30 remain available.

## Limits and verification scope

- Configurable deadline-notification alarms remain a separate demo; this release does not schedule
  native reminders.
- Air quality is a provider's modeled US AQI, not a local campus monitoring-station measurement.
- Missing room-calendar data remains unconfirmed; availability does not itself create a booking.
  Blackboard may not yet have published a grade or teacher feedback.
- Live signed-in reads and native UI checks cover pending/submitted work, feedback, layout and
  persistence. Synthetic device scenarios cover drag ordering, assignment navigation, timetable
  overlap, announcements and combined search filters. No real assignment submission, reservation,
  exchange application or print job is created during release verification; those remote writes
  have not been exercised end to end for this release.
- Actual PMS uploads still require campus-network access. Opening its share-upload screen does not.
