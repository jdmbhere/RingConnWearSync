# Project Rules — RingConnWearSync

## 1. What This Project Is
RingConnWearSync is a local-only, clean-room Android & Wear OS interoperability suite that reads biometric telemetry published by the official RingConn app (`com.gdjztech.ringconn`) via Android Health Connect, `AppWidgetHost`, and `AccessibilityService`, and pushes it over the Google Play Services Wearable Data Layer to a Samsung Galaxy Watch / Wear OS device (five One UI stadium tiles, seven watch-face complications, and Compose detail screens).

## 2. Stack and Directory Layout
- **Language & Build**: Kotlin 2.1.0, Android Gradle Plugin 8.8.0, JDK 21, Android SDK Platform 35.
- **`:mobile`** (`mobile/`): Android phone companion bridge (`io.github.hardbas.wearsync.bridge`).
  - `service/RingConnTelemetryReader.kt`: Health Connect client queries + widget/cache merge policy.
  - `service/RingConnWidgetHostReader.kt`: Persistent `AppWidgetHost` binding (`HOST_ID = 4488`) and `RemoteViews` inflation/scraping across the 6 RingConn Glance widget providers.
  - `service/RingConnAccessibilityService.kt`: Accessibility semantics tree scraper and BAL-exempt morning peek.
  - `service/RingConnTelemetryParser.kt`: Token parser for widget `RemoteViews` and accessibility nodes.
  - `service/WearDataSyncService.kt`: Wearable Data Layer (`DataClient` / `MessageClient`) transport.
- **`:wear`** (`wear/`): Wear OS companion app (`io.github.hardbas.wearsync.app`).
  - `tile/`: Five ProtoLayout tiles (`HeartRateTileService`, `SleepTileService`, `StressTileService`, `DailySummaryTileService`, `VitalsTileService`) and shared `tile/design/` tokens.
  - `complication/`: Seven `SuspendingComplicationDataSourceService` providers.
  - `ui/`: Jetpack Compose for Wear OS detail activities.
- **`scripts/`**: CI and release guard scripts (`check-version-tag.sh`, `check-release-signing-identity.sh`, `audit-watch-strings.py`).

## 3. Install, Run, and Test Commands
```bash
# Run unit tests across both modules
./gradlew :mobile:testDebugUnitTest :wear:testDebugUnitTest --console=plain

# Run Android Lint (errors fail CI)
./gradlew :mobile:lintDebug :wear:lintDebug --no-daemon

# Assemble debug APKs
./gradlew :mobile:assembleDebug :wear:assembleDebug --no-daemon

# Run watch string length audit & release guard tests
python3 scripts/test-audit-watch-strings.py && python3 scripts/audit-watch-strings.py
bash scripts/test-check-version-tag.sh
bash scripts/test-check-release-signing-identity.sh
```

## 4. Branch and Commit Policy
- **Default branch**: `master`. Feature branches use descriptive prefixes (e.g. `feat/...`, `fix/...`).
- **Commit format**: Conventional Commits (`<type>(<scope>): <short imperative description>`) in English (`feat`, `fix`, `docs`, `style`, `refactor`, `test`, `chore`).
- **No AI trailers**: Never include `Co-Authored-By` or generator signatures in commit messages (disclosed at project level in `README.md`).
- **Release tags**: `version.properties` (`versionCode` and `versionName`) is the single source of truth and must match any `v<versionName>` git tag (`RELEASING.md`).

## 5. ODD Threshold and Verification Rule
- **Small task**: Understood in one bounded read batch, no high-risk item, and resumable from the diff alone. Execute inline; observe RED before fixing when a deterministic test applies, then run the focused test and the full unit test suite once each.
- **Large task**: Anything failing the resume test or touching high-risk areas gets `odd/tasks/<feature-name>.md` before the first source edit.
- **UI & String changes**: Any change to `:wear` strings must pass `python3 scripts/audit-watch-strings.py`, and `:mobile` UI strings must not hardcode Spanish literals in Kotlin (`NoHardcodedSpanishUiTest`).
- **TODO**: Confirm with maintainer whether additional on-device ADB screenshot gates are required before merging `:wear` tile layout changes.

## 6. Never-Touch and Safety Boundaries
- **Zero Network Permissions**: Never add `INTERNET` or `CHANGE_NETWORK_STATE` to either manifest (`NetworkPermissionGuardTest` enforces this).
- **Clean-Room Interoperability (`CONTRIBUTING.md` / `NOTICE.md`)**:
  - Rely strictly on public Android OS APIs (`HealthConnectClient`, `AppWidgetHost`, `AccessibilityService`, Wearable Data Layer).
  - Never include decompiled third-party code, proprietary SDKs/binaries, extracted artwork, or private keys/tokens.
  - Never fabricate clinical metrics (e.g., blood pressure, dehydration/migraine risk) or invent a skin-temperature baseline when absent (`SkinTemperatureGuard`, `NoSyntheticHeartRateTest`, `NoSyntheticStressDataTest`).
- **Signing Identity (`signing-identity.properties`)**: Never alter the expected release certificate SHA-256 digest (`1ccf5348ff46a919620d806faa9a5e0e9d857bc74fdb643809ea3108827f1372`) or commit `keystore.properties` / `.p12` / `.keystore` files.
