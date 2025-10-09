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
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.reduce
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import org.json.JSONException
import org.json.JSONObject
import java.io.File
import java.io.FileOutputStream
import java.util.concurrent.atomic.AtomicLong
import java.util.regex.Pattern

class MainViewModelFactory(private val application: Application) : ViewModelProvider.Factory {
    override fun <T : ViewModel> create(modelClass: Class<T>): T {
        if (modelClass.isAssignableFrom(MainViewModel::class.java)) {
            @Suppress("UNCHECKED_CAST")
            // Pass default dispatchers for production code
            return MainViewModel(
                application,
                LLamaAndroid.instance(),
                Dispatchers.Main,
                Dispatchers.IO
            ) as T
        }
        throw IllegalArgumentException("Unknown ViewModel class")
    }
}

private data class ToolCall(val toolName: String, val args: Map<String, Any>)

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
    SYSTEM, USER, MODEL, TOOL_RESULT
}

data class UiMessage(
    val id: Long,
    val text: String,
    val type: MessageType,
    val timestamp: Long = System.currentTimeMillis(),
    val durationMs: Long? = null
)

sealed interface DownloadUiState {
    data object Ready : DownloadUiState
    data class Downloading(val progress: Int) : DownloadUiState
    data object Downloaded : DownloadUiState
    data class Error(val message: String) : DownloadUiState
}

enum class ModelFamily {
    LLAMA3,
    GEMMA2,
    UNKNOWN
}

