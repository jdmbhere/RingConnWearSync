# Changelog

All notable changes to this project are documented here. The format follows
[Keep a Changelog](https://keepachangelog.com/en/1.1.0/) and the project uses
[Semantic Versioning](https://semver.org/spec/v2.0.0.html).

Releases are gated: a tag must name the version in `version.properties`, its unit
tests must pass and both release APKs must assemble, or the tag does not ship.
`scripts/audit-watch-strings.py` additionally fails the build when a one-line
watch label is too long for the space its surface leaves.

## [Unreleased]

### Changed

- **The README says how RingConn actually publishes to Health Connect.** Measured on RingConn
  4.5.0: the official app writes only while its screen is open and focused, drains a backlog at
  about one write per second, and stops when it loses focus. The Limits section now states that,
  lists what goes stale meanwhile, and no longer describes an automatic launch of RingConn that
  the bridge does not perform.

### Fixed

- **Every published metric now comes from RingConn's own Health Connect records.** When the ring
  had no row for resting heart rate, oxygen saturation, HRV, steps, sleep, total calories, the
  overnight heart rate behind the sleep dip, or skin temperature, the reader published whatever
  another app had written for that type — Google Fit's steps, a Polar strap's HRV — with nothing on
  the watch saying whose number it was. A metric the ring did not write is now unknown, which is the
  path the reader already took when Health Connect returned nothing for it. Pinned by
  `HealthConnectOriginTest`.
- **The calorie goal on the watch is the one RingConn shows.** The sport widget prints the unit
  between the amount and its goal (`288`, `kcal`, `/300`); the parser looked for the goal right
  after the amount, found the unit, and kept the cached goal. Pinned by `CalorieTargetParsingTest`.
- **The watch no longer prints a bedtime window or a skin-temperature baseline nobody measured.**
  The sleep coach showed `22:45 - 23:30` when the window was blank, and the temperature cards fell
  back to 36.1 °C when only the offset had arrived. The bedtime row is now omitted, the baseline
  reads `--`, and the explanation no longer quotes a fixed figure. Pinned by
  `NoSyntheticBedtimeAndBaselineTest`.

## [1.9.42] - 2026-11-06

### Changed

- **Sleep stages are aggregated from every nocturnal Health Connect session instead of estimated.**
  RingConn does not write one session carrying a stages list; it writes 25-40 short
  `SleepSessionRecord`s, one per stage interval, plus a coarse umbrella session, so the old reader
  took its per-stage minutes, awake time and bedtime window from a single fragment and filled in
  the rest. Every nocturnal session now feeds `SleepStageMerger` (30 s epochs, specificity wins),
  the efficiency denominator is the span from bed time to the final awakening, and the merged night
  was verified against the RingConn app's own sleep page: deep 107 vs 108 min, REM 60 vs 60,
  light 245 vs 245, awake 20 vs 18, efficiency 92 vs 92.
- **Skin temperature is the ring's per-night record.** The cached `40.0 °C` baseline was never
  produced by the ring: it was a stale value kept alive by a guard that compared its ceiling
  inclusively and by two parser regexes hand-carved to match it. Temperature now reads the
  `SkinTemperatureRecord` Health Connect publishes per night, newest `lastModifiedTime` wins, and
  shows the baseline and offset the RingConn app itself shows (35.92 °C, -0.2 °C overnight). The
  guard ceiling is 38.0 °C, the regex carve-outs are gone, and `READ_BODY_TEMPERATURE` left the
  manifest because nothing consumes it.
- **A watch-triggered sync publishes once, not up to three times.** The watch's request persisted
  a scrape, fired an unforced watch push plus a delayed Health Connect read, and then published a
  forced sync of its own: one action, up to three publications, two Health Connect reads and an
  extra wake lock. The scrape takes a `publishFollowUp` flag now, the watch-triggered caller opts
  out while keeping the persistence, and the measured round trip fell from 0.64 s to 0.26 s.
- **The accessibility scrape is an optional fallback, not a source.** Measured on device, the
  scraped heart rate comes from no labelled `bpm` token and the RingConn UI exposes no parseable
  clock token, so a scrape can never be timestamped and can never take precedence over the widget.
  With the service disabled the published payload is identical field for field, and the app's own
  source label has never credited the scrape. The scaffolding that instrumented the question,
  including two tests that asserted on production source text, is removed; Glance widgets plus
  Health Connect remain the authority.

### Fixed

- **The bridge stays alive across app updates, and the widget worker can be started without the
  UI.** An in-place update takes the old process down with the widget host binding inside it; the
  update path brings the bridge back, and the watch-message path pokes the widget worker directly
  instead of depending on the app's screen. Pinned by `RingConnWidgetHostReaderTest`.

## [1.9.41] - 2026-11-03

### Changed

- **The watch only shows vitals the ring measured.** The reader filled missing measurements with
  arithmetic: a nightly anchor plus fixed offsets for the sleep-phase heart rates (`avgHeartRate 73`
  produced 66/69/71), the stress score times a circadian factor per block (47 produced 32/44/51),
  the sleep score plus five for the sleep efficiency, and free 18/17 points plus a 45 floor for the
  computed sleep score. Every one of those numbers looked measured, so an empty card could not be
  told apart from a reading. They now come from the measurement or stay `0`, decided in one place by
  `MeasuredVitalsPolicy` and pinned by `MeasuredVitalsPolicyTest`. Values written by the old
  arithmetic are dropped from the continuity cache once instead of being restored as data
  (`LegacyVitalsCacheTest`).
- **Basal calories, distance and respiratory rate come from Health Connect records.**
  `BasalMetabolicRateRecord`, `DistanceRecord` and `RespiratoryRateRecord` replace
  `minutesSinceMidnight x 1.166`, `steps x 0.000762` and a pulse/HRV formula; a record the ring did
  not publish leaves the field at `0`. The three read permissions joined the core set, so Health
  Connect asks for them once and the ring's records become readable.
- **The vitals screen no longer calls the breath rate an estimate.** It was described as derived
  from pulse and HRV; it is now the ring's own record, and the card shows `--` until the ring sends
  one.
- **Distance and basal calories are gone from the watch.** Neither can ever carry a measurement on
  this device: the ring writes no `DistanceRecord` at all (the Health Connect Distance category holds
  only Google Fit and the handset), and its basal record stores the energy of a half hour — about
  36 kcal — in a type defined as a rate per day, so the plausibility rule rejects every record. The
  two rows left the activity tile, the daily activity detail card, and the two dead permissions left
  `AndroidManifest.xml` and the core Health Connect set, so the grant screen asks for less. The
  fields stay on the phone-to-watch wire model and hold `0`, so the contract is unchanged.
- **A field that can still be measured is omitted instead of shown as `--`.** The three sleep-phase
  heart rates, the bed efficiency and the four circadian stress blocks are unknown only until the
  first measurement arrives, so their rows are left out entirely and reappear by themselves; a night
  or a day with none of them degrades to a short honest line instead of a grid of dashes. The primary
  metrics keep their `--`, which still means "the phone has not synced yet".

## [1.9.39] - 2026-10-24

### Changed

- **The watch APK is 3.69 MB instead of 26.52 MB.** R8 and resource shrinking were off, so the
  release carried all of Compose, Wear Compose, ProtoLayout, the Tiles runtime and Play Services
  whether the app called them or not: 25.3 MB of the 26.4 MB was unshrunk DEX. Minification is
  now on for the watch release. Everything the system instantiates survived — the five tile
  services, the complication providers, the detail activities and the `WearableListenerService` —
  and the smaller DEX also means less to load, decompress and optimize on a device with a small
  battery.
- **The release key has its own password.** Releases were signed with the Android debug
  certificate, whose keystore password is the public string `android`: anyone holding a copy of
  that file could sign an update Android would accept. The key is re-wrapped into PKCS12 under a
  real password with `scripts/rewrap-release-key.sh`. A keystore password protects the container,
  never the identity, so the certificate is unchanged — which is why this installs over 1.9.38
  and keeps your Health Connect grants and tile setup. `signing-identity.properties` and
  `scripts/check-release-signing-identity.sh` make the published fingerprint checkable by
  machine, and both modules now sign from a properties file that lives outside any synced folder.
- **Android Lint runs in CI.** It was disabled in both modules and absent from the workflow. Its
  first pass found 44 real errors, all fixed in this version.

### Added

- **The sleep diagnostic can leave the phone.** The dialog has a *Copy diagnostics* action that
  puts the report on the clipboard behind a fixed header: versionName, versionCode, API level and
  device. Nothing is transmitted — the user pastes it wherever they already talk to the project —
  and the clip is marked sensitive so the system does not preview the night's stage minutes on a
  lock screen.
- Three screenshots in the README, generated from the app on a Galaxy Watch 8.
- Weekly grouped Dependabot updates for Gradle and GitHub Actions.
- `signing-identity.properties`, `scripts/check-release-signing-identity.sh` with a sixteen-case
  regression suite, and `scripts/rewrap-release-key.sh`.

### Fixed

- **The phone had no theme on Android 8 and 9.** The manifest pointed at
  `Theme.DeviceDefault.DayNight`, which arrived in API 29, under a minSdk of 26. It now goes
  through a local `AppTheme` that resolves to the day/night variant where it exists and to the
  closest one that does below that.
- **`registerForActivityResult` sat on `androidx.fragment` 1.1.0**, which
  `play-services-basement` drags down from 1.0.0. A `FragmentActivity` below 1.3.0 overrides
  `onActivityResult` and consumes the result the registry is waiting for. Nothing here extends
  one, so the fix is a dependency constraint rather than a rewrite.
- **Fourteen resource strings carried a literal percent sign** ("100% exact", "10–20% goal") with
  no format arguments anywhere. They are marked `formatted="false"`, which is what tells AAPT
  the percent is text; escaping it as `%%` would have displayed a doubled percent.
- **`ComplicationType.GOAL_PROGRESS` needs API 33** while the watch app supports API 30, and the
  README claims Watch 4 and Watch 5 support — those run API 30 and 31, where the branch would
  have failed on a field that does not exist. It is behind an explicit version gate now;
  behaviour on API 33 and later is unchanged.
- **The low-battery alert was posted without `POST_NOTIFICATIONS`**, which Android 13 and later
  drop silently. The permission is declared and the notify call logs why it cannot post. The
  vibration runs before that check on purpose, so the alert still reaches the wrist.
- **The bridge asked for `INTERNET`, `ACCESS_NETWORK_STATE` and `CHANGE_NETWORK_STATE`** while
  neither module contains a network call. The first and last are gone; `ACCESS_NETWORK_STATE`
  survives only because `androidx.work:work-runtime` declares it, and no work request here sets a
  network constraint. A unit test fails the build if any of the three reappears.
- An unanchored SpO2 pattern that no reader used was removed, so the next person to touch oxygen
  saturation does not build on it.

## [1.9.38] - 2026-10-24

### Fixed

- **Sleep stages still wrong after 1.9.37, because the Health Connect read itself preferred
  the cache.** Two spots kept the old "whichever source declares more sleep wins" rule:
  `keepExistingStages` inside the Health Connect read and `keepCachedSleep` in the widget-branch
  merge. With the reported night the cache summed 450 minutes against 364 from Health Connect, so
  the cache won every sync and the write policy added in 1.9.37 preserved that value faithfully.
  Both now defer to `SleepSourcePolicy`: Health Connect owns a night it has, the cache covers one
  it does not.

## [1.9.37] - 2026-10-24

### Fixed

- **Stage minutes could be overwritten by the wrong source.** Six places persist vitals and
  only two resolve stages properly; the accessibility scrape and the widget reader run last
  and stored their own numbers, which is why a night displayed deep 99 / rem 104 / awake 0
  while Health Connect held 55 / 60 / 60 and the merge produces exactly that. A non-authoritative
  write now keeps whatever another source already resolved, except on a device where nothing is
  stored yet and the widget is all there is.

## [1.9.36] - 2026-10-24

### Fixed

- **Two labels still shipped in Spanish**, and the audit could not see them: it was a word
  list, and `Leer Widgets Directos` has no accents and no listed word. The guard now rests
  on a language-agnostic rule — a line that paints text must take it from `getString(...)` —
  and only ignores literals that interpolate values. Six real offenders were found and
  migrated, the title now uses `R.string.app_name`, and the hardcoded
  `Galaxy Watch 8 Classic` subtitle is neutral.
- The sync button says "Sync now with the watch" instead of naming one watch model.

### Added

- `SleepMergeReproTest` feeds a reported night's 28 real intervals into the stage merge:
  deep 55 and rem 60, matching Health Connect and contradicting the 99/104 the watch shows.
  It documents that awake loses fragments concluding at the umbrella start.

## [1.9.35] - 2026-10-24

### Fixed

- **A whole screen of the phone app was still Spanish.** The i18n audit only inspected
  lines containing a UI sink, and the metric cards pass their labels as arguments to
  helpers, so `FRECUENCIA CARDÍACA`, `PUNTAJE DE SUEÑO`, `BPM • Prom:` and `En uso`
  never entered the check. The audit now reports every literal except comments, log
  calls, and the parser's Spanish match patterns, and the 25 strings it found are in
  resources with English as the default.
- **The footer claimed `v1.6.1` on every build.** It now reports the version of the
  installed package, so users can tell which build is running.

## [1.9.34] - 2026-10-24

### Added

- The sleep diagnostic prints `typeTotalsMin`, the fragment durations Health Connect
  returned grouped by stage type, next to `persistedStages`, what the app stored for the
  watch. The two numbers decide whether a wrong night comes from the merge or from a
  different source, without another round of guessing.

### Fixed

- The README claimed stage minutes are counted in 30-minute epochs. They are counted in
  30-**second** epochs, so short awakenings are not lost to rounding.

## [1.9.33] - 2026-10-24

### Fixed

- **Nineteen more UI strings were still authored in Spanish.** The i18n audit had
  reported success because its word list was short and case-sensitive, so the sleep
  diagnostic's own label, its "Close" button and several notices stayed in Kotlin. The
  audit now matches case-insensitively, counts any accented character as proof, and
  inspects only literals that reach a UI sink — which also stops it from flagging
  `RingConnTelemetryParser`, whose Spanish keywords are patterns for reading the
  official app's Spanish labels and must stay as they are.

## [1.9.32] - 2026-10-24

### Changed

- **The sleep diagnostic reports stage types, not only their count.** Every session from
  the hardware report carried exactly one stage record, and the reader maps only five
  types; a fragment with an unmapped type is silently dropped and the night reads empty
  even though Health Connect holds it. Each session now prints `types=[...]`, and the
  report closes with the longest session and the distinct stage types received, so it can
  be read at a glance instead of sixty-six lines.

## [1.9.31] - 2026-10-24

### Fixed

- **The phone screen was in Spanish for everyone.** Not a locale problem: the UI was
  written as 46 Spanish literals inside Kotlin, and text in code has no language, so
  Android never had anything to translate. All of it now lives in resources with
  English as the default and Spanish in `values-es`; interpolated strings use
  positional placeholders. A source audit fails the build while any user-facing
  literal still carries Spanish, and names every offender.

## [1.9.30] - 2026-10-24

### Added

- **In-app sleep diagnostic** on the phone: a "Diagnóstico de sueño" button reads the
  sleep sessions of the last 36 hours and reports, for each one, its start, end,
  duration, stage count and whether it falls inside the window the reader uses. It
  exists because "the watch shows the wrong night" could not be resolved remotely:
  with no cable and no adb, the app has to be able to say what it sees. The window
  rule is a pure function with five tests.

## [1.9.29] - 2026-10-24

### Fixed

- **Sleep stages stayed wrong after 1.9.28 even with a correct Health Connect
  record.** The score matched the official app because it comes from the widget;
  the stages did not, because their source was chosen by "whichever claims the most
  sleep" — widget, then cache, then Health Connect. A cache left inflated by the
  earlier merge bug outbid the corrected record permanently, and a per-field
  fallback could build a night from two different sources. Health Connect now owns
  the night whenever it holds one, the cache only covers a night Health Connect has
  not received, and no field is taken from a second source.

## [1.9.28] - 2026-10-24

Published as a pre-release: the sleep fix has not been verified on hardware yet.

### Fixed

- **Sleep stages read wrong even when Health Connect held the data.** The merge
  resolved overlapping segments by `lastModified`, so the newest segment won each
  epoch. RingConn rewrites a coarse night-long segment when it syncs, and that
  rewrite carries the newest timestamp, so it overwrote the short deep, REM and
  awake segments: the night collapsed into one stage and deep/REM read as zero.
  Specificity now wins over recency — a shorter segment owns the epochs it covers,
  recency only breaks ties between segments of equal length, and arrival order
  cannot change the result (`SleepStageMerger`, 5 tests).

### Changed

- **Overnight polling backs off.** The in-service poll ran every five minutes
  around the clock, waking the RingConn app each cycle for data that only changes
  every 15-30 minutes. Between 23:00 and 06:00 the interval is now 150 minutes, so
  a night costs three wake cycles instead of about eighty-four. Reported as 6%
  overnight drain where the same night used to cost 2-3%.

### Known limitations

- Nothing on the phone checks that a captured value is present before sending it:
  `DataMap.getInt` returns 0 for a missing key, so an unmeasured stage and a
  measured zero are indistinguishable on the watch.
- The morning capture still opens the RingConn app for up to 24 seconds, aborting
  after 6 if the app is not in front — which happens when the phone is locked.

## [1.9.27] - 2026-10-24

### Fixed

- Sixteen more card titles were cut mid-word where they share a row with a value:
  stand hours, total TDEE, phase heart rate, cardiac coherence, circadian blocks,
  immune radar, daily range, baseline temperature, autonomic tone and the body
  battery status. Titles now wrap to two lines. Value texts are deliberately left
  on one line, since a number split across two lines is worse than a truncation.

## [1.9.26] - 2026-10-24

Removes fabricated biometric data from two screens. Found by auditing the app
against its own documentation; no user report exposed it.

### Fixed

- **Stress detail** drew a twenty-four bar timeline from an hour formula and
  printed three fixed percentages (`74%`, `26%`, `64`) for every user regardless
  of their data. RingConn records four circadian stress blocks a day and nothing
  finer, so there was never an hourly series to draw. The card now renders the
  four measured blocks, treats an unmeasured block as a gap rather than a measured
  zero, and summarises the lowest, spread and peak of the blocks that exist.
- **Measure button** waited a fixed 3.5 s and then persisted the previous heart
  rate plus a random offset of −2 to +3 bpm, stamped with a fresh timestamp, which
  made an invented number indistinguishable from a measured one. Both entry points
  now route the request to the phone and require the reading's own
  `refreshTimestamp` to advance before claiming a refresh. An unreachable phone
  fails in 3 seconds, and a failed attempt says so instead of returning to silence.

### Changed

- Card titles wrap to two lines instead of being cut mid-word; labels that share a
  row with a value use short copy. Affected: HR zones, heart-rate rhythm and HRV
  titles, blood oxygen, strain radar, stress timeline and the nocturnal
  temperature curve.

### Known limitations

- The measurement gate requires a completion signal **and** an advancing reading,
  but cannot prove the signal belongs to the request that asked for it. A
  concurrent sync that persists a reading satisfies both conditions. Correlating
  request and response needs the phone to echo a request identifier, which is a
  change in the mobile module.
- `WearDataListenerService` publishes the completion signal before it persists the
  payload, so the gate can observe a timestamp that has not been updated yet.
- Parts of the UI are still written as `isSpanish` ternaries or English-only
  literals inside Kotlin (`Optimal Recovery`, `Balanced Charge`), so they are
  neither translatable through resources nor visible to the string audit.

### Infrastructure

- `version.properties` is the single source of the app version; a release gate
  workflow refuses a tag whose version disagrees with it.
- Every push uploads the debug APKs as workflow artifacts, so testing on a device
  no longer requires publishing a release.
- The release certificate fingerprint is documented in the README.

## [1.9.25] - 2026-10-23

### Added

- Heart rate measurement time shown on the main Heart Rate tile.

Older releases predate this changelog; their notes live on the
[releases page](https://github.com/Hardbas/RingConnWearSync/releases).
