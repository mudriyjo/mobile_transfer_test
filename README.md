# Mobile Bank — Lead Mobile Engineering Assessment

This repository is a synthetic mobile banking application used in a practical Lead Mobile Engineer assessment. It contains Kotlin Multiplatform business and data code, Compose Multiplatform UI, Android and iOS application shells, and a deterministic backend stub.

The repository is intentionally larger than a four-hour exercise. You are expected to navigate to the most important user path, select one material production risk, and make a small, defensible change. A broad audit or architectural rewrite is not the goal.

All accounts, credentials, transfers, and user details in this repository are synthetic. Do not introduce real customer data, credentials, or production endpoints.

## Prerequisites

- JDK 21 or newer (the project emits JVM 17 bytecode);
- Android SDK 36 with an emulator or device for manual Android checks;
- macOS and Xcode with an iOS 16+ simulator for iOS application/XCTest checks;
- `curl` for direct backend-fault experiments.

The iOS shell uses direct Xcode integration and does not require CocoaPods.

## Start here

1. Read [ASSIGNMENT.md](ASSIGNMENT.md).
2. Run the environment check:

   ```bash
   ./scripts/doctor.sh
   ```

3. Verify the supplied baseline:

   ```bash
   ./gradlew candidatePreflight
   ```

4. Start the backend stub if you want to run an application manually:

   ```bash
   ./gradlew :backendStub:run
   ```

5. Open the project in Android Studio or IntelliJ IDEA. For iOS, open `iosApp/iosApp.xcodeproj` after the Gradle project has been imported once.

The default stub endpoint is `http://10.0.2.2:8080` on the Android emulator and `http://127.0.0.1:8080` on the iOS simulator. The application uses only synthetic demo credentials.

## Repository map

```text
shared/       Shared domain, application, data, persistence, and platform contracts
composeApp/   Shared Compose UI, navigation, theme, and reusable components
androidApp/   Android application entry point and platform integration
iosApp/       SwiftUI application shell and Compose framework host
backendStub/  Deterministic Ktor service for transfer and status scenarios
docs/         Product, API, lifecycle, architecture, security, and analytics contracts
scripts/      Environment and submission checks
```

The three product paths are:

- Accounts: a stable reference implementation for cached state and restoration.
- Instant transfer: biometric confirmation, submission, status tracking, and result navigation.
- Scheduled payment: a durable queue that shares transfer infrastructure and has device-time constraints.

Saved beneficiaries support the transfer path but are not a separate assessment target.

## Useful commands

```bash
# Fast host checks used at the start of the assessment
./gradlew candidatePreflight

# Shared and Android/JVM tests plus Android debug/release compilation
./gradlew verifyAndroid

# Native/Compose tests, framework linkage, unsigned host build, and XCTest (macOS only)
./gradlew verifyIosSimulator

# Full available verification for the current host
./gradlew verifyAssessment

# Show the size of the change against the assessment base
./scripts/change-budget.sh

# Check required deliverables and common submission mistakes
./scripts/submission-contract.sh
```

To run the iOS application target directly outside the aggregate task:

```bash
xcodebuild -project iosApp/iosApp.xcodeproj \
  -scheme MobileBank \
  -configuration Debug \
  -sdk iphonesimulator \
  -destination 'generic/platform=iOS Simulator' \
  -derivedDataPath build/xcode-derived \
  CODE_SIGNING_ALLOWED=NO build
```

To run only XCTest, optionally set `IOS_SIMULATOR_ID` to a specific available simulator:

```bash
./scripts/verify-ios-xctest.sh
```

`verifyIosSimulator` requires macOS and Xcode. If a required platform check cannot be run, record exactly what you ran, what failed, and what remains analysis-only in `DECISIONS.md`. Do not represent static inspection as a successful platform test.

## Architecture orientation

The shared module owns domain models, use cases, repositories, persistent business state, and the state reducers used by the shared UI. Platform implementations own facilities whose guarantees differ by operating system, including secure storage, lifecycle signals, biometric authentication, database drivers, network engines, and background execution hooks.

SQLDelight is the local source of truth for durable product data. Ktor is used for remote calls, Kotlin Serialization defines wire models, and Koin assembles shared and platform graphs. See [current architecture](docs/current-architecture.md) for the dependency map and [transfer API](docs/transfer-api.md) for the backend contract.

## Assessment constraints

- Timebox: four hours (240 minutes).
- Change one critical vertical slice; do not try to fix every issue.
- Add at least two meaningful checks, including a failure/lifecycle/platform case.
- Keep the production change within the budget described in `ASSIGNMENT.md`, or explain why exceeding it was necessary.
- Complete `PLAN.md`, `DECISIONS.md`, and `AI_USAGE.md`.
- AI coding tools are required, but you remain responsible for every submitted line and claim.

The accepted artifact is the commit SHA captured by the assessment system at the deadline.
