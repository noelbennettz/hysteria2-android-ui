# Hysteria 2 · Android

A native Android client for the [Hysteria 2](https://v2.hysteria.network/) proxy
protocol, built on top of the official Go core (bundled as the prebuilt
`libhysteria.so`) and [go-tun2socks](https://github.com/eycorsican/go-tun2socks)
for the TUN ↔ SOCKS5 bridge.

The app is a thin, Material 3 UI plus an Android `VpnService`; all protocol logic
runs inside the bundled native core. Supports the four common ABIs
(`arm64-v8a`, `armeabi-v7a`, `x86`, `x86_64`).

## Features

- System-wide VPN (full-tunnel `VpnService`) backed by Hysteria 2
- Local SOCKS5 and HTTP proxy listeners exposed by the core
- Multiple saved profiles, each represented by its `hysteria2://` share link
- Import a profile by pasting a `hysteria2://` link, or add fields manually
- Custom SNI (defaults to the server host when left blank)
- Salamander obfuscation (leave the obfuscation password blank to disable)
- Optional "allow insecure certificate"
- Optional bandwidth hint (Mbps) to enable Hysteria's Brutal congestion control;
  leave it at 0 to use BBR
- QUIC fast open enabled by default for lower latency
- Live upload/download counters while connected

## How it works

```
UI (Compose / Material 3)
  └─ MainViewModel ── writes hysteria.yaml ──┐
                                             ▼
                              Hysteria2VpnService (VpnService)
                                 ├─ VpnService.Builder → TUN fd
                                 ├─ exec libhysteria.so -c hysteria.yaml → :1080 socks5 / :1081 http
                                 └─ libtun2socks.so (JNI)  TUN fd → 127.0.0.1:1080
```

The Hysteria core runs as a child process exposing a local SOCKS5 listener;
`tun2socks` bridges the Android TUN device to it. The native `libtun2socks.so`
binds its JNI symbols to `us.leaf3stones.hy2droid.proxy.Hysteria2VpnService`, so
that class name and the `startTun2socks`/`stopTun2socks`/`getTun2socksStats`
signatures must be preserved.

## Usage

- **Server**: the server domain or IP (optionally with `:port`, default `443`)
- **Auth**: the Hysteria 2 password
- **SNI**: TLS SNI; optional, defaults to the server host. May differ from the
  server address when the server is addressed by IP but the certificate is
  issued for a hostname
- **Obfuscation password**: the Salamander password; leave blank if the server
  does not use obfuscation
- **Upload / Download Mbps**: set your real line speeds to enable Brutal; both 0
  disables Brutal and uses BBR (see the
  [official docs](https://v2.hysteria.network/docs/advanced/Full-Server-Config/))
- **Allow insecure certificate**: skips TLS verification; should normally stay
  off (enables man-in-the-middle attacks)

## Bundled core

The Hysteria core binaries are **not committed to git**. They are downloaded at
build time from the upstream release (`hysteria-android-<arch>`) and verified
against that release's `hashes.txt` into
`app/src/main/jniLibs/<abi>/libhysteria.so`.

`core/core.properties` pins the version plus the per-ABI SHA-256 checksums and is
exposed to the app as `BuildConfig.CORE_VERSION` (logged by the VPN service on
every connect).

`scripts/core.sh` manages it:

| Command | Description |
| --- | --- |
| `scripts/core.sh fetch` | Install the pinned core (no-op when already present); Gradle runs this via the `fetchCore` task |
| `scripts/core.sh latest` | Resolve the newest upstream release tag |
| `scripts/core.sh upgrade [tag\|latest]` | Install a specific (default: newest) core |
| `scripts/core.sh version` | Pinned version + whether the binaries match |
| `scripts/core.sh verify` | Verify the binaries against the manifest (run in CI) |
| `scripts/core.sh hashes` | Print the per-ABI checksums |

CI installs **the latest upstream release on every build**; the resolved version
is printed in the workflow run summary. To pin a build, dispatch the workflow
with the `core_tag` input (e.g. `app/v2.12.3`), or locally:

```bash
HY2_CORE_TAG=app/v2.12.3 ./gradlew assembleDebug   # one-off override
scripts/core.sh upgrade app/v2.12.3                # move the committed pin
```

ABI mapping: `arm64-v8a` → `arm64`, `armeabi-v7a` → `armv7`, `x86_64` → `amd64`,
`x86` → `386`. A core bump needs no app changes because the service only execs
the binary with `-c <config>`; if a release changes the YAML schema,
`HysteriaConfigBuilder` is the only app-side touch point.

## Building

```bash
./gradlew assembleDebug      # APK at app/build/outputs/apk/debug/
```

Release builds require a keystore at `signing/production.jks` plus the
`HY2_KEYSTORE_PASSWORD` / `HY2_KEY_PASSWORD` / `HY2_KEY_ALIAS` Gradle properties
or environment variables (never committed).

Continuous integration builds a debug APK on every push to `main` and uploads it
as a workflow artifact.

## Credits

- [hysteria](https://github.com/apernet/hysteria) — protocol and reference
  implementation. [LICENSE](https://github.com/apernet/hysteria/blob/master/LICENSE.md)
- [go-tun2socks](https://github.com/eycorsican/go-tun2socks) — TUN ↔ SOCKS5
  bridge. [LICENSE](https://github.com/eycorsican/go-tun2socks/blob/master/LICENSE)

This application is provided for learning and personal use. Follow the laws of
your jurisdiction.
