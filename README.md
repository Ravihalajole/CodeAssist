# CodeAssist

CodeAssist is an elite, Android-native developer execution engine running a localized clipboard processing loop. It parses specialized instruction payloads to execute deterministic file mutations directly inside your physical local workspace storage system.

Designed specifically for AI-augmented local engineering pipelines, CodeAssist serves as a lightweight, secure bridge that applies structural repository modifications without relying on external cloud environments or heavy desktop setups.

Note: It's a vibe coded app.

## Core Features

- **Clipboard Processing Loop:** Automated or manual interception of payloads safely wrapped in `:::CODE_ASSIST:::` envelopes.
- **Pre-Flight Validation:** Each command block is validated against the workspace before execution. Failing commands are surfaced individually with corrective context; successful ones still commit so the model can re-emit only the failed commands. Execution is partial-success tolerant (non-atomic by design to save tokens).
- **In-Memory Stitching Loop:** Partial-state transaction caching that isolates downstream failures, allowing the model to repair and continue from the point of failure without re-transmitting heavy transaction histories.
- **High-Performance JGit Architecture:** Transitions workspace updates from standard workspace walks down to individual file staging maps ($O(1)$ operations) for localized processing efficiency.
- **Deadlock Resilience Engine:** Multitiered lock guards that isolate thread synchronization (`Mutex`) and proactively clean up transient file system `.git/index.lock` deadlocks.
- **Memory-Efficient Evaluation Loop:** Swaps out garbage-collector-heavy text splitters for allocation-free sliding index tracking arrays when executing multi-pass parsing updates.
- **Material 3 Design Center:** Polished layout containing structured settings menus, horizontal workspace configuration anchors, and a sleek section-grouped commit history workspace view.

## Technology Stack & Environment

- **Language Stack:** 100% Kotlin with Gradle Kotlin DSL (`build.gradle.kts`).
- **Core Staging Engine:** Native Android JSON parsing, regular expression streaming filters, and atomic loop managers.
- **Version Control Integration:** Eclipse JGit framework layer.
- **Target Boundaries:** Minimum SDK 23, Target SDK 34, Compile SDK 36.
- **Background Interface:** Resilient overlay window tracking managed via an Android Foreground Service declared under explicit `dataSync` profile guidelines.

## Quick Start Configuration

1. **Select Workspace:** Open CodeAssist and map the active target workspace directory on internal device memory via the SAF directory picker.
2. **Synchronize Identity:** Navigate to Settings and input your standard Git commit metadata author profile handles.
3. **Seed AI Protocol:** Tap `Copy Protocol & Workspace Structure` from the System Protocol dashboard to instantly package your local architectural context back into your generative AI instruction flow.

## License

This software utility workspace is freely distributed under the terms of the **MIT License**. For deep structural documentation, inspect the `LICENSE` file found in the project root directory.