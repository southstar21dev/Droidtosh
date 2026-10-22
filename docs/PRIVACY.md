# Privacy

Droidtosh has no accounts, no analytics and no servers of its own. Your phone and your Mac talk to
each other directly over your local network, or through a relay you run yourself.

## What each party can see

| Who | Sees | Never sees |
|---|---|---|
| **Your phone and your Mac** | Everything you sync: notifications, calls, clipboard, files, phone status | — |
| **Your relay** | That a phone and a Mac in the same room are connected, when, and how many bytes they exchange; the phone's push token if you set up push | The content of anything you sync, which is end-to-end encrypted between your devices |
| **Firebase (optional)** | A wake-up message, `{"type": "mac_online"}`, sent to your phone's push token | Any content; nothing else is sent through it |
| **GitHub** | That an app checked for updates or downloaded one (an ordinary request to its release API) | Anything about your devices or your data |

## How it's protected

- **End-to-end encryption.** The phone and the Mac run their own TLS 1.3 session, pinned to the
  Mac's certificate and authenticated with a key exchanged when you pair. The relay only forwards
  that encrypted stream.
- **Pinned relay.** The relay's self-signed certificate is pinned by its fingerprint, which travels
  in the pairing QR code, so the phone only talks to your relay.
- **Local network only from local addresses.** In local network mode the Mac accepts connections
  only from private and link-local addresses.
- **Secrets stay on the device.** The Mac keeps its TLS key, pairing key and relay room token in
  `~/Library/Application Support/Droidtosh/Secrets`, readable only by your user. The phone keeps
  its pairing encrypted with an Android Keystore key.
- **Password managers.** Copies marked as sensitive (for example from a password manager) are
  never sent to the other device.
- **Diagnostics.** Reports you share from the app leave out keys, room tokens and pairing codes.
- **Verified updates.** An update installs only if it matches the checksum published with the
  release and is signed with the same key as the installed app.

## Unpairing

Unpairing on the Mac forgets the paired phone's key and moves the Mac to a new relay room, so the
old phone can no longer reach it. To remove everything from the Mac, also delete
`~/Library/Application Support/Droidtosh`.
