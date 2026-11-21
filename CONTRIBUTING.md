# Contributing

Issues and PRs are welcome — a bug report with what you saw and what you expected is worth more
than anything.

## Ground rules

- English only: UI, code, docs, commit messages. Data from the servers (course, room and teacher
  names) stays Chinese as received.
- API clients never follow redirects; a write is confirmed by reading the list back, not by trusting
  the response body.
- Wire layers speak raw codes, never UI copy.
- Tests run without the campus network — see [Testing](#testing) below.

## Build

JDK 17+ and Android SDK (platform 34):

```bash
export JAVA_HOME=/opt/homebrew/opt/openjdk@21
export ANDROID_HOME=$HOME/Library/Android/sdk
./gradlew assembleDebug
adb install -r app/build/outputs/apk/debug/app-debug.apk
```

## Testing

No campus network needed — print flows run against a local mock that speaks the same wire format
(including the RSA login):

```bash
python3 tools/mock_pms.py --port 8080
python3 tools/inject_session.py --base-url http://10.0.2.2:8080 --creds
python3 tools/drive_ui.py --scenario pms-smoke    # also: shell, pms-upload, tis-live, theme
```

`tis-live` runs against the real TIS with your stored account.

The release-only device check uses synthetic attachments and the real Mobile content provider:

```bash
./gradlew testDebugUnitTest assembleRelease assembleReleaseAndroidTest
adb install -r app/build/outputs/apk/release/app-release.apk
adb install -r app/build/outputs/apk/androidTest/release/app-release-androidTest.apk
adb shell am instrument -w edu.sustech.mobile.test/edu.sustech.mobile.ShareFlowCheck
```

It requires the small local `edu.sustech.sink` ShareSink receiver and `edu.sustech.probe` ShareProbe
sender installed on the device. Their
source and build tools are at `D:/dumix/projects/wsa-share-probe` on the development machine.
The check covers PDF, DOCX and image previews, reads the Mobile provider, shares through Android's
chooser to the external receiver (including SHA-256 and seek checks), and chooses **Upload to PMS**
with the same BB file, an external-provider share, a missing-file reset, and ClipData-only receipt.
It never logs in or uploads. It writes screenshots under the app's external
`files/share-check` directory: inspect them, then delete them after review. The instrumentation APK
is a test companion and must never be attached to a public release.

The same runner has a dashboard suite, without the share-helper apps:

```bash
adb shell am instrument -w -e suite dashboard edu.sustech.mobile.test/edu.sustech.mobile.ShareFlowCheck
```

Use `-e suite search` with that runner for the WS and future-room search controls. It uses a
synthetic catalog and room calendars, checking combined filters, reset, unknown calendars and
future availability that deliberately differs from current occupancy. It never submits an
application or reservation. Review and remove external `files/search-check` screenshots afterwards.

The dashboard suite exercises the native day/week grid with overlapping synthetic classes and exams,
a bounded 20-announcement summary, deadline-to-content navigation, and the home deadline intents
for a specific content item or the full deadline list. It does not sign in or submit data.
Inspect and remove screenshots from external `files/dashboard-check` after review. This fixture
check does not prove that a signed-in account has published exams; verify live TIS separately.

For changes to the native presentation, also inspect Today, Services, WS and future-room search
in a phone-width window (390dp) and a wide window. Verify that selector labels remain readable,
that occupied/unconfirmed room badges differ from available rooms, and that the service catalog
still scrolls to every registered module. Check actual BB deadline navigation after restarting
the app to clear the instrumentation's synthetic in-memory cache. Remove temporary screenshots
after visual review; keep the build log and signed APK for identification.

**Before publishing a release, install it and exercise the screens you changed on a device or on
Windows Subsystem for Android.** Unit tests and a green `assembleDebug` do not prove the app runs:
v0.3.22 shipped with the entire Loans tab dead — every fetch failed — because that code had never
executed on a device. `adb shell uiautomator dump` plus reading the text back is enough to check a
screen.

## Release signing

Published APKs are signed with a release key so that releases install over each other. That key is
**not in this repo** — this repo is public, and a leaked signing key lets anyone sign an update that
Android installs silently over the official app. It lives in the private repo
[`sustech-mobile-signing`](https://github.com/dumixthestpd/sustech-mobile-signing) together with
`keystore.properties`.

A checkout reaches it through the gitignored `local.properties`:

```
signing.properties=<path to>/keystore.properties
```

```bash
export JAVA_HOME="<jdk-17>"
./gradlew assembleRelease
apksigner verify --print-certs app/build/outputs/apk/release/app-release.apk
```

Without `signing.properties` the release build is simply unsigned, so a fresh clone still builds.
Losing the key and leaking it cost the same thing: the next release carries a new key, and every
existing user has to uninstall and reinstall once. A signing key cannot be rotated without that.

## License

The project is under the PolyForm Noncommercial License 1.0.0. **The license may change in the
future** — we are trying to solve the budget problems for iOS publishing. By contributing you accept
that your contribution may be carried into such a future license.
