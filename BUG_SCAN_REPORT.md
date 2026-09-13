# RikkaHub 底层扫描报告

日期：2026-09-12

## 修复进度

2026-09-13（AI 设备执行修复）：新增助手设备工具开关、`device_status` 与真实 `device_command` 执行链路。Shizuku 本机命令服务返回实际输出/退出码；卸载和清除应用始终经设备确认，关键路径三次确认，模型参数不能自我批准。会话停止/撤销会取消执行和确认；修复 Binder 服务取消接口、进程 I/O/超时及审计错误显示。新增 22 项相关检查，App 共 240 项测试通过，ARM64 构建和 APK 内容/签名校验通过。新包为桌面的 `RikkaHub-sakura-arm64-device-tools-fixed.apk`，真机端到端卸载待安装验证。

2026-09-13：修复 Shizuku 授权状态反馈，使用 Sticky Binder 监听、处理拒绝再次询问/版本不兼容/请求异常，并增加打开 Shizuku 和返回自动刷新。确认原桌面 latest APK 缺少 Shizuku 授权声明，现已更新为包含正确配置的新包。新增 12 项权限流程测试通过，App 共 218 项测试通过，ARM64 APK 构建和包内配置/签名校验通过。新桌面文件为 `RikkaHub-sakura-arm64-shizuku-auth-fixed.apk`。

以下原始发现保留为扫描记录。后续已修复撤销检查、活动 Job 取消、布尔确认默认计数、基础命令分类漏判、审计写入接线、共享 session、工具 enum、停止状态检查、ADB 24 字节包头、Shizuku Binder 类型和销毁事务号、Root 超时以及 Ultra effort 参数；崩溃日志现在保留更长内容及首尾。13 项独立复现检查已全部通过，Kotlin 编译通过。

这些检查不覆盖全部 17 组问题。目前已经接通 Shizuku 本机命令执行与设备确认 UI。独立 ADB Socket 授权/流协议、下载/安装、VPN、证书、统计数据和无障碍功能仍需完成；不能将本次结果解释为全部功能通过真机验证。

## 原始扫描结论（修复前）

当前定制代码存在多处底层缺陷。编译成功和原有单元测试通过，不能证明设备控制、授权、ADB 或 Shizuku 的真实运行链路已完成。

本次记录 17 组问题，覆盖启动诊断、命令策略与会话、设备通信、下载、流量调试、无障碍及 Token 页面。重点问题通过直接调用生产类的 13 项复现检查确认。设备工具目前仅返回命令预览；授权和执行器问题是在底层类中复现的。

优先级：**P1** 为应优先处理的主要功能或控制边界问题；**P2** 为后续修复的功能、记录和状态管理问题。

## 原始扫描验证结果

| 检查 | 结果 |
| --- | --- |
| 原有 `:app:testDebugUnitTest` | 206 项通过 |
| 原有 `:ai:testDebugUnitTest` | 178 项通过 |
| 独立复现检查 | 13 项执行，13 项断言失败，复现下述关键缺陷 |
| `:app:lintDebug` | 分析未完成；后一次运行在 `:app:lintAnalyzeDebug` 超过 240 秒，不能判定通过 |
| 真机运行 | 本次结论基于源码、合并后的 Manifest、官方协议和 JVM 检查；端到端真机验证待进行 |

复现检查使用模拟 `DeviceCommandRunner`。Root 等待行为使用有限时长的本地 Python 进程代替 `su`，实测约 31.2 秒后返回 `Success(simulation complete)`，没有触发声明的 30 秒超时。

临时复现源码：

```text
C:\Temp\opencode\rikkahub-review-20260912\tests\BottomLayerScanTest.kt
C:\Temp\opencode\rikkahub-review-20260912\tests\RootRunnerTimeoutProbeTest.kt
C:\Temp\opencode\rikkahub-review-20260912\scan.init.gradle
```

原始扫描时运行的命令（使用旧版接口，保留为历史记录）：

