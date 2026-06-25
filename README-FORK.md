# llama.cpp Android Fork

This is a fork of [ggml-org/llama.cpp](https://github.com/ggml-org/llama.cpp) with Android-specific customizations for the [CodeOnTheGo](https://github.com/appdevforall/CodeOnTheGo) IDE.

## Branches

- **`master`**: Tracks upstream llama.cpp (read-only)
- **`androidide-custom`**: Contains Android modifications (default branch)

## Custom Features

- Android JNI wrapper for on-device inference
- Tool calling with JSON parsing
- Agent loop with stop tokens
- Model family support (Gemma-2, Llama 3)
- KV cache management
- Enhanced Android example app

## Maintenance

See [docs/ANDROID_FORK_MAINTENANCE.md](docs/ANDROID_FORK_MAINTENANCE.md) for rebase workflow and update procedures.

## Upstream

This fork is periodically rebased onto [ggml-org/llama.cpp](https://github.com/ggml-org/llama.cpp) to incorporate upstream improvements.

**Base commit:** `6a85531c` (tagged as `androidide-base-2025`)

To see custom changes:
```bash
git log androidide-base-2025..androidide-custom --oneline
```

## Usage in CodeOnTheGo

This fork is used as a git submodule in:
- `plugin-examples/ai-assistant/subprojects/llama.cpp`

## License

Same as upstream llama.cpp: MIT License
