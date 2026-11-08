# Droidtosh protocol v2

The wire version is the `protocol_version` field, `2`; peers on any other version are rejected.
The proto package is named `droidsync.v1`, which is only a package name and not the wire version.

The phone reaches the Mac through the relay (§1.1–1.3), directly on the local network (§1.4), or
through the relay with the local network as a fallback (§1.5).

## 1. Transport layers

```
phone ── TCP ── [outer TLS: client ↔ relay] ── relay ── [outer TLS: relay ↔ client] ── TCP ── mac
                └──────────── inner TLS 1.3: phone (client) ↔ mac (server), pinned ────────────┘
                              └── PSK auth ── length-prefixed Envelope frames ──┘
```

### 1.1 Outer TLS (client ↔ relay)
- The relay listens with TLS 1.2+ (TLS 1.3 preferred) on its port (default 7443). Plain TCP is no longer accepted.
- **Relay certificate:** self-signed ECDSA P-256, generated on first start and persisted next to the state file. The defaults are `CERT_FILE=/data/relay-cert.pem` and `KEY_FILE=/data/relay-key.pem`, both overridable by env var.
  - Validity: 10 years. CN: `droidtosh-relay`.
- **Fingerprint:** lowercase hex SHA-256 of the leaf certificate's DER bytes, 64 characters.
  - The relay logs it at startup as `relay fingerprint: <hex>`.
  - `relay -print-fingerprint` prints it and exits, generating the certificate if it is missing.
- **Clients:** pin that fingerprint and ignore hostname and chain validation. SNI is not required.
- **Mac, trust on first use:** the Mac settings have a "Relay fingerprint" field.
  - If the field is empty, the Mac pins whatever certificate it sees on first successful connect, stores it, and shows it in Settings so the user can compare it with the relay log.
  - If the field is set and the certificate differs, the connection fails with the status "Relay certificate changed" and the Mac does not retry automatically.
- **Phone:** always strict; it uses the `rfp` value from the pairing URI.

### 1.2 Join line (inside outer TLS)
`DROIDSYNC/2 <room> mac|phone [fcm-token]\n`

Max 512 bytes, 5 s deadline. The relay answers with exactly one line: `PAIRED`, `WAIT` or `ERROR <reason>`. The room is 43 characters of base64url (`[A-Za-z0-9_-]`). A join line for any other protocol version (`DROIDSYNC/<n>`) gets `ERROR upgrade`.

### 1.3 Pipe
After `PAIRED`, the relay pipes the decrypted outer-TLS byte streams between the two clients. Those bytes are the inner TLS, so the relay still never sees plaintext. The inner TLS is pinned to the Mac's certificate, and frames are length-prefixed (4-byte big-endian length, max 1 MiB).

### 1.4 Local network (direct)
The phone can also reach the Mac directly on the local network, with no relay in the path:

```
phone ── TCP ── mac (listening on the LAN)
   └── inner TLS 1.3: phone (client) ↔ mac (server), pinned ── PSK auth ── frames ──┘
```

- **No outer TLS and no join line.** The phone opens TCP and starts the inner TLS at once. Everything from the inner TLS up (pinning to `fp`, §3 auth, §4 reliable delivery, §5 resume, features) is identical to the relay path.
- **Mac listener:** TCP on all interfaces, port `7444` by default (configurable in the Mac settings).
  - It accepts connections only from loopback, private and link-local addresses: `127.0.0.0/8`, `10.0.0.0/8`, `172.16.0.0/12`, `192.168.0.0/16`, `169.254.0.0/16`, `100.64.0.0/10`, `::1`, `fc00::/7`, `fe80::/10` (IPv4-mapped IPv6 is checked as IPv4). Anything else is closed at once.
  - At most 4 connections may be unauthenticated at a time; further ones are closed. TLS and authentication must finish within 10 s.
- **Bonjour:** the Mac advertises `_droidtosh._tcp` with the Mac's name as the instance name and the TXT record `v=2`, `fp=<first 16 hex characters of the Mac certificate fingerprint>`. The TXT record holds no secrets (the certificate is shown to anyone who connects anyway). The phone keeps only services whose `fp` is a prefix of its paired `fp`.
- **Phone candidates:** Bonjour resolutions first, then `lhost:lport` from the pairing URI. Each candidate gets a 3 s connect timeout.
- **One session:** the Mac keeps one authenticated phone session at a time across both paths. A newly authenticated session replaces the current one, which is closed and reported as disconnected first.
- **Sleep:** the Mac closes the listener, its Bonjour advertisement and any local session when it goes to sleep (after sending `HostStatus{sleeping}`), and reopens them on wake.

