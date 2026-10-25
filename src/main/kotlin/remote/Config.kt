package remote

data class Config(
    val host: String,
    val port: Int,
    val adb: String,
    val device: String,
    val browser: String?,
    val webDir: String,
    val shelfFile: String,
    val pushFile: String,
    val pushContact: String,
    val scrcpyServer: String,
    val inkOverlayApk: String,
    val claudeBin: String,
    val claudeDir: String,
    val claudeModel: String?,
) {
    companion object {
        fun fromEnv(env: Map<String, String> = System.getenv()) = Config(
            // Loopback for local runs; the Docker image sets HOST=0.0.0.0 so phones on the Wi-Fi can reach it.
            host = env["HOST"] ?: "127.0.0.1",
            port = env["PORT"]?.toIntOrNull() ?: 8790,
            adb = env["ADB"] ?: "adb",
            device = env["PROJECTOR_ADB"] ?: "192.168.1.5:5555",
            // TV Bro by default; set PROJECTOR_BROWSER to an empty string to let Android pick.
            browser = (env["PROJECTOR_BROWSER"] ?: "com.phlox.tvwebbrowser").takeIf { it.isNotBlank() },
            webDir = env["WEB_DIR"] ?: "remote-web/dist",
            shelfFile = env["SHELF_FILE"] ?: "shelf.json",
            pushFile = env["PUSH_FILE"] ?: "push.json",
            // Push services want a contact for the sender; Apple rejects a missing or malformed one.
            pushContact = env["PUSH_CONTACT"] ?: "mailto:projector-remote@example.com",
            scrcpyServer = env["SCRCPY_SERVER"] ?: "tools/scrcpy-server-v5.0.1",
            inkOverlayApk = env["INK_OVERLAY_APK"] ?: "tools/ink-overlay.apk",
            claudeBin = env["CLAUDE_BIN"] ?: "claude",
            // Claude's working folder, where it keeps the MCP config for its runs.
            claudeDir = env["CLAUDE_DIR"] ?: "claude-work",
            claudeModel = env["CLAUDE_MODEL"]?.takeIf { it.isNotBlank() },
        )
    }
}
