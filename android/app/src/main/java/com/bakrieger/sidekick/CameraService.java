package com.bakrieger.sidekick;

import android.content.Context;
import android.graphics.ImageFormat;
import android.hardware.camera2.CameraCaptureSession;
import android.hardware.camera2.CameraCharacteristics;
import android.hardware.camera2.CameraDevice;
import android.hardware.camera2.CameraManager;
import android.hardware.camera2.CaptureRequest;
import android.media.Image;
import android.media.ImageReader;
import android.os.Handler;
import android.os.HandlerThread;
import android.util.Base64;
import android.view.Surface;

import java.nio.ByteBuffer;
import java.util.Collections;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

/** Camera2 single-shot JPEG capture with capability checks and a minimal-config retry ladder. */
public final class CameraService {

    public interface Callback {
        void onJpeg(byte[] jpeg);
        void onError(String message);
    }

    private CameraService() {}

    public static String pickCamera(Context ctx) {
        try {
            CameraManager cm = (CameraManager) ctx.getSystemService(Context.CAMERA_SERVICE);
            if (cm == null) return null;
            String fallback = null;
            for (String id : cm.getCameraIdList()) {
                CameraCharacteristics ch = cm.getCameraCharacteristics(id);
                Integer facing = ch.get(CameraCharacteristics.LENS_FACING);
                if (facing != null && facing == CameraCharacteristics.LENS_FACING_BACK) {
                    return id;
                }
                if (fallback == null) fallback = id;
            }
            return fallback;
        } catch (Exception e) {
            return null;
        }
    }

    /** Full hardware facts for /diag: every camera, sizes, modes, AE range. */
    public static String fullFacts(Context ctx) {
        StringBuilder sb = new StringBuilder();
        try {
            CameraManager cm = (CameraManager) ctx.getSystemService(Context.CAMERA_SERVICE);
            if (cm == null) return "no CameraManager";
            for (String id : cm.getCameraIdList()) {
                CameraCharacteristics ch = cm.getCameraCharacteristics(id);
                Integer facing = ch.get(CameraCharacteristics.LENS_FACING);
                String f = facing == null ? "null" : facing == 0 ? "FRONT" : facing == 1 ? "BACK" : "EXTERNAL";
                sb.append("camera #").append(id).append(" ").append(f).append('\n');
                StringBuilder sizes = new StringBuilder();
                for (android.util.Size s : jpegSizes(ch)) {
                    sizes.append(s.getWidth()).append('x').append(s.getHeight()).append(' ');
                }
                sb.append("  jpeg sizes: ").append(sizes).append('\n');
                int[] af = ch.get(CameraCharacteristics.CONTROL_AF_AVAILABLE_MODES);
                sb.append("  af modes: ").append(java.util.Arrays.toString(af)).append('\n');
                int[] nr = ch.get(CameraCharacteristics.NOISE_REDUCTION_AVAILABLE_NOISE_REDUCTION_MODES);
                sb.append("  nr modes: ").append(java.util.Arrays.toString(nr)).append('\n');
                int[] em = ch.get(CameraCharacteristics.EDGE_AVAILABLE_EDGE_MODES);
                sb.append("  edge modes: ").append(java.util.Arrays.toString(em)).append('\n');
                android.util.Range<Integer> range = ch.get(CameraCharacteristics.CONTROL_AE_COMPENSATION_RANGE);
                android.util.Rational step = ch.get(CameraCharacteristics.CONTROL_AE_COMPENSATION_STEP);
                sb.append("  ae comp range: ").append(range).append(" step ").append(step).append('\n');
            }
        } catch (Exception e) {
            sb.append("facts err: ").append(e.getMessage());
        }
        return sb.toString();
    }

