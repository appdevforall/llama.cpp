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
}
