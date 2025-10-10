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
import org.mockito.kotlin.argumentCaptor
import org.mockito.kotlin.doReturn
import org.mockito.kotlin.mock
import org.mockito.kotlin.times
import org.mockito.kotlin.verify
import org.mockito.kotlin.whenever

@ExperimentalCoroutinesApi
class ChatRepositoryTest {

    @get:Rule
    var instantExecutorRule = InstantTaskExecutorRule()

    @get:Rule
    var mainCoroutineRule = MainCoroutineRule()

    // Mocks for the Repository's dependencies
    private lateinit var mockLlamaAndroid: LLamaAndroid
    private lateinit var mockApplication: Application

    // The class under test
    private lateinit var repository: ChatRepository

    @Before
    fun setup() {
        // Create fresh mocks before each test
        mockLlamaAndroid = mock()
        mockApplication = mock()

        // Initialize the repository with mocks
        repository = ChatRepository(
            mockApplication,
            mockLlamaAndroid,
            ioDispatcher = mainCoroutineRule.testDispatcher // Use test dispatcher for immediate execution
        )
    }

    @Test
    fun `sendMessage with tool use disabled runs simple inference and updates messages`() =
        runTest {
        // Arrange
        val userInput = "Hello, world!"
            val modelResponse = "This is a simple response."

            // Mock the send call that will be triggered by runSimpleInference
            whenever(mockLlamaAndroid.send(any(), any(), any(), any())) doReturn flowOf(
                modelResponse
            )

            // Set the model family to ensure the correct prompt builder is used
            ChatRepository::class.java.getDeclaredField("currentModelFamily").apply {
                isAccessible = true
                set(repository, ModelFamily.GEMMA2)
            }

        // Act
        repository.sendMessage(userInput, isStreaming = false, isToolUseEnabled = false)

        // Assert
            val finalMessages = repository.messages.value

            // 1. The final list should have exactly two messages.
            assertEquals(
                "Should have 2 final messages: user and model response",
                2,
                finalMessages.size
            )

            // 2. Check the first message (index 0) - This should be the user's input.
            val userMessage = finalMessages[0]
            assertEquals(userInput, userMessage.text)
            assertEquals(MessageType.USER, userMessage.type)

            // 3. Check the second message (index 1) - This should be the final model response.
            val modelMessage = finalMessages[1]
            assertEquals(modelResponse, modelMessage.text)
            assertEquals(MessageType.MODEL, modelMessage.type)
    }

    @Test
    fun `loadModel success updates messages with system logs`() = runTest {
        // Arrange
        val modelPath = "/fake/path/to/llama-3-model.gguf"
        val expectedContextSize = 4096
        whenever(mockLlamaAndroid.getContextSize()).thenReturn(expectedContextSize)

        // Act
        repository.loadModel(modelPath)

        // Assert
        // Check that the native load method was called
        verify(mockLlamaAndroid).load(modelPath)

        // Check that the messages flow was updated with system logs
        val messages = repository.messages.value
        assertTrue(
            "Log should contain model family detection",
            messages.any { it.text.contains("LLAMA3") })
        assertTrue(
            "Log should contain the loaded path",
            messages.any { it.text.contains("Loaded $modelPath") })
        assertTrue(
            "Log should contain the context size",
            messages.any { it.text.contains("Model context size: $expectedContextSize") })
    }

    @Test
    fun `sendMessage with simple inference updates final message correctly`() = runTest {
        // Arrange
        val userInput = "Tell me a joke"
        val modelResponseChunks = listOf("Why ", "did the ", "scarecrow ", "win an award?")
        val expectedFullResponse = "Why did the scarecrow win an award?"

        // Mock the `send` method that will be called by `runSimpleInference`
        whenever(
            mockLlamaAndroid.send(
                any(),
                any(),
                any(),
                any()
            )
        ) doReturn modelResponseChunks.asFlow()

        // Act
        repository.sendMessage(userInput, isStreaming = true, isToolUseEnabled = false)

        // Assert
        val finalMessages = repository.messages.value
        assertEquals(2, finalMessages.size) // User message, Final Model Response
        assertEquals(expectedFullResponse, finalMessages.last().text)
        assertEquals(MessageType.MODEL, finalMessages.last().type)
    }

