<p align="center">
  <picture>
    <source media="(prefers-color-scheme: dark)" srcset=".github/brand/droidtosh-stacked-white.svg">
    <img alt="Droidtosh" src=".github/brand/droidtosh-stacked-black.svg" width="220">
  </picture>
</p>

<h3 align="center">Your Android phone, on your Mac.</h3>

<p align="center">
  Notifications, calls, clipboard and files between an Android phone and a Mac,<br>
  over your local network or a relay you host yourself. End-to-end encrypted, no accounts.
</p>

<p align="center">
  <a href="#install">Install</a> |
  <a href="#features">Features</a> |
  <a href="docs/PRIVACY.md">Privacy</a> |
  <a href="relay/README.md">Self-host a relay</a> |
  <a href="docs/TROUBLESHOOTING.md">Troubleshooting</a> |
  <a href="https://github.com/southstar21dev/Droidtosh/releases">Changelog</a> |
  <a href="CONTRIBUTING.md">Contributing</a>
</p>

<p align="center">
  <a href="https://github.com/southstar21dev/Droidtosh/releases"><img alt="Latest release" src="https://img.shields.io/github/v/release/southstar21dev/Droidtosh?include_prereleases&label=release&style=flat-square&color=000000"></a>
  <a href="https://github.com/southstar21dev/Droidtosh/actions/workflows/ci.yml"><img alt="CI" src="https://img.shields.io/github/actions/workflow/status/southstar21dev/Droidtosh/ci.yml?branch=master&label=CI&style=flat-square"></a>
  <img alt="Android 13+" src="https://img.shields.io/badge/Android-13%2B-000000?style=flat-square&logo=android&logoColor=FFFFFF">
  <img alt="macOS 15+" src="https://img.shields.io/badge/macOS-15%2B-000000?style=flat-square&logo=apple&logoColor=FFFFFF">
  <a href="LICENSE"><img alt="License GPL-3.0" src="https://img.shields.io/badge/license-GPL--3.0-000000?style=flat-square"></a>
</p>

<p align="center">
  <picture>
    <source media="(prefers-color-scheme: dark)" srcset=".github/screenshots/mac-dark.png">
    <img alt="The Mac app's overview, menu bar popover and incoming call banner" src=".github/screenshots/mac-light.png" width="860">
  </picture>
</p>

<p align="center">
  <picture>
    <source media="(prefers-color-scheme: dark)" srcset=".github/screenshots/android-dark.png">
    <img alt="Home, file transfers and Find my phone on Android" src=".github/screenshots/android-light.png" width="860">
  </picture>
</p>

## Features

- **Notifications.** Your phone's notifications as native macOS banners. Reply inline, use their actions, dismiss them on either side, mute apps you don't want on the Mac, or pause them all for a while. Banners with a one-time code get a **Copy Code** button.
- **Calls.** Answer, decline and mute incoming calls from the Mac, place calls (choosing the SIM on dual-SIM phones) and browse recent calls.
- **Clipboard.** Copy text or an image on one device, paste it on the other. Password-manager copies are never sent.
- **Files.** Send files and folders both ways, up to 2 GB, from the Mac's menu bar or any Android share sheet. Interrupted transfers resume.
- **Links and numbers.** Share a link from Android to the Mac, or use **Send to Phone** in the Mac's Services menu for selected text, links and files. Links open only when you tap or click their notification, and Droidtosh can open `tel:` links on the Mac to dial them from the phone.
- **Phone status.** Battery, network and sound mode in the menu bar, low-battery alerts, and **Find my phone** to ring it at full volume.
- **Connection health.** Plain-words reasons when the devices are not connected, a Reconnect now button, and a Background checklist on the phone that the Mac warns about when Android or the phone maker may stop the connection.
- **Wake-ups.** With a relay, the phone reconnects as soon as the Mac is back, through [UnifiedPush](docs/TROUBLESHOOTING.md#the-phone-doesnt-wake-when-the-mac-comes-back-no-google-services) (for example ntfy), no Google services needed. Builds made with your own Firebase project can use Firebase instead.
- **Updates.** Both apps update themselves from GitHub releases when you say so, with an opt-in beta channel.

## Install

1. Download the APK and the Mac app from [Releases](https://github.com/southstar21dev/Droidtosh/releases).
2. **Android:** open the APK and allow installing apps when asked. **Mac:** move Droidtosh to `/Applications`; it isn't notarized by Apple, so open it once, then go to **System Settings > Privacy & Security** and click **Open Anyway**.
3. On the Mac, click Droidtosh in the menu bar and choose **Pair Phone…**, pick how the phone connects (see below), and continue to the pairing code.
4. On the phone, open Droidtosh and scan the code.

Requires Android 13 or newer and macOS 15 or newer. Something not working? See [Troubleshooting](docs/TROUBLESHOOTING.md).

## How it connects

| Mode | How the phone reaches the Mac |
|---|---|
| **Local network** | Directly over the same Wi-Fi. Nothing to set up. |
| **Relay** | Through a small relay you run yourself, from any network. See [Self-host a relay](relay/README.md). |
| **Relay, local network as fallback** | Through the relay, or directly when the relay can't be reached. |

Local network mode also works away from home over [Tailscale](docs/TROUBLESHOOTING.md#use-local-mode-away-from-home-with-tailscale).

Either way, the phone and the Mac run their own encrypted session with a key exchanged when you pair, so a relay only ever forwards encrypted bytes. More in [Privacy](docs/PRIVACY.md) and the [protocol spec](protocol/PROTOCOL-v2.md).

## Contributing

Building from source, the repository layout and the test commands are in [CONTRIBUTING.md](CONTRIBUTING.md); releases are covered in [docs/RELEASING.md](docs/RELEASING.md). To report a security problem, see [SECURITY.md](SECURITY.md).

## License

Droidtosh is free software under the [GNU General Public License v3.0](LICENSE). The bundled Jost typeface is under the [SIL Open Font License 1.1](android/third_party/jost/OFL.txt).
