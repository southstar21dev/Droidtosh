# Releasing

How Droidtosh is versioned, signed and published, and how the apps find updates.

## Versions

Droidtosh uses [semantic versioning](https://semver.org): `MAJOR.MINOR.PATCH`, with `-alpha.N`, `-beta.N` or `-rc.N` for pre-releases (`0.2.0-beta.1` comes before `0.2.0-rc.1`, which comes before `0.2.0`).

- **Release tags** are the version with a `v`: `v1.2.0`, `v1.2.0-beta.1`. They are the only place the version lives; there is no version file.
- **Local builds** report the latest release tag with build metadata, for example `1.2.0+268.c5b81ac` (commit count and hash), which semver ignores when ordering, or `0.0.0+…` before the first release.
- **The updaters compare semantic versions**, so a beta never outranks its final release however it was built.
- **Build numbers** (Android's `versionCode`, the Mac's `CFBundleVersion`) are derived from the version by [`scripts/version.sh`](../scripts/version.sh), so they always rise with it: `MAJOR×10⁷ + MINOR×10⁵ + PATCH×10³` plus 1–299 for alpha, 301–599 for beta, 601–899 for rc and 999 for a final release. Minor and patch go up to 99.

## Signing keys (once)

Every release must be signed with the same keys as the installed apps, or updates are refused.

1. `scripts/create-android-release-key.sh` creates the Android release key in `~/.config/droidtosh/release.p12` and `android/keystore.properties` (both git-ignored). **Back them up: without the key, installed apps can never be updated again.** Local debug builds use the same key, so local and released builds can replace each other.
2. The Mac app is signed with the self-signed "Droidtosh Dev" code-signing identity in your keychain; the Mac updater only accepts apps signed by it.
3. `scripts/upload-release-secrets.sh` stores both as GitHub Actions secrets (`ANDROID_KEYSTORE_BASE64`, `ANDROID_KEYSTORE_PASSWORD`, `ANDROID_KEY_ALIAS`, `MAC_SIGNING_P12_BASE64`, `MAC_SIGNING_P12_PASSWORD`), asking before each.

## Publishing

Releases are cut by [semantic-release](https://semantic-release.gitbook.io) from [Conventional Commits](https://www.conventionalcommits.org), the same way as in the other southstar21dev apps ([release workflow](../.github/workflows/release.yml), [config](../.releaserc.json)):

1. Merge `dev` into `master` through a pull request. Since the last release, `fix:` or `perf:` commits give a patch release, `feat:` a minor one, and `feat!:` or a `BREAKING CHANGE:` footer a major one. Anything else (`chore:`, `docs:`, `refactor:`, …) releases nothing.
2. A semantic-release dry run picks the version, and both apps are built and signed with it.
3. Only when both builds pass does semantic-release tag the commit `vX.Y.Z` and publish the GitHub release, with notes generated from the commits and `Droidtosh-<version>-<build>.apk` and `Droidtosh-<version>-<build>-mac.zip` attached. `droidtosh-update.json`, a manifest with the version, build number and each file's SHA-256, is attached last. Nothing is committed back, so `master` stays protected.

Pre-releases (`-alpha.N`, `-beta.N`, `-rc.N`) are published by hand: publish a GitHub release tagged `vX.Y.Z-beta.N` with **Set as a pre-release** ticked, and the release workflow checks the tag and attaches the same builds. To rebuild an existing release's assets, run the release workflow by hand with its tag.

The apps read the repository's recent releases plus its latest stable release (so no number of betas can hide it). They skip drafts, skip pre-releases unless beta versions are on, and skip releases without a manifest (still building); of the rest, they offer the highest version above the installed one. The repository has to be public for this to work.

Old releases stay available for download; publishing a new one never removes them.
