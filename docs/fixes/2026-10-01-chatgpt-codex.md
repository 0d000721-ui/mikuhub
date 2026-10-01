# Codex / ChatGPT 接入与手机安装

## 目标与设计

在现有 OpenAI 服务商和 OAuth 回调流程上增加 ChatGPT 账户登录，登录后发现账户可用模型，通过官方 Responses API 完成聊天和工具调用，再用桌面 ADB 覆盖安装到已授权手机。保留原应用包名、签名和聊天数据。

- 固定的 Codex / ChatGPT 服务商在升级时自动出现；账户登录、选择账户、重新授权、退出和刷新模型在同一配置页面完成。
- 使用官方开源应用注册流程、系统浏览器、PKCE、一次性 state 和 nonce。应用的 host ID 保持稳定，回调为 `127.0.0.1` 上的 `/auth/callback`。
- 每个注册账户的 client ID、验证过的身份和凭据分开保存。凭据使用 Android Keystore 加密，存放于禁止备份目录；服务商配置仅保存账户引用。
- 请求前串行刷新凭据；OAuth 访问令牌绕过 API Key 轮换缓存。强制官方主机、`store=false`、`stream=true`，正常结束须收到完成事件。
- 账户登录成功后读取官方账户模型目录，不用预设模型伪装接通。API Key 服务商继续使用原有协议。
- 兼容官方 Responses 返回有效 SSE 却缺失 Content-Type 的情况：仅在账户请求的固定官方端点检查首个完整事件，不消耗事件、不等待整段回复；HTML、普通 JSON、显式错误类型和 HTTP 错误仍会失败。
- 明确的输出上限或内容过滤失败停止请求，不触发网络重试；真正断流仍可按用户设置重试。

## 实施与验证

1. OAuth 模块扩展回调 client ID 和 ID token，加入签名/身份/授权验证、注册、刷新和撤销；使用签名夹具和本地 HTTP 服务验证异常处理。
2. 应用账户管理器负责加密持久化、授权生命周期、账户隔离和刷新锁。
3. AI 模块加入账户引用和凭据解析器，覆盖模型列表、流式/非流式调用、固定端点、令牌缓存隔离和不完整流错误的回归测试。
4. 服务商 UI、自动出现的内置入口和依赖注入接通；构建优化 APK，运行适用的 JVM 测试和 Android Lint。
5. ADB 覆盖安装、核对版本并启动应用；检查手机上的接入入口和登录页面。账户认证需要用户在官方页面完成，未实际认证时不宣称远程推理成功。

## 本地检查记录

2026-10-01 在 Windows 上使用缓存依赖检查：AI 模块 225 项、OAuth 模块 23 项、app 模块 381 项 JVM 测试通过。覆盖 ID token 签名与账户身份、授权范围、刷新令牌轮换、加密存储、退出取消、模型目录合并、强制请求参数和流中断等情况。13 项真实 HTTP 回归测试还覆盖缺失 Content-Type、大首事件、首段文字实时显示、HTTP 错误和读取期间取消。

高亮模块的 53 项测试通过。最初失败源于 `core.autocrlf` 改写精确比较的测试夹具；将夹具恢复仓库原有 LF 字节，并用 `.gitattributes` 固定 LF 后通过，生产高亮实现未修改。

全项目测试中的 4 项既有工作区 shell 测试在 Windows 启动 `/bin/sh` 时失败；相关实现和测试与原提交相同。这些测试需 Unix 宿主，未修改测试以绕过该环境限制。Android 自带的 `/system/bin/sh` 路径不受此宿主环境影响。

完整 Lint 首次发现 54 项已有源代码问题与一项本机 SDK 路径转义问题。已修复本机路径，并逐条将已有问题对照 `4ad506e6` 验证：40 项 Compose 资源读取、3 项区域设置读取、1 项 Activity 转换、10 项缺失翻译。`app/lint-baseline.xml` 仅记录这些旧错误；没有禁用错误类别，也没有屏蔽新增账户代码或警告。

优化 APK 版本为 `2.5.1-miku.20261001.7-codex`、versionCode 188，包名及原签名已核验。ADB 覆盖安装返回 `Success`，手机已安装 APK 的 SHA-256 与分发文件一致；原安装时间、账户授权和聊天数据保留。

用户已在官方页面完成授权，账户模型读取成功。修复缺失 Content-Type 导致的空白回复后，真机 GPT-6-Astra 显示完整回复“你好！👋 有什么我可以帮你的吗？”，记录输入 2,250 tokens、输出 16 tokens、耗时 4.0 秒。初版 .7 中模型旁的实际上下文用量、下载入口和 `Using ChatGPT plan / Manage usage` 均已在手机确认。这验证了当前账户的登录、模型发现与文本推理；可用模型仍以各账户实时目录为准。

后续 .8 按用户要求移除聊天区的用量管理入口，保留实际上下文和下载状态；同步官方源码、自动刷新模型目录、速度选择和网页生图详见 [新版更新记录](2026-10-01-upstream-sync.md)。

## 官方依据

- [注册与登录](https://developers.openai.com/siwc/token-sharing-open-source/sign-in)
- [账户和会话](https://developers.openai.com/siwc/token-sharing-open-source/profiles-and-sessions)
- [模型和推理](https://developers.openai.com/siwc/token-sharing-open-source/models-and-inference)
- [OpenID 配置](https://auth.openai.com/.well-known/openid-configuration)
