# MikuHub

<img src="assets/branding/miku-launcher-master.png" alt="MikuHub icon" width="112" />

An Android AI assistant customized from **[RikkaHub](https://github.com/rikkahub/rikkahub)**, with a Miku teal theme, agent interactions, downloads, an AI browser and device tools.

[简体中文](README.md) · [Download APK](https://github.com/0d000721-ui/mikuhub/releases)

## Upstream credit

MikuHub is based on RikkaHub source code. The conversation framework, provider integrations, workspace, MCP and other core capabilities come from RikkaHub. Thank you to the original authors and contributors. This is an independently maintained customization, not an official RikkaHub release. Report MikuHub issues in this repository.

Upstream history, copyright notices and the [AGPL-3.0 license](LICENSE) are retained. Archived upstream introductions are in [docs/upstream](docs/upstream).

## Additions and changes

- **Branding:** MikuHub name, default Miku teal `#39C5BB`, a Miku sticker launcher icon and removal of the chat development banner.
- **Agent interaction:** Codex-inspired command approval and question cards with single/multiple choice, custom answers, recommendations and rejection reasons.
- **Model status:** ZCode-inspired compact context and download indicators beside the model selector; estimates and provider usage remain distinguishable.
- **Real downloads:** Android DownloadManager handles HTTP(S) APKs, archives and documents with actual progress, cancel, retry and local file access. HTML landing pages are rejected as files.
- **AI browser:** An opt-in WebView session supports navigation, reading, clicking, filling, scrolling and downloading, with stop and session close controls.
- **Device tools:** Shizuku shell execution and a separate Root path that verifies UID 0, including Root provided by Magisk.
- **Silent APK installation:** After the user requests installation, the app can wait for a download and stream the APK into `pm install` through authorized Shizuku or Root. It reports the actual install outcome.
- **Tool pages:** A unified entry for downloads, browser, device controls, context, diagnostics and audit records.
- **Performance:** Reduced repeated conversation processing and bounded Markdown / HTML rendering caches.

## Requirements and limitations

Android 8.0+; most phones use the arm64-v8a APK. Configure your own model provider. Browser AI control is disabled by default.

The current distribution retains `me.rerere.rikkahub.debug` and the previous local signing identity for upgrades. The visible name is MikuHub. Other machines generate different debug certificates.

Silent installation requires authorized Shizuku or Root and remains subject to OEM installation policy. Only single-file APKs are supported. ADB shell access is provided through Shizuku; an independent ADB pairing/AUTH client is not implemented. Queued installation requires a live app process.

Cookie-authenticated and blob downloads, cross-origin iframe / Canvas control, full accessibility-based device control, VPN packet capture and certificate injection are not completed features.

Local JVM tests, optimized APK builds and build-time lint checks passed. Full browser/download/install flows have not yet been validated on a connected physical device.

## Build

Use JDK 17, Android SDK 37, Node.js 22 and pnpm 11.

```bash
git clone --recurse-submodules https://github.com/0d000721-ui/mikuhub.git
cd mikuhub/web-ui
pnpm install --frozen-lockfile
cd ..
./gradlew :app:testDebugUnitTest :app:assembleOptimized --max-workers=1
```

Use `gradlew.bat` on Windows. APKs are placed in `app/build/outputs/apk/optimized/`. Configure SDK paths locally. The optimized variant uses a local debug certificate; release signing credentials belong in local configuration, never in Git. The manual CI workflow produces test artifacts, not automatic public releases.

## Credits and license

Thanks to RikkaHub and [Shizuku](https://github.com/RikkaApps/Shizuku). Interaction references include Codex and [ZCode](https://zcode.z.ai/cn/docs/agents). See [icon provenance](assets/branding/README.md) for the generated fan-art asset; this is not an official Hatsune Miku application.

Code is licensed under [AGPL-3.0](LICENSE); existing third-party notices remain in place.
