# Android Fork Maintenance Guide

This document describes how to maintain the `androidide-custom` branch with upstream llama.cpp updates.

## Repository Structure

- **Fork:** `appdevforall/llama.cpp`
- **Upstream:** `ggml-org/llama.cpp`
- **Custom Branch:** `androidide-custom` (contains Android-specific modifications)
- **Master Branch:** `master` (tracks upstream master, read-only)

## Branches

### master
- Tracks `ggml-org/llama.cpp` master
- Never commit directly to this branch
- Periodically fast-forward from upstream

### androidide-custom
- Contains ~60 custom Android commits
- Based on upstream commit `6a85531c` (tagged as `androidide-base-2025`)
- Rebased periodically onto upstream master
- Used by plugin-examples/ai-assistant as git submodule

## Periodic Update Workflow

### 1. Update master from upstream

```bash
git checkout master
git fetch upstream
git merge --ff-only upstream/master
git push origin master
```

### 2. Rebase androidide-custom onto latest master

```bash
git checkout androidide-custom
git fetch origin
git rebase origin/master
```

**If conflicts occur:**
```bash
# For each conflict:
git status  # identify conflicting files
# Resolve conflicts in editor
git add <resolved-files>
git rebase --continue

# If rebase gets too messy:
git rebase --abort
# Then manually cherry-pick problematic commits
```

### 3. Test the rebased branch

```bash
cd examples/llama.android
./gradlew assembleDebug
# Run tests, verify Android app works
```

### 4. Push rebased branch

```bash
git push origin androidide-custom --force-with-lease
```

**IMPORTANT:** Use `--force-with-lease`, not `--force`. This prevents overwriting commits pushed by others.

### 5. Update submodules in dependent projects

```bash
cd /path/to/plugin-examples/ai-assistant
git submodule update --remote subprojects/llama.cpp
cd subprojects/llama.cpp
git checkout androidide-custom
git pull
cd ../..
git add subprojects/llama.cpp
git commit -m "chore: Update llama.cpp submodule to latest androidide-custom"
git push
```

## Custom Commits Overview

Our custom commits add:
- Android JNI wrapper (`llama/src/main/java/android/llama/cpp/LLamaAndroid.kt`)
- Tool calling with JSON parsing
- Agent loop with stop tokens
- Model switching support (Gemma-2, Llama 3)
- KV cache management
- Android example app improvements
- Unit tests for Android components

To see all custom changes:
```bash
git log androidide-base-2025..androidide-custom --oneline
```

To see diff from base:
```bash
git diff androidide-base-2025..androidide-custom
```

## Conflict Resolution Tips

Common conflict areas:
- `examples/llama.android/app/src/main/java/` - Our custom Android code
- `examples/llama.android/llama/src/main/java/android/llama/cpp/` - JNI wrapper
- `examples/llama.android/build.gradle.kts` - Build configuration

**Strategy:** Accept upstream changes for core llama.cpp, keep custom changes for Android-specific code.

## Troubleshooting

### "fatal: refusing to merge unrelated histories"
This means the branches diverged significantly. Use:
```bash
git rebase --onto origin/master androidide-base-2025 androidide-custom
```

### Cherry-pick individual commits
If rebase is too complex, cherry-pick specific custom commits:
```bash
git checkout origin/master -b androidide-custom-new
git cherry-pick <commit-hash>  # repeat for each custom commit
```

### Reset to previous state
If rebase goes wrong:
```bash
git reflog  # find previous HEAD
git reset --hard HEAD@{N}  # where N is the reflog entry
```

## Contact

For questions about Android fork maintenance:
- GitHub Issues: https://github.com/appdevforall/llama.cpp/issues
- Main project: https://github.com/appdevforall/CodeOnTheGo
