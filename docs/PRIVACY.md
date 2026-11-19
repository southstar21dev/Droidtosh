# Privacy

Droidtosh has no accounts, no analytics and no servers of its own. Your phone and your Mac talk to
each other directly over your local network, or through a relay you run yourself.

## What each party can see

| Who | Sees | Never sees |
|---|---|---|
| **Your phone and your Mac** | Everything you sync: notifications, calls, clipboard, files, links, phone status | Nothing is hidden between your own devices |
| **Your relay** | That a phone and a Mac in the same room are connected, when, and how many bytes they exchange; the phone's push token or UnifiedPush endpoint if you set up wake-ups | The content of anything you sync, which is end-to-end encrypted between your devices |
| **Firebase (optional)** | A wake-up message, `{"type": "mac_online"}`, sent to your phone's push token | Any content; nothing else is sent through it |
| **Your UnifiedPush distributor (optional)** | The same wake-up, `{"type": "mac_online"}`, posted by your relay to the endpoint the distributor gave your phone, and which relay sent it | Any content; the endpoint URL is a secret the relay holds, as with a push token |
| **GitHub** | That an app checked for updates or downloaded one (an ordinary request to its release API) | Anything about your devices or your data |

## How it's protected

- **End-to-end encryption.** The phone and the Mac run their own TLS 1.3 session, pinned to the
  Mac's certificate and authenticated with a key exchanged when you pair. The relay only forwards
  that encrypted stream.
- **Pinned relay.** The relay's self-signed certificate is pinned by its fingerprint, which travels
  in the pairing QR code, so the phone only talks to your relay.
- **Local network only from local addresses.** In local network mode the Mac accepts connections
  only from loopback, private and link-local addresses, plus the carrier-grade NAT range
  `100.64.0.0/10` that Tailscale uses. The pairing key still authenticates every connection.
- **Secrets stay on the device.** The Mac keeps its TLS key, pairing key and relay room token in
  `~/Library/Application Support/Droidtosh/Secrets`, readable only by your user. The phone keeps
  its pairing encrypted with an Android Keystore key.
- **Password managers.** Copies marked as sensitive (for example from a password manager) are
  never sent to the other device. A code copied with **Copy Code** on the Mac is marked the same
  way, so it stays off the phone, and it is cleared from the clipboard after a minute unless you
  copied something else since.
- **Links.** A link sent from the other device opens only when you tap or click its notification,
  and a `tel:` link opened on the Mac asks before the phone places the call.
- **Diagnostics.** Reports you share from the app leave out keys, room tokens and pairing codes.
- **Verified updates.** An update installs only if it matches the checksum published with the
  release and is signed with the same key as the installed app.

## Unpairing

Unpairing on the Mac forgets the paired phone's key and moves the Mac to a new relay room, so the
old phone can no longer reach it. To remove everything from the Mac, also delete
`~/Library/Application Support/Droidtosh`.