    @Test
    fun `sendMessage with valid tool call follows two-step prompt logic`() = runTest {
        // --- Arrange ---
        val userInput = "What is the time?"
        val toolName = "get_current_datetime"
        val firstModelResponse = "2"
        val finalAnswer = "The current time is 7:14 PM."

        val promptCaptor = argumentCaptor<String>()
        val stopStringsCaptor = argumentCaptor<List<String>>()

        whenever(mockLlamaAndroid.send(any(), any(), any(), any()))
            .doReturn(flowOf(firstModelResponse))
            .doReturn(flowOf(finalAnswer))

        ChatRepository::class.java.getDeclaredField("currentModelFamily").apply {
            isAccessible = true
            set(repository, ModelFamily.GEMMA2)
        }

        repository.sendMessage(userInput, isStreaming = false, isToolUseEnabled = true)

        verify(mockLlamaAndroid, times(2)).send(
            promptCaptor.capture(),
            any(),
            stopStringsCaptor.capture(),
            any()
        )

        val firstPrompt = promptCaptor.firstValue
        val firstStopStrings = stopStringsCaptor.firstValue
        assertTrue(
            "First prompt must be for tool selection",
            firstPrompt.contains("[AVAILABLE_TOOLS]")
        )
        assertEquals("First call should stop on newline", listOf("\n"), firstStopStrings)

        val secondPrompt = promptCaptor.secondValue
        val secondStopStrings = stopStringsCaptor.secondValue

        assertTrue(
            "Second prompt must contain 'Information:'",
            secondPrompt.contains("Information:")
        )
        // Assert against the correct literal string "Question:"
        assertTrue("Second prompt must contain 'Question:'", secondPrompt.contains("Question:"))
        // Assert against the correct stop strings for this turn
        assertEquals(
            "Second call should use Question stop string",
            listOf("Question:", "\n\n"),
            secondStopStrings
        )

        val finalMessages = repository.messages.value
        // Expected sequence is now simpler because we are not calling loadModel()
        // 0. User Input
        // 1. Model's "Tool Call: ..." message
        // 2. Tool Result message
        // 3. Final Answer message from the model
        assertEquals("Expected 4 messages after a successful tool call loop", 4, finalMessages.size)
        assertEquals(MessageType.USER, finalMessages[0].type)
        assertTrue(finalMessages[1].text.contains("Tool Call: $toolName"))
        assertEquals(MessageType.TOOL_RESULT, finalMessages[2].type)
        assertEquals(finalAnswer, finalMessages[3].text)
    }

    @Test
    fun `sendMessage when model does not select a tool should display direct answer`() = runTest {
        // --- Arrange ---
        val userInput = "Make me a sandwich"
        // In our new logic, the model won't invent a tool. It will just fail to provide
        // a valid ID (like "1" or "2") and give a text answer instead.
        val modelDirectAnswer = "I am an AI and cannot make a physical sandwich."

        // Set the model family to ensure the correct prompt is built
        ChatRepository::class.java.getDeclaredField("currentModelFamily").apply {
            isAccessible = true
            set(repository, ModelFamily.GEMMA2)
        }

        // Mock the model to return a direct answer instead of a tool ID
        whenever(mockLlamaAndroid.send(any(), any(), any(), any())) doReturn flowOf(
            modelDirectAnswer
        )

        // --- Act ---
        repository.sendMessage(userInput, isStreaming = false, isToolUseEnabled = true)

        // --- Assert ---
        // 1. Verify that `send` was called only ONCE, because the agent loop should
        //    break immediately when it doesn't get a valid tool identifier.
        verify(mockLlamaAndroid, times(1)).send(any(), any(), any(), any())

        // 2. Assert the final state of the messages
        val finalMessages = repository.messages.value
        // Expected sequence:
        // 0. User Input
        // 1. Final Model Message (which is the direct answer)
        assertEquals("Expected 2 messages after a direct answer", 2, finalMessages.size)

        val userMessage = finalMessages[0]
        val modelMessage = finalMessages[1]

        assertEquals(userInput, userMessage.text)
        assertEquals(MessageType.USER, userMessage.type)

        assertEquals(modelDirectAnswer, modelMessage.text)
        assertEquals(MessageType.MODEL, modelMessage.type)
    }
}