```powershell
.\gradlew.bat :app:testDebugUnitTest --tests "me.rerere.rikkahub.review.*" --init-script "C:\Temp\opencode\rikkahub-review-20260912\scan.init.gradle" --no-configuration-cache --max-workers=2 --console=plain
```

当时该命令以测试失败结束，因为断言检查的是应有行为。当前接口已调整，回归检查使用仓库内测试：`./gradlew :app:testDebugUnitTest --tests "me.rerere.rikkahub.device.*"`。结果写入 `app/build/test-results/testDebugUnitTest/`，HTML 报告位于 `app/build/reports/tests/testDebugUnitTest/index.html`。

## 已确认问题

### B01 · P1 · 未识别的命令被默认判为只读，硬阻断存在漏判

- 定位：`app/src/main/java/me/rerere/rikkahub/device/DeviceCommandPolicy.kt:22-39`。
- `else -> READ_ONLY` 将所有未匹配正则的命令放行到只读等级。实际复现：修改设置的 `settings put global airplane_mode_on 1` 被判为 `READ_ONLY`。
- 对递归删除命令拆开参数进行纯字符串检查，`rm -r -f /` 也被判为 `READ_ONLY`，而非 `BLOCKED`。
- 影响：在执行链路接通后，这种分类无法兑现“修改需审批、指定破坏性命令永久阻断”的要求。
- 建议：采用保守的默认分类，明确允许的只读操作；对 shell 组合、路径、参数和执行包装进行一致解析，在最终执行入口重新验证策略。

### B02 · P1 · 撤销没有阻止执行，停止没有取消正在运行的任务

- 定位：`app/src/main/java/me/rerere/rikkahub/device/DeviceAccessSession.kt:34-55`。
- `revoke()` 只设置 `REVOKED`，`execute()` 没有拒绝这一状态。模拟检查中，撤销后执行 `getprop` 仍调用了 runner。
- `stop()` 只改变两个字段，没有保存并取消执行 Job，也没有关闭正在运行的进程或连接。正在等待的模拟 runner 在停止后仍保持活动。
- 建议：执行前检查有效授权；会话统一持有活动任务和可关闭资源，并实现取消、终止及撤销时的状态失效。

### B03 · P1 · 三次确认可由一次布尔确认满足

- 定位：`app/src/main/java/me/rerere/rikkahub/device/DeviceAccessSession.kt:39-50`。
- 默认参数 `confirmations = if (confirmed) preview.confirmationCount else 0` 会在 `confirmed = true` 时直接填入 3。
- 复现：内核路径命令只传一次 `confirmed = true` 就进入模拟 runner。
- `app/src/main/java/me/rerere/rikkahub/ui/components/device/CriticalCommandConfirmationDialog.kt:17` 只有组件定义，未找到实际调用点。
- 建议：通过可信 UI 状态逐次累计确认，并绑定具体命令、传输方式与会话；执行层验证确认凭据，不能从单个布尔值推导三次确认。

### B04 · P1 · 工具与控制页没有共享完整的执行链路

- 定位：`app/src/main/java/me/rerere/rikkahub/device/DeviceCommandTool.kt:12-33`。
- 工具忽略传入的 `session`，执行函数只返回 `Command preview`；没有使用 transport、confirmed，也没有调用 `session.execute()` 或设备 runner。
- 复现：传入已经停止的 session，工具仍正常返回预览。
- `app/src/main/java/me/rerere/rikkahub/di/AppModule.kt:40` 只向 `LocalTools` 注入四个参数，导致 `app/src/main/java/me/rerere/rikkahub/data/ai/tools/local/LocalTools.kt:16` 创建自己的 session。控制页使用的则是 Koin 单例。
- 影响：设备控制页的授权/停止状态不能控制工具内部的另一份 session；批准工具后也不会实际执行命令。
- 建议：统一注入同一个控制器，接通预览、确认、执行、取消和审计的完整流程。

### B05 · P1 · 工具 JSON Schema 的 enum 类型错误

