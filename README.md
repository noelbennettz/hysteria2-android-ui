# Hy2droid — Hysteria 2 for Android

A native Android client for the [Hysteria 2](https://v2.hysteria.network/) proxy
protocol: a thin Kotlin/Jetpack Compose (Material 3) UI in front of an Android
`VpnService`, with the official Go core running as a child process and
[hev-socks5-tunnel](https://github.com/heiher/hev-socks5-tunnel) bridging the TUN
device to it.

Requires **Android 11 (API 30) or newer**. Ships `arm64-v8a`, `armeabi-v7a`,
`x86` and `x86_64` (per-ABI splits plus a universal APK).

<p align="center">
  <img src="screenshots/s1.png" width="300" alt="Home screen showing the tunnel status and saved profiles">
  <img src="screenshots/s2.png" width="300" alt="Settings screen with appearance options, split tunnelling, blocked traffic, LAN bypass and custom DNS">
</p>

## Features

- Full-tunnel `VpnService` backed by Hysteria 2, dual-stack (IPv4 + IPv6)
- Multiple saved profiles; import by pasting a `hysteria2://` link or by
  scanning a QR code (fully offline scanner, no Play Services needed). Tapping a
  profile while connected switches to it — the service tears the old tunnel down
  and rebuilds it, no manual disconnect needed
- Share a profile as a QR code
- Salamander obfuscation, custom SNI, optional "allow insecure certificate"
- Brutal congestion control via an upload/download bandwidth hint (0 = BBR)
- `fastOpen` is **off** by default; `lazy` is always on (it only defers the
  *first* connection — upstream: "the client will not connect until the first
  call to TCP() or UDP()" — after that the session is held open), with a 20 s
  QUIC heartbeat
- DNS, two modes (Settings → Network). **Cache DNS answers** (default) is served
  by a resolver that lives in the app: the VPN pushes its own TUN address, so
  Android's queries land on a socket the app owns instead of going into the
  tunnel. A repeated lookup is answered from an in-memory cache with every TTL
  aged on the way out; a miss is resolved through the core (DNS over TCP via its
  SOCKS5 listener), so the query still leaves from the server and the answer is
  a real one — no fake addresses, so an app that resolves early and connects
  later keeps working, and AAAA behaves like everywhere else. Turn the switch
  off and the tun gets real resolvers directly — Quad9 (`9.9.9.9`) with
  Cloudflare (`1.1.1.1`) as the backup — because Android has no "no DNS means
  use the tunnel" mode: with an empty DNS list it resolves through the
  underlying network, i.e. the queries leave over Wi-Fi and never reach the
  server. A custom primary/secondary (IPv4 or IPv6, validated) overrides both
  modes.

- **Network handover**: switching Wi-Fi ↔ mobile restarts the Hysteria core, so
  the session is back within a second instead of waiting out the 30 s QUIC idle
  timeout. The TUN and the tunnel engine stay up; flows in flight during the
  restart are reset and retried by the apps.

- Split tunnelling: pick apps that bypass the VPN
- **Blocked traffic**: refuse flows by protocol and port inside the tunnel —
  QUIC (UDP 443) is blocked by default, because Hysteria 2 cannot accelerate it
- Optional **LAN bypass** (Android 13+): local-network traffic — RFC1918,
  link-local, CGNAT and multicast (mDNS/SSDP) — stays outside the VPN, so NAS
  boxes, printers, router admin pages and casting keep working
- **Disable path MTU discovery** (Settings → Network, off by default): emits
  `quic.disablePathMTUDiscovery: true`, the standard workaround for networks
  that silently drop large QUIC packets (MTU black holes), where connections
  otherwise stall
- **Tunnel MTU** (Settings → Network, default 1400, range 1280–1500): the same
  value goes into the TUN interface and the tun2socks config, so the two cannot
  drift apart. 1400 carries a full-size QUIC datagram on a 1500-byte path with
  fewer packets than 1280; drop to 1280 only on networks that stall
- Deleting a profile asks first — the auth secret cannot be recovered
- Quick Settings tile to connect/disconnect, kept in sync with the tunnel state;
  a tap connects the last selected profile (with an empty list it does nothing)
- **Latency test** per node from the profile menu, measured by a real request
  through a throwaway core instance (a Hysteria 2 server only answers UDP, so a
  TCP ping would be meaningless). Optional *Test latency on launch*
  (Settings → Network, off by default) measures the nodes that have no result
  yet and then lists the fastest one first — it never switches the node for you,
  and the numbers survive a restart (they expire after 24 h, so the list is never
  sorted by yesterday's numbers)
- **Share-link deep link**: tapping a `hysteria2://` (or `hy2://`) link in a
  browser opens the app and adds the profile straight away — no pasting, no
  editor, no manual file
- **Export logs** (Settings → Diagnostics): one zip with the app logcat buffer,
  the persisted Hysteria core log, a build/device summary and the running config
  with `auth` / obfs passwords masked — no storage permission needed
- Always-on VPN support: a system-initiated start connects normally instead of
  refusing (refusing only makes Android report "always-on VPN is not connected"
  and retry), and disconnecting while it is on explains that Android will start
  the tunnel again
- Optional **live traffic** (Settings → Appearance, off by default): throughput on
  the status card and in the notification, refreshed every 5 s. Off by default
  because on Android 14 the ongoing notification can be swiped away, and a
  throughput update would immediately bring it back
- Dynamic colour (Material You) toggle, custom DNS, split tunnelling in Settings
- Profile cards show only the profile name by default; the server address is one
  switch away (Settings → Appearance → *Show server address*)
- Profiles and settings are included in Android Auto Backup (secrets too, so a
  restore on a new phone keeps the profiles usable — see Security notes)

## Languages

The UI is translated into the six official languages of the United Nations plus
Traditional Chinese, and Android's per-app language picker
(`Settings → Apps → Hy2droid → Language`) is wired up through
`res/xml/locales_config.xml`:

| Locale | Resource folder |
| --- | --- |
| English | `values/` (default) |
| Arabic (RTL) | `values-ar/` |
| Spanish | `values-es/` |
| French | `values-fr/` |
| Russian | `values-ru/` |
| Chinese (Simplified) | `values-zh-rCN/` |
| Chinese (Traditional) | `values-zh-rTW/` |

All user-visible text lives in `strings.xml`; adding a language means copying
`values/strings.xml` into a new `values-<qualifier>/` folder and translating the
values (keep the `%1$s` / `%1$d` placeholders intact, escape apostrophes as `\'`).
`app_name` is deliberately not translated. The layouts only use direction-aware
padding/alignment, so RTL (Arabic) mirrors correctly.

## How it works

```
UI (Compose / Material 3)
  └─ MainViewModel ── renders config ──┐
                                       ▼
                     Hysteria2VpnService (VpnService)
                        ├─ VpnService.Builder → TUN fd
                        │    IPv4 10.0.88.88/16, IPv6 fc00::1/64,
                        │    MTU from Settings (default 1400)
                        ├─ exec libhysteria.so -c hysteria.yaml
                        │    → 127.0.0.1:1080 (SOCKS5) + :1081 (HTTP)
                        └─ libtun2socks.so (JNI): TUN fd → SOCKS5
```

The core runs as a child process exposing a local SOCKS5 listener;
`libtun2socks.so` (hev-socks5-tunnel plus the patches in `native/tun2socks/`)
bridges the TUN device to it. The service monitors the child process and tears
the tunnel down if it exits.

The app's own UID is excluded from the tunnel
(`VpnService.Builder.addDisallowedApplication(packageName)`), so the core child
process — same UID — reaches the server over the underlying network and resolves
the profile's hostname with the system resolver. If that resolver is broken the
connect fails; there is deliberately no fallback (no hardcoded resolver, no DoH
retry) on that path. DNS *inside* the tunnel is separate: the tun always gets a
resolver (see Features), and in the default mode that resolver is the TUN's own
address, answered by the app's caching resolver (`proxy/LocalDns.kt`), which
forwards misses through the core's SOCKS5 listener.


A network handover cannot be fixed by re-pinning the TUN: Hysteria 2 has no
rebind API, so the service watches the set of validated non-VPN transports and
restarts the core when it changes (Wi-Fi ↔ mobile, airplane mode). The restart
is serialised with connect/disconnect, the old process is killed before the new
one binds the SOCKS port, and a core that dies afterwards is still reported as
an error.


`libtun2socks.so` binds its JNI symbols to the **exact** class
`us.leaf3stones.hy2droid.proxy.Hysteria2VpnService` and to the signatures of
`startTun2socks` / `stopTun2socks` / `getTun2socksStats`. Neither the class name
nor those signatures may change without rebuilding the `.so`, and ProGuard/R8 is
configured to keep them. The prebuilt `.so` files are committed, so building the
app needs no NDK; `scripts/tun2socks.sh build` reproduces them from a pinned
upstream tag when a patch changes.

## Blocked traffic

Hysteria 2 forwards UDP through its own QUIC connection and has no UDP
acceleration, so QUIC inside the tunnel (QUIC over QUIC) is markedly slower than
TCP on lossy networks — a limitation of the protocol, not of this app. Refusing
those flows makes browsers and apps fall back to TCP/TLS, which the core handles
much better.

`Settings → Blocked traffic` keeps a list of `protocol + port` rules (single
ports or ranges, `TCP`, `UDP` or both). **QUIC (UDP 443) is in the list on a
fresh install** — that is the whole point of the feature; users can delete it or
add their own rules. Rules are rendered into the tunnelling config:

```yaml
misc:
  block-tcp: '25,465'
  block-udp: '443'
```

The patched tunnel applies them before any session is created: a blocked TCP
port is answered with a reset (so the client fails over immediately) and a
blocked UDP port is dropped. Nothing of a blocked flow reaches the core, and
there is no per-packet cost for everything else. Changes apply on the next
connection. With no rules the config carries no `misc:` section at all.

## Repository layout

| Path | Contents |
| --- | --- |
| `app/src/main/java/.../ui` | Compose UI (home, profile editor, settings, split tunnel, blocked traffic, QR scanner) |
| `app/src/main/java/.../data` | `Profile`, `ProfileStore`, `SettingsStore`, `BlockRule`, `HysteriaConfigBuilder`, `HysteriaUri`, `ProfileOrdering`, `Redact`, `DiagnosticsExporter`, `ConfigFile` |
| `app/src/main/java/.../proxy` | `Hysteria2VpnService`, `VpnTileService`, `TunnelStatus`, `Tun`, `Tun2socksConfig`, `LatencyProbe`, `CoreLog` |
| `app/src/main/jniLibs` | `libtun2socks.so` (committed, see `native/tun2socks/`); `libhysteria.so` is downloaded at build time |
| `native/tun2socks` | Patches applied on top of hev-socks5-tunnel (blocked-port rules, JNI names) |
| `core/core.properties` | Pinned Hysteria core version + per-ABI checksums |
| `scripts/core.sh` | Fetch / verify / upgrade the core |
| `scripts/tun2socks.sh` | Rebuild `libtun2socks.so` from the pinned upstream tag + patches |
| `scripts/prune-runs.sh` | Delete all but the newest three runs of the workflow calling it |
| `.github/workflows` | `build.yml` (debug), `release.yml` (signed release), `native.yml` (rebuild `libtun2socks.so`) |

## Building

Prerequisites: JDK 17 and an Android SDK with platform 34. The Hysteria core is
fetched automatically (see *Bundled core*), so a fresh clone builds with:

```bash
./gradlew assembleDebug        # app/build/outputs/apk/debug/
./gradlew testDebugUnitTest    # JVM unit tests
./gradlew assembleRelease      # signed APK when a keystore is present
./gradlew lintDebug            # Android lint; errors fail the build

scripts/check-style.sh         # .editorconfig rules (LF, final newline, width,
                               # import order, no tabs/trailing spaces)
scripts/check-style.sh --fix   # rewrite the mechanical ones
```

## Bundled core

The Hysteria core binaries are **not committed to git**. They are downloaded at
build time from the upstream release (`hysteria-android-<arch>`), verified
against that release's `hashes.txt`, and installed into
`app/src/main/jniLibs/<abi>/libhysteria.so`. `core/core.properties` pins the
version plus the per-ABI SHA-256 checksums and is surfaced to the app as
`BuildConfig.CORE_VERSION` (logged by the service on every connect).

`scripts/core.sh` manages it:

| Command | Description |
| --- | --- |
| `scripts/core.sh fetch` | Install the pinned core (no-op when already present); Gradle runs this via the `fetchCore` task on `preBuild` |
| `scripts/core.sh latest` | Resolve the newest upstream release tag |
| `scripts/core.sh upgrade [tag\|latest]` | Install a specific (default: newest) core |
| `scripts/core.sh version` | Pinned version + whether the binaries match |
| `scripts/core.sh verify` | Verify the binaries against the manifest (run in CI) |
| `scripts/core.sh hashes` | Print the per-ABI checksums |

CI installs **the newest upstream release on every build** and prints the resolved
version in the run summary. Pin a build with the `core_tag` workflow input
(e.g. `app/v2.12.3`) or locally:

```bash
HY2_CORE_TAG=app/v2.12.3 ./gradlew assembleDebug   # one-off override
scripts/core.sh upgrade app/v2.12.3                # move the committed pin
```

ABI mapping: `arm64-v8a` → `arm64`, `armeabi-v7a` → `armv7`, `x86_64` → `amd64`,
`x86` → `386`. A core bump needs no app changes because the service only execs
the binary with `-c <config>`; if a release changes the YAML schema,
`HysteriaConfigBuilder` is the only app-side touch point.

## Continuous integration and releases

| Workflow | Trigger | Output |
| --- | --- | --- |
| `.github/workflows/build.yml` | push to `main`, or manual | debug APK as a workflow artifact (unit tests + `lintDebug` gate) |
| `.github/workflows/release.yml` | pushing a `v*` tag, or manual with a version | signed release APKs + a GitHub Release |
| `.github/workflows/native.yml` | manual | rebuilt `libtun2socks.so` for all four ABIs, as an artifact |

Both verify the bundled core before building; the release workflow additionally
runs the unit tests, and a tag build ships the core **pinned in
`core/core.properties`** so rebuilding a tag yields the same binaries (only a
manual run with an explicit `core_tag` moves it). The debug workflow follows the
newest upstream core, which is the point of it. Signing uses a keystore from
repository secrets, verified with `apksigner`; the release publishes
`app-universal-release.apk` plus the four ABI splits.

Every workflow ends with `scripts/prune-runs.sh 3`: it deletes the runs of *that
workflow* older than the three newest (logs and artifacts included, which keeps
the Actions tab and the artifact quota small). A deleted release run does not
remove its GitHub Release. Each workflow grants `actions: write` for it, and the
script stops after one pass if it cannot delete anything.

### Setting up signing

Add four repository secrets (Settings → Secrets and variables → Actions):

| Secret | Value |
| --- | --- |
| `ANDROID_KEYSTORE_BASE64` | keystore, base64-encoded: `base64 -w0 signing/production.jks` |
| `ANDROID_KEYSTORE_PASSWORD` | keystore password |
| `ANDROID_KEY_PASSWORD` | key password |
| `ANDROID_KEY_ALIAS` | key alias |

Create the keystore once and back it up — losing it means installed apps can
never be updated under the same package name:

```bash
keytool -genkeypair -v -keystore signing/production.jks -alias signing \
  -keyalg RSA -keysize 2048 -validity 10000
```

Then publish by pushing a tag (or from the Actions tab with a version input):

```bash
git tag v2.1.0 && git push origin v2.1.0     # versionName 2.1.0
```

`versionName` comes from the tag (or the `version_name` input) and `versionCode`
from the workflow run number, so every release is upgradeable. Without
`ANDROID_KEYSTORE_BASE64` the workflow still builds (validating R8/ProGuard
against the JNI bindings) but produces an unsigned APK, skips the signature
check and does not publish.

To sign locally, place the keystore at `signing/production.jks` (gitignored, or
point `HY2_KEYSTORE_FILE` at it) and export `HY2_KEYSTORE_PASSWORD`,
`HY2_KEY_PASSWORD`, `HY2_KEY_ALIAS`, then run `./gradlew assembleRelease`.

## Profile fields

- **Server** — domain or IP, plus a separate **port** field (default `443`)
- **Auth** — the Hysteria 2 password
- **SNI** — TLS server name; blank means "derive from the server host". Needed
  when the server is addressed by IP but the certificate is issued to a hostname
- **Obfuscation password** — Salamander password; blank disables obfs
- **Upload / Download Mbps** — your real line speeds to enable Brutal; both 0
  keeps BBR (see the
  [official docs](https://v2.hysteria.network/docs/advanced/Full-Server-Config/))
- **Allow insecure certificate** — skips TLS verification; keep it off unless
  you know what you are doing
- **QUIC fast open** — off by default; only enable it if you know your network
  tolerates QUIC 0-RTT

## Security notes

- **Where the secrets live**: `auth` and the obfs password are stored as-is in
  the profiles DataStore under the app's private directory (`/data/data/...`,
  mode `0700`, owned by the app UID). No other app can read them — that is
  enforced by the per-app UID, not by our code. The app writes nothing to shared
  storage, declares no `FileProvider`, and no exported component hands out data.
- **Screens and clipboard**: the share dialog and the profile editor set
  `FLAG_SECURE` while they are open, so the QR, the link and the passwords cannot
  be captured by screenshots, screen recordings or the recents thumbnail; copying
  the link marks the clip `EXTRA_IS_SENSITIVE` (Android 13+), which hides
  previews and shortens its life.
- **What this does not cover, on purpose**: a rooted device or `adb` on a
  debuggable build can read the private directory, and Android's own backup can
  carry the file (Auto Backup is end-to-end encrypted with the device lock on
  Android 9+). That is the price for a backup restore that keeps working on a new
  phone, and it is why there is no Keystore wrapper: the encryption bought little
  against those two paths and cost a restore that silently lost every password.
- The YAML the core reads (`files/hysteria.yaml`) is written for the session and
  deleted when the tunnel stops; it, the `.tmp` file an interrupted atomic write
  would leave behind, and the core log are all excluded from backups.
- Exported diagnostics mask `auth` / obfs `password` and drop core log lines that
  carry a share link. Nothing else in the app writes the secret anywhere (not the
  notification, not logcat, not files outside the private dir).
- Any app on the device can hand us a `hysteria2://` link — that is the deep-link
  feature. An imported profile becomes the selected one (so the power button and
  the Quick Settings tile use it) but is never connected automatically: the user
  still has to press connect.

## Troubleshooting

- Settings → Diagnostics → *Export logs* writes a zip the user picks in the system
  file picker: `app-info.txt`, `logcat.txt`, `hysteria.log` (plus the rotated
  `hysteria.1.log`), and `hysteria.config.redacted.yaml`. Attach that to a bug
  report instead of pasting logcat.
- The core logs to logcat under the `hysteria` tag (lines containing a share URI
  or the obfs password are redacted). The same lines are appended to
  `filesDir/logs/hysteria.log` so they survive a reboot or an app crash.
- The service logs to the `Hysteria2VpnService` tag, including the bundled core
  version at every connect: `adb logcat -s Hysteria2VpnService | grep 'Hysteria core'`.
- If a newly released core breaks connectivity, pin the previous one via the
  `core_tag` input or `HY2_CORE_TAG` and open an issue.

## License

Hy2droid is released under the **GNU Affero General Public License v3.0**
(AGPL-3.0). The full text is in [LICENSE](LICENSE); the project notice and the
third-party (MIT) notices are in [NOTICE](NOTICE).

It is a network application, so section 13 applies: if you run a modified version
and let other people use it over a network, you must offer them the corresponding
source code.

## Credits

- [hysteria](https://github.com/apernet/hysteria) — protocol and reference
  implementation. [LICENSE](https://github.com/apernet/hysteria/blob/master/LICENSE.md)
- [hev-socks5-tunnel](https://github.com/heiher/hev-socks5-tunnel) — TUN ↔ SOCKS5
  bridge, patched for blocked-port rules (see `native/tun2socks/`).
  [LICENSE](https://github.com/heiher/hev-socks5-tunnel/blob/main/LICENSE)

This application is provided for learning and personal use. Follow the laws of
your jurisdiction.
