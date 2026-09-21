<script setup>
export default {
  data: {
    phase: 'Phase 1 — capability probe',
    battery: '--',
    running: false,
    n1: 'localStorage', s1: 'wait', d1: '', c1: 'g-faint',
    n2: 'OPFS file system', s2: 'wait', d2: '', c2: 'g-faint',
    n3: 'Battery API', s3: 'wait', d3: '', c3: 'g-faint',
    n4: 'Camera + ImageCapture', s4: 'wait', d4: '', c4: 'g-faint',
    n5: 'Speech Recognition', s5: 'wait', d5: '', c5: 'g-faint',
    n6: 'BarcodeDetector', s6: 'wait', d6: '', c6: 'g-faint',
    n7: 'Network (DeepInfra)', s7: 'wait', d7: '', c7: 'g-faint',
  },

  onReady() {
    this.runAll();
  },

  withTimeout(p, ms) {
    return Promise.race([
      p,
      new Promise((resolve, reject) => {
        setTimeout(() => reject(new Error('timeout ' + ms + 'ms')), ms);
      }),
    ]);
  },

  setResult(idx, state, detail) {
    const cls = state === 'ok' ? 'g-ok' : state === 'fail' ? 'g-mid' : 'g-faint';
    const patch = {};
    patch['s' + idx] = state;
    patch['d' + idx] = detail || '';
    patch['c' + idx] = cls;
    this.setData(patch);
  },

  async rerun() {
    this.runAll();
  },

  async runAll() {
    if (this.data.running) return;
    this.setData({ running: true });
    const tests = [
      this.testStorage,
      this.testOpfs,
      this.testBattery,
      this.testCamera,
      this.testStt,
      this.testBarcode,
      this.testNetwork,
    ];
    for (let i = 0; i < tests.length; i++) {
      this.setResult(i + 1, 'run', '');
      try {
        await this.withTimeout(tests[i].call(this), 9000);
      } catch (e) {
        this.setResult(i + 1, 'fail', String(e.message || e).slice(0, 60));
      }
    }
    this.setData({ running: false });
  },

  async testStorage() {
    try {
      localStorage.setItem('sidekick.probe', String(Date.now()));
      const v = localStorage.getItem('sidekick.probe');
      this.setResult(1, v ? 'ok' : 'fail', v ? 'roundtrip ok' : 'empty read');
    } catch (e) {
      this.setResult(1, 'fail', String(e.message || e).slice(0, 60));
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
      this.setResult(2, f.size === 65536 ? 'ok' : 'fail', 'write/read ' + f.size + 'B, ' + quota);
    } catch (e) {
      this.setResult(2, 'fail', String(e.message || e).slice(0, 60));
    }
  },

  async testBattery() {
    try {
      const b = await navigator.getBattery();
      const pct = Math.round(b.level * 100) + '%';
      this.setData({ battery: pct });
      this.setResult(3, 'ok', 'level ' + pct + ', charging: ' + b.charging);
    } catch (e) {
      this.setResult(3, 'fail', String(e.message || e).slice(0, 60));
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
      let ok = true;
      try {
        const capture = new ImageCapture(track);
        const photo = await capture.takePhoto({ quality: 'low' });
        const size = photo && photo.size ? Math.round(photo.size / 1024) + 'KB' : 'blob?';
        detail += ', photo ' + size;
      } catch (e2) {
        ok = false;
        detail += ', takePhoto: ' + String(e2.message || e2).slice(0, 40);
      }
      this.setResult(4, ok ? 'ok' : 'fail', detail);
    } catch (e) {
      this.setResult(4, 'fail', String(e.message || e).slice(0, 60));
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
        this.setResult(5, 'fail', 'no STT global');
        return;
      }
      if (typeof SpeechRecognitionSession !== 'undefined') {
        const caps = await SpeechRecognitionSession.getCapabilities();
        this.setResult(5, 'ok', (caps.audioFormats || []).length + ' audio formats');
      } else {
        this.setResult(5, 'ok', 'SpeechRecognition available');
      }
    } catch (e) {
      this.setResult(5, 'fail', String(e.message || e).slice(0, 60));
    }
  },

  async testBarcode() {
    const has = typeof BarcodeDetector !== 'undefined';
    this.setResult(6, has ? 'ok' : 'fail', has ? 'supported' : 'not exposed');
  },

  async testNetwork() {
    const t0 = Date.now();
    try {
      const res = await fetch('https://api.deepinfra.com/v1/openai/models');
      const ms = Date.now() - t0;
      // 200 or 401 both prove DNS + TLS + egress over the BT link.
      this.setResult(7, res.status > 0 ? 'ok' : 'fail', 'HTTP ' + res.status + ' in ' + ms + 'ms');
    } catch (e) {
      this.setResult(7, 'fail', String(e.message || e).slice(0, 60));
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

    <view class="card">
      <view class="row">
        <text class="g-ok">{{n1}}</text>
        <text class="{{c1}}">{{s1}}</text>
      </view>
      <text class="g-dim small">{{d1}}</text>
    </view>

    <view class="card">
      <view class="row">
        <text class="g-ok">{{n2}}</text>
        <text class="{{c2}}">{{s2}}</text>
      </view>
      <text class="g-dim small">{{d2}}</text>
    </view>

    <view class="card">
      <view class="row">
        <text class="g-ok">{{n3}}</text>
        <text class="{{c3}}">{{s3}}</text>
      </view>
      <text class="g-dim small">{{d3}}</text>
    </view>

    <view class="card">
      <view class="row">
        <text class="g-ok">{{n4}}</text>
        <text class="{{c4}}">{{s4}}</text>
      </view>
      <text class="g-dim small">{{d4}}</text>
    </view>

    <view class="card">
      <view class="row">
        <text class="g-ok">{{n5}}</text>
        <text class="{{c5}}">{{s5}}</text>
      </view>
      <text class="g-dim small">{{d5}}</text>
    </view>

    <view class="card">
      <view class="row">
        <text class="g-ok">{{n6}}</text>
        <text class="{{c6}}">{{s6}}</text>
      </view>
      <text class="g-dim small">{{d6}}</text>
    </view>

    <view class="card">
      <view class="row">
        <text class="g-ok">{{n7}}</text>
        <text class="{{c7}}">{{s7}}</text>
      </view>
      <text class="g-dim small">{{d7}}</text>
    </view>
  </scroll-view>
</page>

<style>
.wrap { padding: 12px; }
.sub { font-size: 13px; margin-bottom: 8px; display: block; }
.small { font-size: 12px; margin-top: 4px; display: block; }
.title { font-size: 22px; font-weight: bold; }
</style>
