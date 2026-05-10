/*
 * Copyright (C) 2024 Shubham Panchal
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *      http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package io.shubham0204.smollm

import android.os.Build
import android.util.Log
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.File
import java.io.FileNotFoundException

/** This class interacts with the JNI binding and provides a Kotlin API to infer a GGUF LLM model */
class SmolLM {
    companion object {
        init {
            val logTag = SmolLM::class.java.simpleName

            val cpuFeatures = getCPUFeatures()
            val hasFp16 = cpuFeatures.contains("fp16") || cpuFeatures.contains("fphp")
            val hasDotProd = cpuFeatures.contains("dotprod") || cpuFeatures.contains("asimddp")
            val hasSve = cpuFeatures.contains("sve")
            val hasI8mm = cpuFeatures.contains("i8mm")
            val isAtLeastArmV82 =
                cpuFeatures.contains("asimd") &&
                    cpuFeatures.contains("crc32") &&
                        cpuFeatures.contains("aes")
            val isAtLeastArmV84 = cpuFeatures.contains("dcpop") && cpuFeatures.contains("uscat")

            Log.d(logTag, "CPU features: $cpuFeatures")
            Log.d(logTag, "- hasFp16: $hasFp16")
            Log.d(logTag, "- hasDotProd: $hasDotProd")
            Log.d(logTag, "- hasSve: $hasSve")
            Log.d(logTag, "- hasI8mm: $hasI8mm")
            Log.d(logTag, "- isAtLeastArmV82: $isAtLeastArmV82")
            Log.d(logTag, "- isAtLeastArmV84: $isAtLeastArmV84")

            val isEmulated =
                (Build.HARDWARE.contains("goldfish") || Build.HARDWARE.contains("ranchu"))
            Log.d(logTag, "isEmulated: $isEmulated")

            if (!isEmulated) {
                if (supportsArm64V8a()) {
                    if (isAtLeastArmV84 && hasSve && hasI8mm && hasFp16 && hasDotProd) {
                        Log.d(logTag, "Loading libsmollm_v8_4_fp16_dotprod_i8mm_sve.so")
                        System.loadLibrary("smollm_v8_4_fp16_dotprod_i8mm_sve")
                    } else if (isAtLeastArmV84 && hasSve && hasFp16 && hasDotProd) {
                        Log.d(logTag, "Loading libsmollm_v8_4_fp16_dotprod_sve.so")
                        System.loadLibrary("smollm_v8_4_fp16_dotprod_sve")
                    } else if (isAtLeastArmV84 && hasI8mm && hasFp16 && hasDotProd) {
                        Log.d(logTag, "Loading libsmollm_v8_4_fp16_dotprod_i8mm.so")
                        System.loadLibrary("smollm_v8_4_fp16_dotprod_i8mm")
                    } else if (isAtLeastArmV84 && hasFp16 && hasDotProd) {
                        Log.d(logTag, "Loading libsmollm_v8_4_fp16_dotprod.so")
                        System.loadLibrary("smollm_v8_4_fp16_dotprod")
                    } else if (isAtLeastArmV82 && hasFp16 && hasDotProd) {
                        Log.d(logTag, "Loading libsmollm_v8_2_fp16_dotprod.so")
                        System.loadLibrary("smollm_v8_2_fp16_dotprod")
                    } else if (isAtLeastArmV82 && hasFp16) {
                        Log.d(logTag, "Loading libsmollm_v8_2_fp16.so")
                        System.loadLibrary("smollm_v8_2_fp16")
                    } else {
                        Log.d(logTag, "Loading libsmollm_v8.so")
                        System.loadLibrary("smollm_v8")
                    }
                } else if (Build.SUPPORTED_32_BIT_ABIS[0]?.equals("armeabi-v7a") == true) {
                    Log.d(logTag, "Loading libsmollm_v7a.so")
                    System.loadLibrary("smollm_v7a")
                } else {
                    Log.d(logTag, "Loading default libsmollm.so")
                    System.loadLibrary("smollm")
                }
            } else {
                Log.d(logTag, "Loading default libsmollm.so")
                System.loadLibrary("smollm")
            }
        }

        private fun getCPUFeatures(): String {
            val cpuInfo =
                try {
                    File("/proc/cpuinfo").readText()
                } catch (e: FileNotFoundException) {
                    ""
                }
            val cpuFeatures =
                cpuInfo.substringAfter("Features").substringAfter(":").substringBefore("\n").trim()
            return cpuFeatures
        }

        private fun supportsArm64V8a(): Boolean = Build.SUPPORTED_ABIS[0].equals("arm64-v8a")
    }

    private var nativePtr = 0L

    object DefaultInferenceParams {
        val contextSize: Long = 1024L
        val chatTemplate: String =
            "{% for message in messages %}{% if loop.first and messages[0]['role'] != 'system' %}{{ '<|im_start|>system You are a helpful AI assistant named SmolLM, trained by Hugging Face<|im_end|> ' }}{% endif %}{{'<|im_start|>' + message['role'] + ' ' + message['content'] + '<|im_end|>' + ' '}}{% endfor %}{% if add_generation_prompt %}{{ '<|im_start|>assistant ' }}{% endif %}"
    }

