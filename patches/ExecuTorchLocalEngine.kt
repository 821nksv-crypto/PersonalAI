package com.personalai.mvp.ai

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.pytorch.executorch.extension.llm.LlmCallback
import org.pytorch.executorch.extension.llm.LlmGenerationConfig
import org.pytorch.executorch.extension.llm.LlmModule
import org.pytorch.executorch.extension.llm.LlmModuleConfig
import java.io.File
import android.os.Handler
import android.os.Looper
import java.util.concurrent.atomic.AtomicBoolean

class ExecuTorchLocalEngine(
    private val modelFile: File,
    private val tokenizerFile: File
) : LocalModelEngine {

    private var module: LlmModule? = null
    private var loadError: String? = null
    private val generating = AtomicBoolean(false)
    private val moduleLock = Any()
    private val mainHandler = Handler(Looper.getMainLooper())

    private fun loadModule(): LlmModule {
        module?.let { return it }
        synchronized(moduleLock) {
            module?.let { return it }
            try {
                val config = LlmModuleConfig.create()
                    .modulePath(modelFile.absolutePath)
                    .tokenizerPath(tokenizerFile.absolutePath)
                    .temperature(0.7f)
                    .modelType(LlmModuleConfig.MODEL_TYPE_TEXT)
                    .loadMode(LlmModuleConfig.LOAD_MODE_MMAP)
                    .build()
                val loaded = LlmModule(config)
                loaded.load()
                module = loaded
                loadError = null
                return loaded
            } catch (t: Throwable) {
                loadError = t.message ?: t.javaClass.simpleName
                throw t
            }
        }
    }

    override suspend fun generate(prompt: String): String = withContext(Dispatchers.Default) {
        "Native inference diagnostic mode: ExecuTorch generation is temporarily isolated. The chat UI is running; native model execution is the component under test."
    }

    fun startStreaming(
        prompt: String,
        onToken: (String) -> Unit,
        onComplete: (String) -> Unit,
        onError: (Throwable) -> Unit
    ) {
        if (!generating.compareAndSet(false, true)) {
            mainHandler.post { runCatching { onError(IllegalStateException("A generation is already running.")) } }
            return
        }
        mainHandler.post {
            runCatching {
                onComplete(
                    "Native inference diagnostic mode is active. Chat UI is responding normally; ExecuTorch/Qwen native execution is isolated to prevent the app from closing while the runtime crash is diagnosed."
                )
            }
            generating.set(false)
        }
    }

    fun stopStreaming() {
        runCatching { module?.stop() }
        generating.set(false)
    }

    private fun applySystemInstructions(prompt: String): String {
        if (prompt.contains("<|im_start|>system")) return prompt
        var dir = modelFile.parentFile
        repeat(8) {
            val candidate = dir?.resolve("personalai_ai_instructions.txt")
            if (candidate?.isFile == true) {
                val instruction = candidate.readText().trim()
                if (instruction.isNotBlank()) {
                    return "<|im_start|>system\n$instruction<|im_end|>\n$prompt"
                }
            }
            dir = dir?.parentFile
        }
        return prompt
    }

    override fun isReady(): Boolean =
        modelFile.isFile && modelFile.length() > 0L && tokenizerFile.isFile && tokenizerFile.length() > 0L

    override fun modelName(): String = modelFile.name
}
