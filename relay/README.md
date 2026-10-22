# Droidtosh relay

A tiny self-hosted relay that lets the Droidtosh Android app and macOS app
find each other across the internet. Both devices dial the relay, the relay
pairs one `mac` with one `phone` per room and forwards the bytes in both
directions. A second, inner TLS session and the pairing handshake run
end-to-end between the phone and the Mac, so the relay never sees
notification, call, clipboard or file content.

The relay is optional. In the **Local network** connection mode the phone
reaches the Mac directly over Wi-Fi and no relay is involved; in **Relay, local
network as fallback** the phone uses the relay and falls back to the local
network when the relay can't be reached. See the main [README](../README.md#how-it-connects).

Every connection to the relay is TLS (protocol v2, see
`protocol/PROTOCOL-v2.md`). The relay uses a self-signed certificate that the
apps pin by fingerprint, so the room ID and the phone's FCM token in the join
line are no longer visible on the network path. Plain TCP is not accepted.

- Join line, sent inside TLS: `DROIDSYNC/2 <room> mac|phone [fcm-token]\n`
  (max 512 bytes). A `DROIDSYNC/1` line gets `ERROR upgrade`.
- Responses: exactly one line, `PAIRED\n`, `WAIT\n`, or `ERROR <reason>\n`
- Default port: `7443` (TLS over TCP)
- The TLS handshake and join line must complete within 5 seconds.
- Max concurrent connections: `512` total, `16` per source IP; further
  connections get `ERROR busy` immediately

When a Mac parks in a room that has a registered FCM token, the relay sends a
data-only `mac_online` push so the phone can wake and reconnect. The push never
contains notification or call content.

## Configuration

| Env var              | Required | Description                                                                    |
| -------------------- | -------- | ------------------------------------------------------------------------------ |
| `PORT`               | no       | Listen port, default `7443`.                                                   |
| `FCM_SERVICE_ACCOUNT`| no       | Firebase service-account JSON (the whole file contents). Enables `mac_online` pushes. |
| `FCM_PROJECT_ID`     | no       | Firebase project ID; falls back to `project_id` from the service-account JSON. |
| `STATE_FILE`         | no       | Path to persist room → FCM token state (e.g. `/data/state.json`).              |
| `MAX_CONNS_PER_IP`   | no       | Concurrent connections allowed from one source IP, default `16`.               |
| `CERT_FILE`          | no       | TLS certificate (PEM), default `/data/relay-cert.pem`. Generated on first start. |
| `KEY_FILE`           | no       | TLS private key (PEM, written `0600`), default `/data/relay-key.pem`.          |
| `TLS_CERT`, `TLS_KEY` | no      | The certificate and key themselves (PEM, or base64 of the PEM). Take precedence over the files; use them on hosts without a persistent volume. |

| Flag                 | Description                                                                     |
| -------------------- | ------------------------------------------------------------------------------- |
| `-print-fingerprint` | Print the certificate fingerprint and exit (generates the certificate if missing). |
| `-print-env`         | Print the certificate as `TLS_CERT=`/`TLS_KEY=` lines plus its fingerprint, and exit (generates it if missing). |

## TLS certificate and fingerprint

On first start the relay generates a self-signed ECDSA P-256 certificate
(valid 10 years) at `CERT_FILE`/`KEY_FILE` and reuses it afterwards. Its
fingerprint, the lowercase hex SHA-256 of the certificate, is logged at
every start:

```
relay fingerprint: 3a9672cb6a16…
```

To read it without starting the relay, run `droidtosh-relay -print-fingerprint`
(or `go run . -print-fingerprint`, or
`docker compose run --rm relay -print-fingerprint`).

Paste it into the Mac's **Settings › Connection › Relay › Fingerprint**. If you leave that
field empty, the Mac trusts the certificate it sees on first connect and
shows its fingerprint so you can compare it with the log line. The Mac puts
the fingerprint into the pairing QR code, and the phone pins it.

Keep the certificate stable. If it changes, the Mac refuses to connect
("Relay certificate changed") and every phone has to re-pair. Either:

- keep both files on a persistent volume (the `/data` volume does this in
  Docker Compose), or
- pass the certificate in `TLS_CERT` and `TLS_KEY`. Run
  `CERT_FILE=data/relay-cert.pem KEY_FILE=data/relay-key.pem go run . -print-env`
  once to generate a certificate and print both values, then store them as
  secrets on your host. They take precedence over the files.

If only one of the two files (or variables) is present, the relay refuses to
start rather than silently using a different certificate. When it does
generate a new certificate, it logs `WARNING: generated a NEW relay
certificate`; seeing that on every deploy means `/data` isn't persistent.