- 定位：`app/src/main/java/me/rerere/rikkahub/device/DeviceCommandTool.kt:20`。
- 当前输出 `"enum": "adb,shizuku,root"`，JSON Schema 要求 enum 是数组。
- 独立检查直接读取生产工具的 schema，确认其类型为字符串。
- 影响：启用设备工具后，校验 schema 的服务商可能直接拒绝整次聊天请求。
- 建议：使用 `"enum": ["adb", "shizuku", "root"]`，并验证真实生成的请求 schema。

### B06 · P2 · 审计存储未接通，拒绝记录会丢失

- 定位：`app/src/main/java/me/rerere/rikkahub/device/DeviceAccessSession.kt:45-60`、`app/src/main/java/me/rerere/rikkahub/device/DeviceAuditStore.kt:25-38`。
- 阻断/确认检查在审计 `try` 块之外，抛出异常后不会记录。复现：阻断一条请求后，`session.audit()` 仍为空。
- 执行器产生的是独立内存列表；未找到生产代码调用 `DeviceAuditStore.append()`。审计页面读的是持久化文件，无法看到该内存列表。
- 内存列表本身也没有条数和输出长度上限。
- 建议：将所有允许/拒绝/失败/取消结果写入统一审计存储，并在输入存储时执行条数和大小限制。

### B07 · P1 · ADB 编解码与官方传输协议不兼容

- 定位：`app/src/main/java/me/rerere/rikkahub/device/AdbProtocol.kt:7-24`、`app/src/main/java/me/rerere/rikkahub/device/AdbShellTransport.kt:15-35`。
- 官方协议规定 24 字节包头，包含六个小端 32 位字段。当前只写 command 和 length 两个字段，共 8 字节，而且 `DataOutput.writeInt()` 使用大端序。
- 复现：按官方格式构造的 `CNXN` 被解析成 `NXNC`；空负载写包的实际长度为 8，而非 24。
- 缺少 arg0/arg1，因此没有协议版本、最大包长、local-id/remote-id 的正确传递；也缺少校验字段和 magic。
- Transport 没有 AUTH/RSA 或 STLS 处理；请求 `shell,v2,raw:` 后将二进制 shell-v2 payload 直接当 UTF-8 字符串拼接，也没有解析输出通道和退出状态。
- 影响：当前代码不能与真实 adbd 正常通信，问题不仅是缺少用户授权。
- 建议：按官方帧格式及握手/流状态机修复，并用独立标准帧和真实 adbd 交互测试验证。

### B08 · P1 · Shizuku 缺少 Binder 获取入口，UserService 类型不正确

- 定位：`app/build.gradle.kts:150`、`app/src/main/AndroidManifest.xml`、`app/src/main/java/me/rerere/rikkahub/device/ShizukuUserService.kt:8-19`。
- 只依赖 `dev.rikka.shizuku:api`，没有 provider 依赖和 `ShizukuProvider` 声明；合并后的 Debug Manifest 也没有该 provider，未找到其他 Binder 初始化入口。
- 官方要求 UserService 类本身实现 `IBinder`，通常继承 AIDL Stub。当前继承的是 Android `Service`，只在 `onBind()` 中返回 Binder；Shizuku 不按普通 Android Service 的生命周期加载它。
- 复现：`IBinder.isAssignableFrom(ShizukuUserService)` 为 false。
- `IShizukuUserService.kt:27` 的 destroy 事务号为 2，也与官方 UserService 销毁协议的 `16777115` 不一致。
- 建议：完成 provider 初始化，使用符合 Shizuku 契约的 Stub 服务，并修复绑定、断连和销毁流程。

### B09 · P1 · Root 超时无效，Shizuku 命令缺少执行边界

- 定位：`app/src/main/java/me/rerere/rikkahub/device/RootDeviceCommandRunner.kt:7-21`、`app/src/main/java/me/rerere/rikkahub/device/ShizukuUserService.kt:10-17`。
- Root 先阻塞执行 `readText()` 到 EOF，之后才调用带超时的 `waitFor()`。进程不关闭输出流时，控制流根本到不了超时检查。
- 复现：31 秒的模拟进程正常返回成功，没有触发 30 秒超时；root 探测的 5 秒限制也使用同样顺序。
- 没有 finally 销毁进程，协程取消也不能可靠中断阻塞读取。Shizuku 使用无超时的 `waitFor()`，且没有检查退出码。
- 建议：并发消费有上限的输出，用覆盖整个进程生命周期的超时控制等待；取消/超时/异常时关闭流并销毁进程，返回明确退出状态。

