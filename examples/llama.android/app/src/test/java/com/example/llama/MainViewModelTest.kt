package com.example.llama

import android.app.Application
import android.llama.cpp.LLamaAndroid
import androidx.arch.core.executor.testing.InstantTaskExecutorRule
import com.example.llama.util.MainCoroutineRule
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.asFlow
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.mockito.kotlin.any
import org.mockito.kotlin.doReturn
import org.mockito.kotlin.mock
import org.mockito.kotlin.verify
import org.mockito.kotlin.whenever

@ExperimentalCoroutinesApi
class MainViewModelTest {

    @get:Rule
    var instantExecutorRule = InstantTaskExecutorRule()

    @get:Rule
    var mainCoroutineRule = MainCoroutineRule()

    private lateinit var mockLlamaAndroid: LLamaAndroid
    private lateinit var mockApplication: Application
    private lateinit var viewModel: MainViewModel

    @Before
    fun setup() {
        mockLlamaAndroid = mock()
        mockApplication = mock()

        viewModel = MainViewModel(
            mockApplication,
            mockLlamaAndroid,
            mainCoroutineRule.testDispatcher,
            mainCoroutineRule.testDispatcher
        )
    }

    @Test
    fun `send adds user message and placeholder model message`() {
        val userMessage = "Hello, world!"
        viewModel.updateMessage(userMessage)

        viewModel.send()

        val messages = viewModel.uiMessages.value!!
        assertEquals("Expected 3 messages (Initial, User, Model Placeholder)", 3, messages.size)
        assertEquals(userMessage, messages[1].text)
        assertEquals(MessageType.USER, messages[1].type)
        assertEquals("", messages[2].text)
        assertEquals(MessageType.MODEL, messages[2].type)
    }

    @Test
    fun `send calls llamaAndroid send method`() = runTest {
        val userMessage = "What is the time?"
        viewModel.updateMessage(userMessage)

        // FIX: Provide a matcher for ALL FOUR arguments of the send method.
        whenever(
            mockLlamaAndroid.send(
                any<String>(),       // message
                any<Boolean>(),      // formatChat
                any<List<String>>(), // stop
                any<Boolean>()       // clearCache
            )
        ) doReturn flowOf("Response")

        viewModel.send()

        mainCoroutineRule.testDispatcher.scheduler.advanceUntilIdle()

        // FIX: The verify call must also match the full signature with 4 matchers.
        verify(mockLlamaAndroid).send(
            any<String>(),
            any<Boolean>(),
            any<List<String>>(),
            any<Boolean>()
        )
    }

    @Test
    fun `load success updates log and context size`() = runTest {
        val modelPath = "/fake/path/to/model.gguf"
        val expectedContextSize = 2048
        whenever(mockLlamaAndroid.getContextSize()).thenReturn(expectedContextSize)

        viewModel.load(modelPath)

        mainCoroutineRule.testDispatcher.scheduler.advanceUntilIdle()

        verify(mockLlamaAndroid).load(modelPath)
        val messages = viewModel.uiMessages.value!!
        assertTrue(messages.any { it.text.contains("Loaded $modelPath") })
        assertTrue(messages.any { it.text == "Model context size: $expectedContextSize tokens" })
    }

    @Test
    fun `load failure updates log with error message`() = runTest {
        val modelPath = "/fake/path/to/model.gguf"
        val errorMessage = "Failed to load model"

        whenever(mockLlamaAndroid.load(modelPath)).thenThrow(IllegalStateException(errorMessage))

        viewModel.load(modelPath)

        mainCoroutineRule.testDispatcher.scheduler.advanceUntilIdle()

        val messages = viewModel.uiMessages.value!!
        assertTrue(
            "Error message '$errorMessage' not found in UI messages",
            messages.any { it.text == errorMessage })
    }