If `FCM_SERVICE_ACCOUNT` is unset, the relay logs `FCM not configured: mac_online
pushes disabled` and runs without pushes.

With `STATE_FILE` set, state is loaded at startup and written on graceful
shutdown (SIGINT/SIGTERM). It is not written on every change, so a hard
kill can lose the most recent token registrations; phones re-register their
token on their next connect.

The relay holds up to 1024 rooms. A token is kept for 30 days after the room's
last join. Tokens FCM reports as invalid are dropped. When the table is full,
the least recently seen room with no open connection is evicted.

## Local development

```bash
cd relay
go test ./...          # unit + integration tests
CERT_FILE=data/relay-cert.pem KEY_FILE=data/relay-key.pem go run .
                       # listens on :7443 (TLS); set PORT to change
```

The default certificate paths are under `/data`, so set `CERT_FILE` and
`KEY_FILE` when running outside Docker. `relay/data/` is gitignored.

Or with Docker Compose (builds `relay/Dockerfile`):

```bash
cd relay
docker compose up --build
```

Point the Mac app's relay address at `127.0.0.1:7443` and pair as usual.

## Firebase setup (free)

1. Create a project at <https://console.firebase.google.com> (the Spark/free
   plan is enough; FCM does not require billing).
2. In the project, add an **Android** app with package name `dev.southstar21.droidtosh`
   (this must match `applicationId` in `android/app/build.gradle.kts`; the
   Kotlin source namespace is the same, `dev.southstar21.droidtosh`).
3. Download `google-services.json` and place it at `android/app/google-services.json`
   (gitignored). Rebuild the Android app.
4. In Firebase: **Project settings → Service accounts → Generate new private
   key**. A service-account JSON file downloads. Copy its full contents — this
   is the relay's `FCM_SERVICE_ACCOUNT`.
5. Set `FCM_PROJECT_ID` only if it differs from the `project_id` inside the
   service-account JSON.

The Android app itself does not need any secret: `google-services.json`
contains only public project configuration.

## Deploy with Coolify

1. In Coolify, create a resource from this repository (or a fork), branch
   `master`, application type **Dockerfile**. Coolify then redeploys the relay
   whenever `master` changes.
2. Set **Base Directory** to `/relay` and **Dockerfile Location** to
   `/Dockerfile` (the file is `relay/Dockerfile` in the repo). This makes the
   `relay/` directory the Docker build context.
3. Under **Ports Mappings**, add `7443:7443` (TCP).
4. Keep the TLS certificate across deploys, or every deploy forces a re-pair.
   Coolify redeploys into a new container on every push, so use one of:
   - **Recommended:** on your own machine, in `relay/`, run
     `CERT_FILE=data/relay-cert.pem KEY_FILE=data/relay-key.pem go run . -print-env`.
     Add the printed `TLS_CERT` and `TLS_KEY` as secret environment
     variables in Coolify. Keep `relay/data/` (gitignored) as your backup.
   - Or add a **persistent volume mounted at `/data`**.
5. Under **Environment Variables**, add `FCM_SERVICE_ACCOUNT` as a secret and
   paste the full service-account JSON. Optionally add `FCM_PROJECT_ID` and
   `STATE_FILE=/data/state.json`.
6. Set the **Health Check** to TCP on port `7443`. Don't put an HTTP or TLS
   proxy in front of the port: the relay terminates TLS itself.
7. Deploy. Make sure the VPS firewall or cloud security group allows inbound
   TCP `7443` (for example `ufw allow 7443/tcp`), and that Coolify is not
   terminating the port on HTTP.
8. Copy the `relay fingerprint: …` line from the deploy logs. It should say
   `(from TLS_CERT/TLS_KEY)` or `(from /data/relay-cert.pem)`, never
   `(newly generated)` after the first deploy.
9. Verify from any machine:
   `openssl s_client -connect <relay-host>:7443 </dev/null 2>/dev/null | openssl x509 -outform DER | shasum -a 256`
   should print the same fingerprint.

## Pair the Mac

1. In Droidtosh on the Mac, open **Settings › Connection**. Choose **Relay**
   (or **Relay, local network as fallback**), then enter the relay host and
   port (for example `relay.example.com` / `7443`) and, optionally, the relay
   fingerprint.
2. Choose **Show Pairing Code**.
3. On the phone, open Droidtosh and scan the code. The phone connects to the
   relay, the Mac parks in the same room, and both show connected.

## Notes

- FCM payloads contain only `{"type": "mac_online"}`; notification, call,
  clipboard and file content stays end-to-end encrypted between the devices.
- FCM tokens are persisted only when `STATE_FILE` is set.
- The relay is stateless otherwise; restart it at any time and both devices
  reconnect.
