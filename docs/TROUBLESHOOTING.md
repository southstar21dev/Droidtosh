# Troubleshooting

Start with **Diagnostics** on either device (Mac: in the main window's sidebar; phone: Settings ›
Advanced › Diagnostics). It shows the connection state, recent errors and round-trip times, and
can share a report without secrets in it.

## The Mac app won't open the first time

It isn't notarized by Apple. Right-click Droidtosh in `/Applications` and choose **Open**, or allow
it under System Settings › Privacy & Security.

## The phone shows "Pairing expired" or never connects after pairing

The phone's pairing key no longer matches the Mac's, for example after you unpaired on the Mac or
reinstalled the phone app. On the Mac, choose **Show Pairing Code** and scan it again on the phone.

## "Relay certificate changed"

The relay now presents a different certificate than the one pinned when you paired, usually
because its `/data` folder or `TLS_CERT`/`TLS_KEY` variables were lost on a redeploy. Restore the
old certificate (see [Keep the certificate stable](../relay/README.md#tls-certificate-and-fingerprint)),
or clear the fingerprint in the Mac's Settings › Connection and pair the phone again.

## Local network mode doesn't connect

- Both devices must be on the same network, and the Mac's network must allow devices to see each
  other (guest Wi-Fi and some office networks don't).
- On the Mac, allow Droidtosh under System Settings › Privacy & Security › Local Network, and
  accept incoming connections if the firewall asks.
- Settings › Connection on the Mac shows whether it is listening and on which address and port.
- Some routers block Bonjour; the phone then falls back to the address in the pairing code, which
  goes stale if the Mac's IP address changes. Pair again, or use the relay with local fallback.

## The phone disconnects when the screen is off

Allow Droidtosh to run in the background: Settings › Connection & pairing › Battery optimization on
the phone. Some manufacturers add their own battery managers; set Droidtosh to "unrestricted" there
too.

## An update fails to install

- **Android:** allow Droidtosh to install apps when Android asks. If it says the update is signed
  differently, the installed app came from another build; uninstall it, install the release, and
  pair again.
- **Mac:** the app must be in a folder you can write to (normally `/Applications` on an admin
  account). An update that isn't signed with the same certificate as the installed app is refused.

Still stuck? [Open an issue](https://github.com/southstar21dev/Droidtosh/issues) with the
diagnostics report attached.
