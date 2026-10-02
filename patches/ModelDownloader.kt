package com.personalai.mvp.models

import android.content.Context
import android.os.StatFs
import com.personalai.mvp.importer.ImportResult
import java.io.File
import java.io.FileInputStream
import java.io.FileOutputStream
import java.net.HttpURLConnection
import java.net.URL
import java.security.MessageDigest
import java.util.UUID
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.json.JSONObject

class ModelDownloader(
    private val context: Context,
    private val importService: ModelImportService
) {
    companion object {
        private const val MODEL_FILE = "Qwen2.5-0.5B-8da4w-2k.pte"
        private const val TOKENIZER_FILE = "tokenizer.json"
        private const val MODEL_ID = "qwen2.5-0.5b-xnnpack-8da4w-2k"
        private const val MODEL_VERSION = "5.0"
        private const val MODEL_URL =
            "https://huggingface.co/experimentalmachines/Qwen2.5-0.5B-ExecuTorch/resolve/main/xnnpack/Qwen2.5-0.5B-8da4w-2k.pte"
        private const val TOKENIZER_URL =
            "https://huggingface.co/experimentalmachines/Qwen2.5-0.5B-ExecuTorch/resolve/main/tokenizer.json"
        private const val MIN_FREE_BYTES = 850L * 1024L * 1024L
    }

    suspend fun downloadAndInstall(
        onProgress: (downloaded: Long, total: Long) -> Unit = { _, _ -> }
    ): String = withContext(Dispatchers.IO) {
        val modelsDir = File(context.filesDir, "models/incoming").apply { mkdirs() }
        val model = File(modelsDir, MODEL_FILE)
        val tokenizer = File(modelsDir, TOKENIZER_FILE)
        val free = StatFs(context.filesDir.absolutePath).availableBytes
        require(free > MIN_FREE_BYTES) {
            "Not enough free storage. Keep at least 850 MB free before downloading the local model."
        }
        try {
            downloadToFile(MODEL_URL, model, onProgress)
            downloadToFile(TOKENIZER_URL, tokenizer) { done, total -> onProgress(done, total) }
            require(model.isFile && model.length() > 0L) { "Model download is empty." }
            require(tokenizer.isFile && tokenizer.length() > 0L) { "Tokenizer download is empty." }

            val manifest = JSONObject()
                .put("id", MODEL_ID)
                .put("displayName", "Qwen2.5 0.5B • XNNPACK 2K")
                .put("version", MODEL_VERSION)
                .put("runtime", "executorch")
                .put("architecture", "arm64-v8a")
                .put("fileName", MODEL_FILE)
                .put("sizeBytes", model.length())
                .put("sha256", sha256Streaming(model))
                .put("tokenizerFileName", TOKENIZER_FILE)
                .put("tokenizerSha256", sha256Streaming(tokenizer))
            val manifestFile = File(modelsDir, "qwen2.5-0.5b-manifest.json")
            manifestFile.writeText(manifest.toString())

            val pair = SelectedModelPair(UUID.randomUUID().toString(), model, manifestFile, tokenizer)
            when (val result = importService.verifyAndInstall(pair)) {
                is ImportResult.Success -> {
                    model.delete()
                    tokenizer.delete()
                    manifestFile.delete()
                    "Qwen2.5 0.5B • ExecuTorch 1.4.0 • XNNPACK 2K installed. Restarting local AI engine…"
                }
                is ImportResult.Failure -> throw IllegalStateException(result.message)
                is ImportResult.Cancelled -> throw IllegalStateException("Model installation cancelled.")
            }
        } catch (t: Throwable) {
            model.delete()
            tokenizer.delete()
            File(modelsDir, "qwen2.5-0.5b-manifest.json").delete()
            throw t
        }
    }

    private fun downloadToFile(
        url: String,
        destination: File,
        onProgress: (Long, Long) -> Unit = { _, _ -> }
    ) {
        val partial = File(destination.parentFile, destination.name + ".part")
        partial.delete()
        val connection = (URL(url).openConnection() as HttpURLConnection).apply {
            connectTimeout = 20_000
            readTimeout = 60_000
            instanceFollowRedirects = true
            requestMethod = "GET"
            setRequestProperty("Accept", "*/*")
            setRequestProperty("User-Agent", "PersonalAI/1.0 (Android; ExecuTorch)")
            connect()
        }
        try {
            val code = connection.responseCode
            require(code in 200..299) { "Model server returned HTTP $code" }
            val total = connection.contentLengthLong
            connection.inputStream.use { input ->
                FileOutputStream(partial).use { output ->
                    val buffer = ByteArray(1024 * 1024)
                    var done = 0L
                    while (true) {
                        val n = input.read(buffer)
                        if (n < 0) break
                        output.write(buffer, 0, n)
                        done += n
                        onProgress(done, total)
                    }
                    output.fd.sync()
                }
            }
            require(partial.length() > 0L) { "Downloaded file is empty." }
            if (destination.exists()) destination.delete()
            check(partial.renameTo(destination)) { "Could not finalize downloaded model file." }
        } finally {
            connection.disconnect()
            partial.delete()
        }
    }

    private fun sha256Streaming(file: File): String {
        val digest = MessageDigest.getInstance("SHA-256")
        FileInputStream(file).use { input ->
            val buffer = ByteArray(1024 * 1024)
            while (true) {
                val n = input.read(buffer)
                if (n < 0) break
                digest.update(buffer, 0, n)
            }
        }
        return digest.digest().joinToString("") { "%02x".format(it) }
    }
}