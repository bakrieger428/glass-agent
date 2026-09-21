/**
 * Perception helpers — camera capture and frame-diff for handwriting trigger.
 * Phase 1: capture + encode utilities verified by the diagnostics page.
 * Phase 3: continuous idle loop wired here.
 */
import config from './config.js';

export const perception = {
  async openCamera(w, h) {
    const stream = await navigator.mediaDevices.getUserMedia({
      audio: false,
      video: { width: { ideal: w || config.perception.photoW }, height: { ideal: h || config.perception.photoH } },
    });
    return stream;
  },

  /** Full-res photo as base64 data URL (for vision models). */
  async snapDataUrl(stream) {
    const tracks = stream ? stream.getVideoTracks() : null;
    const track = tracks ? tracks[0] : (await this.openCamera()).getVideoTracks()[0];
    const capture = new ImageCapture(track);
    const photo = await capture.takePhoto({ quality: 'high' });
    const blob = photo instanceof Blob ? photo : (photo.blob || photo.imageBitmap);
    const dataUrl = await new Promise((resolve, reject) => {
      const reader = new FileReader();
      reader.onload = () => resolve(reader.result);
      reader.onerror = reject;
      reader.readAsDataURL(blob);
    });
    return dataUrl;
  },

  /** Grayscale downsample matrix for frame-diff (64x48 default). */
  async sampleFrame(stream) {
    const track = stream.getVideoTracks()[0];
    const capture = new ImageCapture(track);
    const frame = await capture.grabFrame();
    const sw = config.perception.frameSampleW;
    const sh = config.perception.frameSampleH;
    // Downscale via offscreen canvas if available; fallback: raw grab stats.
    try {
      const canvas = new OffscreenCanvas(sw, sh);
      const ctx = canvas.getContext('2d');
      ctx.drawImage(frame, 0, 0, sw, sh);
      const data = ctx.getImageData(0, 0, sw, sh).data;
      const gray = new Float32Array(sw * sh);
      for (let i = 0; i < gray.length; i++) {
        gray[i] = 0.299 * data[i * 4] + 0.587 * data[i * 4 + 1] + 0.114 * data[i * 4 + 2];
      }
      return gray;
    } catch (e) {
      return null;
    }
  },

  /** Mean absolute difference between two sample frames. */
  frameDelta(a, b) {
    if (!a || !b || a.length !== b.length) return 0;
    let sum = 0;
    for (let i = 0; i < a.length; i++) sum += Math.abs(a[i] - b[i]);
    return sum / a.length;
  },

  /** "Dark strokes on light field" heuristic — writing on paper signature. */
  strokeScore(gray) {
    if (!gray) return 0;
    let dark = 0;
    let light = 0;
    for (let i = 0; i < gray.length; i++) {
      if (gray[i] < 90) dark++;
      else if (gray[i] > 170) light++;
    }
    return dark / Math.max(1, light); // small ratio on paper, large on dark scenes
  },

  async battery() {
    try {
      const b = await navigator.getBattery();
      return b.level;
    } catch (e) {
      return null;
    }
  },
};
