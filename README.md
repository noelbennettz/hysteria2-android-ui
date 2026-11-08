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
./gradlew assembleRelease    # signed APK (needs the keystore, see below)
```

### Continuous integration

| Workflow | Trigger | Output |
| --- | --- | --- |
| `.github/workflows/build.yml` | every push to `main`, or manual | debug APK as a workflow artifact |
| `.github/workflows/release.yml` | pushing a `v*` tag, or manual with a version | **signed** release APKs + a GitHub Release |

Both workflows install the newest Hysteria core before building (see *Bundled
core* above).

## Releasing

The release workflow signs the APK and publishes it as a GitHub Release. It
needs four repository secrets (Settings → Secrets and variables → Actions):

| Secret | Value |
| --- | --- |
| `ANDROID_KEYSTORE_BASE64` | your keystore, base64-encoded: `base64 -w0 signing/production.jks` |
| `ANDROID_KEYSTORE_PASSWORD` | keystore password |
| `ANDROID_KEY_PASSWORD` | key password |
| `ANDROID_KEY_ALIAS` | key alias |

Create the keystore once (keep it safe and backed up — losing it means you can
never update an installed app under the same package name):

```bash
keytool -genkeypair -v -keystore signing/production.jks -alias signing \
  -keyalg RSA -keysize 2048 -validity 10000
```

Then publish either by pushing a tag, or manually from the Actions tab:

```bash
git tag v2.1.0 && git push origin v2.1.0        # tag → versionName 2.1.0
```

`versionName` comes from the tag (or the `version_name` input) and `versionCode`
from the workflow run number, so every release is upgradeable.

Without `ANDROID_KEYSTORE_BASE64` the workflow still builds (validating R8 /
ProGuard against the JNI bindings) but produces an unsigned APK, skips the
signature check and does not publish — the log says so explicitly.

To sign locally instead, drop the keystore at `signing/production.jks`
(gitignored) and export `HY2_KEYSTORE_PASSWORD`, `HY2_KEY_PASSWORD` and
`HY2_KEY_ALIAS` before running `./gradlew assembleRelease`.


## Credits

- [hysteria](https://github.com/apernet/hysteria) — protocol and reference
  implementation. [LICENSE](https://github.com/apernet/hysteria/blob/master/LICENSE.md)
- [go-tun2socks](https://github.com/eycorsican/go-tun2socks) — TUN ↔ SOCKS5
  bridge. [LICENSE](https://github.com/eycorsican/go-tun2socks/blob/master/LICENSE)

This application is provided for learning and personal use. Follow the laws of
your jurisdiction.