    /** Human-readable info about the camera that will be used (for diagnostics). */
    public static String cameraInfo(Context ctx) {
        try {
            CameraManager cm = (CameraManager) ctx.getSystemService(Context.CAMERA_SERVICE);
            String id = pickCamera(ctx);
            if (id == null || cm == null) return "NONE";
            CameraCharacteristics ch = cm.getCameraCharacteristics(id);
            Integer facing = ch.get(CameraCharacteristics.LENS_FACING);
            String f = facing == null ? "ext" : facing == 0 ? "FRONT" : facing == 1 ? "BACK" : "EXTERNAL";
            android.util.Size[] sizes = jpegSizes(ch);
            int maxW = 0;
            for (android.util.Size s : sizes) maxW = Math.max(maxW, s.getWidth());
            return "#" + id + " " + f + " max" + maxW + "px";
        } catch (Exception e) {
            return "err " + e.getMessage();
        }
    }

    /** Full capture for paper OCR: walks the config ladder until the sensor delivers. */
    public static void capture(final Context ctx, final Callback cb) {
        walkLadder(ctx, cb, new int[]{0, 1, 2, 3, 4}, 0);
    }

    /** Scan capture for motion detection: bare configs only. */
    public static void captureScan(final Context ctx, final Callback cb) {
        walkLadder(ctx, cb, new int[]{3, 4}, 0);
    }

    private static void walkLadder(final Context ctx, final Callback cb, final int[] stages, final int idx) {
        if (idx >= stages.length) { cb.onError("all ladder stages failed"); return; }
        final int stage = stages[idx];
        captureInternal(ctx, new Callback() {
            @Override public void onJpeg(byte[] jpeg) { cb.onJpeg(jpeg); }
            @Override public void onError(String message) {
                Diag.log("cam: stage " + stage + " FAILED (" + message + ")");
                walkLadder(ctx, cb, stages, idx + 1);
            }
        }, stage);
    }

    /** Software brightness boost (gamma-style gain) for dark captures - no driver risk. */
    public static byte[] boostBrightness(byte[] jpeg, float gain) {
        try {
            android.graphics.Bitmap bmp = android.graphics.BitmapFactory.decodeByteArray(jpeg, 0, jpeg.length);
            if (bmp == null) return jpeg;
            android.graphics.ColorMatrix cm = new android.graphics.ColorMatrix();
            cm.setScale(gain, gain, gain, 1f);
            android.graphics.Bitmap out = android.graphics.Bitmap.createBitmap(bmp.getWidth(), bmp.getHeight(), android.graphics.Bitmap.Config.ARGB_8888);
            android.graphics.Canvas canvas = new android.graphics.Canvas(out);
            android.graphics.Paint paint = new android.graphics.Paint();
            paint.setColorFilter(new android.graphics.ColorMatrixColorFilter(cm));
            canvas.drawBitmap(bmp, 0, 0, paint);
            java.io.ByteArrayOutputStream bos = new java.io.ByteArrayOutputStream();
            out.compress(android.graphics.Bitmap.CompressFormat.JPEG, 82, bos);
            bmp.recycle();
            out.recycle();
            byte[] boosted = bos.toByteArray();
            Diag.log("cam: brightness x" + gain + " " + (jpeg.length / 1024) + "KB->" + (boosted.length / 1024) + "KB");
            return boosted;
        } catch (Exception e) {
            Diag.log("cam: boost skipped " + e.getMessage());
            return jpeg;
        }
    }

    private static android.util.Size[] jpegSizes(CameraCharacteristics ch) {
        try {
            return ch.get(CameraCharacteristics.SCALER_STREAM_CONFIGURATION_MAP)
                    .getOutputSizes(ImageFormat.JPEG);
        } catch (Exception e) {
            return new android.util.Size[0];
        }
    }

