<div align="center">
  <img src="assets/branding/miku-launcher-master.png" alt="MikuHub 图标" width="112" />
  <h1>MikuHub</h1>
  <p>初音青主题的 Android AI 助手，支持对话、设备工具、真实下载和 AI 浏览器。</p>
  <p><a href="https://github.com/0d000721-ui/mikuhub/releases">下载 APK</a> · <a href="README_EN.md">English</a> · <a href="README_ZH_TW.md">繁體中文</a></p>
</div>

## 项目来源

**MikuHub 基于 [RikkaHub](https://github.com/rikkahub/rikkahub) 开源项目进行定制开发。**
聊天框架、多模型接入、工作区、MCP、消息分支等基础能力来自 RikkaHub，感谢原作者及所有贡献者。
本项目由独立维护者开发，与 RikkaHub 官方团队无隶属关系；MikuHub 的问题请提交到[本仓库 Issues](https://github.com/0d000721-ui/mikuhub/issues)。

保留上游代码历史、版权声明和 [AGPL-3.0 许可证](LICENSE)。上游原始项目介绍保存在 [docs/upstream](docs/upstream)。

## 相比 RikkaHub 新增或调整了什么

| 方向 | MikuHub 的改动 |
| --- | --- |
| 名称与主题 | 应用名称 MikuHub；默认初音青 `#39C5BB`；初音贴纸风桌面图标；移除聊天顶部开发模式标记 |
| Agent 交互 | 参考 Codex 的指令确认卡片和 AI 提问交互；支持单选、多选、自定义答案、推荐项和拒绝原因 |
| 模型状态栏 | 参考 ZCode 的紧凑布局，将上下文用量和真实下载状态整合到模型选择器旁边 |
| 上下文显示 | 展示实际请求整理后的上下文估算及服务商返回的用量；区分估算值和真实值；未知容量不伪造百分比 |
| 真实下载 | 使用 Android DownloadManager 下载 APK、ZIP、文档等 HTTP(S) 文件；显示进度、大小和状态；支持取消、重试和打开本地文件 |
| AI 下载工具 | `download_start`、`download_status`、`download_cancel`，下载到设备本地；识别 HTML 下载页，避免把网页误当成文件 |
| 内置浏览器 | WebView 浏览器，用户开启 AI 操作后支持浏览、读取、点击、填写、滚动及下载；可随时停止或关闭会话 |
| Shizuku / Root | Shizuku 执行设备 shell 指令；Root 通道通过 `su` 验证实际 UID 0；支持使用 Magisk 提供的 Root 授权 |
| 静默安装 | 用户要求安装后，支持等待 APK 下载完成，经已授权 Shizuku / Root 以流式方式提交 `pm install`；展示真实安装结果 |
| 扩展功能入口 | 统一下载、浏览器、设备控制、上下文与用量、性能诊断、审计记录等页面入口 |
| 流畅度 | 优化流式消息更新、Markdown / HTML 解析缓存、附件扫描与思考内容渲染，减少重复解析和不必要的动画 |

保留 RikkaHub 原有的多供应商模型接入、图片/文档输入、Markdown/代码/公式/Mermaid、工作区、MCP、搜索、助手配置、记忆、消息分支和 Web 访问等能力。

## 下载与使用

在 [Releases](https://github.com/0d000721-ui/mikuhub/releases) 下载 MikuHub APK。大多数手机使用 `arm64-v8a` 版本，最低 Android 8.0（API 26）。

1. 安装后在设置中配置模型供应商和 API Key。
2. 从设置中的扩展功能入口进入下载中心、内置浏览器或设备控制。
3. 聊天输入区的模型选择器旁显示上下文与下载状态。
4. 使用设备指令或静默安装前，启动并授权 Shizuku，或选择 Root 通道并完成 `su` 授权。
5. AI 浏览器操作默认关闭，需要在浏览器页面手动开启。

当前分发版本继续使用 `me.rerere.rikkahub.debug` 包名和此前本机签名，以便覆盖之前的定制版并保留数据。桌面显示名称已经改为 MikuHub。它与官方 RikkaHub 的应用包独立。

### 下载和安装的区别

- 下载工具会创建真实本地下载任务；获得任务编号不代表下载完成。
- 安装工具为 `download_install`，结果查询为 `download_install_status`；只有系统安装命令明确成功才显示已安装。
- 未获得设备授权时，仍可使用下载中心的系统安装器入口，按 Android 提示安装。
- 当前的 ADB shell 能力由 Shizuku 提供，没有实现独立的 ADB 配对 / AUTH 客户端。

## 当前限制与验证范围

- 静默安装要求 Shizuku 或 Root 权限，不能保证绕过手机厂商的 USB 安装确认或安装限制。
- 目前支持单文件 APK，不支持 APKS / APKM / XAPK 或拆分 APK 集合安装。
- 等待安装任务依赖应用进程存活；进程被终止后不会自动恢复旧授权并重新执行命令。
- 内置浏览器目前针对普通网页 DOM；Canvas 和跨域 iframe 操作有限。需要登录 Cookie 的下载、`blob:` 下载暂不支持。
- 完整无障碍 AI 控屏、VPN 抓包和证书注入仍未完成，不列为已实现功能。
- 已通过本地 JVM 单元测试、优化 APK 构建与构建过程中的 Lint 检查。尚未完成连接真机后的浏览器、下载及静默安装全流程验证。

## 本地构建

工具链：JDK 17、Android SDK 37、Gradle Wrapper；Web 前端使用 Node.js 22 和 pnpm 11。

```bash
git clone --recurse-submodules https://github.com/0d000721-ui/mikuhub.git
cd mikuhub
cd web-ui
pnpm install --frozen-lockfile
cd ..
./gradlew :app:testDebugUnitTest :app:assembleOptimized --max-workers=1
```

Windows 使用 `gradlew.bat`。通过环境变量或本地 `local.properties` 配置 Android SDK 路径。

- 优化 APK 输出：`app/build/outputs/apk/optimized/`
- `optimized` 关闭调试并启用优化，使用本机 debug 签名；其他机器生成的签名不能直接覆盖已安装的发布包。
- 正式 `release` 签名需自行在本地配置 `storeFile`、`storePassword`、`keyAlias`、`keyPassword`，不要提交密钥。
- 仓库提供手动触发的构建工作流，产物为 CI 测试包，不自动替换已发布 APK。

## 致谢与素材

- [RikkaHub](https://github.com/rikkahub/rikkahub)：本项目的源码基础与原兔子标志。
- [Shizuku](https://github.com/RikkaApps/Shizuku)：设备 shell 权限与进程访问能力。
- Codex / [ZCode](https://zcode.z.ai/cn/docs/agents)：交互与信息布局参考。
- 初音主题图标使用用户提供的桌面截图作为风格参考，通过图像生成工具生成；并非初音未来或相关权利方的官方应用。图标来源和生成说明见 [assets/branding](assets/branding/README.md)。

## 许可证

代码沿用 [GNU Affero General Public License v3.0（AGPL-3.0）](LICENSE)。原有第三方依赖的许可证和版权声明保持保留。
