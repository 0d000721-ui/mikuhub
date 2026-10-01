# 同步官方源码、速度档位与模型刷新

## 上游版本

同步 [RikkaHub 官方仓库](https://github.com/rikkahub/rikkahub) 主分支提交 `b2d73a65759977903d68d6cbcdb4cf00f07bd956`（2026-09-30），包含最新稳定版 2.5.5 及其后续更新。使用 Git 合并保留官方历史与作者信息。

保留 MikuHub 的初音主题、图标、名称、设备授权与工具、真实下载、内置浏览器、紧凑上下文与下载状态，以及 Codex / ChatGPT 账户接入。合入官方会话管理、设置恢复、技能创建、图表、界面、导出和模型等更新。

## OpenAI 接入

- 模型来自所选账户的官方目录；启动和打开模型列表时刷新，保留已有模型的 UUID、工具、请求头和自定义参数。目录暂未列出的手动模型也会保留，例如已确认可用的 `gpt-6.1-sol`；切换账户会清空前一账户的模型配置。设置页仍支持手动刷新，不用硬编码目录伪装账户权限。
- GPT-6 的模型名称与速度档位分开。Standard、Fast、Ultrafast 使用真实的 `service_tier` 参数；旧 `priority` 设置兼容 Fast。Standard 显式发送 `default`，防止继续继承助手的快速设置。
- 部分官方登录接口仍拒绝新的 `fast` 拼写。仅在首次请求明确返回 HTTP 400 `Unsupported service_tier: fast` 且尚无输出时，以同义的 `priority` 重试一次；不降低到 Standard，不重放已输出内容，也不重复重试其他错误。
- Ultrafast 只向已核实支持的模型提供；实际权限以 OpenAI 返回为准。资格拒绝显示明确错误，避免确定性失败被反复网络重试。
- 聊天输入区移除用量管理入口，保留上下文实际用量和下载进度。
- 按官方登录接口当前限制处理不支持的参数与图像工具；普通 API Key 路径独立。

## 网页生图

官方第三方 ChatGPT 登录接口当前不支持 Image generation。[官方限制](https://developers.openai.com/siwc/token-sharing-open-source/preview-limitations)

MikuHub 提供内置浏览器的 ChatGPT 网页生图入口。用户在官方网页完成登录，网页可用的模型与功能以账户实际界面为准。原生 ChatGPT 授权令牌不会复制到网页，也不使用私有后端接口。AI 操作沿用浏览器的显式启用与停止控制。

浏览器通过当前 Activity 创建 WebView，并用显示宿主标记防止旧页面清理移除新页面的内容；离开页面后解除 Activity 引用，保留已授权的后台浏览。网页标题加载完成不代表内容已显示，显示检查仅记录计数，不记录正文、表单或网页控制台原文。

网页图片保存支持 PNG、JPEG、WebP，单张不超过 20 MiB。仅当前官方页面的近期手动点击或已授权 AI 操作可触发；在网页立即撤销 Blob 下载链接之前保留图片，验证图片类型、内容和完整写入后再显示成功。导航、停止、超时或写入失败会取消并清理未完成文件。Android 10 及以上保存到 `Pictures/MikuHub`；Android 8–9 保存到应用图片目录并明确提示相册不可见及卸载影响。

从聊天打开浏览器或由该聊天的 AI 工具保存图片后，图片回传发起操作的原聊天。流式回复尚未结束时等待结束再追加，避免覆盖消息或工具等待自身。切换来源、停止操作或关闭会话会取消待回传任务。保存与回传分别报告结果；回传失败时手机中已保存的图片仍保留。全局生图入口仅保存到手机。

## 执行授权

- 默认「重要操作手动确认」：普通查询、浏览和下载自动执行；安装、删除、Shell、MCP 和网页提交等操作仍需确认。
- 可选择「完全不受限」：应用内工具执行自动批准，系统权限、浏览器 AI 开关和停止控制仍有效。AI 向用户提问仍等待真实回答。
- 聊天输入工具栏、设置页和设备页均提供入口。授权模式独立保存在禁止备份目录，导入设置不会提升执行权限。

## 账户隐私与发布

账户凭据使用 Android Keystore 加密，位于禁止备份目录；源码和 APK 均不包含用户账户、登录令牌、Cookie 或手机账户备份。网页登录信息只保留在手机的浏览器存储中。

GitHub 发布只上传明确指定的 APK 和 SHA-256 校验文件。构建目录、密钥、账户加密文件和常见浏览器登录数据库由 `.gitignore` 排除；发布前重新检查源码、安装包和上传脚本。

## 构建与验证

分发版本为 `2.5.5-miku.20261001.8-upstream`，versionCode 191；继续沿用原包名和签名以便覆盖升级、保留数据。工具链与官方同步到 Gradle 9.6、AGP 9.4、Kotlin 2.4.20 和 Android SDK 37.2。

验证记录（2026-10-01）：

- AI、OAuth、App、Common、Search、Speech、Highlight 七个模块共 **865 项 JVM 测试通过**；另有 **21 项网页图片脚本 Node 回归通过**。
- 优化构建和 Android Lint 通过。Lint 保留原基线，报告 352 个警告、3 个提示及 44 个由既有基线过滤的问题；未通过修改基线隐藏错误。现有工作区的 4 项 Unix shell 测试依赖 `/bin/sh`，在 Windows 宿主上不能正常运行，未计入上述通过项目。
- 整合测试包在真机上复现原 Fast HTTP 400，再验证同义参数及自动兼容：GPT-6 Astra 返回测试回复；GPT-6.1 Sol 在 Fast 下正常返回回复，手动添加后强制刷新目录仍保留。
- 已生成网页图片通过真实下载按钮保存为 726,405 字节 PNG，手机相册目录中实际存在；应用自动返回发起操作的聊天，并显示图片缩略图。此流程在与最终构建相同源码的整合测试包上验证。
- 最终非调试优化 APK 已通过 ADB 覆盖安装并比对手机 APK 哈希；首次安装时间保留，原授权和模型配置保留。最终包上的 GPT-6.1 Sol Fast 再次正常返回回复，两档授权入口切换正常，测试后恢复「重要操作手动确认」。人工提问与取消/切换边界有单元回归覆盖。
- 全量 Git 发布内容以及最终 APK 的全部 1,491 个条目、87,956,805 个解压字节已完成隐私审查，未发现用户账户凭据、Cookie 或手机备份。仅发布 APK 和 SHA-256 文件。

最终 ARM64 APK：40,482,816 字节。SHA-256：`ce0519f6a29d60b8999d7153e7cce5fe351b741363df46af44cabb7934adb5f6`。

## 官方依据

- [Fast](https://developers.openai.com/api/docs/guides/fast-mode)
- [Ultrafast](https://developers.openai.com/api/docs/guides/ultrafast-mode)
- [Codex 速度](https://learn.chatgpt.com/docs/agent-configuration/speed)
- [账户模型与推理](https://developers.openai.com/siwc/token-sharing-open-source/models-and-inference)
- [登录接口当前限制](https://developers.openai.com/siwc/token-sharing-open-source/preview-limitations)
