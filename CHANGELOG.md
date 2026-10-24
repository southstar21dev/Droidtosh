# Changelog

All notable changes to Droidtosh. The project follows [semantic versioning](https://semver.org);
see [docs/RELEASING.md](docs/RELEASING.md).

## Unreleased

- The Mac menu bar popover is cleaner on Liquid Glass: its sections and quick actions share one
  translucent fill, corner radius and spacing instead of opaque cards next to glass buttons.
- The Mac Notifications screen with no apps yet fills the window with the app's background instead
  of showing a box behind the message and a grey toolbar.

## 0.1.1 – 2026-10-23

- The Mac app now uses the same Roast & Cream palette as the Android app: dark roast and cream
  surfaces, a matching accent colour, and tonal icon tiles.
- A file the phone was sending no longer fails with "Not supported by the Mac" when the connection
  drops; it waits and resumes after the reconnect.
- The relay answers `ERROR upgrade` to a join line for any protocol version other than the current one.

## 0.1.0 – 2026-10-23

First public release.

- Notifications from the phone as native macOS banners, with replies, actions and per-app muting.
- Calls: answer, decline and mute incoming calls on the Mac, place calls, and browse recent calls.
- Clipboard text and images in both directions.
- Files and folders in both directions, up to 2 GB, resuming after interruptions.
- Phone battery, network and sound status in the menu bar, low-battery alerts, and Find my phone.
- Connection through a self-hosted relay, directly over the local network, or the relay with the
  local network as a fallback.
- In-app updates from GitHub releases on both platforms, with an opt-in beta channel.
