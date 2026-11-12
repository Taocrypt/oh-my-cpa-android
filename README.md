<div align="center">

<img src="docs/assets/mark.svg" alt="Oh My CPA" width="96">

# Oh My CPA · Android

**把 CLIProxyAPI 和 oh-my-cpa 控制台装进口袋：在手机本地运行，无需服务器、无需 root。**

[![Release](https://img.shields.io/github/v/release/Taocrypt/oh-my-cpa-android?style=flat-square&label=Release)](https://github.com/Taocrypt/oh-my-cpa-android/releases/latest)
[![Android 10+ arm64](https://img.shields.io/badge/Android-10%2B%20arm64-3451b2?style=flat-square)](#系统要求)
[![CPA v8](https://img.shields.io/badge/CLIProxyAPI-v8-5865F2?style=flat-square)](https://github.com/router-for-me/CLIProxyAPI)
[![License: MIT](https://img.shields.io/badge/License-MIT-64748B?style=flat-square)](LICENSE)

**简体中文** · [English](#english)

[下载 APK](https://github.com/Taocrypt/oh-my-cpa-android/releases/latest) · [项目主页](https://taocrypt.github.io/oh-my-cpa-android/) · [功能](#功能) · [工作原理](#工作原理) · [从源码构建](#从源码构建) · [常见问题](#常见问题)

</div>

> [!NOTE]
> 这是一个**社区项目**，与 [router-for-me/CLIProxyAPI](https://github.com/router-for-me/CLIProxyAPI) 和 [WizisCool/oh-my-cpa](https://github.com/WizisCool/oh-my-cpa) 的作者**没有任何从属或合作关系**。问题请先在本仓库反馈，不要打扰上游。

## 这是什么

**Oh My CPA**（包名 `com.taocrypt.omc`）是一个 Android 外壳应用：

- 在手机上以子进程方式运行 **[CLIProxyAPI](https://github.com/router-for-me/CLIProxyAPI)**（CPA，v8）——把 Codex、Claude Code、Gemini 等账号包装成 OpenAI / Gemini / Claude 兼容的 API；
- 同时运行 **[oh-my-cpa](https://github.com/WizisCool/oh-my-cpa)**（v0.3.19）——基于 Ant Design 的 CPA 管理控制台，带请求记录与用量分析；
- 用全屏 WebView 打开 oh-my-cpa 控制台，并补上 Android 原生能力：常驻服务、悬浮窗、快捷设置磁贴、局域网 / 远程访问、日志诊断等。

APK 本身很小：两个程序的 Linux arm64 二进制在**首次启动时**从各自的 GitHub Releases 下载并校验，不打包在安装包里。

## 功能

| | |
| --- | --- |
| **首次启动自动部署** | 从 GitHub Releases 下载 CPA（`linux_aarch64_no-plugin`）和 oh-my-cpa（`linux_arm64`）二进制，SHA-256 固定校验 + `checksums.txt`，支持断点续传。 |
| **下载镜像** | 可设置 GitHub 下载镜像前缀（例如 `https://ghfast.top/`），国内网络也能顺利完成首次部署和更新。 |
| **前台服务常驻** | 两个进程由前台服务托管，异常退出自动按退避重启，多次失败会停下并给出最后一行日志；可一键申请忽略电池优化、开机自启。 |
| **Go DNS 兼容** | 内置 DNS 代理，解决 Go 静态程序在 Android 上找不到 `/etc/resolv.conf` 无法解析域名的问题。 |
| **自动登录** | 自动生成并填入管理密钥，打开即进入控制台，不用每次输入密码。 |
| **原生面板** | 右下角可拖动的悬浮按钮（带服务状态点），点开从右侧滑入的原生面板，配色实时跟随控制台主题；也可在控制台侧栏里加一个「App 设置」入口。 |
| **悬浮窗 + 气泡** | 控制台可以小窗悬浮在其他应用之上，可缩放、调透明度，收起为贴边气泡；另有快捷设置磁贴和桌面快捷方式。 |
| **局域网访问** | 一键让 CPA 和控制台监听 `0.0.0.0`，同一 Wi-Fi 下的电脑直接调用手机上的 API；可选 mDNS 广播。端口可自定义。 |
| **远程访问** | Cloudflare Quick Tunnel 生成临时公网地址；或用 Tailscale（用户态网络栈）加入你的 tailnet，控制台与 API 都可达。组件按需下载。 |
| **插件模式** | 跟随控制台里的「启用插件系统」开关：开启后自动换用 CPA 的 glibc 插件版，通过 APK 内置的 Termux glibc 加载器原生运行（无需 PRoot / root）；失败自动回退到静态版。 |
| **插件页自动填密钥** | 插件页面（如「降智测试」）会自动拿到管理密钥，无需手动连接 CPA；可在设置中关闭。 |
| **空间管理** | 查看并清理二进制、日志、旧版本备份、隧道组件等占用的存储空间。 |
| **组件更新** | 检查 CPA / oh-my-cpa 新版本：强制校验 `checksums.txt`，保留上一版本可一键回滚；cloudflared / Tailscale 也可检查并更新。 |
| **外壳自更新** | 启动时检查本仓库 GitHub Releases 的新版 APK（可走镜像），校验包名与签名后调起系统安装，下载与安装进度显示在通知栏；也可选择用浏览器下载。 |
| **日志与诊断** | 分标签查看 oh-my-cpa / CPA / App / 隧道 / 崩溃日志，复制或分享（自动脱敏密钥），一键导出诊断压缩包到 `Download/OhMyCPA`。 |

## 工作原理

```
┌──────────────────────── Android 手机 ────────────────────────┐
│  Oh My CPA (com.taocrypt.omc)                                 │
│   ├─ 前台服务 ── 进程守护 / DNS 代理 / 隧道                    │
│   │    ├─ cli-proxy-api   127.0.0.1:8317   ← 你的 AI 客户端    │
│   │    └─ oh-my-cpa       127.0.0.1:8080   → 管理 CPA          │
│   └─ 全屏 WebView ──→ http://127.0.0.1:8080 （自动登录）       │
└───────────────────────────────────────────────────────────────┘
        局域网 0.0.0.0 / Cloudflare Tunnel / Tailscale（可选）
```

- CPA 默认监听 `127.0.0.1:8317`，oh-my-cpa 默认监听 `127.0.0.1:8080` 并指向 CPA；两个端口都可在「应用设置」里修改。
- 二进制保存在应用私有目录。应用的 `targetSdk` 为 28，这是 Android 允许执行应用自己下载的可执行文件的前提（targetSdk ≥ 29 会被系统禁止）。
- 管理接口的远程管理（`allow-remote`）始终关闭；开启局域网只开放 API 与控制台端口。

## 系统要求

- Android **10（API 29）或更高**，**arm64-v8a**（64 位 ARM）设备。
- 首次启动需要能访问 GitHub（或设置镜像），下载约数十 MB 的二进制。
- 远程访问、插件模式会额外下载 cloudflared / Tailscale / CPA 插件版。

## 安装

1. 打开 [Releases](https://github.com/Taocrypt/oh-my-cpa-android/releases/latest)，下载最新的 `oh-my-cpa-<版本>.apk`。之后的版本可以在应用内「检查更新」直接升级。
2. 在手机上安装（需要允许「安装未知来源应用」）。
3. 首次打开时等待下载并校验 CPA 与 oh-my-cpa；网络不畅可打开「使用下载镜像」。
4. 允许通知权限（前台服务需要），建议在面板 → 应用设置里「忽略电池优化」。
5. 进入控制台后，在 oh-my-cpa 里添加账号或 API Key；客户端把 Base URL 设为 `http://127.0.0.1:8317/v1`（局域网则用手机 IP）。

## 权限说明

| 权限 | 用途 |
| --- | --- |
| 网络、网络状态 | 运行本地服务、下载组件、远程访问 |
| 前台服务、通知 | 让服务在后台常驻并显示状态与操作按钮 |
| 唤醒锁、忽略电池优化 | 防止熄屏后系统冻结服务（可选） |
| 开机启动 | 「开机自启」开关（可选，默认关闭） |
| 悬浮窗 | 悬浮窗与气泡（仅在你打开悬浮窗时申请） |
| 本地网络 | 局域网访问与 mDNS |
| 安装未知应用 | 外壳自更新时调起系统安装（首次直接下载时申请） |

应用不包含任何统计或追踪 SDK。

## 从源码构建

项目不依赖 Gradle：`build.sh` 直接调用 Android SDK 工具链 `aapt2 → javac → d8 → zipalign → apksigner`。

需要：

- JDK 11 或更高（`javac` / `java` 在 `PATH` 中，或设置 `JAVA_HOME`）
- Android SDK：`build-tools`（含 `aapt2`、`zipalign`、`lib/d8.jar`、`lib/apksigner.jar`）与 `platforms/android-34/android.jar`
- `zip`、POSIX `sh`

**签名密钥不包含在仓库中**，请使用你自己的 keystore，通过环境变量传入：

```sh
# 生成一个自己的签名密钥（只需一次）
keytool -genkeypair -keystore my-release.p12 -storetype PKCS12 \
  -alias omc -keyalg RSA -keysize 4096 -validity 10000

export ANDROID_HOME=$HOME/Android/Sdk          # 默认取 build-tools 下最新版本
export OMC_KEYSTORE=$PWD/my-release.p12
export OMC_KS_PASS='你的密码'                   # 只从环境变量读取
export OMC_KEY_ALIAS=omc                        # 默认 omc
sh build.sh                                     # 输出 build/oh-my-cpa-<版本>.apk
```

| 变量 | 说明 | 默认 |
| --- | --- | --- |
| `ANDROID_HOME` / `ANDROID_SDK_ROOT` | Android SDK 根目录 | `$HOME/Android/Sdk` |
| `BUILD_TOOLS` | build-tools 目录 | `$ANDROID_HOME/build-tools/` 下最新版本 |
| `ANDROID_JAR` | 编译用 `android.jar` | `$ANDROID_HOME/platforms/android-34/android.jar` |
| `JAVA_HOME` | JDK 目录 | 使用 `PATH` 中的 `java` |
| `OMC_KEYSTORE` | 签名 keystore 路径 | **必填** |
| `OMC_KS_PASS` | keystore 密码 | **必填** |
| `OMC_KEY_ALIAS` / `OMC_KEY_PASS` / `OMC_KS_TYPE` | 别名 / 密钥密码 / 类型 | `omc` / 同 `OMC_KS_PASS` / `PKCS12` |
| `VERSION_NAME` / `VERSION_CODE` | 版本号 | 见脚本 |

> 发布新版本时，Release 的 tag 用 `v<版本>`，APK 资源命名为 `oh-my-cpa-<版本>.apk`——应用内的外壳自更新按这个约定查找安装包，并校验包名与签名证书。

> 用自己的密钥签名的 APK 与 Releases 中的官方包签名不同，不能覆盖安装，需要先卸载（会清除应用数据）。

## 常见问题

**为什么首次启动要下载东西？**
CPA 与 oh-my-cpa 的二进制直接来自上游官方 Releases（版本固定、SHA-256 校验），APK 只保留外壳，体积小、上游更新也无需重新发版。下载失败时可以打开镜像后重试。

**后台被杀 / 熄屏后 API 不通？**
在面板 → 应用设置里点「忽略电池优化」；部分国产系统还需要在系统设置中允许「自启动」「后台运行」，并在多任务界面锁定应用。

**我的数据存在哪里？安全吗？**
账号凭据、配置与数据库都存放在应用私有目录，不会上传到任何地方。开启局域网或远程访问时，请为 CPA 设置 API Key，并只在可信网络中使用；分享日志时密钥会自动脱敏。

**插件模式打不开？**
插件模式依赖 glibc 加载器在 Android 上运行，个别系统的 seccomp 策略可能拦截。失败时应用会自动回退到静态版并发送通知。

**这是官方客户端吗？**
不是。本项目是社区作品，与 CLIProxyAPI、oh-my-cpa 的作者无关。上游项目本身的问题请到各自仓库反馈。

## 许可证与致谢

本项目以 [MIT](LICENSE) 许可证发布，与 oh-my-cpa 相同。运行时下载或随 APK 分发的第三方组件及其许可证见 [NOTICE.md](NOTICE.md)：

- [CLIProxyAPI](https://github.com/router-for-me/CLIProxyAPI) — MIT
- [oh-my-cpa](https://github.com/WizisCool/oh-my-cpa) — MIT（应用图标与字标源自 oh-my-cpa 的品牌图形）
- [Termux glibc](https://github.com/termux-pacman/glibc-packages)（随 APK 分发，`assets/glibc`）— LGPL
- [cloudflared](https://github.com/cloudflare/cloudflared) — Apache-2.0
- [Tailscale](https://github.com/tailscale/tailscale) — BSD-3-Clause

---

## English

**Oh My CPA** (`com.taocrypt.omc`) is an Android app that runs [CLIProxyAPI](https://github.com/router-for-me/CLIProxyAPI) (v8) and the [oh-my-cpa](https://github.com/WizisCool/oh-my-cpa) management console (v0.3.19) **locally on your phone** as child processes, and shows the console in a fullscreen WebView. No server, no root.

- **First launch** downloads the official linux arm64 binaries from GitHub Releases with pinned SHA-256 checks; an optional download mirror helps on slow networks.
- **Always on**: foreground service with a process supervisor, DNS proxy for Go on Android, auto-login, boot start and battery-optimisation exemption.
- **Native extras**: draggable status button and right-hand native panel themed from the console, floating window + bubble, Quick Settings tile, LAN access, remote access via Cloudflare quick tunnel or Tailscale.
- **Plugin mode** follows the console's “启用插件系统” switch and runs CPA's glibc plugin build through a bundled Termux glibc loader; plugin pages get the management key automatically.
- **Maintenance**: storage manager, component updates with rollback, in-app shell updates from this repo's Releases with notification progress, logs and diagnostics export.

Defaults: CPA on `127.0.0.1:8317`, oh-my-cpa on `127.0.0.1:8080`. Requires Android 10+ (API 29) on arm64. Build from source with `build.sh` (Android SDK build-tools + JDK; bring your own keystore via `OMC_KEYSTORE` / `OMC_KS_PASS`).

This is a **community project, not affiliated with** the CLIProxyAPI or oh-my-cpa authors. MIT licensed; third-party notices in [NOTICE.md](NOTICE.md).
