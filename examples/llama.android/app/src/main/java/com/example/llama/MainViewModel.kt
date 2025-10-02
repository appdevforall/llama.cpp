package com.example.llama

import android.app.DownloadManager
import android.content.Context
import android.database.Cursor
import android.llama.cpp.LLamaAndroid
import android.net.Uri
import android.provider.OpenableColumns
import android.util.Log
import androidx.core.database.getLongOrNull
import androidx.core.net.toUri
import androidx.lifecycle.LiveData
import androidx.lifecycle.MutableLiveData
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.catch
import kotlinx.coroutines.flow.reduce
import kotlinx.coroutines.launch
import java.io.File
import java.io.FileOutputStream
import java.util.concurrent.atomic.AtomicLong

enum class MessageType {
    SYSTEM, USER, MODEL
}

data class UiMessage(
    val id: Long,
    val text: String,
    val type: MessageType
)

sealed interface DownloadUiState {
    data object Ready : DownloadUiState
    data class Downloading(val progress: Int) : DownloadUiState // Progress as Int 0-100
    data object Downloaded : DownloadUiState
    data class Error(val message: String) : DownloadUiState
}

class MainViewModel(private val llamaAndroid: LLamaAndroid = LLamaAndroid.instance()) : ViewModel() {
    private val messageIdCounter = AtomicLong(0)
    private val _contextSize = MutableLiveData(0)
    val contextSize: LiveData<Int> get() = _contextSize

    var conversation = listOf<String>()
        private set

    private val _uiMessages = MutableLiveData<List<UiMessage>>(
        listOf(
            UiMessage(
                id = messageIdCounter.getAndIncrement(),
                text = "Initializing...",
                type = MessageType.SYSTEM
            )
        )
    )
    val uiMessages: LiveData<List<UiMessage>> get() = _uiMessages

    var isStreamingEnabled = true
        private set

    fun setStreaming(isEnabled: Boolean) {
        isStreamingEnabled = isEnabled
    }

    private val _savedModelUri = MutableLiveData<Uri?>(null)
    val savedModelUri: LiveData<Uri?> get() = _savedModelUri

    private val _modelStates = MutableLiveData<Map<String, DownloadUiState>>(emptyMap())
    val modelStates: LiveData<Map<String, DownloadUiState>> get() = _modelStates


    companion object {
        @JvmStatic
        private val NanosPerSecond = 1_000_000_000.0
        private const val CONTEXT_RESERVATION_PERCENT = 0.4
    }

    private val tag: String? = this::class.simpleName

    var message: String = ""

    private fun addUiMessage(text: String, type: MessageType) {
        val message = UiMessage(id = messageIdCounter.getAndIncrement(), text = text, type = type)
        _uiMessages.value = _uiMessages.value.orEmpty() + message
    }

    private fun updateLastUiMessage(updatedText: String) {
        val currentMessages = _uiMessages.value.orEmpty()
        if (currentMessages.isNotEmpty()) {
            val lastMessage = currentMessages.last()
            val updatedMessage = lastMessage.copy(text = updatedText)
            _uiMessages.value = currentMessages.dropLast(1) + updatedMessage
        }
    }

    fun initializeModelStates(models: List<Downloadable>) {
        val initialState = models.associate { model ->
            val state = if (model.destination.exists()) DownloadUiState.Downloaded else DownloadUiState.Ready
            model.name to state
        }
        _modelStates.value = initialState
    }


    override fun onCleared() {
        super.onCleared()
        viewModelScope.launch {
            try {
                llamaAndroid.unload()
            } catch (exc: IllegalStateException) {
                addUiMessage(exc.message!!, MessageType.SYSTEM)
            }
        }
    }

    private fun getFileName(context: Context, uri: Uri): String {
        var result: String? = null
        if (uri.scheme == "content") {
            val cursor: Cursor? = context.contentResolver.query(uri, null, null, null, null)
            try {
                if (cursor != null && cursor.moveToFirst()) {
                    val colIndex = cursor.getColumnIndex(OpenableColumns.DISPLAY_NAME)
                    if (colIndex >= 0) {
                        result = cursor.getString(colIndex)
                    }
                }
            } finally {
                cursor?.close()
            }
        }
        if (result == null) {
            result = uri.path
            val cut = result?.lastIndexOf('/')
            if (cut != null && cut != -1) {
                result = result?.substring(cut + 1)
            }
        }
        return result?.ifBlank { "temp_model.gguf" } ?: "temp_model.gguf"
    }

