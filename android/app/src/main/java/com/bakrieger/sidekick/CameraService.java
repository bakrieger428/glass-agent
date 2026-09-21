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

/** Camera2 single-shot JPEG capture (world camera of the glasses). */
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
                if (facing == null || facing == CameraCharacteristics.LENS_FACING_EXTERNAL) {
                    if (fallback == null) fallback = id;
                }
            }
            if (fallback != null) return fallback;
            String[] ids = cm.getCameraIdList();
            return ids.length > 0 ? ids[0] : null;
        } catch (Exception e) {
            return null;
        }
    }

    /** Open camera, take one still JPEG, close. Callbacks arrive on the main thread. */
    public static void capture(final Context ctx, final Callback cb) {
        final Handler ui = new Handler(ctx.getMainLooper());
        final String cameraId = pickCamera(ctx);
        if (cameraId == null) {
            ui.post(() -> cb.onError("no camera found"));
            return;
        }

        HandlerThread thread = new HandlerThread("sidekick-cam");
        thread.start();
        final Handler handler = new Handler(thread.getLooper());
        final ExecutorService exec = Executors.newSingleThreadExecutor();
        final long deadline = System.currentTimeMillis() + 8000;

        final CameraManager cm = (CameraManager) ctx.getSystemService(Context.CAMERA_SERVICE);
        try {
            CameraCharacteristics ch = cm.getCameraCharacteristics(cameraId);
            android.util.Size best = null;
            for (android.util.Size s : ch.get(CameraCharacteristics.SCALER_STREAM_CONFIGURATION_MAP)
                    .getOutputSizes(ImageFormat.JPEG)) {
                // Prefer ~1280 width frames; fall back to the smallest available.
                if (best == null || Math.abs(s.getWidth() - 1280) < Math.abs(best.getWidth() - 1280)) {
                    best = s;
                }
            }
            final android.util.Size size = best != null ? best : new android.util.Size(1280, 720);

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
                    final byte[] out = jpeg;
                    ui.post(() -> cb.onJpeg(out));
                } catch (Exception e) {
                    finish.run();
                    ui.post(() -> cb.onError("image read: " + e.getMessage()));
                } finally {
                    if (img != null) try { img.close(); } catch (Exception ignored) {}
                }
            }, handler);

            handler.postDelayed(() -> {
                if (!done[0]) {
                    finish.run();
                    ui.post(() -> cb.onError("capture timeout"));
                }
            }, Math.max(0, deadline - System.currentTimeMillis()));

            cm.openCamera(cameraId, new CameraDevice.StateCallback() {
                @Override public void onOpened(final CameraDevice camera) {
                    try {
                        Surface surface = reader.getSurface();
                        final CaptureRequest.Builder req = camera.createCaptureRequest(CameraDevice.TEMPLATE_STILL_CAPTURE);
                        req.addTarget(surface);
                        Integer af = ch.get(CameraCharacteristics.CONTROL_AF_AVAILABLE_MODES) != null ? 1 : null;
                        req.set(CaptureRequest.CONTROL_AF_MODE, CaptureRequest.CONTROL_AF_MODE_CONTINUOUS_PICTURE);
                        req.set(CaptureRequest.JPEG_QUALITY, (byte) 70);

                        camera.createCaptureSession(Collections.singletonList(surface),
                                new CameraCaptureSession.StateCallback() {
                                    @Override public void onConfigured(final CameraCaptureSession session) {
                                        try {
                                            session.capture(req.build(), new CameraCaptureSession.CaptureCallback() {}, handler);
                                        } catch (Exception e) {
                                            finish.run();
                                            ui.post(() -> cb.onError("capture start: " + e.getMessage()));
                                        }
                                    }
                                    @Override public void onConfigureFailed(CameraCaptureSession session) {
                                        finish.run();
                                        ui.post(() -> cb.onError("session config failed"));
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
                    ui.post(() -> cb.onError("camera error " + error));
                }
            }, handler);
        } catch (Exception e) {
            thread.quitSafely();
            exec.shutdown();
            ui.post(() -> cb.onError("camera: " + e.getMessage()));
        }
    }

    public static String toDataUrl(byte[] jpeg) {
        return "data:image/jpeg;base64," + Base64.encodeToString(jpeg, Base64.NO_WRAP);
    }
}