    private static android.util.Size chooseSize(CameraCharacteristics ch, int targetWidth, boolean minimal) {
        android.util.Size[] sizes = jpegSizes(ch);
        if (sizes.length == 0) return new android.util.Size(640, 480);
        android.util.Size smallest = sizes[0];
        for (android.util.Size s : sizes) {
            if (s.getWidth() * s.getHeight() < smallest.getWidth() * smallest.getHeight()) smallest = s;
        }
        if (minimal) return smallest;
        android.util.Size best = null;
        for (android.util.Size s : sizes) {
            if (s.getWidth() > 3200) continue;
            if (best == null || Math.abs(s.getWidth() - targetWidth) < Math.abs(best.getWidth() - targetWidth)) {
                best = s;
            }
        }
        return best != null ? best : smallest;
    }

    /** Stages: 0=1920+AE12, 1=1920+AE4, 2=1920 bare, 3=640 bare, 4=smallest. */
    private static void captureInternal(final Context ctx, final Callback cb, final int stage) {
        final Handler ui = new Handler(ctx.getMainLooper());
        final String cameraId = pickCamera(ctx);
        if (cameraId == null) { ui.post(() -> cb.onError("no camera found")); return; }
        final int targetWidth = stage <= 2 ? 1920 : 640;
        final boolean minimal = stage == 4;

        HandlerThread thread = new HandlerThread("sidekick-cam");
        thread.start();
        final Handler handler = new Handler(thread.getLooper());
        final ExecutorService exec = Executors.newSingleThreadExecutor();

        final CameraManager cm = (CameraManager) ctx.getSystemService(Context.CAMERA_SERVICE);
        try {
            final CameraCharacteristics ch = cm.getCameraCharacteristics(cameraId);
            final android.util.Size size = chooseSize(ch, targetWidth, minimal);
            Diag.log("cam: opening #" + cameraId + " @" + size.getWidth() + "x" + size.getHeight()
                    + " stage" + stage);

            final ImageReader reader = ImageReader.newInstance(size.getWidth(), size.getHeight(), ImageFormat.JPEG, 1);
            final boolean[] done = {false};

            final Runnable finish = new Runnable() {
                @Override public void run() {
                    if (done[0]) return;
                    done[0] = true;
                    try { reader.close(); } catch (Exception ignored) {}
                    exec.shutdown();
                    thread.quitSafely();
                }
            };

            reader.setOnImageAvailableListener(r -> {
                Image img = null;
                try {
                    img = r.acquireLatestImage();
                    if (img == null) return;
                    ByteBuffer buf = img.getPlanes()[0].getBuffer();
                    byte[] jpeg = new byte[buf.remaining()];
                    buf.get(jpeg);
                    finish.run();
                    Diag.log("cam: jpeg " + (jpeg.length / 1024) + "KB");
                    final byte[] out = jpeg;
                    ui.post(() -> cb.onJpeg(out));
                } catch (Exception e) {
                    finish.run();
                    Diag.log("cam: image read FAIL " + e.getMessage());
                    ui.post(() -> cb.onError("image read: " + e.getMessage()));
                } finally {
                    if (img != null) try { img.close(); } catch (Exception ignored) {}
                }
            }, handler);

            handler.postDelayed(() -> {
                if (!done[0]) {
                    finish.run();
                    Diag.log("cam: TIMEOUT @" + size.getWidth() + "x" + size.getHeight());
                    ui.post(() -> cb.onError("timeout @" + size.getWidth() + "x" + size.getHeight()));
                }
            }, stage <= 2 ? 12000 : 8000);

            cm.openCamera(cameraId, new CameraDevice.StateCallback() {
                @Override public void onOpened(final CameraDevice camera) {
                    try {
                        Surface surface = reader.getSurface();
                        final CaptureRequest.Builder req = camera.createCaptureRequest(CameraDevice.TEMPLATE_STILL_CAPTURE);
                        req.addTarget(surface);
                        req.set(CaptureRequest.JPEG_QUALITY, (byte) (stage >= 3 ? 75 : 82));
                        applyStageKeys(req, ch, stage);

                        camera.createCaptureSession(Collections.singletonList(surface),
                                new CameraCaptureSession.StateCallback() {
                                    @Override public void onConfigured(final CameraCaptureSession session) {
                                        Diag.log("cam: session configured");
                                        try {
                                            session.capture(req.build(), new CameraCaptureSession.CaptureCallback() {}, handler);
                                        } catch (Exception e) {
                                            finish.run();
                                            Diag.log("cam: capture start FAIL " + e.getMessage());
                                            ui.post(() -> cb.onError("capture start @" + size.getWidth() + ": " + e.getMessage()));
                                        }
                                    }
                                    @Override public void onConfigureFailed(CameraCaptureSession session) {
                                        finish.run();
                                        Diag.log("cam: session CONFIG FAILED @" + size.getWidth() + "x" + size.getHeight());
                                        ui.post(() -> cb.onError("session config failed @" + size.getWidth() + "x" + size.getHeight()));
                                    }
                                }, handler);
                    } catch (Exception e) {
                        finish.run();
                        try { camera.close(); } catch (Exception ignored) {}
                        ui.post(() -> cb.onError("open: " + e.getMessage()));
                    }
                }
                @Override public void onDisconnected(CameraDevice camera) {
                    finish.run();
                    try { camera.close(); } catch (Exception ignored) {}
                }
                @Override public void onError(CameraDevice camera, int error) {
                    finish.run();
                    try { camera.close(); } catch (Exception ignored) {}
                    Diag.log("cam: device ERROR " + error);
                    ui.post(() -> cb.onError("camera error " + error));
                }
            }, handler);
        } catch (Exception e) {
            thread.quitSafely();
            exec.shutdown();
            ui.post(() -> cb.onError("camera: " + e.getMessage()));
        }
    }

