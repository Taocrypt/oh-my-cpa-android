# Third-party notices · 第三方组件声明

Oh My CPA for Android is MIT licensed (see [LICENSE](LICENSE)). It is a community project and is
**not affiliated with or endorsed by** the authors of any component below.

本项目以 MIT 许可证发布，是社区项目，与下列任何组件的作者均无从属或背书关系。

The app's own source contains no third-party code. The components below are either **downloaded at
runtime** from their official release channels (they are not inside the APK) or **bundled in the
APK** (marked). Each remains under its own license.

| Component | How it is used | License | Copyright / source |
| --- | --- | --- | --- |
| [CLIProxyAPI](https://github.com/router-for-me/CLIProxyAPI) | Downloaded at first launch (`linux_aarch64_no-plugin`; the glibc plugin build when plugin mode is on), executed as a child process | MIT | © 2025 Luis Pater; © 2025.9–present Router-For.ME — [LICENSE](https://github.com/router-for-me/CLIProxyAPI/blob/main/LICENSE) |
| [oh-my-cpa](https://github.com/WizisCool/oh-my-cpa) | Downloaded at first launch (`linux_arm64`), executed as a child process; its console UI is shown in the WebView | MIT | © 2026 JUNZE — [LICENSE](https://github.com/WizisCool/oh-my-cpa/blob/main/LICENSE) |
| oh-my-cpa brand artwork | The app icon, notification icon and wordmark are redrawn from oh-my-cpa's `mark` / `wordmark` drawings | MIT (as part of oh-my-cpa) | © 2026 JUNZE |
| **GNU C Library (glibc) 2.44, Termux build** — **bundled** in `assets/glibc` | `ld-linux-aarch64.so.1`, `libc.so.6`, `libdl.so.2`, `libm.so.6`, `libpthread.so.0`, `libresolv.so.2`, `librt.so.1`, unmodified binaries from the Termux `gpkg` package `glibc 2.44-0`; used only to run CPA's plugin build | LGPL-2.1-or-later (GNU C Library); Termux packaging scripts MIT | © Free Software Foundation, Inc. and contributors — upstream source: <https://ftp.gnu.org/gnu/glibc/glibc-2.44.tar.xz>; Android patches and build recipe: <https://github.com/termux-pacman/glibc-packages/tree/main/gpkg/glibc> |
| [cloudflared](https://github.com/cloudflare/cloudflared) | Downloaded on demand when Cloudflare quick tunnel is enabled | Apache-2.0 | © Cloudflare, Inc. — [LICENSE](https://github.com/cloudflare/cloudflared/blob/master/LICENSE) |
| [Tailscale](https://github.com/tailscale/tailscale) (`tailscale`, `tailscaled`) | Downloaded on demand from pkgs.tailscale.com when Tailscale is enabled | BSD-3-Clause | © 2020 Tailscale Inc & contributors — [LICENSE](https://github.com/tailscale/tailscale/blob/main/LICENSE) |
| [Lucide](https://lucide.dev) icons | Some UI icons are redrawn as Android vector drawables in the Lucide style | ISC | © Lucide contributors |

## LGPL notice for the bundled glibc

The glibc libraries in `assets/glibc` are distributed under the GNU Lesser General Public License,
version 2.1 or (at your option) any later version. They are shipped as separate, unmodified shared
objects and loaded at runtime only by CPA's plugin build; you may replace them with your own build
(the app checks them against `assets/glibc/SHA256SUMS`, so update that file too). The corresponding
source code is available from the links above (upstream glibc 2.44 plus the Termux patches and build
recipe).

The full text of the LGPL is at <https://www.gnu.org/licenses/old-licenses/lgpl-2.1.html>.

## Trademarks

“CLIProxyAPI”, “oh-my-cpa”, “Cloudflare”, “Tailscale” and other names belong to their respective
owners and are used here only to describe compatibility.
