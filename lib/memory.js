/**
 * GlassMemory — persistent memory for Sidekick.
 * Tier 1: OPFS (agent-private, on-glasses). Tier 2 cloud sync added in Phase 5.
 *
 * Layout under OPFS root:
 *   memory/profile.json
 *   memory/people/<name>.json
 *   memory/episodes/YYYY-MM-DD.jsonl
 *   memory/index.json   { keywords: { term: [ref,...] } }
 */
import config from './config.js';

const DIR = 'memory';

function today() {
  const d = new Date();
  const p = (n) => (n < 10 ? '0' + n : '' + n);
  return d.getFullYear() + '-' + p(d.getMonth() + 1) + '-' + p(d.getDate());
}

async function getDir(path, create) {
  const root = await navigator.storage.getDirectory();
  const parts = path.split('/').filter(Boolean);
  let dir = root;
  for (const part of parts) {
    dir = await dir.getDirectoryHandle(part, { create: !!create });
  }
  return dir;
}

async function readJson(path, fallback) {
  try {
    const root = await navigator.storage.getDirectory();
    const fileHandle = await root.getFileHandle(path);
    const file = await fileHandle.getFile();
    return JSON.parse(await file.text());
  } catch (e) {
    return fallback;
  }
}

async function writeJson(path, obj) {
  const root = await navigator.storage.getDirectory();
  const parts = path.split('/');
  const name = parts.pop();
  let dir = root;
  for (const part of parts) {
    dir = await dir.getDirectoryHandle(part, { create: true });
  }
  const fh = await dir.getFileHandle(name, { create: true });
  const w = await fh.createWritable();
  await w.write(new Blob([JSON.stringify(obj)]));
  await w.close();
}

export const memory = {
  async ready() {
    // Verify OPFS works at all (Phase 1 gate).
    await writeJson(DIR + '/profile.json', { name: '', prefs: {}, createdAt: Date.now() });
    return true;
  },

  // ---- Episodes (raw Q&A history) ----
  async appendEpisode(entry) {
    const day = today();
    const path = DIR + '/episodes/' + day + '.jsonl';
    try {
      const root = await navigator.storage.getDirectory();
      const parts = path.split('/');
      const name = parts.pop();
      let dir = root;
      for (const part of parts) dir = await dir.getDirectoryHandle(part, { create: true });
      const fh = await dir.getFileHandle(name, { create: true });
      const existing = await fh.getFile();
      const text = await existing.text();
      const line = JSON.stringify({ t: Date.now(), ...entry }) + '\n';
      const w = await fh.createWritable();
      await w.write(new Blob([text + line]));
      await w.close();
      return true;
    } catch (e) {
      return false;
    }
  },

  async recentEpisodes(days, limit) {
    const out = [];
    for (let i = 0; i < (days || 3); i++) {
      const d = new Date(Date.now() - i * 86400000);
      const p = (n) => (n < 10 ? '0' + n : '' + n);
      const day = d.getFullYear() + '-' + p(d.getMonth() + 1) + '-' + p(d.getDate());
      try {
        const root = await navigator.storage.getDirectory();
        const fh = await root.getFileHandle(DIR + '/episodes/' + day + '.jsonl');
        const text = await (await fh.getFile()).text();
        for (const line of text.split('\n')) {
          if (line.trim()) out.push(JSON.parse(line));
        }
      } catch (e) { /* no file for that day */ }
    }
    return out.slice(-(limit || 50));
  },

  // ---- People cards ----
  async upsertPerson(name, fields) {
    const path = DIR + '/people/' + name.toLowerCase() + '.json';
    const card = await readJson(path, { name, firstSeen: Date.now() });
    Object.assign(card, fields, { updated: Date.now() });
    await writeJson(path, card);
    await this.indexTerms([name], 'person:' + name.toLowerCase());
    return card;
  },

  async getPerson(name) {
    return readJson(DIR + '/people/' + name.toLowerCase() + '.json', null);
  },

  // ---- Keyword index (no embeddings needed at this scale) ----
  async indexTerms(terms, ref) {
    const idx = await readJson(DIR + '/index.json', { keywords: {} });
    for (const raw of terms) {
      const term = String(raw).toLowerCase().trim();
      if (!term || term.length < 2) continue;
      const list = idx.keywords[term] || [];
      if (list.indexOf(ref) === -1) list.push(ref);
      idx.keywords[term] = list.slice(-20);
    }
    await writeJson(DIR + '/index.json', idx);
  },

  async searchIndex(query, topN) {
    const idx = await readJson(DIR + '/index.json', { keywords: {} });
    const now = Date.now();
    const halfLife = (config.memory.recencyHalfLifeH || 36) * 3600000;
    const scores = {};
    const meta = {};
    for (const term of query.toLowerCase().split(/\W+/)) {
      const refs = idx.keywords[term];
      if (!refs) continue;
      for (const ref of refs) {
        scores[ref] = (scores[ref] || 0) + 1;
        meta[ref] = meta[ref] || { lastSeen: 0 };
      }
    }
    // Recency boost from episode timestamps encoded in refs (person:xxx only here)
    const results = Object.keys(scores).map((ref) => ({
      ref,
      score: scores[ref],
    }));
    results.sort((a, b) => b.score - a.score);
    return results.slice(0, topN || config.memory.retrievalTopN);
  },

  // ---- Purge ----
  async purgeAll() {
    const root = await navigator.storage.getDirectory();
    try { await root.removeEntry(DIR, { recursive: true }); return true; }
    catch (e) { return false; }
  },

  // ---- Sync snapshot for Tier 2 backup (Phase 5) ----
  async snapshot() {
    return {
      profile: await readJson(DIR + '/profile.json', null),
      index: await readJson(DIR + '/index.json', null),
      episodes: await this.recentEpisodes(7, 300),
    };
  },
};