### B10 · P1 · ULTRA 未映射到 OpenAI 支持的 effort 值

- 定位：`ai/src/main/java/me/rerere/ai/core/Reasoning.kt:34`、`ai/src/main/java/me/rerere/ai/provider/providers/openai/ChatCompletionsAPI.kt:410-415`、`ai/src/main/java/me/rerere/ai/provider/providers/openai/ResponseAPI.kt:240-248`。
- ULTRA 的 effort 为 `ultra`，普通 OpenAI Chat Completions 与 Responses 分支直接使用该值。
- 复现：调用真实请求构造函数得到 `"reasoning_effort": "ultra"`。官方 SDK 的枚举不包含 `ultra`。
- 当前定制修改仅为 DeepSeek/NVIDIA 的部分分支做了相应转换。
- 影响：选择 Ultra 后，相关服务端会拒绝不支持的参数值。
- 建议：为服务商和模型能力做明确映射，保留本地 Ultra 等级，但发送服务端实际支持的值。

### B11 · P1 · 下载文件位置与 FileProvider 不匹配，安装入口缺失

- 定位：`app/src/main/java/me/rerere/rikkahub/device/DownloadInstallManager.kt:18-37`、`app/src/main/res/xml/file_paths.xml:3-11`、`app/src/main/java/me/rerere/rikkahub/ui/pages/device/DownloadPage.kt:19-25`。
- 下载目标是公共 `Download` 目录，FileProvider 配置只覆盖 cache、files 和应用专属 external-files；公共 Download 不在这些根目录下。
- 将该下载文件传给 `installApk()` 时，FileProvider 无法为该路径生成 URI；受存储访问限制时也可能先在文件可读性检查处失败。
- 下载页面只有开始下载和进度，没有调用安装方法的入口；生产代码中未找到 `installApk()` 调用。
- 建议：通过下载 ID 获取 DownloadManager 的 content URI，接通完成后的安装操作与未知来源安装授权流程。

### B12 · P2 · 下载轮询不终止，重复点击会累积协程

- 定位：`app/src/main/java/me/rerere/rikkahub/ui/pages/device/DownloadPage.kt:20-24`。
- 每次点击都启动新的 `while (id != null)`，完成、失败时不退出，也没有替换旧轮询任务。
- 多个协程共享可变 id，反复查询的都是最新任务；离开页面后 id 又因仅使用 remember 而丢失。
- 建议：按任务 ID 管理单个生命周期感知的观察任务，处理完成/失败状态并保存任务标识。

### B13 · P1 · VPN 只有默认路由，没有转发或采集循环

- 定位：`app/src/main/java/me/rerere/rikkahub/service/TrafficCaptureVpnService.kt:9-17`、`app/src/main/java/me/rerere/rikkahub/ui/pages/device/TrafficDebugPage.kt:12-20`。
- Service 将全部 IPv4 流量路由到 TUN，但没有读取、转发或响应 TUN 数据的实现。
- 影响：若该 Service 被授权启动，被路由的 IPv4 流量会丢失，而不是完成可用的流量调试。
- 当前页面也没有 `VpnService.prepare()` 授权流程或启动/停止按钮。重复启动时还会覆盖原有 TUN 引用，没有先关闭旧描述符。
- 建议：先实现有正确转发与资源生命周期的 VPN 数据通路，再接通可观察的启动、停止和授权状态。

### B14 · P2 · “调试证书”实际是公钥文件

- 定位：`app/src/main/java/me/rerere/rikkahub/device/DebugCertificateStore.kt:16-20`。
- `pair.public.encoded` 是 SubjectPublicKeyInfo 公钥编码，不是包含颁发者、有效期、CA 扩展和签名的 X.509 证书。
- 私钥在生成后也没有持久保存。扩展名 `.cer` 和 SHA-256 指纹不会把公钥变成可用的 CA 证书。
- 影响：该文件不能按声明用途充当 HTTPS 调试证书。
- 建议：生成并验证真正的 X.509 CA 证书，妥善管理对应私钥和撤销状态。

