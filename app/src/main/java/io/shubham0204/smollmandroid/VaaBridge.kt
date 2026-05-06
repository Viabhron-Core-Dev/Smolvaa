package io.shubham0204.smollmandroid

import android.webkit.JavascriptInterface
import android.webkit.WebView
import io.shubham0204.smollm.SmolLM
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import org.json.JSONObject

/**
 * VaaBridge provides a JavaScript interface for the Vaa frontend to interact with the SmolLM engine.
 */
class VaaBridge(private val webView: WebView, private val smolLM: SmolLM) {

    private val _isModelLoaded = MutableStateFlow(false)
    val isModelLoaded: StateFlow<Boolean> = _isModelLoaded

    private val _isGenerating = MutableStateFlow(false)
    val isGenerating: StateFlow<Boolean> = _isGenerating

    private var chatHistory = ""
    private val scope = CoroutineScope(Dispatchers.Main + SupervisorJob())

    /**
     * Loads an LLM model from the given path using the confirmed Step 1 signature.
     *
     * @param modelPath The local filesystem path to the GGUF model.
     * @param contextSize The context size to use for the model.
     * @param useGpu Whether to use GPU acceleration.
     * @param onModelLoaded The name of the JavaScript function to call when the model is loaded.
     */
    @JavascriptInterface
    fun loadModel(modelPath: String, contextSize: Int, useGpu: Boolean, onModelLoaded: String) {
        scope.launch {
            smolLM.loadModel(modelPath, contextSize.toLong(), useGpu) {
                _isModelLoaded.value = true
                webView.post {
                    webView.evaluateJavascript("window.$onModelLoaded()", null)
                }
            }
        }
    }

    /**
     * Sends a message to the LLM using the confirmed Step 1 signature.
     * The bridge manages internal chatHistory and calls window.onVaaToken() for each token.
     *
     * @param prompt The user's query/prompt.
     * @param systemPrompt An optional system prompt to prepend to the user's prompt.
     */
    @JavascriptInterface
    fun sendMessage(prompt: String, systemPrompt: String) {
        val formattedPrompt = if (systemPrompt.isNotEmpty()) {
            "[SYSTEM: $systemPrompt]\n$prompt"
        } else {
            prompt
        }
        
        scope.launch {
            _isGenerating.value = true
            var fullResponse = ""
            val fullQuery = if (chatHistory.isNotEmpty()) "$chatHistory\n$formattedPrompt" else formattedPrompt
            
            try {
                smolLM.getResponseAsFlow(fullQuery).collect { token ->
                    fullResponse += token
                    webView.post {
                        webView.evaluateJavascript("window.onVaaToken(${JSONObject.quote(token)})", null)
                    }
                }
                
                // Update internal context history after generation
                chatHistory += if (chatHistory.isEmpty()) {
                    "$formattedPrompt\n$fullResponse"
                } else {
                    "\n$formattedPrompt\n$fullResponse"
                }
                
                onVaaComplete()
            } catch (e: Exception) {
                // Handle error if needed
            } finally {
                _isGenerating.value = false
            }
        }
    }

    private fun onVaaComplete() {
        webView.post {
            webView.evaluateJavascript("window.onVaaComplete()", null)
        }
    }
}