class MainViewModel(
    application: Application,
    private val llamaAndroid: LLamaAndroid = LLamaAndroid.instance(),
    // Add dispatchers to the constructor
    private val mainDispatcher: CoroutineDispatcher = Dispatchers.Main,
    private val ioDispatcher: CoroutineDispatcher = Dispatchers.IO
) : AndroidViewModel(application) {

    private val customScope = CoroutineScope(SupervisorJob() + mainDispatcher)

    private val messageIdCounter = AtomicLong(0)
    private val _contextSize = MutableLiveData(0)
    val contextSize: LiveData<Int> get() = _contextSize
    private val tools: Map<String, Tool> = listOf(
        BatteryTool(),
        GetDateTimeTool()
    ).associateBy { it.name }

    private var currentModelFamily: ModelFamily = ModelFamily.UNKNOWN

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

    var isToolUseEnabled = true
        private set

    fun setStreaming(isEnabled: Boolean) {
        isStreamingEnabled = isEnabled
    }

    fun setToolUse(isEnabled: Boolean) {
        isToolUseEnabled = isEnabled
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
            val state =
                if (model.destination.exists()) DownloadUiState.Downloaded else DownloadUiState.Ready
            model.name to state
        }
        _modelStates.value = initialState
    }


    override fun onCleared() {
        super.onCleared()
        customScope.launch(mainDispatcher) {
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
        customScope.launch(mainDispatcher) {
            try {
                val destinationFile = withContext(ioDispatcher) {
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

        Log.d("ViewModelSend", "--- NEW REQUEST ---")
        Log.d("ViewModelSend", "User Input: \"$text\"")

        addMessage(text, MessageType.USER)

        val placeholder = if (isStreamingEnabled) "" else "..."
        addMessage(placeholder, MessageType.MODEL)

        customScope.launch(mainDispatcher) {
            llamaAndroid.clearKvCache()
            if (isToolUseEnabled) {
                runAgentLoop()
            } else {
                runSimpleInference(text)
            }
        }
    }

    private fun updateLastMessageDuration(durationMs: Long) {
        if (conversation.isNotEmpty()) {
            val lastMessage = conversation.last()
            if (lastMessage.type == MessageType.MODEL) {
                val updatedMessage = lastMessage.copy(durationMs = durationMs)
                conversation = conversation.dropLast(1) + updatedMessage
                _uiMessages.postValue(conversation)
            }
        }
    }

    private suspend fun runSimpleInference(prompt: String) {
        Log.d("SimpleInference", "Running simple inference with prompt: \"$prompt\"")
        val startTime = System.nanoTime()
        try {
            withContext(ioDispatcher) {
                if (isStreamingEnabled) {
                    var currentText = ""
                    llamaAndroid.send(prompt).collect { responseChunk ->
                        currentText += responseChunk
                        updateLastMessage(currentText)
                    }
                } else {
                    val modelResponse = llamaAndroid.send(prompt)
                        .reduce { acc, s -> acc + s }
                    updateLastMessage(modelResponse)
                }
            }
            val durationMs = (System.nanoTime() - startTime) / 1_000_000
            updateLastMessageDuration(durationMs)
            Log.d("SimpleInference", "Simple inference complete.")
        } catch (e: Exception) {
            val errorMsg = "Error: Could not get a response from the model."
            Log.e("SimpleInference", "Model inference failed", e)
            updateLastMessage(errorMsg)
        }
    }

    private suspend fun runAgentLoop(maxTurns: Int = 5) {
        var currentTurn = 0

        while (currentTurn < maxTurns) {
            Log.d("AgentDebug", "--- [Step ${currentTurn + 1}] ---")

            // Determine if this turn is for generating the final answer
            val isFinalAnswerTurn =
                conversation.getOrNull(conversation.size - 2)?.type == MessageType.TOOL_RESULT

            val stopStrings = if (isFinalAnswerTurn) {
                listOf("<end_of_turn>")
            } else {
                listOf("</tool_call>")
            }

            val fullPromptHistory = buildPromptWithHistory(conversation)
            Log.d("AgentDebug", "Final Prompt Sent:\n$fullPromptHistory")

            val startTime = System.nanoTime()
            val modelResponse = try {
                llamaAndroid.clearKvCache()
                withContext(ioDispatcher) {
                    llamaAndroid.send(fullPromptHistory, stop = stopStrings)
                        .reduce { acc, s -> acc + s }
                }
            } catch (e: Exception) {
                Log.e("AgentLoop", "Model inference failed", e)
                "Error: Could not get a response from the model."
            }
            val durationMs = (System.nanoTime() - startTime) / 1_000_000

            val finalResponse = modelResponse.split(stopStrings.first()).first()
            Log.d("AgentDebug", "Raw Model Result: \"$modelResponse\"")
            Log.d("AgentDebug", "Trimmed Final Result: \"$finalResponse\"")

            if (isFinalAnswerTurn) {
                updateLastMessage(finalResponse)
                Log.d("AgentDebug", "Final answer received. Concluding.")
                updateLastMessageDuration(durationMs)
                break

            } else {
                val toolCall = parseToolCall(finalResponse)

                if (toolCall != null) {
                    // A tool call was found, as expected.
                    val toolCallString = "<tool_call>\n" +
                        "{\n" +
                        "  \"tool_name\": \"${toolCall.toolName}\",\n" +
                        "  \"args\": {}\n" +
                        "}\n" +
                        "</tool_call>"

                    updateLastMessageDuration(durationMs)
                    updateLastMessage(toolCallString) // Show the tool call in the UI
                    Log.d("AgentDebug", "Tool Call Detected: $toolCall")

                    val tool = tools[toolCall.toolName]
                    if (tool != null) {
                        val result = tool.execute(getApplication(), toolCall.args)
                        Log.d("AgentDebug", "Tool Response: \"$result\"")
                        addMessage(result, MessageType.TOOL_RESULT)
                        addMessage("", MessageType.MODEL) // Add placeholder for the final answer
                    } else {
                        val errorMsg =
                            "Error: Model tried to call unknown tool '${toolCall.toolName}'"
                        updateLastMessage(errorMsg)
                        Log.e("AgentDebug", errorMsg)
                        break
                    }
                } else {
                    updateLastMessage(finalResponse)
                    updateLastMessageDuration(durationMs)
                    Log.d("AgentDebug", "No tool call detected. Model gave a direct answer.")
                    break
                }
            }
            currentTurn++
        }
    }

    private fun parseToolCall(responseText: String): ToolCall? {
        val jsonString = findPotentialJsonObjectString(responseText)
        if (jsonString != null) {
            val toolCallFromJson = parseJsonObjectToToolCall(jsonString)
            if (toolCallFromJson != null) {
                Log.d("ToolParse", "Successfully parsed tool call from well-formed JSON.")
                return toolCallFromJson
            }
        }

        Log.w(
            "ToolParse",
            "Could not parse JSON. Attempting recovery by searching for tool name..."
        )

        val tagContent = responseText.substringAfter("<tool_call>", "")
            .substringBefore("</tool_call>", "").trim()

        if (tagContent.isNotBlank()) {
            for (toolName in tools.keys) {
                if (tagContent.contains(toolName)) {
                    Log.d(
                        "ToolParse",
                        "RECOVERY SUCCESS: Found tool name '$toolName' in malformed output."
                    )
                    return ToolCall(toolName, emptyMap())
                }
            }
        }

        Log.e(
            "ToolParse",
            "RECOVERY FAILED: No valid JSON or known tool name found in the response."
        )
        return null
    }

    private fun findPotentialJsonObjectString(responseText: String): String? {
        var candidateString = responseText

        val tagPattern = Pattern.compile("<tool_call>(.*?)</tool_call>", Pattern.DOTALL)
        val tagMatcher = tagPattern.matcher(responseText)
        if (tagMatcher.find()) {
            candidateString = tagMatcher.group(1) ?: ""
        }

        // Now, find the boundaries of the JSON object within the candidate string.
        val firstBraceIndex = candidateString.indexOf('{')
        val lastBraceIndex = candidateString.lastIndexOf('}')

        if (firstBraceIndex != -1 && lastBraceIndex != -1 && firstBraceIndex < lastBraceIndex) {
            return candidateString.substring(firstBraceIndex, lastBraceIndex + 1)
        }

        return null
    }

    private fun parseJsonObjectToToolCall(jsonStr: String): ToolCall? {
        return try {
            val json = JSONObject(jsonStr)
            val toolName = json.getString("tool_name")
            val argsJson = json.getJSONObject("args")
            val argsMap = mutableMapOf<String, Any>()
            argsJson.keys().forEach { key ->
                argsMap[key] = argsJson.get(key)
            }
            ToolCall(toolName, argsMap)
        } catch (e: JSONException) {
            Log.e("ToolParse", "Could not parse individual JSON object to ToolCall: $jsonStr", e)
            null
        }
    }

    fun bench(pp: Int, tg: Int, pl: Int, nr: Int = 1) {
        customScope.launch(mainDispatcher) {
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
        customScope.launch(mainDispatcher) {
            try {
                currentModelFamily = detectModelFamily(pathToModel)
                log("Detected model family: $currentModelFamily")

                withContext(ioDispatcher) {
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

    private fun detectModelFamily(modelPath: String): ModelFamily {
        val fileName = File(modelPath).name.lowercase()
        return when {
            "gemma" in fileName -> ModelFamily.GEMMA2
            "llama-3" in fileName || "llama3" in fileName -> ModelFamily.LLAMA3
            else -> {
                Log.w(
                    "ModelDetect",
                    "Could not determine model family for '$fileName'. Defaulting to Llama3."
                )
                ModelFamily.LLAMA3
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
        return when (currentModelFamily) {
            ModelFamily.GEMMA2 -> buildGemma2Prompt(history)
            ModelFamily.LLAMA3, ModelFamily.UNKNOWN -> buildLlama3Prompt(history)
        }
    }

    private fun buildGemma2Prompt(history: List<UiMessage>): String {
        val promptBuilder = StringBuilder()

        val toolsAsJsonArray =
            tools.values.joinToString(prefix = "[\n", postfix = "\n]", separator = ",\n") { tool ->
                """  {
    |    "tool_name": "${tool.name}",
    |    "description": "${tool.description.replace("\"", "\\\"")}",
    |    "args": {}
    |  }""".trimMargin()
            }

        val systemInstruction = """
You are a helpful assistant. You can answer questions by using the tools provided below.

**TOOLS:**
$toolsAsJsonArray

**INSTRUCTIONS:**
1. Examine the user's question.
2. Decide if a tool can help. If so, respond with a `<tool_call>` containing the correct tool JSON.
3. After you receive the `<start_of_turn>tool` result, provide the final answer to the user.

**EXAMPLE:**
<start_of_turn>user
What is the battery level?<end_of_turn>
<start_of_turn>model
<tool_call>
{
  "tool_name": "get_device_battery",
  "args": {}
}
</tool_call><end_of_turn>
<start_of_turn>tool
[Tool Result for get_device_battery]: Device battery is at 85%.<end_of_turn>
<start_of_turn>model
Your device's battery is at 85%.<end_of_turn>
    """.trimIndent()

        promptBuilder.append(systemInstruction)
        promptBuilder.append("\n\n**CURRENT CONVERSATION:**\n")

        history.forEach { message ->
            when (message.type) {
                MessageType.USER -> {
                    promptBuilder.append("<start_of_turn>user\n${message.text}<end_of_turn>\n")
                }

                MessageType.MODEL -> {
                    if (message.text.isNotBlank()) {
                        promptBuilder.append("<start_of_turn>model\n${message.text}<end_of_turn>\n")
                    }
                }

                MessageType.TOOL_RESULT -> {
                    promptBuilder.append("<start_of_turn>tool\n${message.text}<end_of_turn>\n")
                }

                MessageType.SYSTEM -> {}
            }
        }
        promptBuilder.append("<start_of_turn>model\n")

        return promptBuilder.toString()
    }


    private fun buildLlama3Prompt(history: List<UiMessage>): String {
        val historyBuilder = StringBuilder()

        historyBuilder.append("<|begin_of_text|>")
        historyBuilder.append("<|start_header_id|>system<|end_header_id|>\n\n$masterSystemPrompt<|eot_id|>")

        for (message in history) {
            when (message.type) {
                MessageType.USER -> {
                    historyBuilder.append("<|start_header_id|>user<|end_header_id|>\n\n${message.text}<|eot_id|>")
                }

                MessageType.MODEL -> {
                    if (message.text.isNotBlank()) {
                        historyBuilder.append("<|start_header_id|>assistant<|end_header_id|>\n\n${message.text}")
                    }
                }

                MessageType.SYSTEM -> {}
                MessageType.TOOL_RESULT -> {
                    historyBuilder.append("<|start_header_id|>tool<|end_header_id|>\n")
                    historyBuilder.append(message.text)
                    historyBuilder.append("<|eot_id|>\n")
                }
            }
        }

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

        customScope.launch {
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
            _savedModelUri.value = savedUriString.toUri()
        }
    }

    fun onNewModelSelected(uri: Uri?) {
        _savedModelUri.value = uri
    }
}
