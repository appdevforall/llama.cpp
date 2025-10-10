package com.example.llama

import android.app.Application
import android.llama.cpp.LLamaAndroid
import android.util.Log
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
//            UiMessage(
//                id = messageIdCounter.getAndIncrement(),
//                text = "Hello! How can I help you today?",
//                type = MessageType.MODEL
//            )
        )
    )
    val messages: StateFlow<List<UiMessage>> = _messages.asStateFlow()

    private var loadedModelPath: String? = null

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
            val fullPrompt = buildPromptWithHistory(_messages.value, false)
            runSimpleInference(fullPrompt, isStreaming)
        }
    }

    suspend fun loadModel(pathToModel: String) {
        if (pathToModel == loadedModelPath) {
            val message = "Model is already loaded."
            Log.d(tag, message)
            addMessage(message, MessageType.SYSTEM)
            return
        }

        try {
            if (loadedModelPath != null) {
                Log.d(tag, "Switching models. Unloading: $loadedModelPath")
                addMessage("Unloading previous model...", MessageType.SYSTEM)
                withContext(ioDispatcher) {
                    llamaAndroid.unload()
                }
            }

            currentModelFamily = detectModelFamily(pathToModel)
            addMessage("Detected model family: $currentModelFamily", MessageType.SYSTEM)
            withContext(ioDispatcher) {
                llamaAndroid.load(pathToModel)
            }

            loadedModelPath = pathToModel
            addMessage("Loaded $pathToModel", MessageType.SYSTEM)

            val contextSize = llamaAndroid.getContextSize()
            addMessage("Model context size: $contextSize tokens", MessageType.SYSTEM)

        } catch (exc: IllegalStateException) {
            Log.e(tag, "loadModel() failed", exc)
            addMessage(
                exc.message ?: "An unknown error occurred during model loading.",
                MessageType.SYSTEM
            )
            loadedModelPath = null
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
            Log.d("AgentDebug", "--- [Step ${currentTurn + 1}] ---")
            val currentHistory = _messages.value
            val isFinalAnswerTurn =
                currentHistory.getOrNull(currentHistory.size - 2)?.type == MessageType.TOOL_RESULT

            val stopStrings = if (isFinalAnswerTurn) {
                listOf("Question:", "\n\n")
            } else {
                listOf("\n")
            }

            val fullPromptHistory = buildPromptWithHistory(currentHistory, isFinalAnswerTurn)
            Log.d("AgentDebug", "Final Prompt Sent:\n$fullPromptHistory")
            val startTime = System.nanoTime()
            val modelResponse = try {
                llamaAndroid.clearKvCache()
                withContext(Dispatchers.IO) {
                    llamaAndroid.send(fullPromptHistory, stop = stopStrings)
                        .reduce { acc, s -> acc + s }
                }
            } catch (e: Exception) {
                Log.e("AgentLoop", "Model inference failed", e)
                "Error: Could not get a response from the model."
            }
            val durationMs = (System.nanoTime() - startTime) / 1_000_000

            val finalResponse = modelResponse.trim() // Trim whitespace
            Log.d("AgentDebug", "Raw Model Result: \"$modelResponse\"")
            Log.d("AgentDebug", "Trimmed Final Result: \"$finalResponse\"")

            if (isFinalAnswerTurn) {
                var cleanResponse = finalResponse
                for (stopWord in stopStrings) {
                    if (cleanResponse.contains(stopWord)) {
                        // Take only the text *before* the first occurrence of a stop word
                        cleanResponse = cleanResponse.substringBefore(stopWord).trim()
                    }
                }
                updateLastMessage(cleanResponse)

                Log.d("AgentDebug", "Final answer received. Concluding.")
                updateLastMessageDuration(durationMs)
                break
            } else {
                val toolIdMap =
                    tools.values.mapIndexed { index, tool -> (index + 1).toString() to tool.name }
                        .toMap()

                var identifiedToolName: String? = null

                if (toolIdMap.containsKey(finalResponse)) {
                    identifiedToolName = toolIdMap[finalResponse]
                } else {
                    for (toolName in tools.keys) {
                        if (finalResponse.contains(toolName)) {
                            identifiedToolName = toolName
                            break
                        }
                    }
                }
                if (identifiedToolName != null) {
                    // We found a tool call!
                    val tool = tools[identifiedToolName]!!
                    updateLastMessage("Tool Call: ${tool.name}")
                    updateLastMessageDuration(durationMs)
                    updateLastMessage(identifiedToolName)
                    Log.d("AgentDebug", "Tool Call Detected: $identifiedToolName")
                    updateLastMessage("Tool Call: ${tool.name}")

                    val result = tool.execute(application, emptyMap())
                    addMessage(result, MessageType.TOOL_RESULT)
                    addMessage("", MessageType.MODEL)
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
    private fun detectModelFamily(path: String): ModelFamily {
        val lowerPath = path.lowercase()
        return when {
            lowerPath.contains("gemma") -> ModelFamily.GEMMA2
            lowerPath.contains("llama") -> ModelFamily.LLAMA3
            else -> ModelFamily.UNKNOWN
        }
    }

    private fun buildPromptWithHistory(
        history: List<UiMessage>,
        isFinalAnswerTurn: Boolean
    ): String {
        return when (currentModelFamily) {
            ModelFamily.LLAMA3 -> buildLlama3Prompt(history)
            ModelFamily.GEMMA2 -> {
                if (isFinalAnswerTurn) {
                    buildGemma2FinalAnswerPrompt(history)
                } else {
                    buildGemma2Prompt(history)
                }
            }
            else -> history.lastOrNull { it.type == MessageType.USER }?.text ?: ""
        }
    }

    private fun buildGemma2Prompt(history: List<UiMessage>): String {
        val promptBuilder = StringBuilder()

        val toolMap =
            tools.values.mapIndexed { index, tool -> (index + 1).toString() to tool.name }.toMap()

        val toolDescriptions = toolMap.entries.joinToString("\n") { (id, name) ->
            val tool = tools[name]
            "$id: ${tool?.name} - ${tool?.description}"
        }

        val systemInstruction = """
You are a helpful assistant.
Analyze the user's question and determine if one of the following tools can help.
If no tool is needed, answer the question directly.
If a tool is needed, you MUST respond with ONLY the tool's ID number on a new line, and nothing else.

[AVAILABLE_TOOLS]
$toolDescriptions
[END_TOOLS]

EXAMPLE:
user: What time is it?
model:
2
    """.trimIndent()

        promptBuilder.append(systemInstruction)
        promptBuilder.append("\n\n**CONVERSATION:**\n")

        // Keep the history, but only the most recent turn might be needed.
        val relevantHistory = history.takeLast(4)
        relevantHistory.forEach { message ->
            when (message.type) {
                MessageType.USER -> promptBuilder.append("user: ${message.text}\n")
                MessageType.MODEL -> {
                    if (message.text.isNotBlank()) {
                        promptBuilder.append("model: ${message.text}\n")
                    }
                }
                MessageType.TOOL_RESULT, MessageType.SYSTEM -> {}
            }
        }
        promptBuilder.append("model:\n") // Ready for the model's response
        return promptBuilder.toString()
    }

    private fun buildGemma2FinalAnswerPrompt(history: List<UiMessage>): String {
        val promptBuilder = StringBuilder()
        val userQuestion = history.findLast { it.type == MessageType.USER }?.text ?: ""
        val toolResult = history.findLast { it.type == MessageType.TOOL_RESULT }?.text ?: ""

        val finalPrompt = """
You are a helpful assistant.
Use the following information to answer the user's question.
Answer in a single, friendly sentence.

Information: $toolResult
Question: $userQuestion
Answer:
    """.trimIndent()

        promptBuilder.append(finalPrompt)
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

    suspend fun cleanup() {
        try {
            llamaAndroid.unload()
            loadedModelPath = null
            Log.d(tag, "LLamaAndroid resources unloaded successfully.")
        } catch (e: Exception) {
            Log.e(tag, "Error during LLamaAndroid unload", e)
        }
    }
}