### 1.5 Connection modes
Both apps have a connection mode setting:

| Mode | URI value | Mac | Phone |
|---|---|---|---|
| Relay | `relay` | parks on the relay | dials the relay (FCM wake, §1.1–1.3) |
| Local network | `local` | listens on the LAN (§1.4) | dials the Mac directly |
| Relay, local fallback | `both` | parks on the relay and listens | dials the relay; on `WAIT` or any relay failure it tries the local candidates before acting on the relay result |

- **Phone, `both`:** if a local session comes up it is used. Otherwise a relay `WAIT` leads to the usual WAIT handling, and a relay failure to the usual backoff. Once a session ends, the next attempt starts with the relay again.
- **Phone, `local`:** failures back off up to 30 s. With no relay there is no FCM, so the Mac-asleep wait uses the no-FCM probe (60 s). Bonjour seeing the Mac appear, or the network changing, ends any wait early.
- The Mac's mode at pairing time goes in the QR code and becomes the phone's initial mode; either side can change its own mode later.

## 2. Pairing URI
`droidsync://pair?v=2&did=…&name=…&host=…&port=…&fp=<mac cert sha256>&rfp=<relay cert sha256>&psk=…&room=…&mode=…&lhost=…&lport=…`

- `v=2` is required. The phone rejects an older code with "Pairing code is from an older version — re-pair".
- `mode` is `relay`, `local` or `both` (§1.5). It defaults to `relay` when absent, so older codes stay valid.
- `host`, `port` and `rfp` are required unless `mode=local`, where they are omitted.
- `room` is always present.
- `lport` is required unless `mode=relay`. `lhost` is the Mac's private IPv4 address when the code was made; it is optional, because Bonjour usually finds the Mac anyway.

## 3. Auth and negotiation (inner, first frames)
1. **Mac → `AuthChallenge`:** `server_nonce`, `min_version=2`, `max_version=2`, and the Mac's `capabilities`.
2. **Phone → `AuthResponse`:** `client_nonce`, `mac` (the HMAC), its `min_version`, `max_version` and `capabilities`.
3. **Mac chooses** `version = min(mac.max, phone.max)` and requires it to be at least `max(mac.min, phone.min)`.
   - If no version fits, the Mac sends `ProtocolError{code:"unsupported_version"}` and closes.
   - Otherwise it sends `AuthOk{mac, version, capabilities = intersection}`.
4. **Gating:** both sides store the negotiated capabilities and only use a feature whose capability is in that set.

Every `Envelope.protocol_version` must equal the negotiated version (2); anything else is a protocol error.

**Capability strings (v2):**
- `notifications`
- `calls`
- `calllog`
- `clipboard.text`
- `clipboard.image`
- `reliable` (sections 4 and 5)
- `files` (section 7)
- `phone.status` (section 8)
- `find` (section 9)

## 4. Reliable delivery
Each side keeps a **send stream** identified by `stream_id`. This is a random UUID created when the process starts, or when the Android service starts, and it is never persisted.

### 4.1 Sending
- **Reliable envelopes** get `seq = ++nextSeq`, starting at 1. Everything else has `seq = 0`.
- **The outbox:**
  - Holds each reliable envelope until it is acknowledged.
  - Caps: 256 entries or 4 MiB total; when full, the oldest entry is evicted and counted as a drop.
  - TTL: 120 s; on expiry the envelope is evicted and counted.
  - Expiry is deliberate: a stale "decline call" or "reply" should not fire minutes later.
- **Piggybacked ack:** every outgoing envelope carries `ack` = the highest contiguous seq processed from the peer's current stream.
- **Standalone ack:** if a reliable envelope has been received and no outgoing envelope carried its ack within 1 s, send `Envelope{ack_only:{}}`. It has `seq` 0 and sets `ack`.
- **Receiving an ack:** drop outbox entries with `seq <= ack`.

