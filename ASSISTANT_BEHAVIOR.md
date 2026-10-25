# PrismCampus assistant

- Demo timetable data remains MSRIT. The IIT Guwahati map change is deferred.
- Next class, timetable, assignments, known campus destinations and greetings use saved records immediately, with the existing text-to-speech output.
- Arithmetic such as `8+5` or spoken "eight plus five" is calculated locally without any model or network request.
- Chat is owned above the Home/Schedule/Map tabs and saved in private app preferences (latest 200 messages). Switching tabs keeps both the conversation and in-flight answers; reopening the app restores saved messages. Home also retains its scroll position between tabs.
- General questions use Gemini on a validated internet connection, using the existing BuildConfig key configuration. A cloud failure falls back to Qwen.
- Offline general questions use Qwen2.5-0.5B-Instruct Q4_K_M through a native llama.cpp engine. The existing `server/models/qwen2.5-0.5b-instruct-q4_k_m.gguf` is packaged in the APK and installed into private app storage on first use. No laptop server or internet connection is needed for this path.
- The first offline question takes longer because the model must be installed. Subsequent questions still load the model for inference and release it after each answer to reduce idle memory usage.
- Native generation uses a 512-token context, at most 64 output tokens and a 32-token processing microbatch, with short prompts. CPU inference uses optimized Release compilation even in a debug APK. A 20-second native deadline aborts model loading/CPU decoding through llama.cpp callbacks; existing text is returned if generation already started. Device storage setup and resource cleanup can add time outside decoding. It checks app RSS against 1024 MiB before loading and during decoding. This is a best-effort memory budget, not a guarantee of an Android process memory ceiling: allocation spikes and device-specific OS limits can occur between checks.
- The home screen samples actual process PSS once per second while visible, displaying measured memory against an 1024 MiB budget. Android total PSS includes proportional shared pages and proportional swap; the native guard independently checks resident RSS. The fallback measurement is labelled JVM Heap if process sampling fails. Readings are never clipped to hide usage above budget.
- Offline voice uses Vosk. Repeated identical partial transcripts no longer reset the silence clock. A query completes after 1.2 seconds without transcript changes, with a 15-second overall speech window. Offline generation never probes laptop/LAN endpoints after an on-device failure.

## Native build

Uses Android NDK 26.1.10909125 and CMake 3.22.1 for arm64-v8a phones and x86_64 emulators. The first build installs these tools through the Android SDK. llama.cpp source is vendored at b5046, commit `74d4f5b041ad837153b0e90fc864b8290e01d8d5`, with its upstream license. This avoids relying on MediaPipe to open an incompatible GGUF file.

## Manual device verification

Validation on 24 October 2026: all 27 JVM tests passed; both native ABI builds and the APK build succeeded. A direct test on a connected Android phone calculated spoken arithmetic locally and generated a programming answer with Qwen in 13,725 ms. This direct native test does not exercise live microphone audio or physically tap through the tabs; regression tests cover speech endpoint timing and chat persistence independently.

1. Ask "Where is my next class?" and "Show my timetable" online and in airplane mode. Confirm an immediate campus answer using the selected MSRIT section.
2. Ask "What is a Java package?" online. Confirm the response identifies Cloud Gemini rather than recording a maintenance complaint.
3. Disable internet and ask the same question. Confirm the response identifies On-Device Qwen, without a laptop or LAN server.
4. Watch process memory rise during offline generation and fall after the response. Confirm the UI continues updating and shows the 1024 MiB denominator.
5. Ask "Hostel mess fan is broken" and confirm it still records a maintenance report; asking about a class or a hostel must not create a report.
6. Ask two questions on Home, switch to Schedule and Maps, and return. Both questions and answers should remain, including an answer that completed while Home was hidden. Close and reopen the app to verify saved history.
7. In airplane mode, say "eight plus five", then pause without pressing Stop. Confirm the transcript finalizes and the app answers "The answer is 13."


## Memory profiling and final budget handoff

The shared generation budget is defined in `AppMemoryBudget.MIB`; normal builds pass it to native inference and the monitor. An explicit `-PmemoryProfiling=true` build temporarily passes zero to disable only that guard and labels the monitor as profiling. The normal default is profiling disabled. There is no runtime UI switch to disable the guard.

See [the measured memory report](artifacts/memory-profile/REPORT.md) for unrestricted online/offline results, metric definitions, response times, memory release, device coverage, and limitations. The final 1024 MiB change follows all profiling and regression checks. By request, only one APK assembly follows it; there is no subsequent profiling, device testing, installation or launch.
