# Contributing to Droidtosh

Thanks for helping. Bug reports, fixes and features are all welcome; for anything larger than a
small fix, open an issue first so we can agree on the approach. Security problems go through
[SECURITY.md](SECURITY.md), not public issues.

## Prerequisites

Xcode 26+, JDK 17, the Android SDK (API 35), Go (the version in `relay/go.mod`), and:

```bash
brew install protobuf xcodegen
```

The Xcode project is generated from `macos/project.yml`; the build scripts run XcodeGen for you.

For wake-up pushes, put your Firebase `google-services.json` in `android/app/` (see [Firebase setup](relay/README.md#firebase-setup-free)). Without it, the app builds, runs and reconnects without push.

## Repository layout

| Path | What's there |
|---|---|
| [`android/`](android) | Android app (Kotlin, Jetpack Compose, Material 3 Expressive) |
| [`macos/`](macos) | Mac app (SwiftUI) and `DroidsyncKit`, the shared Swift package |
| [`relay/`](relay) | The relay server (Go) |
| [`protocol/`](protocol) | The wire protocol: protobuf schema, test fixtures and [`PROTOCOL-v2.md`](protocol/PROTOCOL-v2.md), the spec all three implement |
| [`e2e/`](e2e) | End-to-end tests that run the real relay, a headless Mac peer and a JVM phone peer against each other |
| [`scripts/`](scripts) | Build, install and release-signing scripts |

## Common commands

```bash
make install          # rebuild both apps and replace the installed ones (keeps data and pairing)
make install-android  # just the phone (USB or wireless debugging)
make install-mac      # just the Mac app in /Applications
make build-all        # build both into ./builds
make test-android     # Android JVM unit tests
make test-swift       # macOS package unit tests
make test-relay       # Go relay unit and integration tests
make e2e              # end-to-end tests (relay + Mac peer + phone peer)
make proto-sync       # copy protos and fixtures into both platform trees
make fixtures         # regenerate binary fixtures from protocol/fixtures/*.txt
make generate-swift   # regenerate committed Swift protobuf sources
make verify-generated # fail if generated files are stale
```

`make install-android` installs over the app on whichever phone `adb devices` lists; set `ANDROID_SERIAL` to pick one. To skip the cable, pair the phone under Developer options › Wireless debugging, then `adb pair <ip:port>` and `adb connect <ip:port>`.

`make build-all` writes `builds/Droidtosh-<version>-<build>.apk` and `builds/Droidtosh-<version>-<build>-mac.zip`.

The canonical schema lives in `protocol/proto/droidsync/v1/`. Never edit `*.proto`, `*.pb.swift` or `fixtures/bin/*.bin` inside the platform trees directly; change the canonical copy and run `make proto-sync`.

## Branches

`dev` is where work happens, `staging` holds release candidates, and `master` is what releases are tagged from. CI runs on all three and on pull requests.

## Pull requests

- Branch from `dev` and open the pull request against `dev`.
- Keep the Mac app, the Android app and the relay compatible: if a change touches the wire
  protocol, update [`protocol/PROTOCOL-v2.md`](protocol/PROTOCOL-v2.md) and both apps together.
- Run the tests for what you changed (`make test-android`, `make test-swift`, `make test-relay`,
  and `make e2e` for anything that crosses the network). CI runs them all.
- Add an entry under **Unreleased** in [CHANGELOG.md](CHANGELOG.md) for user-visible changes.

Releases are covered in [docs/RELEASING.md](docs/RELEASING.md).

By contributing you agree that your contributions are licensed under the project's
[GPL-3.0 license](LICENSE).
