<script setup>
export default {
  data: {
    phase: 'Phase 1 — capability probe',
    battery: '--',
    tests: [
      { id: 'storage', name: 'localStorage', state: 'wait', detail: '' },
      { id: 'opfs', name: 'OPFS file system', state: 'wait', detail: '' },
      { id: 'battery', name: 'Battery API', state: 'wait', detail: '' },
      { id: 'camera', name: 'Camera + ImageCapture', state: 'wait', detail: '' },
      { id: 'stt', name: 'Speech Recognition', state: 'wait', detail: '' },
      { id: 'barcode', name: 'BarcodeDetector', state: 'wait', detail: '' },
      { id: 'network', name: 'Network (DeepInfra)', state: 'wait', detail: '' },
    ],
    running: false,
  },

  onReady() {
    this.runAll();
  },

  setResult(id, state, detail) {
    const tests = this.data.tests.map((t) =>
      t.id === id ? { ...t, state, detail: detail || '' } : t
    );
    this.setData({ tests });
  },

  async rerun() {
    if (this.data.running) return;
    this.runAll();
  },

  async runAll() {
    this.setData({ running: true });
    await this.testStorage();
    await this.testOpfs();
    await this.testBattery();
    await this.testCamera();
    await this.testStt();
    await this.testBarcode();
    await this.testNetwork();
    this.setData({ running: false });
  },

  async testStorage() {
    try {
      localStorage.setItem('sidekick.probe', String(Date.now()));
      const v = localStorage.getItem('sidekick.probe');
      this.setResult('storage', v ? 'ok' : 'fail', v ? 'roundtrip ok' : 'empty read');
    } catch (e) {
      this.setResult('storage', 'fail', String(e.message || e).slice(0, 60));
    }
  },

  async testOpfs() {
    try {
      const root = await navigator.storage.getDirectory();
      const dir = await root.getDirectoryHandle('diag', { create: true });
      const fh = await dir.getFileHandle('probe.bin', { create: true });
      const w = await fh.createWritable();
      await w.write(new Blob([new Uint8Array(65536)]));
      await w.close();
      const f = await fh.getFile();
      let quota = 'estimate n/a';
      try {
        const est = await navigator.storage.estimate();
        quota = Math.round(est.quota / 1048576) + 'MB quota';
      } catch (e2) { /* estimate not in subset */ }
      this.setResult(
        'opfs',
        f.size === 65536 ? 'ok' : 'fail',
        'write/read ' + f.size + 'B, ' + quota
      );
    } catch (e) {
      this.setResult('opfs', 'fail', String(e.message || e).slice(0, 60));
    }
  },

  async testBattery() {
    try {
      const b = await navigator.getBattery();
      const pct = Math.round(b.level * 100) + '%';
      this.setData({ battery: pct });
      this.setResult('battery', 'ok', 'level ' + pct + ', charging: ' + b.charging);
    } catch (e) {
      this.setResult('battery', 'fail', String(e.message || e).slice(0, 60));
    }
  },

  async testCamera() {
    let stream = null;
    try {
      stream = await navigator.mediaDevices.getUserMedia({
        audio: false,
        video: { width: { ideal: 640 }, height: { ideal: 480 } },
      });
      const track = stream.getVideoTracks()[0];
      const s = track.getSettings();
      let detail = 'stream ' + (s.width || '?') + 'x' + (s.height || '?');
      try {
        const capture = new ImageCapture(track);
        const photo = await capture.takePhoto({ quality: 'low' });
        const size = photo && photo.size ? Math.round(photo.size / 1024) + 'KB' : 'blob?';
        detail += ', photo ' + size;
      } catch (e2) {
        detail += ', takePhoto: ' + String(e2.message || e2).slice(0, 40);
      }
      this.setResult('camera', detail.indexOf('takePhoto') === -1 ? 'ok' : 'fail', detail);
    } catch (e) {
      this.setResult('camera', 'fail', String(e.message || e).slice(0, 60));
    } finally {
      if (stream) {
        const tracks = stream.getTracks();
        for (const t of tracks) t.stop();
      }
    }
  },

  async testStt() {
    try {
      if (typeof SpeechRecognitionSession === 'undefined' && typeof SpeechRecognition === 'undefined') {
        this.setResult('stt', 'fail', 'no STT global');
        return;
      }
      if (typeof SpeechRecognitionSession !== 'undefined') {
        const caps = await SpeechRecognitionSession.getCapabilities();
        this.setResult('stt', 'ok', (caps.audioFormats || []).length + ' audio formats');
      } else {
        this.setResult('stt', 'ok', 'SpeechRecognition available');
      }
    } catch (e) {
      this.setResult('stt', 'fail', String(e.message || e).slice(0, 60));
    }
  },

  async testBarcode() {
    const has = typeof BarcodeDetector !== 'undefined';
    this.setResult('barcode', has ? 'ok' : 'fail', has ? 'supported' : 'not exposed');
  },

  async testNetwork() {
    const t0 = Date.now();
    try {
      const res = await fetch('https://api.deepinfra.com/v1/openai/models');
      const ms = Date.now() - t0;
      // 200 or 401 both prove DNS + TLS + egress over the BT link.
      this.setResult('network', res.status > 0 ? 'ok' : 'fail', 'HTTP ' + res.status + ' in ' + ms + 'ms');
    } catch (e) {
      this.setResult('network', 'fail', String(e.message || e).slice(0, 60));
    }
  },
};
</script>

<page>
  <scroll-view class="wrap" scroll-y>
    <text class="title">Sidekick</text>
    <text class="g-dim sub">{{phase}}</text>
    <text class="g-mid">Battery: {{battery}}</text>

    <view class="card" bindtap="rerun">
      <text class="{{running ? 'g-dim' : 'g-ok'}}">{{running ? 'Testing...' : 'Tap to re-run all tests'}}</text>
    </view>

    <view class="card" wx:for="{{tests}}" wx:for-item="t" wx:key="id">
      <view class="row">
        <text class="g-ok">{{t.name}}</text>
        <text class="{{t.state === 'ok' ? 'g-ok' : (t.state === 'fail' ? 'g-mid' : 'g-faint')}}">{{t.state}}</text>
      </view>
      <text class="g-dim small" wx:if="{{t.detail}}">{{t.detail}}</text>
    </view>
  </scroll-view>
</page>

<style>
.wrap { padding: 12px; }
.sub { font-size: 13px; margin-bottom: 8px; display: block; }
.small { font-size: 12px; margin-top: 4px; display: block; }
.title { font-size: 22px; font-weight: bold; }
</style>