    /** Stage-based keys: AE compensation only on stages 0/1; FAST processing modes everywhere. */
    private static void applyStageKeys(CaptureRequest.Builder req, CameraCharacteristics ch, int stage) {
        try {
            android.util.Range<Integer> range = ch.get(CameraCharacteristics.CONTROL_AE_COMPENSATION_RANGE);
            android.util.Rational step = ch.get(CameraCharacteristics.CONTROL_AE_COMPENSATION_STEP);
            if ((stage == 0 || stage == 1) && range != null && step != null && step.floatValue() > 0f && range.getUpper() > 0) {
                float ev = stage == 0 ? 2.0f : 0.67f;
                int steps = Math.round(ev / step.floatValue());
                steps = Math.max(range.getLower(), Math.min(range.getUpper(), steps));
                req.set(CaptureRequest.CONTROL_AE_EXPOSURE_COMPENSATION, steps);
            }
        } catch (Exception ignored) {}
        try {
            int[] afModes = ch.get(CameraCharacteristics.CONTROL_AF_AVAILABLE_MODES);
            boolean hasCAF = false;
            if (afModes != null) for (int m : afModes) if (m == CaptureRequest.CONTROL_AF_MODE_CONTINUOUS_PICTURE) hasCAF = true;
            req.set(CaptureRequest.CONTROL_AF_MODE,
                    hasCAF ? CaptureRequest.CONTROL_AF_MODE_CONTINUOUS_PICTURE : CaptureRequest.CONTROL_AF_MODE_OFF);
        } catch (Exception ignored) {}
        try { req.set(CaptureRequest.NOISE_REDUCTION_MODE, CaptureRequest.NOISE_REDUCTION_MODE_FAST); } catch (Exception ignored) {}
        try { req.set(CaptureRequest.EDGE_MODE, CaptureRequest.EDGE_MODE_FAST); } catch (Exception ignored) {}
    }

    @SuppressWarnings("unused")
    private static void applySafeKeys(CaptureRequest.Builder req, CameraCharacteristics ch, boolean minimal) {
        applyStageKeys(req, ch, minimal ? 4 : 0);
    }

    public static String toDataUrl(byte[] jpeg) {
        return "data:image/jpeg;base64," + Base64.encodeToString(jpeg, Base64.NO_WRAP);
    }
}
