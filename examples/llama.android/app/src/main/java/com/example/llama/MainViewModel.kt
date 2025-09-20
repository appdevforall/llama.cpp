package com.example.llama

import android.llama.cpp.LLamaAndroid
import android.util.Log
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.setValue
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import kotlinx.coroutines.flow.catch
import kotlinx.coroutines.launch

class MainViewModel(private val llamaAndroid: LLamaAndroid = LLamaAndroid.instance()): ViewModel() {

    // ADD THIS to store the model's context size
    var contextSize by mutableIntStateOf(0)
        private set

    var conversation by mutableStateOf(listOf<String>())
        private set

    // ADD THIS: A new list for all UI messages, including logs
    var uiMessages by mutableStateOf(listOf("Initializing..."))
        private set

    companion object {
        @JvmStatic
        private val NanosPerSecond = 1_000_000_000.0
        private const val CONTEXT_RESERVATION_PERCENT = 0.4
    }

    private val tag: String? = this::class.simpleName

    var message by mutableStateOf("")
        private set

    override fun onCleared() {
        super.onCleared()

        viewModelScope.launch {
            try {
                llamaAndroid.unload()
            } catch (exc: IllegalStateException) {
                messages += exc.message!!
            }
        }
    }

    fun send() {
        val text = message
        if (text.isBlank()) return

        message = ""

        // Add user message to the UI immediately
        messages += text
        messages += "" // Placeholder for the bot's reply

        viewModelScope.launch {
            try {
                // First, check if even the new message alone is too long
                val singleMessageTokens = llamaAndroid.tokenize(text)
                val maxPromptTokens = (contextSize * (1.0 - CONTEXT_RESERVATION_PERCENT)).toInt()

                if (contextSize > 0 && singleMessageTokens.size >= maxPromptTokens) {
                    messages = messages.dropLast(1) + "Error: Your message is too long to process. Please shorten it."
                    Log.e(tag, "Single message is too long. Tokens: ${singleMessageTokens.size}, Max: $maxPromptTokens")
                    return@launch
                }

                // Build the final prompt with history
                val finalPrompt = buildPromptWithHistory(text)

                // Send the truncated, context-aware prompt to the model
                llamaAndroid.send(finalPrompt)
                    .catch {
                        Log.e(tag, "send() failed", it)
                        messages = messages.dropLast(1) + (it.message ?: "An unknown error occurred.")
                    }
                    .collect { messages = messages.dropLast(1) + (messages.last() + it) }

            } catch (e: IllegalStateException) {
                messages = messages.dropLast(1) + "Error: Model not loaded. Cannot send message."
            }
        }
    }

    fun bench(pp: Int, tg: Int, pl: Int, nr: Int = 1) {
        viewModelScope.launch {
            try {
                val start = System.nanoTime()
                val warmupResult = llamaAndroid.bench(pp, tg, pl, nr)
                val end = System.nanoTime()

                uiMessages += warmupResult

                val warmup = (end - start).toDouble() / NanosPerSecond
                uiMessages += "Warm up time: $warmup seconds, please wait..."

                if (warmup > 5.0) {
                    uiMessages += "Warm up took too long, aborting benchmark"
                    return@launch
                }

                uiMessages += llamaAndroid.bench(512, 128, 1, 3)
            } catch (exc: IllegalStateException) {
                Log.e(tag, "bench() failed", exc)
                uiMessages += exc.message!!
            }
        }
    }

    fun load(pathToModel: String) {
        viewModelScope.launch {
            try {
                llamaAndroid.load(pathToModel)
                uiMessages += "Loaded $pathToModel" // <-- Change to uiMessages
                contextSize = llamaAndroid.getContextSize()
                uiMessages += "Model context size: $contextSize tokens" // <-- Change to uiMessages
            } catch (exc: IllegalStateException) {
                Log.e(tag, "load() failed", exc)
                uiMessages += exc.message!! // <-- Change to uiMessages
            }
        }
    }


    fun updateMessage(newMessage: String) {
        message = newMessage
    }

    fun clear() {
        conversation = listOf()
        uiMessages = listOf()
    }

    // Your log function should ONLY write to the UI list
    fun log(message: String) {
        uiMessages += message
    }

    private suspend fun buildPromptWithHistory(newUserMessage: String): String {
        // Calculate the maximum number of tokens allowed for the prompt
        val promptTokenBudget = (contextSize * (1.0 - CONTEXT_RESERVATION_PERCENT)).toInt()

        val conversationHistory = mutableListOf<String>()
        var totalTokens = 0

        // 1. Tokenize and add the new user message first
        val newUserMessageTokens = llamaAndroid.tokenize(newUserMessage)
        totalTokens += newUserMessageTokens.size
        conversationHistory.add(newUserMessage)

        // 2. Add previous conversation in reverse order (most recent first)
        // We skip the last two empty conversation added in the send() function
        val history = conversation.dropLast(if (conversation.last().isBlank()) 2 else 0)
        for (message in conversation.reversed()) {
            if (message.isBlank()) continue

            val messageTokens = llamaAndroid.tokenize(message)

            // If adding the next message exceeds the budget, stop
            if (totalTokens + messageTokens.size > promptTokenBudget) {
                break
            }

            // Otherwise, add it to the start of our history list
            totalTokens += messageTokens.size
            conversationHistory.add(0, message)
        }

        Log.i(tag, "Prompt built with $totalTokens tokens, using ${conversationHistory.size} messages.")

        // 3. Join the selected messages into a single prompt string
        return conversationHistory.joinToString(separator = "\n")
    }
}
