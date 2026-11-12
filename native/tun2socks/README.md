# libtun2socks.so — hev-socks5-tunnel + blocked-port rules

`app/src/main/jniLibs/<abi>/libtun2socks.so` is the TUN ↔ SOCKS5 bridge the
`VpnService` uses. It is not built from source in this repository: it is
[hev-socks5-tunnel](https://github.com/heiher/hev-socks5-tunnel) (MIT, C) at the
version pinned in `scripts/tun2socks.sh`, plus the two patches in this
directory.

```
scripts/tun2socks.sh build          # rebuild all four ABIs into app/src/main/jniLibs
scripts/tun2socks.sh build arm64-v8a
scripts/tun2socks.sh info
```

The built `.so` files are committed, so a normal `./gradlew assembleDebug` (and
CI) never needs the NDK. You only need one when a patch or the pinned upstream
tag changes.

## The patches

**`0001-block-ports.patch`** — adds `misc.block-tcp` / `misc.block-udp` to the
YAML config (comma separated ports and ranges, e.g. `"25,465,8000-8100"`):

- a blocked **TCP** destination port is answered with `ERR_RST` in
  `tcp_accept_handler`, so the client sees a reset immediately and can fall back
  to another protocol instead of waiting for the proxy to time out;
- a blocked **UDP** destination port is dropped in `udp_recv_handler` before any
  session is created, so the datagram never reaches the Hysteria core.

Both hooks use `pcb->local_ip` / `pcb->local_port`, which lwIP fills with the
*original destination* of the intercepted packet (see
`hev-socks5-session-{tcp,udp}.c`).

Motivation: Hysteria 2 forwards UDP through its own QUIC connection and cannot
accelerate it, so QUIC (UDP/443) inside the tunnel is much slower than TCP on
lossy networks. Refusing UDP/443 makes browsers and apps use TCP/TLS instead.
The app exposes this in Settings → Blocked traffic.

**`0002-jni-contract.patch`** — renames the JNI entry points to the ones
`Hysteria2VpnService` declares (`startTun2socks`, `stopTun2socks`,
`getTun2socksStats`) and drops the unused `isRunning` binding.

## Mapped DNS (`mapdns`): available upstream, deliberately unused

2.19.3 already answers DNS locally when the YAML has a top-level `mapdns` block,
and it converts a mapped address back to a **name** before dialling
(`src/misc/hev-utils.c:hev_socks5_addr_from_lwip()` →
`hev_mapped_dns_lookup()` → `ATYP_DOMAIN`, on both the TCP and the UDP session
path). It is not used by this app.

Its answers are *fake* addresses out of a reserved pool (`network | index`, TTL
1 second). That breaks the two things users notice most: an app that resolves a
name and connects minutes later reaches a pool address that no longer maps to
anything, and an AAAA query is answered with "no records", so IPv6-only
destinations stop resolving. Fake-IP is a memory-saving trick for proxies that
cannot cache; this app has real answers to cache instead.

`proxy/LocalDns.kt` + `data/DnsMessage.kt` implement a plain DNS cache: the VPN
pushes the TUN's own address as the resolver, the kernel delivers those queries
to the app's socket, a hit is answered from memory with TTLs aged, and a miss is
sent through the core (DNS over TCP inside the core's SOCKS5 listener). Nothing
of that lives in `libtun2socks.so`, so `scripts/tun2socks.sh` still applies
exactly the two patches above, and the `.so` files need no rebuild.

## Testing the rule parser without a device

`block-ports-test.c` exercises `hev_config_init_from_str` and the two getters on
the host (it needs no TUN):

```bash
HEV=$(scripts/tun2socks.sh source)
cd "$HEV" && make static
gcc -O1 -o /tmp/block-ports-test "$OLDPWD"/native/tun2socks/block-ports-test.c \
    bin/libhev-socks5-tunnel.a third-part/lwip/bin/liblwip.a \
    third-part/yaml/bin/libyaml.a third-part/hev-task-system/bin/libhev-task-system.a \
    -I src -I src/core/include -I third-part/yaml/include \
    -I third-part/lwip/src/include -I third-part/lwip/src/ports/include \
    -I third-part/hev-task-system/include -lpthread
/tmp/block-ports-test
```

## Constraints

- The library name (`libtun2socks.so`), the class (`us.leaf3stones.hy2droid.proxy.Hysteria2VpnService`)
  and the three native method signatures are a binary contract with the app.
  Change them together, and keep the R8 `-keep` rules in
  `app/proguard-rules.pro` in sync.
- The upstream library is built with NDK r25c (`ANDROID_NDK_HOME`), the same
  toolchain the current binaries came from; `ndk-build` output is installed over
  `app/src/main/jniLibs/<abi>/libtun2socks.so`.
- Bumping upstream: change `HEV_TAG` in `scripts/tun2socks.sh`, check that both
  patches still apply (`git apply` will say so), rebuild, and re-check the
  tunnel on a device.
