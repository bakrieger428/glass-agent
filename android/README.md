# Sidekick — native Android APK

iPhone-only build via GitHub Actions. Zero external dependencies (no AndroidX).

- **UI**: programmatic, monochrome-green HUD (matches glasses display)
- **Camera**: Camera2 single-shot JPEG → DeepInfra Qwen2.5-VL (z.ai glm-4.5v fallback)
- **Keys**: paste from iPhone at `http://<glasses-ip>:8080` (built-in server)
- **Input**: temple tap / DPAD center / screen tap = capture; swipe or volume = scroll; back = background
- **Memory**: daily episode log in `files/episodes/*.jsonl` (GlassMemory seed)
- **TTS**: system TextToSpeech when available, silent otherwise

Build: push to `main` (or run the workflow manually) → download `Sidekick.apk`
from the repo's Releases page → Hi Rokid → Toolbox → Install Local App.