    fun loadModelFromUri(uri: Uri, context: Context) {
        viewModelScope.launch {
            try {
                val fileName = getFileName(context, uri)
                log("Preparing to copy '$fileName' from storage...")
                val destinationFile = File(context.cacheDir, fileName)

                context.contentResolver.openInputStream(uri)?.use { inputStream ->
                    FileOutputStream(destinationFile).use { outputStream ->
                        inputStream.copyTo(outputStream)
                    }
                }

                log("Model copied to cache. Loading...")
                load(destinationFile.path)

            } catch (e: Exception) {
                Log.e(tag, "Failed to load model from URI", e)
                log("Error loading model from file: ${e.message}")
            }
        }
    }

    fun send() {
        val text = message
        if (text.isBlank()) return
        message = ""

        conversation += text
        addUiMessage(text, MessageType.USER)

        val placeholderText = if (isStreamingEnabled) "" else "..."
        addUiMessage(placeholderText, MessageType.MODEL)

        viewModelScope.launch {
            try {
                val singleMessageTokens = llamaAndroid.tokenize(text)
                val maxPromptTokens = (_contextSize.value ?: 0 * (1.0 - CONTEXT_RESERVATION_PERCENT)).toInt()

                if ((_contextSize.value ?: 0) > 0 && singleMessageTokens.size >= maxPromptTokens) {
                    updateLastUiMessage("Error: Your message is too long to process. Please shorten it.")
                    Log.e(tag, "Single message is too long. Tokens: ${singleMessageTokens.size}, Max: $maxPromptTokens")
                    return@launch
                }

                val finalPrompt = buildPromptWithHistory(text)

                if (isStreamingEnabled) {
                    llamaAndroid.send(finalPrompt)
                        .catch { e ->
                            Log.e(tag, "send() failed", e)
                            updateLastUiMessage(e.message ?: "An unknown error occurred.")
                        }
                        .collect { newTextChunk ->
                            val currentLastText = _uiMessages.value?.lastOrNull()?.text ?: ""
                            updateLastUiMessage(currentLastText + newTextChunk)
                        }
                } else {
                    try {
                        val fullResponse = llamaAndroid.send(finalPrompt)
                            .reduce { accumulator, value -> accumulator + value }
                        updateLastUiMessage(fullResponse)
                    } catch (e: NoSuchElementException) {
                        updateLastUiMessage("Agent returned an empty response.")
                    } catch (e: Exception) {
                        Log.e(tag, "send() [non-streaming] failed", e)
                        updateLastUiMessage(e.message ?: "An unknown error occurred.")
                    }
                }

            } catch (e: IllegalStateException) {
                updateLastUiMessage("Error: Model not loaded.")
            }
        }
    }

    fun bench(pp: Int, tg: Int, pl: Int, nr: Int = 1) {
        viewModelScope.launch {
            try {
                val start = System.nanoTime()
                val warmupResult = llamaAndroid.bench(pp, tg, pl, nr)
                val end = System.nanoTime()

                addUiMessage(warmupResult, MessageType.MODEL)

                val warmup = (end - start).toDouble() / NanosPerSecond
                addUiMessage("Warm up time: $warmup seconds, please wait...", MessageType.SYSTEM)

                if (warmup > 5.0) {
                    addUiMessage("Warm up took too long, aborting benchmark", MessageType.SYSTEM)
                    return@launch
                }

                addUiMessage(llamaAndroid.bench(512, 128, 1, 3), MessageType.SYSTEM)
            } catch (exc: IllegalStateException) {
                Log.e(tag, "bench() failed", exc)
                addUiMessage(exc.message!!, MessageType.SYSTEM)
            }
        }
    }

    fun load(pathToModel: String) {
        viewModelScope.launch {
            try {
                llamaAndroid.load(pathToModel)
                addUiMessage("Loaded $pathToModel", MessageType.SYSTEM)
                _contextSize.value = llamaAndroid.getContextSize()
                addUiMessage("Model context size: ${contextSize.value} tokens", MessageType.SYSTEM)
            } catch (exc: IllegalStateException) {
                Log.e(tag, "load() failed", exc)
                addUiMessage(exc.message!!, MessageType.SYSTEM)
            }
        }
    }

    fun updateMessage(newMessage: String) {
        message = newMessage
    }

    fun clear() {
        conversation = listOf()
        _uiMessages.value = listOf()
    }