### B15 · P2 · Token/上下文页面没有真实数据源

- 定位：`app/src/main/java/me/rerere/rikkahub/RouteActivity.kt:542`、`app/src/main/java/me/rerere/rikkahub/ui/pages/device/UsageContextPage.kt:13-19`。
- 路由直接调用 `UsageContextPage()`，因此始终使用默认的全零 UsageSummary 和空 contextLength。
- `summary()` 只有测试使用，没有接到页面的数据流；当前占用公式使用累计 total，也不能代表经过上下文裁剪后的实际请求占用。
- 建议：接入当前会话/模型的真实使用量和有效上下文预算，区分累计计费 Token 与当前上下文 Token。

### B16 · P2 · 无障碍接口与服务能力声明不一致

- 定位：`app/src/main/res/xml/accessibility_service_config.xml:2-6`、`app/src/main/java/me/rerere/rikkahub/service/RikkaAccessibilityService.kt:14-17`。
- `clickText()` 依赖窗口节点，但服务声明 `canRetrieveWindowContent=false`，无法取得所需窗口内容。
- `swipe()` 使用 `dispatchGesture()`，XML 没有声明 `canPerformGestures=true`。
- 影响：仅在系统中启用服务，也无法获得当前接口所宣称的点击/滑动能力。
- 建议：使能力声明、用户启用流程和实际接口保持一致，并针对服务中断及动作结果建立测试。

### B17 · P2 · 崩溃日志截断最底层根因

- 定位：`app/src/main/java/me/rerere/rikkahub/utils/CrashHandler.kt:11,39-47`。
- 日志构造后使用 `.take(8000)`，只保存前 8,000 个字符。
- Compose 导航和 Koin 的外层调用栈很长，真正的末尾 `Caused by` 容易被截断。这可以解释之前提供的日志缺少底层原因，但不能据此独立确认那次崩溃的原始异常。
- 建议：保存完整日志到文件，在界面保留根因摘要及调用栈首尾，支持完整导出。

## 为什么原有测试未发现问题

1. `app/src/test/java/me/rerere/rikkahub/device/DeviceCommandPolicyTest.kt:25-34`：撤销测试在执行成功后调用 `error("expected rejection")`，随后又捕获这一相同类型异常并视为成功。因此撤销完全失效时也会通过。
2. `app/src/test/java/me/rerere/rikkahub/device/AdbProtocolTest.kt:12-17`：只用自己的编码器写、自己的解码器读。两者采用同一种错误格式，也能通过 round-trip。
3. `app/src/test/java/me/rerere/rikkahub/device/RootDeviceCommandRunnerTest.kt:7-8`：只检查构造函数能否创建对象，没有验证超时、输出、失败退出或取消。

## 修复顺序建议

1. 修复 B01–B05 的策略、会话、确认及工具契约，并修正上述失真的测试。
2. 修复 B09 的进程超时与取消、B06 的统一审计，再接通执行链路。
3. 按官方协议完成 B07、B08，并进行授权设备上的真实交互验证。
4. 修复 B10 的 Ultra 请求参数，恢复对应服务商聊天兼容性。
5. 补齐下载/安装、VPN、证书、数据页面及无障碍功能，并修复日志导出。

## 参考依据

- ADB 官方协议（按用户更正后的地址）：https://android.googlesource.com/platform/packages/modules/adb/+/refs/heads/main/docs/dev/protocol.md
- Shizuku API 官方集成与 UserService 文档：https://github.com/RikkaApps/Shizuku-API/blob/master/README.md
- OpenAI SDK effort 枚举：https://github.com/openai/openai-python/blob/main/src/openai/types/shared/reasoning_effort.py

以上结论对应本次扫描时的工作区源码。Gemini 各型号预算上限、不同 Android 版本的真机行为，以及完整 Android Lint 结果仍需后续核验。
