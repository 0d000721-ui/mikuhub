package me.rerere.rikkahub.browser

import android.content.ContentValues
import android.content.Context
import android.graphics.BitmapFactory
import android.net.Uri
import android.os.Build
import android.os.Environment
import android.provider.MediaStore
import androidx.annotation.RequiresApi
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.withContext
import java.io.File
import java.util.UUID

/** Only complete, validated images are published. Pending files are removed on failure/cancellation. */
internal class BrowserImageSaver(private val context: Context) {
    suspend fun prepare(bytes: ByteArray, mime: String, onStage: (BrowserImageStage) -> Unit = {}): PendingBrowserImage {
        var pending: PendingBrowserImage? = null
        try {
            return withContext(Dispatchers.IO) {
                require(bytes.size in 1..MAX_BROWSER_IMAGE_BYTES) { "图片为空或超过 20 MiB" }
                onStage(BrowserImageStage.SIGNATURE)
                requireImageSignature(bytes, mime)
                onStage(BrowserImageStage.DECODE)
                val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
                BitmapFactory.decodeByteArray(bytes, 0, bytes.size, bounds)
                require(bounds.outWidth > 0 && bounds.outHeight > 0 && bounds.outMimeType == mime) { "图片无法解码，未保存" }
                currentCoroutineContext().ensureActive()
                val name = "MikuHub-${UUID.randomUUID()}.${imageFileExtension(mime)}"
                onStage(BrowserImageStage.CREATE)
                val target = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) mediaStoreTarget(name, mime) else privateTarget(name)
                pending = target
                val operation = currentCoroutineContext()
                onStage(BrowserImageStage.WRITE)
                target.write(bytes) { operation.ensureActive() }
                target
            }
        } catch (error: Throwable) {
            withContext(NonCancellable + Dispatchers.IO) { pending?.discard() }
            throw error
        }
    }

    @RequiresApi(Build.VERSION_CODES.Q)
    private fun mediaStoreTarget(name: String, mime: String): PendingBrowserImage {
        val resolver = context.contentResolver
        val uri = resolver.insert(MediaStore.Images.Media.EXTERNAL_CONTENT_URI, ContentValues().apply {
            put(MediaStore.Images.Media.DISPLAY_NAME, name)
            put(MediaStore.Images.Media.MIME_TYPE, mime)
            put(MediaStore.Images.Media.RELATIVE_PATH, "${Environment.DIRECTORY_PICTURES}/MikuHub")
            put(MediaStore.Images.Media.IS_PENDING, 1)
        }) ?: error("无法创建相册图片，未保存")
        return PendingBrowserImage(
            open = { resolver.openOutputStream(uri, "w") ?: error("无法写入相册图片，未保存") },
            publish = {
                check(resolver.update(uri, ContentValues().apply { put(MediaStore.Images.Media.IS_PENDING, 0) }, null, null) > 0) {
                    "无法完成相册保存，未保存"
                }
                SavedBrowserImage(uri, "已保存到相册 Pictures/MikuHub：$name")
            },
            remove = { resolver.delete(uri, null, null) },
        )
    }

    private fun privateTarget(name: String): PendingBrowserImage {
        val pictures = context.getExternalFilesDir(Environment.DIRECTORY_PICTURES) ?: error("应用图片目录不可用，未保存")
        val folder = File(pictures, "MikuHub")
        check(folder.isDirectory || folder.mkdirs()) { "无法创建应用图片目录，未保存" }
        val file = File(folder, name)
        val temporary = File(folder, "$name.part")
        return PendingBrowserImage(
            open = { temporary.outputStream() },
            publish = {
                check(temporary.renameTo(file)) { "无法完成应用图片保存，未保存" }
                SavedBrowserImage(Uri.fromFile(file), "已保存到应用图片目录：$name（此系统版本的相册不会显示；卸载应用会删除）")
            },
            remove = { temporary.delete(); file.delete() },
        )
    }
}

internal data class SavedBrowserImage(val uri: Uri, val message: String)

/** publish() is called on Main only after checking the original document and action permission. */
internal typealias PendingBrowserImage = BrowserImagePublication<SavedBrowserImage>
