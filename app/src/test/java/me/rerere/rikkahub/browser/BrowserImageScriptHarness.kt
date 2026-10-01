package me.rerere.rikkahub.browser

import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import java.io.File

/** Emits the exact production scripts for the standalone Node HTTP/WebView mock test. */
fun main(args: Array<String>) {
    File(args.single()).writeText(buildJsonObject {
        put("begin", BrowserImageScripts.begin("__test_image", "https://chatgpt.com/image-test"))
        put("blob", BrowserImageScripts.begin("__test_image", "blob:https://chatgpt.com/image-id"))
        put("cachedBlob", BrowserImageScripts.begin("__test_image", "blob:https://chatgpt.com/image-id", "__test_cache"))
        put("install", BrowserImageScripts.install("__test_cache"))
        put("arm", BrowserImageScripts.arm("__test_cache"))
        put("clear", BrowserImageScripts.clear("__test_cache"))
        put("uninstall", BrowserImageScripts.uninstall("__test_cache"))
        put("renderCounts", BrowserScripts.renderCounts)
        put("cdn", BrowserImageScripts.begin("__test_image", "https://files.oaiusercontent.com/image-test"))
        put("badSource", BrowserImageScripts.begin("__test_image", "https://evil.test/image-test"))
        put("poll", BrowserImageScripts.poll("__test_image"))
        put("cancel", BrowserImageScripts.cancel("__test_image"))
        put("maxBytes", MAX_BROWSER_IMAGE_BYTES)
        put("chunkBytes", BROWSER_IMAGE_CHUNK_BYTES)
    }.toString())
}
