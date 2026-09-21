/**
 * Sidekick runtime config.
 * API keys are NEVER committed. Paste keys at deploy time via AIUI Studio
 * "environment" values or through the settings page (Phase 4) — router.js
 * reads them from localStorage first, then falls back to these fields.
 */
export default {
  deepinfra: {
    baseUrl: 'https://api.deepinfra.com/v1/openai',
    apiKey: '', // filled at deploy (or via settings)
    visionModel: 'Qwen/Qwen2.5-VL-72B-Instruct',
    fastTextModel: 'Qwen/Qwen2.5-7B-Instruct',
    longContextModel: 'Qwen/Qwen2.5-32B-Instruct',
  },
  zai: {
    baseUrl: 'https://api.z.ai/api/paas/v4',
    apiKey: '', // filled at deploy (or via settings)
    visionModel: 'glm-4.5v',
    fastTextModel: 'glm-4-air',
  },
  perception: {
    idleFps: 1,            // frame-diff sampling rate while agent is open
    lowBatteryFps: 0.2,    // sampling rate below battery threshold
    pauseBattery: 0.15,    // suspend proactive loops below this level
    frameSampleW: 64,      // downscaled grayscale width for frame diff
    frameSampleH: 48,
    triggerThreshold: 14,  // mean abs pixel delta to consider "scene change"
    cooldownMs: 30000,     // after answering, suppress auto triggers
    photoW: 1280,
    photoH: 720,
    photoQuality: 0.7,
  },
  stt: {
    vadSilenceMs: 1500,    // silence before ending an utterance
    windowMs: 90000,       // rolling transcript window for fact-check
    factcheckEveryMs: 25000,
  },
  memory: {
    maxEpisodesPerDay: 500,
    retrievalTopN: 5,
    recencyHalfLifeH: 36,  // recency weighting for retrieval scoring
  },
};
