/**
 * Bridge worker — Phase 1 stub.
 * Later phases: holds the persistent input channel (Rokid cache polling /
 * optional Minis WebSocket) and routes typed text + notification payloads
 * to the active page via postMessage.
 */
export default {
  beats: 0,

  onOpen(event) {
    event.waitUntil(this.heartbeat());
  },

  async heartbeat() {
    // Keep the worker alive while the agent is open; replace with real
    // bridge connection in Phase 4/5.
    while (this.beats < 600) {
      await new Promise((r) => setTimeout(r, 5000));
      this.beats += 1;
      try {
        console.log('[bridge] heartbeat', this.beats);
      } catch (e) { /* console may not exist in worker context */ }
    }
  },
};
