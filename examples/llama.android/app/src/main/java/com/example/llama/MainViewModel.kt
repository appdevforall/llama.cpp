package com.example.llama

import android.app.Application
import android.app.DownloadManager
import android.content.Context
import android.database.Cursor
import android.llama.cpp.LLamaAndroid
import android.net.Uri
import android.provider.OpenableColumns
import android.util.Log
import androidx.core.database.getLongOrNull
import androidx.core.net.toUri
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.LiveData
import androidx.lifecycle.MutableLiveData
import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.viewModelScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.reduce
import kotlinx.coroutines.launch
import org.json.JSONObject
import java.io.File
import java.io.FileOutputStream
import java.util.concurrent.atomic.AtomicLong
import java.util.regex.Pattern

class MainViewModelFactory(private val application: Application) : ViewModelProvider.Factory {
    override fun <T : ViewModel> create(modelClass: Class<T>): T {
        if (modelClass.isAssignableFrom(MainViewModel::class.java)) {
            // This is where we manually call our ViewModel's constructor
            // with all the required parameters.
            @Suppress("UNCHECKED_CAST")
            return MainViewModel(application, LLamaAndroid.instance()) as T
        }
        throw IllegalArgumentException("Unknown ViewModel class")
    }
}

private data class ToolCall(val toolName: String, val args: Map<String, Any>)

// THE NEW SYSTEM PROMPT
private const val SYSTEM_PROMPT = """
You are a helpful and smart assistant integrated into an Android application.
You have access to the following tools to get real-time information. Do not make up information for these tools.

[AVAILABLE_TOOLS]

To use a tool, you must respond with a JSON object inside a special <tool_call> tag. Your response should contain nothing else.
The JSON object must have "tool_name" and "args" keys.
"args" must be an object containing the arguments for the tool. If no arguments are needed, use an empty object {}.

Example of a tool call:
<tool_call>
{
  "tool_name": "get_current_datetime",
  "args": {}
}
</tool_call>

After the tool is called, you will receive the result and you must use it to answer the user's original question.
"""

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
    data class Downloading(val progress: Int) : DownloadUiState
    data object Downloaded : DownloadUiState
    data class Error(val message: String) : DownloadUiState
}

class MainViewModel(
    application: Application,
    private val llamaAndroid: LLamaAndroid = LLamaAndroid.instance()
) : AndroidViewModel(application) {
    private val messageIdCounter = AtomicLong(0)
    private val _contextSize = MutableLiveData(0)
    val contextSize: LiveData<Int> get() = _contextSize
    private val tools: Map<String, Tool> = listOf(
        BatteryTool(),
        GetDateTimeTool()
    ).associateBy { it.name }

    var conversation = listOf<UiMessage>()

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

    private val masterSystemPrompt: String by lazy {
        val toolDescriptions = tools.values.joinToString("\n") { "- ${it.name}: ${it.description}" }
        SYSTEM_PROMPT.replace("[AVAILABLE_TOOLS]", toolDescriptions)
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
        val message = UiMessage(messageIdCounter.getAndIncrement(), text, type)
        val currentMessages = _uiMessages.value.orEmpty().toMutableList()

        // Simple add logic for now, as streaming the final answer would require more state
        currentMessages.add(message)

        conversation = conversation + message
        _uiMessages.postValue(currentMessages)
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

        addUiMessage(text, MessageType.USER)

        // Start the agent loop
        viewModelScope.launch {
            runAgentLoop(text)
        }
    }

    private suspend fun runAgentLoop(initialUserMessage: String, maxTurns: Int = 5) {
        var currentTurn = 0
        var lastModelResponse = ""

        while (currentTurn < maxTurns) {
            val prompt = buildPromptWithHistory()
            Log.d("AgentLoop", "Turn ${currentTurn + 1}, sending prompt:\n$prompt")

            // For agent reasoning, we process the full response at once.
            val modelResponse = try {
                llamaAndroid.send(prompt).reduce { acc, s -> acc + s }
            } catch (e: Exception) {
                "Error: Could not get a response from the model."
            }

            // The model might output its reasoning before the tool call. Display it.
            // If streaming is on, we stream the *final* answer later.
            if (currentTurn > 0) {
                addUiMessage(modelResponse, MessageType.MODEL)
            } else {
                // For the first turn, create the initial placeholder
                addUiMessage(modelResponse, MessageType.MODEL)
            }
            lastModelResponse = modelResponse

            val toolCall = parseToolCall(modelResponse)
            if (toolCall != null) {
                Log.d("AgentLoop", "Tool call detected: $toolCall")
                val tool = tools[toolCall.toolName]
                if (tool != null) {
                    val result = tool.execute(getApplication(), toolCall.args)
                    // Add tool result to history as a system message
                    addUiMessage(result, MessageType.SYSTEM)
                } else {
                    addUiMessage(
                        "Error: Model tried to call unknown tool '${toolCall.toolName}'",
                        MessageType.SYSTEM
                    )
                    break // Exit loop if tool is invalid
                }
            } else {
                Log.d("AgentLoop", "No tool call detected. Concluding.")
                break // No tool call found, this is the final answer
            }
            currentTurn++
        }

        // If the loop finished and the last response was a tool call, get a final answer.
        if (parseToolCall(lastModelResponse) != null) {
            val finalPrompt = buildPromptWithHistory()
            val finalAnswer = try {
                llamaAndroid.send(finalPrompt).reduce { acc, s -> acc + s }
            } catch (e: Exception) {
                "Done."
            }
            addUiMessage(finalAnswer, MessageType.MODEL)
        }
    }

    private fun parseToolCall(text: String): ToolCall? {
        val pattern = Pattern.compile("<tool_call>(.*?)</tool_call>", Pattern.DOTALL)
        val matcher = pattern.matcher(text)
        if (matcher.find()) {
            val jsonStr = matcher.group(1)?.trim()

            // FIX 1: Add a null/blank check for the extracted string
            if (jsonStr.isNullOrBlank()) {
                Log.e("ToolParse", "Found tool_call tags but the content was empty.")
                return null
            }

            try {
                // Now jsonStr is guaranteed to be a non-null String
                val json = JSONObject(jsonStr)
                val toolName = json.getString("tool_name")
                val argsJson = json.getJSONObject("args")
                val argsMap = mutableMapOf<String, Any>()
                argsJson.keys().forEach { key ->
                    argsMap[key] = argsJson.get(key)
                }
                // FIX 2: This now matches the updated ToolCall data class
                return ToolCall(toolName, argsMap)
            } catch (e: Exception) {
                Log.e("ToolParse", "Failed to parse tool call JSON", e)
                return null
            }
        }
        return null
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

    private fun buildPromptWithHistory(): String {
        // Prepend the detailed system prompt
        val historyString = conversation.joinToString(separator = "\n") {
            "[${it.type}] ${it.text}"
        }
        return "$masterSystemPrompt\n$historyString"
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
