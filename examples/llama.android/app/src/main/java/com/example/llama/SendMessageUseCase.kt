package com.example.llama

// A use case should ideally do one thing.
class SendMessageUseCase(
    private val chatRepository: ChatRepository
) {
    // The 'invoke' operator allows us to call the class instance as a function.
    suspend operator fun invoke(
        userInput: String,
        isStreaming: Boolean,
        isToolUseEnabled: Boolean
    ) {
        chatRepository.sendMessage(userInput, isStreaming, isToolUseEnabled)
    }
}
