package com.example.llama

import android.app.Application
import android.llama.cpp.LLamaAndroid
import androidx.arch.core.executor.testing.InstantTaskExecutorRule
import com.example.llama.util.MainCoroutineRule
import com.example.llama.util.getOrAwaitValue
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.mockito.kotlin.any
import org.mockito.kotlin.doReturn
import org.mockito.kotlin.doThrow
import org.mockito.kotlin.mock
import org.mockito.kotlin.verify
import org.mockito.kotlin.whenever

@ExperimentalCoroutinesApi
class MainViewModelTest {

    // Rule to execute LiveData operations synchronously
    @get:Rule
    var instantExecutorRule = InstantTaskExecutorRule()

    // Rule to handle main dispatcher for coroutines
    @get:Rule
    var mainCoroutineRule = MainCoroutineRule()

    // Mocks for dependencies
    private lateinit var mockLlamaAndroid: LLamaAndroid
    private lateinit var mockApplication: Application

    // The ViewModel under test
    private lateinit var viewModel: MainViewModel

    @Before
    fun setup() {
        // Initialize mocks before each test
        mockLlamaAndroid = mock()
        mockApplication = mock()

        // Create the ViewModel instance with mocked dependencies
        viewModel = MainViewModel(mockApplication, mockLlamaAndroid)
    }

    @Test
    fun `initial state contains an initializing message`() {
        val messages = viewModel.uiMessages.getOrAwaitValue()
        assertEquals(1, messages.size)
        assertEquals("Initializing...", messages[0].text)
        assertEquals(MessageType.SYSTEM, messages[0].type)
    }

    @Test
    fun `clear empties the uiMessages list`() {
        // Given a message already exists
        viewModel.log("A test message")

        // When clear is called
        viewModel.clear()

        // Then the message list should be empty
        val messages = viewModel.uiMessages.getOrAwaitValue()
        assertTrue(messages.isEmpty())
    }

    @Test
    fun `send adds user message and placeholder model message`() {
        // Given a user message is set
        val userMessage = "Hello, world!"
        viewModel.updateMessage(userMessage)

        // When send is called
        viewModel.send()

        // Then the uiMessages should contain the user message and a model placeholder
        val messages = viewModel.uiMessages.getOrAwaitValue()
        // Note: The list will also contain the initial system message
        assertEquals(3, messages.size)
        assertEquals(userMessage, messages[1].text)
        assertEquals(MessageType.USER, messages[1].type)
        assertEquals("", messages[2].text) // Placeholder for streaming response
        assertEquals(MessageType.MODEL, messages[2].type)
    }

    @Test
    fun `send calls llamaAndroid send method`() = runTest {
        // Given
        val userMessage = "What is the time?"
        viewModel.updateMessage(userMessage)
        // FIXED: Use 4 matchers because the send method has 4 arguments
        whenever(mockLlamaAndroid.send(any(), any(), any(), any())) doReturn flowOf("Response")

        // When
        viewModel.send()

        // Then verify that the underlying llama instance was called
        // FIXED: Verify with 4 matchers
        verify(mockLlamaAndroid).send(any(), any(), any(), any())
    }

    @Test
    fun `load success updates log and context size`() = runTest {
        // Given a model path and expected context size
        val modelPath = "/fake/path/to/model.gguf"
        val expectedContextSize = 2048
        whenever(mockLlamaAndroid.getContextSize()).thenReturn(expectedContextSize)

        // When load is called
        viewModel.load(modelPath)

        // Then verify the model was loaded and the log was updated
        verify(mockLlamaAndroid).load(modelPath)
        val messages = viewModel.uiMessages.getOrAwaitValue()
        assertTrue(messages.any { it.text == "Loaded $modelPath" })
        assertTrue(messages.any { it.text == "Model context size: $expectedContextSize tokens" })
    }

    @Test
    fun `load failure updates log with error message`() = runTest {
        // Given the load method will throw an exception
        val modelPath = "/fake/path/to/model.gguf"
        val errorMessage = "Failed to load model"
        whenever(mockLlamaAndroid.load(modelPath)).doThrow(IllegalStateException(errorMessage))

        // When load is called
        viewModel.load(modelPath)

        val messages = viewModel.uiMessages.getOrAwaitValue()
        assertTrue(messages.any { it.text == errorMessage })
    }
}
