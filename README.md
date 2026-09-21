# Sidekick — Proactive Assistant for Rokid Glasses

Single-agent system that runs on Rokid AI Glasses (YodaOS-Sprite / AIUI runtime):

- **Proactive perception** — watches the environment (camera frame-diff) and listens
  (speech recognition), surfaces fact-checks and helpful info as HUD cards.
- **Auto PaperChat** — detects when the wearer writes a question on paper (no wake
  gesture), reads it with a vision model, answers on the HUD + TTS.
- **GlassMemory** — persistent memory: people cards, project facts, and an episode
  log (Q&A history) stored in agent-private OPFS, with cloud backup.
- **Model router** — DeepInfra first, z.ai fallback. No Gemini.
- **iPhone input** — text arrives via Rokid cloud notification/cache APIs (Apple
  Shortcuts) and optionally a Minis local bridge.

## Layout

- `AGENTS.md` — identity and behavioral rules
- `app.json` — pages, widget, worker, permissions
- `pages/home/` — main assistant page (Phase 1: capability diagnostics)
- `widgets/assist/` — ambient 1x2 status card
- `workers/bridge.js` — background worker (input bridge stub)
- `lib/` — memory, model router, perception, config

## Deploy (local only, no store)

AIUI Studio → Package AIX → Hi Rokid iOS app → Settings → Developer →
Update glasses resource package → "Hi Rokid, open Sidekick".