### 4.2 Receiving
- **`seq == 0`:** process it.
- **`seq == lastProcessed + 1`:** process it and advance `lastProcessed`.
- **`seq <= lastProcessed`:** a duplicate. Drop it, but still ack it.
- **`seq > lastProcessed + 1`:** a gap; this can only happen after evictions. Process it, set `lastProcessed = seq`, and count the gap. The inner TLS is ordered, so gaps only come from the sender evicting entries.

### 4.3 Which payloads are reliable (`seq > 0`)
- **Phone → Mac:** `action_result`, `clipboard_text`.
- **Mac → phone:** `notification_action`, `notification_dismiss`, `call_action`, `dial_request`, `mute_list_set`, `open_app_request`, `clipboard_text`.

Everything else is unreliable, because either the snapshot sent on every reconnect restores it or it is latest-wins: notification posted, updated and removed, call state, snapshots, apps list and icons, clipboard blobs, ping and pong, server info, host status, phone status. A clipboard blob transfer interrupted by a disconnect is simply abandoned.

## 5. Resume
1. **After `AuthOk`:** each side sends `Resume{stream_id, peer_stream_id, last_received_seq}`.
   - `peer_stream_id` is the peer stream it last received from; `last_received_seq` is that stream's `lastProcessed`.
   - On first contact both are empty and 0.
2. **On receiving `Resume`:**
   - **Receive side:** if `peer.stream_id` differs from the stream we last received from, reset `lastProcessed = 0` and remember the new id.
   - **Send side:** if `peer.peer_stream_id == our stream_id`, drop outbox entries with `seq <= peer.last_received_seq` and retransmit the rest in order, keeping their seq numbers and refreshing `ack`. If the ids differ, the peer restarted and lost its receive state, so retransmit the whole outbox.
3. **Ordering:** no feature envelopes are sent before `Resume` has been both sent and received, apart from the retransmissions themselves. Features start their normal post-connect sends (snapshot request, mute list, server info, and so on) after that.

## 6. Fixtures
`protocol/fixtures/*.txt` use `protocol_version: 2`, and `resume.txt` covers the new fields. Both platforms' fixture tests must decode every `.bin`.

## 7. File transfer (`files` capability)
Either side can send files; both directions are symmetric. A sender runs **one transfer at a time** and queues any others.

### 7.1 Messages
| Message | Direction | Reliable (§4) |
|---|---|---|
| `FileOffer{transfer_id, name, mime_type, size, modified_at_ms}` | sender → receiver | yes |
| `FileAccept{transfer_id, resume_offset}` / `FileReject{transfer_id, reason}` | receiver → sender | yes |
| `FileChunk{transfer_id, offset, data}` | sender → receiver | no |
| `FileProgress{transfer_id, received}` | receiver → sender | no |
| `FileEnd{transfer_id, sha256}` | sender → receiver | yes |
| `FileComplete{transfer_id, ok, error, saved_as}` | receiver → sender | yes |
| `FileCancel{transfer_id, reason}` | either | yes |

### 7.2 Limits
- **Size:** max 2 GiB per file. A larger offer gets `FileReject{reason:"too_large"}`. A receiver may set a lower limit.
- **Chunks:** 256 KiB of `data` per chunk, which keeps every frame well under 1 MiB.
- **Window:** the sender keeps at most **4 MiB** of chunk data unacknowledged, meaning `offset_sent - progress.received <= 4 MiB`.
- **Progress:** the receiver sends `FileProgress` at least every 1 MiB written, and once after the last chunk.
- **Stall:** no chunk or progress for 30 s in either direction means the transfer has stalled. The sender then waits for a reconnect (§7.4); it does not cancel.

### 7.3 Receiving
- **Accepting:** receivers accept automatically when the feature is enabled.
  - The Mac saves to `~/Downloads`.
  - Android saves to `Download/Droidtosh/` through MediaStore.
- **Names:** sanitise `name` before using it. Normalise it to Unicode NFC first, then strip path separators and control characters, cap it at 200 UTF-8 bytes, and fall back to `file` if nothing is left. If a file with that name already exists, add ` (1)`, ` (2)`, and so on.
- **Partial files:** write to a temporary partial file and move it into place only after the SHA-256 checks out. Send `FileComplete{saved_as}` with the final display name.
- **Errors:**
  - A checksum mismatch sends `FileComplete{ok:false, error:"checksum_mismatch"}` and deletes the partial file.
  - A write failure sends `FileComplete{ok:false, error:"write_failed"}`.
  - Running out of space during the transfer sends `FileCancel{reason:"disk_full"}`.
