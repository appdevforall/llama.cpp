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

    companion object {
        @JvmStatic
        private val NanosPerSecond = 1_000_000_000.0
    }

    private val tag: String? = this::class.simpleName

    var messages by mutableStateOf(listOf("Initializing..."))
        private set

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
        if (text.isBlank()) return // Don't send empty messages

        message = ""

        viewModelScope.launch {
            // ADD THIS: Token validation logic
            try {
                val tokens = llamaAndroid.tokenize(text)
                // Leave a buffer (e.g., 25%) for the model's response
                val maxPromptTokens = (contextSize * 0.75).toInt()

                if (tokens.size >= maxPromptTokens) {
                    messages += "Error: Prompt is too long. Please shorten your message."
                    Log.e(tag, "Prompt too long. Tokens: ${tokens.size}, Max allowed: $maxPromptTokens")
                    return@launch
                }
            } catch (e: IllegalStateException) {
                messages += "Error: Model not loaded. Cannot send message."
                return@launch
            }

            // Your existing send logic
            messages += text
            messages += ""

            llamaAndroid.send(text)
                .catch {
                    Log.e(tag, "send() failed", it)
                    messages += it.message!!
                }
                .collect { messages = messages.dropLast(1) + (messages.last() + it) }
        }
    }

    fun bench(pp: Int, tg: Int, pl: Int, nr: Int = 1) {
        viewModelScope.launch {
            try {
                val start = System.nanoTime()
                val warmupResult = llamaAndroid.bench(pp, tg, pl, nr)
                val end = System.nanoTime()

                messages += warmupResult

                val warmup = (end - start).toDouble() / NanosPerSecond
                messages += "Warm up time: $warmup seconds, please wait..."

                if (warmup > 5.0) {
                    messages += "Warm up took too long, aborting benchmark"
                    return@launch
                }

                messages += llamaAndroid.bench(512, 128, 1, 3)
            } catch (exc: IllegalStateException) {
                Log.e(tag, "bench() failed", exc)
                messages += exc.message!!
            }
        }
    }

    fun load(pathToModel: String) {
        viewModelScope.launch {
            try {
                llamaAndroid.load(pathToModel)
                messages += "Loaded $pathToModel"

                // ADD THIS: Get and store the context size after loading
                contextSize = llamaAndroid.getContextSize()
                messages += "Model context size: $contextSize tokens"

            } catch (exc: IllegalStateException) {
                Log.e(tag, "load() failed", exc)
                messages += exc.message!!
            }
        }
    }

    fun updateMessage(newMessage: String) {
        message = newMessage
    }

    fun clear() {
        messages = listOf()
    }

    fun log(message: String) {
        messages += message
    }
}