    fun log(message: String) {
        addUiMessage(message, MessageType.SYSTEM)
    }

    private suspend fun buildPromptWithHistory(newUserMessage: String): String {
        val promptTokenBudget = ((contextSize.value ?: 0) * (1.0 - CONTEXT_RESERVATION_PERCENT)).toInt()
        val conversationHistory = mutableListOf<String>()
        var totalTokens = 0
        val newUserMessageTokens = llamaAndroid.tokenize(newUserMessage)
        totalTokens += newUserMessageTokens.size
        conversationHistory.add(newUserMessage)
        val history = conversation.dropLast(if (conversation.last().isBlank()) 2 else 0)
        for (message in conversation.reversed()) {
            if (message.isBlank()) continue
            val messageTokens = llamaAndroid.tokenize(message)
            if (totalTokens + messageTokens.size > promptTokenBudget) {
                break
            }
            totalTokens += messageTokens.size
            conversationHistory.add(0, message)
        }
        Log.i(tag, "Prompt built with $totalTokens tokens, using ${conversationHistory.size} messages.")
        return conversationHistory.joinToString(separator = "\n")
    }

    fun onDownloadableClicked(item: Downloadable, dm: DownloadManager) {
        val currentState = _modelStates.value?.get(item.name)
        when (currentState) {
            is DownloadUiState.Downloaded -> {
                load(item.destination.path)
            }
            is DownloadUiState.Ready, is DownloadUiState.Error, null -> {
                startDownload(item, dm)
            }
            is DownloadUiState.Downloading -> {
                // Already downloading, do nothing
            }
        }
    }

    private fun startDownload(item: Downloadable, dm: DownloadManager) {
        item.destination.delete()
        val request = DownloadManager.Request(item.source).apply {
            setTitle("Downloading model")
            setDescription("Downloading model: ${item.name}")
            setAllowedNetworkTypes(DownloadManager.Request.NETWORK_WIFI)
            setDestinationUri(item.destination.toUri())
        }
        log("Saving ${item.name} to ${item.destination.path}")
        val id = dm.enqueue(request)

        viewModelScope.launch {
            monitorDownload(item, id, dm)
        }
    }

    private suspend fun monitorDownload(item: Downloadable, downloadId: Long, dm: DownloadManager) {
        while (true) {
            val cursor = dm.query(DownloadManager.Query().setFilterById(downloadId))
            if (cursor == null) {
                updateModelState(item.name, DownloadUiState.Error("Download query returned null"))
                return
            }
            if (!cursor.moveToFirst() || cursor.count < 1) {
                cursor.close()
                updateModelState(item.name, DownloadUiState.Ready) // Assume canceled
                return
            }

            val pix = cursor.getColumnIndex(DownloadManager.COLUMN_BYTES_DOWNLOADED_SO_FAR)
            val tix = cursor.getColumnIndex(DownloadManager.COLUMN_TOTAL_SIZE_BYTES)
            val statusIndex = cursor.getColumnIndex(DownloadManager.COLUMN_STATUS)

            val sofar = cursor.getLongOrNull(pix) ?: 0
            val total = cursor.getLongOrNull(tix) ?: 1
            val status = cursor.getInt(statusIndex)
            cursor.close()

            if (status == DownloadManager.STATUS_SUCCESSFUL || sofar == total) {
                updateModelState(item.name, DownloadUiState.Downloaded)
                return
            }

            if (status == DownloadManager.STATUS_FAILED) {
                updateModelState(item.name, DownloadUiState.Error("Download failed"))
                return
            }

            val progress = ((sofar * 100.0) / total).toInt()
            updateModelState(item.name, DownloadUiState.Downloading(progress))

            delay(1000L)
        }
    }

    private fun updateModelState(modelName: String, state: DownloadUiState) {
        val currentStates = _modelStates.value.orEmpty().toMutableMap()
        currentStates[modelName] = state
        _modelStates.postValue(currentStates)
    }

    fun checkInitialSavedModel(context: Context) {
        val prefs = context.getSharedPreferences("LlamaPrefs", Context.MODE_PRIVATE)
        val savedUriString = prefs.getString(SAVED_MODEL_URI_KEY, null)
        if (savedUriString != null) {
            _savedModelUri.value = Uri.parse(savedUriString)
        }
    }

    fun onNewModelSelected(uri: Uri?) {
        _savedModelUri.value = uri
    }
}
