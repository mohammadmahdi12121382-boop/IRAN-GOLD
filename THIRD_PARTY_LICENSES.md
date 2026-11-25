# Third-party components

IRAN GOLD itself is GPLv3 (see `LICENSE`). No source code of MLM VPN or any other VPN client was copied; the
multi-engine idea was reimplemented independently.

| Component | Version | License | Source / how it is obtained |
|---|---|---|---|
| Xray-core (unmodified official binary, shipped as `libxray.so`) | v26.9.9 | MPL-2.0 | https://github.com/XTLS/Xray-core/releases/tag/v26.9.9 — downloaded by `scripts/fetch-engines.sh`, SHA-256 pinned in `engines.lock`. MPL-2.0 source: the upstream repository at that tag. |
| hev-socks5-tunnel (JNI lib + `hev.htproxy.TProxyService`) | release AAR, vendored | MIT | https://github.com/heiher/hev-socks5-tunnel — AAR checked in at `third_party/hev-socks5-tunnel/`, license alongside, SHA-256 in `engines.lock`. |
| tor-android (`libtor.so`, Tor daemon) | 0.4.8.16 | Tor: BSD-3-Clause (plus its bundled libs: OpenSSL/libevent/zlib licenses) | Guardian Project Maven repo https://github.com/guardianproject/gpmaven — **license of the AAR not re-verified in this build environment; check before redistributing.** |
| IPtProxy (Snowflake client) | 5.7.0 | MIT (Snowflake itself: BSD-3-Clause) | Maven Central `com.netzarchitekten:IPtProxy` — **coordinate taken from the project README; could not be fetched here.** |
| zxing-android-embedded | 4.3.0 | Apache-2.0 | Maven Central |
| AndroidX / Jetpack Compose / Material3 / Kotlin | see `app/build.gradle.kts` | Apache-2.0 | Google Maven / Maven Central |

## Public values used (not code)
Snowflake broker/front/STUN list in `TorEngine.kt` come from the official Snowflake sample torrc
(https://raw.githubusercontent.com/keroserene/snowflake/master/client/torrc, fetched 2026-11-25). They are public
Tor Project infrastructure and may go stale.

## Not included (by design)
WARP, Psiphon, Geph, AmneziaWG: not in the MVP (license/terms or integration not verified; see README).
