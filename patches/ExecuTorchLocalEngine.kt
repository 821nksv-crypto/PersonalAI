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
        val m = try {
            loadModule()
        } catch (t: Throwable) {
            return@withContext "Local model could not be loaded: " + (t.message ?: t.javaClass.simpleName)
        }
        val out = StringBuilder()
        val done = Object()
        var finished = false
        var error: String? = null
        val callback = object : LlmCallback {
            override fun onResult(token: String) {
                synchronized(done) { out.append(token) }
            }
            override fun onStats(statsJson: String) {
                synchronized(done) {
                    finished = true
                    done.notifyAll()
                }
            }
            override fun onError(errorCode: Int, message: String) {
                synchronized(done) {
                    error = "Inference error " + errorCode + ": " + message
                    finished = true
                    done.notifyAll()
                }
            }
        }
        try {
            generating.set(true)
            val config = LlmGenerationConfig.create()
                .seqLen(2048)
                .maxNewTokens(192)
                .temperature(0.7f)
                .echo(false)
                .build()
            m.generate(applySystemInstructions(prompt), config, callback)
            synchronized(done) {
                while (!finished && error == null) done.wait(50)
            }
            error ?: out.toString().ifBlank { "(No output generated)" }
        } catch (t: Throwable) {
            "Local inference failed: " + (t.message ?: t.javaClass.simpleName)
        } finally {
            generating.set(false)
        }
    }

    fun startStreaming(
        prompt: String,
        onToken: (String) -> Unit,
        onComplete: (String) -> Unit,
        onError: (Throwable) -> Unit
    ) {
        if (!generating.compareAndSet(false, true)) {
            mainHandler.post {
                runCatching { onError(IllegalStateException("A generation is already running.")) }
            }
            return
        }
        Thread {
            val output = StringBuilder()
            try {
                val m = loadModule()
                val config = LlmGenerationConfig.create()
                    .seqLen(2048)
                    .maxNewTokens(192)
                    .temperature(0.7f)
                    .echo(false)
                    .build()
                val callback = object : LlmCallback {
                    override fun onResult(token: String) {
                        output.append(token)
                        mainHandler.post { runCatching { onToken(token) } }
                    }
                    override fun onStats(statsJson: String) {
                        val text = output.toString()
                        mainHandler.post { runCatching { onComplete(text) } }
                    }
                    override fun onError(errorCode: Int, message: String) {
                        mainHandler.post {
                            runCatching {
                                onError(IllegalStateException("ExecuTorch error " + errorCode + ": " + message))
                            }
                        }
                    }
                }
                m.generate(applySystemInstructions(prompt), config, callback)
            } catch (t: Throwable) {
                mainHandler.post { runCatching { onError(t) } }
            } finally {
                generating.set(false)
            }
        }.apply {
            name = "personal-ai-llm"
            start()
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
                    return "<|im_start|>system\n" + instruction + "<|im_end|>\n" + prompt
                }
            }
            dir = dir?.parentFile
        }
        return prompt
    }

    override fun isReady(): Boolean =
        modelFile.isFile &&
            modelFile.length() > 0L &&
            tokenizerFile.isFile &&
            tokenizerFile.length() > 0L &&
            loadError == null

    override fun modelName(): String = modelFile.name
}