- **Keeping partials:** keep a partial file for 10 minutes after a disconnect so the transfer can resume.

### 7.4 Resume
- **Sender:** after a reconnect and the Resume exchange, re-send `FileOffer` for an unfinished transfer, with the same `transfer_id` and size.
- **Receiver:** if it still has that partial file, it answers `FileAccept{resume_offset = bytes written}`; otherwise it answers with `resume_offset = 0`. The sender continues from `resume_offset`.
- **Retransmits:** the offer is reliable, so a §4 retransmission of the original offer may arrive first. The receiver treats a duplicate offer for a transfer that is already active as a resume request.

### 7.5 UX
Both apps show active transfers with progress and a cancel button, and post a notification when a transfer completes. Tapping it opens the file, or reveals it in Finder on the Mac.

## 8. Phone status (`phone.status` capability)
The phone sends `DeviceStatus` (unreliable, latest wins). Fields it can't read stay at their "unknown" values (`-1`, `UNSPECIFIED`, empty).

- **Battery:** percentage, charging, charge source and power save.
- **Network:** the default network type, Wi-Fi and cellular signal bars (0–4), cellular generation and carrier. Cellular fields need `READ_PHONE_STATE`.
- **Sound:** ringer mode and Do Not Disturb, plus airplane mode.

### 8.1 When the phone sends it
- **After Resume:** once on every session, after the Resume exchange.
- **On change:** whenever a field the Mac shows changes (battery percentage, charging, charge source, power save, network type, bars, generation, carrier, airplane mode, ringer, DND). Changes are coalesced: at most one envelope every 10 s, except that a change of `charging`, `network_type`, `airplane_mode`, `ringer_mode` or `do_not_disturb` is sent within 1 s.
- **Heartbeat:** at least every 5 minutes while connected, even with no change.

The `phone_status{metered}` message from the clipboard feature is unrelated and unchanged.

### 8.2 Mac
- **Menu bar:** the popover shows battery (with a charging mark), signal bars, network type and ringer/DND. A setting "Show phone battery in menu bar" (default off) puts the percentage next to the icon.
- **Stale data:** status older than 10 minutes, or from a session that has ended, is shown greyed with "last seen <time>".
- **Low battery:** an optional macOS notification (default on) when the phone drops to 20% and again at 10% while not charging. Each threshold fires once per discharge; plugging in resets them.

## 9. Find my phone (`find` capability)
- **Request:** the Mac sends `FindPhoneRequest{request_id, action}`. It is reliable (§4), so it is retransmitted after a reconnect within the outbox TTL.
- **Start:** the phone rings for **60 s** at full volume, even when the ringer is silent or vibrate:
  - It plays the default alarm sound with `USAGE_ALARM` audio attributes, and vibrates.
  - It sets the alarm stream to its maximum volume while ringing and restores the previous volume afterwards.
  - It shows a high-priority notification, with a full-screen intent where allowed, that has a **Stop** button.
- **Already ringing:** a `START` while ringing restarts the 60 s timer and adopts the new `request_id`.
- **Stop:** a `STOP` (any `request_id`) stops the ring. So do the Stop button, dismissing the notification, or pressing a volume key while the ringing screen is open.
- **Duplicates and stale requests:**
  - The phone handles each `request_id` once per service start.
  - It ignores a `START` whose `Envelope.sent_at_ms` is more than 90 s older than its own clock, answering `FindPhoneState{ringing:false, ended:FAILED, error:"stale"}`. This keeps a retransmitted ring from going off minutes later; the check assumes the two clocks are roughly in sync.
- **Reports:** the phone sends `FindPhoneState` (reliable) with `ringing:true` when sound starts, and `ringing:false` with the reason when it ends.
  - If the feature is disabled in the phone settings, it answers `FAILED` with error `disabled`.
  - If audio can't start, it answers `FAILED` with error `audio_failed`.
- **Mac UI:** a "Find My Phone" button in the popover and the device page. While the phone rings it becomes "Stop Ringing". The Mac shows "Ringing…", then how it ended. If no `FindPhoneState` arrives within 15 s while connected, it shows "Phone didn't respond". While disconnected, the button says the request will be sent when the phone reconnects.
