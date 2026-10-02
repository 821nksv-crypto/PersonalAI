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
        validateModelForDevice()
        module?.let { return it }
        synchronized(moduleLock) {
            module?.let { return it }
            try {
                val config = LlmModuleConfig.create()
                    .modulePath(modelFile.absolutePath)
                    .tokenizerPath(tokenizerFile.absolutePath)
                    .temperature(0.7f)
                    .modelType(LlmModuleConfig.MODEL_TYPE_TEXT)
                    .loadMode(LlmModuleConfig.LOAD_MODE_FILE)
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
                .seqLen(512)
                .maxNewTokens(64)
                .temperature(0.7f)
                .echo(false)
                .warming(false)
                .build()
            // DIAGNOSTIC STEP: verify native model loading without entering the
            // XNNPACK generation kernel. The current APK exits during inference,
            // so isolate load() from generate() before changing the model/backend.
            "Diagnostic: local model loaded successfully. Inference call is temporarily isolated."
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
                    .seqLen(512)
                    .maxNewTokens(96)
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
        // The ExecuTorch exports used here are chat-tuned models with ChatML templates.
        // Do not send the old USER:/ASSISTANT: wrapper; it is not the format these exports
        // were trained on.
        if (prompt.contains("<|im_start|>system")) return prompt
        val instruction = findLocalInstruction()
        val system = if (instruction.isNotBlank()) instruction
        else "You are a helpful AI assistant named SmolLM, trained by Hugging Face"
        val legacyUser = prompt.substringAfter("\n\nUSER:\n", prompt)
        val user = legacyUser.substringBeforeLast("\n\nASSISTANT:", legacyUser).trim()
        return "<|im_start|>system\n" + system + "<|im_end|>\n" +
            "<|im_start|>user\n" + user + "<|im_end|>\n" +
            "<|im_start|>assistant\n"
    }

    private fun findLocalInstruction(): String {
        var dir = modelFile.parentFile
        repeat(8) {
            val candidate = dir?.resolve("personalai_ai_instructions.txt")
            if (candidate?.isFile == true) {
                val instruction = candidate.readText().trim()
                if (instruction.isNotBlank()) return instruction
            }
            dir = dir?.parentFile
        }
        return ""
    }

    private fun validateModelForDevice() {
        require(modelFile.isFile && modelFile.canRead()) { "Local model file is missing or unreadable." }
        require(tokenizerFile.isFile && tokenizerFile.canRead()) { "Tokenizer file is missing or unreadable." }
        require(modelFile.length() <= 700L * 1024L * 1024L) {
            "This model is too large for the lightweight PersonalAI profile. Install the SmolLM2 135M XNNPACK 2K model."
        }
        require(tokenizerFile.length() <= 20L * 1024L * 1024L) {
            "Tokenizer file is invalid or too large."
        }
    }

    override fun isReady(): Boolean =
        modelFile.isFile &&
            modelFile.length() > 0L &&
            tokenizerFile.isFile &&
            tokenizerFile.length() > 0L &&
            loadError == null

    override fun modelName(): String = modelFile.name
}