    data class InferenceParams(
        val minP: Float = 0.1f,
        val temperature: Float = 0.8f,
        val storeChats: Boolean = true,
        val contextSize: Long? = null,
        val chatTemplate: String? = null,
        val numThreads: Int = 4,
        val useMmap: Boolean = true,
        val useMlock: Boolean = false,
    )

    suspend fun load(modelPath: String, params: InferenceParams = InferenceParams()) =
        withContext(Dispatchers.IO) {
            val ggufReader = GGUFReader()
            ggufReader.load(modelPath)
            val modelContextSize = ggufReader.getContextSize() ?: DefaultInferenceParams.contextSize
            val modelChatTemplate =
                ggufReader.getChatTemplate() ?: DefaultInferenceParams.chatTemplate
            nativePtr =
                loadModel(
                    modelPath,
                    params.minP,
                    params.temperature,
                    params.storeChats,
                    params.contextSize ?: modelContextSize,
                    params.chatTemplate ?: modelChatTemplate,
                    params.numThreads,
                    params.useMmap,
                    params.useMlock,
                )
        }

    /**
     * VaaBridge-facing wrapper — NOT suspend.
     * Launches its own coroutine so it can be called from @JavascriptInterface.
     */
    fun loadModel(
        modelPath: String,
        contextSize: Long,
        useGpu: Boolean,
        onModelLoaded: () -> Unit
    ) {
        CoroutineScope(Dispatchers.IO).launch {
            load(modelPath, InferenceParams(contextSize = contextSize))
            onModelLoaded()
        }
    }

    fun addUserMessage(message: String) {
        verifyHandle()
        addChatMessage(nativePtr, message, "user")
    }

    fun addSystemPrompt(prompt: String) {
        verifyHandle()
        addChatMessage(nativePtr, prompt, "system")
    }

    fun addAssistantMessage(message: String) {
        verifyHandle()
        addChatMessage(nativePtr, message, "assistant")
    }

    fun getResponseGenerationSpeed(): Float {
        verifyHandle()
        return getResponseGenerationSpeed(nativePtr)
    }

    fun getContextLengthUsed(): Int {
        verifyHandle()
        return getContextSizeUsed(nativePtr)
    }

    fun getResponseAsFlow(query: String): Flow<String> = flow {
        verifyHandle()
        startCompletion(nativePtr, query)
        var piece = completionLoop(nativePtr)
        while (piece != "[EOG]") {
            emit(piece)
            piece = completionLoop(nativePtr)
        }
        stopCompletion(nativePtr)
    }

    /**
     * VaaBridge-facing wrapper — callback-based streaming.
     * Launches its own coroutine so it can be called from @JavascriptInterface.
     */
    fun getResponse(
        query: String,
        history: String,
        onTokenGenerated: (String) -> Unit
    ) {
        CoroutineScope(Dispatchers.IO).launch {
            val fullQuery = if (history.isNotEmpty()) "$history\n$query" else query
            getResponseAsFlow(fullQuery).collect { token ->
                onTokenGenerated(token)
            }
        }
    }

    fun getResponse(query: String): String {
        verifyHandle()
        startCompletion(nativePtr, query)
        var piece = completionLoop(nativePtr)
        var response = ""
        while (piece != "[EOG]") {
            response += piece
            piece = completionLoop(nativePtr)
        }
        stopCompletion(nativePtr)
        return response
    }

    fun benchModel(pp: Int, tg: Int, pl: Int, nr: Int): String {
        verifyHandle()
        return benchModel(nativePtr, pp, tg, pl, nr)
    }

    fun close() {
        if (nativePtr != 0L) {
            close(nativePtr)
            nativePtr = 0L
        }
    }

    private fun verifyHandle() {
        assert(nativePtr != 0L) { "Model is not loaded. Use SmolLM.create to load the model" }
    }

    private external fun loadModel(
        modelPath: String,
        minP: Float,
        temperature: Float,
        storeChats: Boolean,
        contextSize: Long,
        chatTemplate: String,
        nThreads: Int,
        useMmap: Boolean,
        useMlock: Boolean,
    ): Long

    private external fun addChatMessage(modelPtr: Long, message: String, role: String)

    private external fun getResponseGenerationSpeed(modelPtr: Long): Float

    private external fun getContextSizeUsed(modelPtr: Long): Int

    private external fun close(modelPtr: Long)

    private external fun startCompletion(modelPtr: Long, prompt: String)

    private external fun completionLoop(modelPtr: Long): String

    private external fun stopCompletion(modelPtr: Long)

    private external fun benchModel(modelPtr: Long, pp: Int, tg: Int, pl: Int, nr: Int): String
}
