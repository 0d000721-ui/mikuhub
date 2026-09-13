# Device Operations

Device commands are opt-in. The app never bypasses Shizuku or root authorization.
Factory reset, data wipe, and destructive system erase commands are permanently blocked.
Accessibility is user-enabled through Android Settings and starts with no content retrieval or gestures.

## Shizuku authorization

1. Install the current APK, then start the service in the official Shizuku app.
2. Open RikkaHub's device control page and request Shizuku permission. Accept the Shizuku dialog.
3. If further requests were denied, use **Open Shizuku** and allow the package shown on the device page in Shizuku's application manager.
4. Returning to RikkaHub refreshes the status automatically; **Recheck Shizuku status** can refresh it manually.

Debug builds use `me.rerere.rikkahub.debug`; release builds use `me.rerere.rikkahub`. The Shizuku permission belongs to the installed package. Device session authorization is a separate control.

The current combined authorization and AI execution fix is delivered as `RikkaHub-sakura-arm64-device-tools-fixed.apk` on the desktop. Its signing certificate matches `RikkaHub-sakura-arm64-shizuku-auth-fixed.apk`, so it can update that build.

The packaged manifest was verified to include `moe.shizuku.manager.permission.API_V23`, `moe.shizuku.client.V3_SUPPORT`, and `rikka.shizuku.ShizukuProvider` with authority `me.rerere.rikkahub.debug.shizuku`. The APK also contains the new device controller, confirmation UI, process executor, and versioned Binder service. All 240 app unit tests passed, including Shizuku permission-flow, command execution/confirmation, and actual host-subprocess tests. End-to-end operation on a physical Android device still requires verification after installation.

## Letting the AI use device commands

1. In **Device control**, enable **Allow the current assistant to use device tools**. The same switch is available under the assistant's built-in tools as **Device control (Shizuku / ADB shell)**.
2. Use a model with its tool-calling capability enabled. The device page shows a message if the current model is not configured for tools.
3. Complete Shizuku authorization, then press **Test Shizuku command channel (id)**. The output should show the service's actual UID (usually `2000` for ADB/wireless debugging, or `0` for root-started Shizuku).
4. If the device session was revoked or stopped, start a new session on that page.
5. Ask the assistant to call `device_status`, then list installed third-party packages. Request an uninstall only after the target package and Android user have been identified.

The AI receives two tools:

- `device_status`: returns actual Shizuku state/UID and device-session state. This does not invoke `su` or probe root automatically.
- `device_command`: executes a single supported literal Android command and returns `success`, `transport`, `command`, `output`, `exit_code` (when available), and error/rejection/timeout details.

Examples: `pm list packages -3`, `am get-current-user`, `pm uninstall --user 0 com.example.demo`. The package/user in the uninstall example are placeholders for a verified user-selected target.

`auto` selects Shizuku. `adb` is a compatibility alias for local Shizuku execution, and the result reports the actual transport as `shizuku`. There is no direct ADB socket connection or `adb connect/pair` workflow in these tools. Explicit `root` selection uses the standard `su` manager permission flow.

Uninstall/clear operations always require an on-device confirmation, including in an authorized session. Kernel-critical commands require three distinct steps. Model JSON fields such as `confirmed` do not authorize execution. Stop/revoke cancels pending confirmations and active commands; old requests cannot resume in a new session. Scripts, shell substitutions, pipelines and redirections are rejected. Command processes have a 30-second limit and bounded output.

APK SHA-256: `D0489C8C6ECD206503E68CFF3C137D369000BEC47D4FC421A33B7D918D6E0A07`.
