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

The Hysteria core ships as a prebuilt Android executable at
`app/src/main/jniLibs/<abi>/libhysteria.so` (the upstream release asset
`hysteria-android-<arch>`, renamed). Its version and per-ABI SHA-256 checksums
are recorded in `core/core.properties` and exposed to the app as
`BuildConfig.CORE_VERSION` (the VPN service logs it on every connect).

`scripts/core.sh` manages the core:

| Command | Description |
| --- | --- |
| `scripts/core.sh version` | Show the recorded vs. embedded core version |
| `scripts/core.sh verify` | Verify the committed binaries against the manifest (also run in CI) |
| `scripts/core.sh upgrade app/v2.13.0` | Download, checksum-verify and install a new core |
| `scripts/core.sh hashes` | Print the current per-ABI checksums |

Upgrading is a single command plus a commit:

```bash
scripts/core.sh upgrade app/v2.13.0
git add core/core.properties app/src/main/jniLibs
git commit -m "chore: bump Hysteria core to v2.13.0"
```

ABI mapping: `arm64-v8a` → `arm64`, `armeabi-v7a` → `armv7`, `x86_64` → `amd64`,
`x86` → `386`. The service only execs the binary with `-c <config>`, so a core
bump is a straight binary swap as long as the config schema is unchanged. If a
future core changes its YAML schema, `HysteriaConfigBuilder` is the only
app-side touch point.

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
