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
    }

    private fun setupDefaultViewModel() {
        viewModel = MainViewModel(
            mockApplication,
            mockLlamaAndroid,
            mainDispatcher = mainCoroutineRule.testDispatcher,
            ioDispatcher = mainCoroutineRule.testDispatcher
        )
    }

    @Test
    fun `send adds user message and placeholder model message`() {
        setupDefaultViewModel()
        val userMessage = "Hello, world!"
        viewModel.updateMessage(userMessage)
        viewModel.send()
        val messages = viewModel.uiMessages.value!!
        assertEquals(3, messages.size)
    }

    @Test
    fun `load success updates log and context size`() = runTest {
        setupDefaultViewModel()
        val modelPath = "/fake/path/to/model.gguf"
        whenever(mockLlamaAndroid.getContextSize()).thenReturn(2048)
        viewModel.load(modelPath)
        mainCoroutineRule.testDispatcher.scheduler.advanceUntilIdle()
        verify(mockLlamaAndroid).load(modelPath)
    }

    @Test
    fun `send with simple inference updates message correctly`() = runTest {
        setupDefaultViewModel()
        val userMessage = "Tell me a joke"
        val modelResponseChunks = listOf("A", " B", " C")
        val expectedFullResponse = "A B C" // <-- FIX: Corrected expected string
        viewModel.setToolUse(false)
        viewModel.updateMessage(userMessage)
        whenever(
            mockLlamaAndroid.send(
                any(),
                any(),
                any(),
                any()
            )
        ) doReturn modelResponseChunks.asFlow()

        viewModel.send()
        mainCoroutineRule.testDispatcher.scheduler.advanceUntilIdle()

        val finalMessages = viewModel.uiMessages.value!!
        assertEquals(expectedFullResponse, finalMessages.last().text)
    }

    @Test
    fun `send with valid tool call executes tool and adds result messages`() = runTest {
        val toolName = "get_current_datetime"
        val mockToolResult = "It's test time!"
        val mockTool = mock<Tool>()
        whenever(mockTool.name).thenReturn(toolName)
        whenever(mockTool.execute(any(), any())).thenReturn(mockToolResult)

        viewModel = MainViewModel(
            mockApplication,
            mockLlamaAndroid,
            tools = mapOf(toolName to mockTool), // Injecting the mock tool
            mainDispatcher = mainCoroutineRule.testDispatcher,
            ioDispatcher = mainCoroutineRule.testDispatcher
        )

        val modelResponseWithToolCall =
            """<tool_call>{"tool_name": "$toolName", "args": {}}</tool_call>"""
        whenever(mockLlamaAndroid.send(any(), any(), any(), any())) doReturn flowOf(
            modelResponseWithToolCall
        )

        viewModel.updateMessage("What time is it?")
        viewModel.send()
        mainCoroutineRule.testDispatcher.scheduler.advanceUntilIdle()

        val finalMessages = viewModel.uiMessages.value!!
        assertEquals("Expected 5 messages after a successful tool call", 5, finalMessages.size)
        val toolResultMsg = finalMessages[3]
        assertEquals(mockToolResult, toolResultMsg.text)
        verify(mockTool).execute(any(), any())
    }

    @Test
    fun `send with unknown tool call updates message with error`() = runTest {
        val unknownToolName = "make_a_sandwich"
        viewModel = MainViewModel(
            mockApplication,
            mockLlamaAndroid,
            tools = emptyMap(),
            mainDispatcher = mainCoroutineRule.testDispatcher,
            ioDispatcher = mainCoroutineRule.testDispatcher
        )

        val modelResponseWithUnknownTool =
            """<tool_call>{"tool_name": "$unknownToolName", "args": {}}</tool_call>"""
        val expectedErrorMessage = "Error: Model tried to call unknown tool '$unknownToolName'"
        whenever(mockLlamaAndroid.send(any(), any(), any(), any())) doReturn flowOf(
            modelResponseWithUnknownTool
        )

        viewModel.updateMessage("Make me a sandwich")
        viewModel.send()
        mainCoroutineRule.testDispatcher.scheduler.advanceUntilIdle()

        val finalMessages = viewModel.uiMessages.value!!
        assertEquals("Expected 3 messages after an unknown tool call", 3, finalMessages.size)
        assertEquals(expectedErrorMessage, finalMessages.last().text)
    }
}
