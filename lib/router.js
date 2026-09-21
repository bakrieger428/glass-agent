/**
 * Model router — DeepInfra primary, z.ai fallback. Keys come from
 * localStorage (set via settings page / input bridge) or lib/config.js.
 */
import config from './config.js';

function loadKey(provider) {
  try {
    const k = localStorage.getItem('key.' + provider);
    if (k) return k;
  } catch (e) { /* storage unavailable */ }
  return provider === 'deepinfra' ? config.deepinfra.apiKey : config.zai.apiKey;
}

async function callOpenAICompat(baseUrl, apiKey, model, messages, opts) {
  const res = await fetch(baseUrl + '/chat/completions', {
    method: 'POST',
    headers: {
      'content-type': 'application/json',
      authorization: 'Bearer ' + apiKey,
    },
    body: JSON.stringify({
      model,
      messages,
      max_tokens: (opts && opts.maxTokens) || 300,
      temperature: (opts && opts.temperature) != null ? opts.temperature : 0.4,
      stream: false,
    }),
  });
  if (!res.ok) throw new Error('HTTP ' + res.status);
  const data = await res.json();
  const text = data.choices && data.choices[0] && data.choices[0].message
    ? data.choices[0].message.content : '';
  return text;
}

export const router = {
  hasKey(provider) { return !!loadKey(provider); },

  /**
   * Chat completion with automatic provider fallback.
   * opts.image: { base64: 'data:image/jpeg;base64,...' } attaches a vision image.
   */
  async chat(messages, opts) {
    const withImage = !!(opts && opts.image);
    const providers = [
      {
        name: 'deepinfra',
        baseUrl: config.deepinfra.baseUrl,
        model: withImage ? config.deepinfra.visionModel : config.deepinfra.fastTextModel,
      },
      {
        name: 'zai',
        baseUrl: config.zai.baseUrl,
        model: withImage ? config.zai.visionModel : config.zai.fastTextModel,
      },
    ];
    let lastErr = null;
    for (const p of providers) {
      const key = loadKey(p.name);
      if (!key) { lastErr = new Error('no key for ' + p.name); continue; }
      try {
        const text = await callOpenAICompat(p.baseUrl, key, p.model, messages, opts);
        return { text, provider: p.name, model: p.model };
      } catch (e) {
        lastErr = e;
      }
    }
    throw lastErr || new Error('no providers configured');
  },

  /** Quick image question (PaperChat / perception confirm). */
  async askAboutImage(base64DataUrl, question) {
    const messages = [
      {
        role: 'user',
        content: [
          { type: 'image_url', image_url: { url: base64DataUrl } },
          { type: 'text', text: question },
        ],
      },
    ];
    return this.chat(messages, { image: { base64: base64DataUrl }, maxTokens: 350, temperature: 0.2 });
  },

  /** Reachability probe used by the Phase 1 diagnostics page. */
  async probe() {
    const t0 = Date.now();
    const res = await fetch(config.deepinfra.baseUrl + '/models');
    return { status: res.status, ms: Date.now() - t0 };
  },
};
