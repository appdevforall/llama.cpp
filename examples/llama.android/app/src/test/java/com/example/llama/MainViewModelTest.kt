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
            mainDispatcher = mainCoroutineRule.testDispatcher,
            ioDispatcher = mainCoroutineRule.testDispatcher
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
        val toolName = "get_current_datetime"
        val mockToolResult = "It's test time!" // We can define a fake result

        // 1. Create a mock Tool
        val mockTool = mock<Tool>()
        whenever(mockTool.name).thenReturn(toolName)
        whenever(mockTool.execute(any(), any())).thenReturn(mockToolResult)

        // 2. Put the mock tool in a map
        val mockTools = mapOf(toolName to mockTool)

        // 3. Re-initialize the ViewModel for this test, injecting the mock tools
        viewModel = MainViewModel(
            mockApplication,
            mockLlamaAndroid,
            mockTools, // <-- Here is the injection
            mainCoroutineRule.testDispatcher,
            mainCoroutineRule.testDispatcher
        )

        val modelResponseWithToolCall = """
        <tool_call>
        {
          "tool_name": "$toolName",
          "args": {}
        }
        </tool_call>
    """.trimIndent()

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
        assertEquals("Expected 5 messages after a successful tool call", 5, finalMessages.size)

        val toolResultMsg = finalMessages[3]
        assertEquals(MessageType.TOOL_RESULT, toolResultMsg.type)
        // Now we can assert the exact text because our mock tool returned it!
        assertEquals(mockToolResult, toolResultMsg.text)

        // Also, verify that our mock tool was executed
        verify(mockTool).execute(any(), any())
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
