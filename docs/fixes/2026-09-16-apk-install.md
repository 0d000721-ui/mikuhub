# 静默安装 `Unknown option -` 修复

适用版本：`2.5.1-miku.20260916.6-installfix`（versionCode 187）。

## 用户遇到的问题

文件下载完成后点击静默安装，系统返回退出码 255：

```text
java.lang.IllegalArgumentException: Unknown option -
    at com.android.server.pm.PackageManagerShellCommand.makeInstallParams(...)
```

## 原因与修复

旧版本生成 `pm install -r --user <user> -S <size> -`。Android 的选项解析器会把最后单独的 `-` 继续作为选项处理，安装逻辑还没读取 APK 就报错。

新版本生成 `pm install -r --user <user> -S <size> -- -`：先用 `--` 结束选项解析，再把 `-` 作为 APK 输入流路径。Shizuku 和 Root 共用这套参数生成逻辑。行为依据：[AOSP 参数解析器](https://android.googlesource.com/platform/prebuilts/fullsdk/sources/+/88c7ff1cd72d6305ec59f97aadc2198cc2dc3592/android-34/com/android/modules/utils/BasicShellCommandHandler.java)、[PackageManager 安装实现](https://android.googlesource.com/platform/frameworks/base/+/refs/heads/android13-release/services/core/java/com/android/server/pm/PackageManagerShellCommand.java)。

保留 Shizuku 服务标识，将服务版本从 3 升至 4，让升级后的应用请求重建旧服务。[Shizuku 服务版本说明](https://github.com/RikkaApps/Shizuku-API/blob/master/README.md#user-service)

下载页现在只在对应任务里显示安装结果；长错误显示为简短原因，原始日志可点击“查看详情”展开、滚动和选中复制。旧安装记录中的长堆栈也使用同样的显示方式。

## 回归验证

新增测试使用 Android SDK 36.1 / AOSP 的参数解析方法作为独立测试夹具：

- 旧参数确实复现 `Unknown option -`。
- 新参数在主用户和工作资料用户下保留用户编号、完整字节数与输入流路径。
- Root 命令文本序列化、重新解析后仍保留 `--` 与 `-` 的边界。
- 用户截图中的长堆栈会生成短提示，同时保留完整错误详情。

此前仅比较参数列表的测试未覆盖 Android 的解析规则，本次补充了这一层验证。测试夹具仅用于 JVM 测试，不打包进应用；保留 AOSP 版权及 Apache-2.0 许可证。

本地没有已连接的 Android 设备，不能将这些回归测试视作真机安装成功的证明。系统或厂商仍可能因安装权限、签名冲突等原因拒绝安装。

## 更新后使用

覆盖安装新版 MikuHub，打开原下载任务再次点击“静默安装 APK”。已下载的目标 APK 无需重新下载。若仍显示旧参数错误，重启 Shizuku 后重新授权并重试；其他错误可展开详情查看系统原因。
