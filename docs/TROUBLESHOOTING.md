# Troubleshooting

Start with **Diagnostics** on either device. On the Mac it is on the **Connection** page of the
main window (**Details…** opens the full view); on the phone, tap your Mac on Home to open
**Connection**, then **All diagnostics**. It shows the connection state, recent errors and
round-trip times, and can copy a report without secrets in it.

## The Mac app won't open the first time

It isn't notarized by Apple, and on macOS 15 and later right-clicking and choosing **Open** no longer
gets past Gatekeeper. Open Droidtosh once from `/Applications` and dismiss the warning, then go to
System Settings > Privacy & Security, click **Open Anyway** next to Droidtosh and confirm.

## The phone shows "Pairing expired" or never connects after pairing

The phone's pairing key no longer matches the Mac's, for example after you unpaired on the Mac or
reinstalled the phone app. On the Mac's **Connection** page, choose **Show Pairing Code** and scan
it again on the phone.

## "Relay certificate changed"

The relay now presents a different certificate than the one pinned when you paired, usually
because its `/data` folder or `TLS_CERT`/`TLS_KEY` variables were lost on a redeploy. Restore the
old certificate (see [Keep the certificate stable](../relay/README.md#tls-certificate-and-fingerprint)),
or click **Clear** next to the relay fingerprint on the Mac's **Connection** page and pair the
phone again.

## Local network mode doesn't connect

- Both devices must be on the same network, and the Mac's network must allow devices to see each
  other (guest Wi-Fi and some office networks don't).
- On the Mac, allow Droidtosh under System Settings > Privacy & Security > Local Network, and
  accept incoming connections if the firewall asks.
- The **Connection** page on the Mac shows whether it is listening and on which address and port.
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

Open Droidtosh on the phone, tap your Mac on Home to open **Connection**, and scroll to
**Background**. That checklist lists everything that
can stop the connection while the phone is idle, each with a **Fix** button that opens the right
settings screen:

- **Battery optimization.** Set Droidtosh to unrestricted so Android doesn't pause it.
- **Your phone maker's battery manager.** Xiaomi, Samsung, Huawei and Honor, OnePlus, Oppo and
  Realme, and Vivo and iQOO add their own manager on top of Android's, which kills background apps
  more aggressively. **Fix** opens its screen where it can (the exact screen varies by model and
  system version, so it falls back to Droidtosh's app settings, then to the guide for your maker at
  [dontkillmyapp.com](https://dontkillmyapp.com)). Allow Droidtosh to run in the background, or
  autostart, or lock it in the recent apps list, depending on what your phone offers. Android can't
  tell whether you changed anything there, so this item counts as done once you have opened it.
- **Notification access** and **Show notifications.** Without them notifications don't reach the
  Mac and the connection status can't be shown.

While the first two are open, the Mac shows "Your phone may disconnect when idle" on the Home page
and in the menu bar popover, with the way back to this checklist.

## The Mac says why the phone isn't connected

The Home page, the popover and the Connection page say it in plain words, with **Reconnect now** to
try again at once (**Reconnect** in the popover). On the phone it is on Home and the Connection
page.

- **Mac is waiting for the phone.** The relay has the Mac, and the phone is asleep, offline or out
  of range. It connects by itself when the phone is back.
- **Mac asleep or offline** (phone). No Mac is waiting at the relay: it is asleep, or Droidtosh is not
  running on it.
- **Relay unreachable.** Check the internet connection of the device that shows it, and the relay.
- **Phone not on this network (local mode)** (Mac), **Mac not found on this network (local mode)**
  or **No Wi-Fi (local mode)** (phone). Local mode only works on
  the same Wi-Fi or Ethernet network. The phone stops trying until it is on one, and starts again
  as soon as it is.
- **Pairing expired.** See above.

## An update fails to install

- **Android:** allow Droidtosh to install apps when Android asks. If it says the update is signed
  differently, the installed app came from another build; uninstall it, install the release, and
  pair again.
- **Mac:** the app must be in a folder you can write to (normally `/Applications` on an admin
  account). An update that isn't signed with the same certificate as the installed app is refused.

## The phone doesn't wake when the Mac comes back (no Google services)

Phones without Google services (GrapheneOS, /e/) can't use FCM, so use UnifiedPush. Wake-ups are sent
by the relay.

1. Install a distributor app such as ntfy and sign in or pick a server.
2. In Droidtosh, tap your Mac on Home to open **Connection**, choose **UnifiedPush** under
   **Wake-ups**, and pick the distributor. The **Distributor** row should say "Registered".
3. The relay posts the wake-up to your distributor's server, so that server must be reachable from
   the relay over `https`. The relay refuses plain `http` endpoints and loopback, private and
   link-local addresses, so a distributor server that only exists on your home network won't work.
4. If the endpoint is very long (over 397 characters) the phone can't send it and falls back to
   checking every minute. Use a distributor with shorter endpoints.
5. Allow the distributor to run in the background (battery settings), or its wake-ups arrive late.

## Verification codes show as "Sensitive notification content hidden"

Android 15 hides the content of one-time-code notifications from apps that read notifications, so Droidtosh can only mirror the placeholder. The Notifications tab in the phone app shows a hint once it sees one. To let Droidtosh read them, connect the phone to a computer with USB debugging on and run:

```
adb shell appops set --user 0 dev.southstar21.droidtosh RECEIVE_SENSITIVE_NOTIFICATIONS allow
```

The setting survives restarts. Undo it with `ignore` in place of `allow`.

Still stuck? [Open an issue](https://github.com/southstar21dev/Droidtosh/issues) with the
diagnostics report attached.
