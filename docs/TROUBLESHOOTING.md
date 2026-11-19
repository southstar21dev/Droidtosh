# Troubleshooting

Start with **Diagnostics** on either device (Mac: in the main window's sidebar; phone: Settings ›
Advanced › Diagnostics). It shows the connection state, recent errors and round-trip times, and
can share a report without secrets in it.

## The Mac app won't open the first time

It isn't notarized by Apple, and on macOS 15 and later right-clicking and choosing **Open** no longer
gets past Gatekeeper. Open Droidtosh once from `/Applications` and dismiss the warning, then go to
System Settings > Privacy & Security, click **Open Anyway** next to Droidtosh and confirm.

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

## Use local mode away from home with Tailscale

The Mac accepts local connections from Tailscale addresses (`100.64.0.0/10`) as well as from your
home network, and the pairing key still authenticates every connection. To use it:

1. Install Tailscale on the Mac and the phone and sign in to the same tailnet.
2. With both devices connected to Tailscale, pair the phone again so the code carries an address
   the phone can reach.
3. Bonjour does not work across Tailscale, so the phone uses the address from the pairing code. If
   the Mac's address changes, pair again, or use the relay instead.

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

## The phone doesn't wake when the Mac comes back (no Google services)

Phones without Google services (GrapheneOS, /e/) can't use FCM, so use UnifiedPush.
1. Install a distributor app such as ntfy and sign in or pick a server.
2. In Droidtosh, open the Connection page, choose **UnifiedPush** under **Wake-ups**, and pick the distributor. The row should say "Registered".
3. Check that the relay is reachable over `https` from the internet: it will not push to loopback or private addresses, and endpoints must be `https`.
4. If the endpoint is very long (over about 397 characters) the phone can't send it and falls back to checking every minute. Use a distributor with shorter endpoints.
5. Allow the distributor to run in the background (battery settings), or its wake-ups arrive late.
