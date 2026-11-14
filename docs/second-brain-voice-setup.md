# Second Brain Voice Capture & Recovery Setup

This guide covers full disaster recovery for **WakeHermesClaw (Android)** + **Second Brain Mac Bridge** from GitHub (`moustafamin1/second-brain` + `moustafamin1/openclaw-assistant`).

## 1. Clone Both Repositories

```bash
git clone https://github.com/moustafamin1/second-brain.git ~/Documents/"2nd Brain"
cd ~/Documents/"2nd Brain"
git clone https://github.com/moustafamin1/openclaw-assistant.git openclaw-assistant
```

## 2. Mac Bridge & Glossary Service (`second-brain`)

- Glossary overrides live in `assets/glossary-overrides.txt`.
- Rebuild the deterministic domain glossary (`assets/ticktick-glossary.json`) via:
  ```bash
  agy-node scripts/build-glossary.js
  ```
- Start the Mac bridge HTTP server (`http://<MAC_LAN_IP>:8642/glossary.json`) via `scripts/voice-bridge.js` (managed automatically by Antigravity sidecars or manually via `agy-node scripts/voice-bridge.js`).

## 3. Optional Offline Whisper Fallback (`whisper-base-en`, Excluded from GitHub)

`app/src/main/assets/whisper-base-en/` (`~166 MB`, including a `125 MB` decoder exceeding GitHub's `100 MB` file limit) is intentionally `.gitignore`d.

- **Default setup:** No Whisper download is required if using **Google On-Device STT (`AiAiSpeechRecognitionService`)**, which handles English, German (`de-DE`), Arabic (where offline packs exist), `EXTRA_BIASING_STRINGS`, and `EXTRA_ENABLE_FORMATTING`.
- **If keeping/enabling Whisper ONNX fallback (`SherpaWhisperEngine.kt`):** Download the `k2-fsa/sherpa-onnx` Whisper `base.en` int8 export (`csukuangfj/sherpa-onnx-whisper-base.en` on Hugging Face) and place the 3 files in `openclaw-assistant/app/src/main/assets/whisper-base-en/`:
  - `base.en-encoder.int8.onnx`
  - `base.en-decoder.int8.onnx`
  - `base.en-tokens.txt`
- For multilingual offline Whisper (German + Arabic), download `csukuangfj/sherpa-onnx-whisper-small` int8 files instead and update filenames/language in `SherpaWhisperEngine.kt`.

## 4. Build, Test & Install Android APK

```bash
cd ~/Documents/"2nd Brain"/openclaw-assistant
./gradlew :app:testStandardDebugUnitTest --tests "com.openclaw.assistant.ticktick.*" :app:assembleStandardDebug
adb install -r app/build/outputs/apk/standard/debug/openclaw-*-debug.apk
```

After install on Pixel/Android:
1. Enable **Accessibility Service** (`AgentVoiceAccessibilityService`) in Android Settings for screen-off volume-button capture.
2. Open **Settings → TickTick Quick Capture**, verify TickTick OAuth/token, confirm **Google On-Device (Best Accuracy)**, tap **Refresh Packs** (for `EN / DE / AR`), and tap **Sync Now**.
