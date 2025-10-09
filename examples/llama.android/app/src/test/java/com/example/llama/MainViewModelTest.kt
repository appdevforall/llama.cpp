package com.example.llama

import android.app.Application
import android.llama.cpp.LLamaAndroid
import androidx.arch.core.executor.testing.InstantTaskExecutorRule
import com.example.llama.util.MainCoroutineRule
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

        // Inject the TestDispatcher for both main and IO dispatchers
        viewModel = MainViewModel(
            mockApplication,
            mockLlamaAndroid,
            mainCoroutineRule.testDispatcher,
            mainCoroutineRule.testDispatcher
        )
    }

    @Test
    fun `initial state contains an initializing message`() {
        val messages = viewModel.uiMessages.value
        assertEquals(1, messages!!.size)
        assertEquals("Initializing...", messages[0].text)
        assertEquals(MessageType.SYSTEM, messages[0].type)
    }

    @Test
    fun `clear empties the uiMessages list`() {
        viewModel.log("A test message")
        viewModel.clear()
        val messages = viewModel.uiMessages.value
        assertTrue(messages!!.isEmpty())
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
        whenever(mockLlamaAndroid.send(any(), any(), any(), any())) doReturn flowOf("Response")

        viewModel.send()

        verify(mockLlamaAndroid).send(any(), any(), any(), any())
    }

    @Test
    fun `load success updates log and context size`() = runTest {
        val modelPath = "/fake/path/to/model.gguf"
        val expectedContextSize = 2048
        whenever(mockLlamaAndroid.getContextSize()).thenReturn(expectedContextSize)

        viewModel.load(modelPath)

        verify(mockLlamaAndroid).load(modelPath)
        val messages = viewModel.uiMessages.value!!
        assertTrue(messages.any { it.text == "Loaded $modelPath" })
        assertTrue(messages.any { it.text == "Model context size: $expectedContextSize tokens" })
    }

    @Test
    fun `load failure updates log with error message`() = runTest {
        val modelPath = "/fake/path/to/model.gguf"
        val errorMessage = "Failed to load model"
        whenever(mockLlamaAndroid.load(modelPath)).doThrow(IllegalStateException(errorMessage))

        viewModel.load(modelPath)

        val messages = viewModel.uiMessages.value!!
        assertTrue(
            "Error message '$errorMessage' not found in UI messages",
            messages.any { it.text == errorMessage })
    }
}