    @Test
    fun `send with simple inference updates message correctly`() = runTest {
        // Arrange
        val userMessage = "Tell me a joke"
        val modelResponseChunks =
            listOf("Why ", "did ", "the ", "scarecrow ", "win ", "an ", "award?")
        val expectedFullResponse = "Why did the scarecrow win an award?"

        viewModel.setToolUse(false) // This is key to forcing the runSimpleInference path
        viewModel.updateMessage(userMessage)

        // Mock the single-argument send method used by runSimpleInference
        // We need to import kotlinx.coroutines.flow.asFlow
        whenever(mockLlamaAndroid.send(userMessage)) doReturn modelResponseChunks.asFlow()

        // Act
        viewModel.send()
        mainCoroutineRule.testDispatcher.scheduler.advanceUntilIdle()

        // Assert
        val finalMessages = viewModel.uiMessages.value!!
        val lastMessage = finalMessages.last()

        assertEquals(
            "The final message text should match the fully streamed response",
            expectedFullResponse, lastMessage.text
        )
        assertEquals(MessageType.MODEL, lastMessage.type)

        // Verify that the correct, single-argument send method was called.
        verify(mockLlamaAndroid).send(userMessage)
    }

    @Test
    fun `send with valid tool call executes tool and adds result messages`() = runTest {
        // Arrange
        val userMessage = "What is the current time?"
        val toolName = "get_current_datetime" // This must match a real tool name in the ViewModel
        val modelResponseWithToolCall = """
        <tool_call>
        {
          "tool_name": "$toolName",
          "args": {}
        }
        </tool_call>
    """.trimIndent()

        // This is the exact string the ViewModel constructs to display the tool call
        val expectedFormattedToolCall = "<tool_call>\n" +
            "{\n" +
            "  \"tool_name\": \"$toolName\",\n" +
            "  \"args\": {}\n" +
            "}\n" +
            "</tool_call>"

        // Mock the agent loop's send method to return the tool call
        whenever(
            mockLlamaAndroid.send(
                any<String>(),       // message
                any<Boolean>(),      // formatChat
                any<List<String>>(), // stop
                any<Boolean>()
            )
        ) doReturn flowOf(modelResponseWithToolCall)

        viewModel.updateMessage(userMessage)

        // Act
        viewModel.send()
        mainCoroutineRule.testDispatcher.scheduler.advanceUntilIdle()

        // Assert
        val finalMessages = viewModel.uiMessages.value!!

        // We expect 5 messages in total:
        // 1. Initializing...
        // 2. User prompt ("What is the current time?")
        // 3. Model's thinking (updated to show the formatted tool call)
        // 4. The result from the tool execution
        // 5. A new empty placeholder for the model's final answer
        assertEquals("Expected 5 messages after a successful tool call", 5, finalMessages.size)

        val modelResponseMsg = finalMessages[2]
        val toolResultMsg = finalMessages[3]
        val newModelPlaceholderMsg = finalMessages[4]

        assertEquals(
            "Model message should be updated with the formatted tool call",
            expectedFormattedToolCall, modelResponseMsg.text
        )

        assertEquals(
            "A message with the tool result should be added",
            MessageType.TOOL_RESULT, toolResultMsg.type
        )
        assertTrue("The tool result message should contain text", toolResultMsg.text.isNotBlank())

        assertEquals(
            "A new placeholder message for the model should be added",
            "", newModelPlaceholderMsg.text
        )
        assertEquals(MessageType.MODEL, newModelPlaceholderMsg.type)
    }

    @Test
    fun `send with unknown tool call updates message with error`() = runTest {
        // Arrange
        val userMessage = "Make me a sandwich"
        val unknownToolName = "make_a_sandwich"
        val modelResponseWithUnknownTool = """
        <tool_call>
        {
          "tool_name": "$unknownToolName",
          "args": {}
        }
        </tool_call>
    """.trimIndent()
        val expectedErrorMessage = "Error: Model tried to call unknown tool '$unknownToolName'"

        whenever(
            mockLlamaAndroid.send(
                any<String>(),       // message
                any<Boolean>(),      // formatChat
                any<List<String>>(), // stop
                any<Boolean>()
            )
        ) doReturn flowOf(modelResponseWithUnknownTool)

        viewModel.updateMessage(userMessage)

        // Act
        viewModel.send()
        mainCoroutineRule.testDispatcher.scheduler.advanceUntilIdle()

        // Assert
        val finalMessages = viewModel.uiMessages.value!!

        // We expect only 3 messages: Initial, User, and the final updated Model message with the error
        assertEquals("Expected 3 messages after an unknown tool call", 3, finalMessages.size)

        val lastMessage = finalMessages.last()
        assertEquals(MessageType.MODEL, lastMessage.type)
        assertEquals(
            "Last message should contain the unknown tool error",
            expectedErrorMessage, lastMessage.text
        )
    }
}
