package com.example.viewmodel

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.example.BuildConfig
import com.google.ai.client.generativeai.GenerativeModel
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

sealed interface AiResponseState {
    object Idle : AiResponseState
    object Loading : AiResponseState
    data class Success(val text: String, val timestamp: Long = System.currentTimeMillis()) : AiResponseState
    data class Error(val message: String, val isApiKeyMissing: Boolean = false) : AiResponseState
}

class PromptViewModel : ViewModel() {

    private val _promptText = MutableStateFlow("")
    val promptText: StateFlow<String> = _promptText.asStateFlow()

    private val _uiState = MutableStateFlow<AiResponseState>(AiResponseState.Idle)
    val uiState: StateFlow<AiResponseState> = _uiState.asStateFlow()

    fun onPromptChange(newText: String) {
        _promptText.value = newText
    }

    fun clearPrompt() {
        _promptText.value = ""
    }

    fun clearResponse() {
        _uiState.value = AiResponseState.Idle
    }

    fun generateWithAi(modelName: String = "gemini-1.5-flash") {
        val currentPrompt = _promptText.value.trim()
        if (currentPrompt.isEmpty()) return

        val apiKey = try {
            BuildConfig.GEMINI_API_KEY
        } catch (e: Exception) {
            ""
        }

        if (apiKey.isBlank() || apiKey == "MY_GEMINI_API_KEY") {
            _uiState.value = AiResponseState.Error(
                message = "GEMINI_API_KEY is not configured. Please set your API key in the AI Studio Secrets panel.",
                isApiKeyMissing = true
            )
            return
        }

        _uiState.value = AiResponseState.Loading

        viewModelScope.launch {
            try {
                val resultText = withContext(Dispatchers.IO) {
                    val generativeModel = GenerativeModel(
                        modelName = modelName,
                        apiKey = apiKey
                    )
                    try {
                        val response = generativeModel.generateContent(currentPrompt)
                        response.text ?: "No text returned by the model."
                    } catch (e: Exception) {
                        // If model isn't available or deprecated, attempt fallback to latest flash preview
                        if (e.message?.contains("not found", ignoreCase = true) == true ||
                            e.message?.contains("unsupported", ignoreCase = true) == true
                        ) {
                            val fallbackModel = GenerativeModel(
                                modelName = "gemini-2.5-flash",
                                apiKey = apiKey
                            )
                            val fallbackResponse = fallbackModel.generateContent(currentPrompt)
                            fallbackResponse.text ?: "No text returned by the model."
                        } else {
                            throw e
                        }
                    }
                }
                _uiState.value = AiResponseState.Success(resultText)
            } catch (e: Exception) {
                val errorMessage = when {
                    e.message?.contains("API_KEY_INVALID", ignoreCase = true) == true ||
                    e.message?.contains("API key not valid", ignoreCase = true) == true ->
                        "Invalid Gemini API Key. Please verify your key in AI Studio Secrets."
                    e.message?.contains("QUOTA_EXCEEDED", ignoreCase = true) == true ->
                        "Gemini API quota exceeded. Please try again later."
                    e.message?.contains("Unable to resolve host", ignoreCase = true) == true ->
                        "Network connection error. Please check your internet connection."
                    else -> e.localizedMessage ?: "Failed to generate AI response. Please try again."
                }
                _uiState.value = AiResponseState.Error(errorMessage)
            }
        }
    }
}
