package com.personalai.mvp.ai

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.pytorch.executorch.extension.llm.LlmModule
import org.pytorch.executorch.extension.llm.LlmModuleConfig
import java.io.File
import java.util.concurrent.atomic.AtomicBoolean

class ExecuTorchLocalEngine(
    private val modelFile: File,
    private val tokenizerFile: File
) : LocalModelEngine {

    private var module: LlmModule? = null
    private val generating = AtomicBoolean(false)
    private val moduleLock = Any()

    private fun loadModule(): LlmModule {
        module?.let { return it }
        synchronized(moduleLock) {
            module?.let { return it }
            val config = LlmModuleConfig.create()
                .modulePath(modelFile.absolutePath)
                .tokenizerPath(tokenizerFile.absolutePath)
                .temperature(0.7f)
                .modelType(LlmModuleConfig.MODEL_TYPE_TEXT)
                .loadMode(LlmModuleConfig.LOAD_MODE_MMAP)
                .build()
            return LlmModule(config).also { it.load(); module = it }
        }
    }

    override suspend fun generate(prompt: String): String = withContext(Dispatchers.Default) {
        "Chat diagnostic mode: native inference and completion callbacks are disabled."
    }

    fun startStreaming(
        prompt: String,
        onToken: (String) -> Unit,
        onComplete: (String) -> Unit,
        onError: (Throwable) -> Unit
    ) {
        // Deliberately do not invoke any callback. This isolates the Send -> callback -> Compose state path.
        generating.set(true)
    }

    fun stopStreaming() {
        generating.set(false)
    }

    override fun isReady(): Boolean =
        modelFile.isFile && modelFile.length() > 0L &&
            tokenizerFile.isFile && tokenizerFile.length() > 0L

    override fun modelName(): String = modelFile.name
}
