package com.example.llama

import android.app.Application
import android.llama.cpp.LLamaAndroid
import android.util.Log
import com.example.llama.Util.parseToolCall
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.reduce
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.withContext
import java.util.concurrent.atomic.AtomicLong

private const val SYSTEM_PROMPT = """
 You are a helpful and smart assistant integrated into an Android application.
 You have access to the following tools:
 [AVAILABLE_TOOLS]

 When a user asks a question, you must follow these steps:
 1.  Determine if any of the available tools can help answer the question.
 2.  If a tool is appropriate, you MUST respond with a single `<tool_call>` XML tag. The tag should contain a JSON object with the tool's name and its arguments.
 3.  If no tool is needed, you should answer the user's question directly.
 """

/**
 * The single source of truth for chat data and business logic.
 * This class manages the conversation state and all interactions with LLamaAndroid.
 */
class ChatRepository(
    private val application: Application,
    private val llamaAndroid: LLamaAndroid,
    private val ioDispatcher: CoroutineDispatcher = Dispatchers.IO
) {
    private val tag: String = this::class.java.simpleName
    private val messageIdCounter = AtomicLong(0)

    private val tools: Map<String, Tool> = listOf(
        BatteryTool(),
        GetDateTimeTool()
    ).associateBy { it.name }

    private var currentModelFamily: ModelFamily = ModelFamily.UNKNOWN

    private val masterSystemPrompt: String by lazy {
        val toolDescriptions = tools.values.joinToString("\n") { "- ${it.name}: ${it.description}" }
        SYSTEM_PROMPT.replace("[AVAILABLE_TOOLS]", toolDescriptions)
    }

    private val _messages = MutableStateFlow<List<UiMessage>>(
        listOf(
            UiMessage(
                id = messageIdCounter.getAndIncrement(),
                text = "Hello! How can I help you today?",
                type = MessageType.MODEL
            )
        )
    )
    val messages: StateFlow<List<UiMessage>> = _messages.asStateFlow()

    // --- Public API for ViewModel ---

    suspend fun sendMessage(
        userInput: String,
        isStreaming: Boolean,
        isToolUseEnabled: Boolean
    ) {
        addMessage(userInput, MessageType.USER)
        val placeholder = if (isStreaming) "" else "..."
        addMessage(placeholder, MessageType.MODEL)

        llamaAndroid.clearKvCache()
        if (isToolUseEnabled) {
            runAgentLoop()
        } else {
            runSimpleInference(userInput, isStreaming)
        }
    }

    suspend fun loadModel(pathToModel: String) {
        try {
            currentModelFamily = detectModelFamily(pathToModel)
            addMessage("Detected model family: $currentModelFamily", MessageType.SYSTEM)
            withContext(ioDispatcher) {
                llamaAndroid.load(pathToModel)
            }
            addMessage("Loaded $pathToModel", MessageType.SYSTEM)
            val contextSize = llamaAndroid.getContextSize()
            addMessage("Model context size: $contextSize tokens", MessageType.SYSTEM)
        } catch (exc: IllegalStateException) {
            Log.e(tag, "loadModel() failed", exc)
            addMessage(
                exc.message ?: "An unknown error occurred during model loading.",
                MessageType.SYSTEM
            )
        }
    }

    suspend fun bench(pp: Int, tg: Int, pl: Int, nr: Int = 1) {
        try {
            val start = System.nanoTime()
            val warmupResult = llamaAndroid.bench(pp, tg, pl, nr)
            val end = System.nanoTime()
            addMessage(warmupResult, MessageType.MODEL)

            val warmupTime = (end - start).toDouble() / 1_000_000_000.0
            addMessage("Warm up time: $warmupTime seconds, please wait...", MessageType.SYSTEM)

            if (warmupTime > 5.0) {
                addMessage("Warm up took too long, aborting benchmark", MessageType.SYSTEM)
                return
            }
            val benchmarkResult = llamaAndroid.bench(512, 128, 1, 3)
            addMessage(benchmarkResult, MessageType.SYSTEM)
        } catch (exc: IllegalStateException) {
            Log.e(tag, "bench() failed", exc)
            addMessage(
                exc.message ?: "An unknown error occurred during benchmark.",
                MessageType.SYSTEM
            )
        }
    }

    fun clear() {
        _messages.value = listOf(
            UiMessage(
                id = messageIdCounter.getAndIncrement(),
                text = "Hello! How can I help you today?",
                type = MessageType.MODEL
            )
        )
    }

    // --- Internal Logic ---

    private suspend fun runSimpleInference(prompt: String, isStreaming: Boolean) {
        val startTime = System.nanoTime()
        try {
            withContext(ioDispatcher) {
                if (isStreaming) {
                    var currentText = ""
                    llamaAndroid.send(prompt).collect { responseChunk ->
                        currentText += responseChunk
                        updateLastMessage(currentText)
                    }
                } else {
                    val modelResponse = llamaAndroid.send(prompt).reduce { acc, s -> acc + s }
                    updateLastMessage(modelResponse)
                }
            }
        } catch (e: Exception) {
            Log.e(tag, "Inference failed", e)
            updateLastMessage("Error: ${e.message}")
        } finally {
            val durationMs = (System.nanoTime() - startTime) / 1_000_000
            updateLastMessageDuration(durationMs)
        }
    }

    private suspend fun runAgentLoop(maxTurns: Int = 5) {
        var currentTurn = 0
        while (currentTurn < maxTurns) {
            Log.d(tag, "--- [Agent Loop: Turn ${currentTurn + 1}] ---")
            val currentHistory = _messages.value
            val isFinalAnswerTurn =
                currentHistory.getOrNull(currentHistory.size - 2)?.type == MessageType.TOOL_RESULT
            val stopStrings =
                if (isFinalAnswerTurn) listOf("<end_of_turn>") else listOf("</tool_call>")
            val fullPromptHistory = buildPromptWithHistory(currentHistory)

            val startTime = System.nanoTime()
            val modelResponse = try {
                withContext(ioDispatcher) {
                    llamaAndroid.send(fullPromptHistory, stop = stopStrings)
                        .reduce { acc, s -> acc + s }
                }
            } catch (e: Exception) {
                Log.e(tag, "Agent inference failed", e)
                "Error: Could not get a response from the model."
            }
            val durationMs = (System.nanoTime() - startTime) / 1_000_000

            val finalResponse = modelResponse.split(stopStrings.first()).first()
            if (isFinalAnswerTurn) {
                updateLastMessage(finalResponse)
                updateLastMessageDuration(durationMs)
                break // End of loop
            } else {
                val toolCall = parseToolCall(finalResponse, tools.keys)
                if (toolCall != null) {
                    val toolCallString =
                        "<tool_call>\n{\n  \"tool_name\": \"${toolCall.toolName}\",\n  \"args\": {}\n}\n</tool_call>"
                    updateLastMessage(toolCallString)
                    updateLastMessageDuration(durationMs)

                    val tool = tools[toolCall.toolName]
                    if (tool != null) {
                        val result = tool.execute(application, toolCall.args)
                        addMessage(result, MessageType.TOOL_RESULT)
                        addMessage("", MessageType.MODEL) // Add placeholder for the final answer
                    } else {
                        val errorMsg =
                            "Error: Model tried to call unknown tool '${toolCall.toolName}'"
                        updateLastMessage(errorMsg)
                        break
                    }
                } else {
                    updateLastMessage(finalResponse)
                    updateLastMessageDuration(durationMs)
                    break
                }
            }
            currentTurn++
        }
    }

    // --- State Update Helpers ---

    private fun addMessage(text: String, type: MessageType) {
        val message = UiMessage(messageIdCounter.getAndIncrement(), text, type)
        _messages.update { currentList -> currentList + message }
    }

    private fun updateLastMessage(updatedText: String) {
        _messages.update { currentList ->
            if (currentList.isEmpty()) return@update currentList
            val lastMessage = currentList.last()
            val updatedMessage = lastMessage.copy(text = updatedText)
            currentList.dropLast(1) + updatedMessage
        }
    }

    private fun updateLastMessageDuration(durationMs: Long) {
        _messages.update { currentList ->
            if (currentList.isEmpty()) return@update currentList
            val lastMessage = currentList.last()
            if (lastMessage.type == MessageType.MODEL) {
                val updatedMessage = lastMessage.copy(durationMs = durationMs)
                currentList.dropLast(1) + updatedMessage
            } else {
                currentList
            }
        }
    }

    // --- Prompt Building and Utility Logic ---

    private fun detectModelFamily(path: String): ModelFamily {
        val lowerPath = path.lowercase()
        return when {
            lowerPath.contains("gemma-2") -> ModelFamily.GEMMA2
            lowerPath.contains("llama-3") -> ModelFamily.LLAMA3
            else -> ModelFamily.UNKNOWN
        }
    }

    private fun buildPromptWithHistory(history: List<UiMessage>): String {
        return when (currentModelFamily) {
            ModelFamily.LLAMA3 -> buildLlama3Prompt(history)
            ModelFamily.GEMMA2 -> buildGemma2Prompt(history)
            else -> history.lastOrNull { it.type == MessageType.USER }?.text ?: ""
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
                MessageType.USER -> promptBuilder.append("<start_of_turn>user\n${message.text}<end_of_turn>\n")
                MessageType.MODEL -> {
                    if (message.text.isNotBlank()) {
                        promptBuilder.append("<start_of_turn>model\n${message.text}<end_of_turn>\n")
                    }
                }

                MessageType.TOOL_RESULT -> promptBuilder.append("<start_of_turn>tool\n${message.text}<end_of_turn>\n")
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
                MessageType.USER -> historyBuilder.append("<|start_header_id|>user<|end_header_id|>\n\n${message.text}<|eot_id|>")
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
}
