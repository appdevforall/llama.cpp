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
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.reduce
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
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

    private fun addMessage(text: String, type: MessageType) {
        val message = UiMessage(messageIdCounter.getAndIncrement(), text, type)
        conversation = conversation + message
        _uiMessages.postValue(conversation)
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
                addMessage(exc.message!!, MessageType.SYSTEM)
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
                val destinationFile = withContext(Dispatchers.IO) {
                    val fileName = getFileName(context, uri)
                    val dest = File(context.cacheDir, fileName)
                    context.contentResolver.openInputStream(uri)?.use { inputStream ->
                        FileOutputStream(dest).use { outputStream ->
                            inputStream.copyTo(outputStream)
                        }
                    }
                    dest
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

        // <<< LOG 1: The initial user input >>>
        Log.d("AgentDebug", "--- NEW REQUEST ---")
        Log.d("AgentDebug", "User Input: \"$text\"")

        addMessage(text, MessageType.USER)

        val placeholder = if (isStreamingEnabled) "" else "..."
        addMessage(placeholder, MessageType.MODEL)

        viewModelScope.launch {
            llamaAndroid.clearKvCache()
            runAgentLoop()
        }
    }

    private suspend fun runAgentLoop(maxTurns: Int = 5) {
        var currentTurn = 0

        while (currentTurn < maxTurns) {
            val isFinalAnswerTurn = currentTurn > 0
            val prompt: String

            // --- Construct the prompt for the current turn ---
            if (currentTurn == 0) {
                prompt = buildPromptWithHistory(conversation)
                Log.d("AgentLoop", "Turn 1: Sending full prompt.")
            } else {
                // On SUBSEQUENT turns, the history is in the KV cache.
                // We ONLY send the last two messages, formatted correctly, WITHOUT any system headers.
                val lastModelMsg = conversation[conversation.size - 2]
                val lastSystemMsg = conversation.last()

                prompt = """
                <|start_header_id|>assistant<|end_header_id|>

                ${lastModelMsg.text}<|start_header_id|>user<|end_header_id|>

                [TOOL_RESULT]
                ${lastSystemMsg.text}
                [/TOOL_RESULT]<|eot_id|>
            """.trimIndent() + "\n" + // Add the final instruction
                    """
                <|start_header_id|>assistant<|end_header_id|>

                Based on the tool results, here is the answer to the user's question:
            """.trimIndent()

                Log.d("AgentLoop", "Turn ${currentTurn + 1}: Sending incremental prompt.")
            }

            // <<< LOG 2: The exact prompt being sent to the model >>>
            Log.d("AgentDebug", "--- [Step ${currentTurn + 1}] ---")
            Log.d("AgentDebug", "Final Prompt Sent:\n$prompt")

            // Stop tokens are crucial for controlling the output.
            val stopStrings = if (isFinalAnswerTurn) {
                listOf("<|eot_id|>")
            } else {
                listOf("<|eot_id|>", "</tool_call>")
            }

            // --- Get model response ---
            val modelResponse = try {
                withContext(Dispatchers.IO) {
                    llamaAndroid.send(prompt, stop = stopStrings).reduce { acc, s -> acc + s }
                }
            } catch (e: Exception) {
                Log.e("AgentLoop", "Model inference failed", e)
                "Error: Could not get a response from the model."
            }

            // <<< LOG 3: The raw result from the model >>>
            Log.d("AgentDebug", "Raw Model Result: \"$modelResponse\"")

            // --- Process and update UI ---
            val completeModelResponse =
                modelResponse + if (stopStrings.contains("<|eot_id|>")) "<|eot_id|>" else ""
            updateLastMessage(completeModelResponse)

            // --- Check for tool calls ---
            val toolCall = parseToolCall(completeModelResponse)
            if (toolCall != null) {
                Log.d("AgentDebug", "Tool Call Detected: $toolCall")
                val tool = tools[toolCall.toolName]
                if (tool != null) {
                    val result = tool.execute(getApplication(), toolCall.args)

                    // <<< LOG 4: The result from the executed tool >>>
                    Log.d("AgentDebug", "Tool Response: \"$result\"")

                    addMessage(result, MessageType.SYSTEM)
                } else {
                    val errorMsg = "Error: Model tried to call unknown tool '${toolCall.toolName}'"
                    addMessage(errorMsg, MessageType.SYSTEM)
                    Log.e("AgentDebug", errorMsg)
                    break
                }
            } else {
                // <<< LOG 5: The final conclusion >>>
                Log.d("AgentDebug", "No tool call detected. Concluding.")
                break
            }
            currentTurn++
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

                addMessage(warmupResult, MessageType.MODEL)

                val warmup = (end - start).toDouble() / NanosPerSecond
                addMessage("Warm up time: $warmup seconds, please wait...", MessageType.SYSTEM)

                if (warmup > 5.0) {
                    addMessage("Warm up took too long, aborting benchmark", MessageType.SYSTEM)
                    return@launch
                }

                addMessage(llamaAndroid.bench(512, 128, 1, 3), MessageType.SYSTEM)
            } catch (exc: IllegalStateException) {
                Log.e(tag, "bench() failed", exc)
                addMessage(exc.message!!, MessageType.SYSTEM)
            }
        }
    }

    fun load(pathToModel: String) {
        viewModelScope.launch {
            try {
                withContext(Dispatchers.IO) {
                    llamaAndroid.load(pathToModel)
                }
                log("Loaded $pathToModel")
                val contextSize = llamaAndroid.getContextSize()
                log("Model context size: $contextSize tokens")
            } catch (exc: IllegalStateException) {
                Log.e("ModelLoad", "load() failed", exc)
                log(exc.message!!)
            }
        }
    }

    private fun updateLastMessage(updatedText: String) {
        if (conversation.isNotEmpty()) {
            val lastMessage = conversation.last()
            val updatedMessage = lastMessage.copy(text = updatedText)
            conversation = conversation.dropLast(1) + updatedMessage
            _uiMessages.postValue(conversation)
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
        addMessage(message, MessageType.SYSTEM)
    }

    private fun buildPromptWithHistory(history: List<UiMessage>): String {
        val historyBuilder = StringBuilder()

        // Add the special begin_of_text token ONLY at the start.
        historyBuilder.append("<|begin_of_text|>")
        historyBuilder.append("<|start_header_id|>system<|end_header_id|>\n\n$masterSystemPrompt<|eot_id|>")

        for (message in history) {
            when (message.type) {
                MessageType.USER -> {
                    historyBuilder.append("<|start_header_id|>user<|end_header_id|>\n\n${message.text}<|eot_id|>")
                }
                // The placeholder is the last message, so we don't append it to the prompt.
                MessageType.MODEL -> {
                    if (message.text.isNotBlank()) {
                        historyBuilder.append("<|start_header_id|>assistant<|end_header_id|>\n\n${message.text}")
                    }
                }
                // There are no SYSTEM messages in the initial turn.
                MessageType.SYSTEM -> {}
            }
        }

        // Prompt the assistant to start its turn.
        historyBuilder.append("<|start_header_id|>assistant<|end_header_id|>\n\n")

        return historyBuilder.toString()
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
