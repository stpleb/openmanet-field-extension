package com.manetnode.launcher;

import android.annotation.SuppressLint;
import android.app.Activity;
import android.content.Context;
import android.content.Intent;
import android.content.pm.PackageManager;
import android.graphics.ImageFormat;
import android.graphics.SurfaceTexture;
import android.hardware.camera2.CameraCaptureSession;
import android.hardware.camera2.CameraCharacteristics;
import android.hardware.camera2.CameraDevice;
import android.hardware.camera2.CameraManager;
import android.hardware.camera2.CaptureRequest;
import android.media.Image;
import android.media.ImageReader;
import android.os.Bundle;
import android.os.Handler;
import android.os.HandlerThread;
import android.view.Gravity;
import android.view.Surface;
import android.view.TextureView;
import android.widget.FrameLayout;
import android.widget.TextView;

import com.google.zxing.BinaryBitmap;
import com.google.zxing.DecodeHintType;
import com.google.zxing.PlanarYUVLuminanceSource;
import com.google.zxing.Result;
import com.google.zxing.common.HybridBinarizer;
import com.google.zxing.qrcode.QRCodeReader;

import java.nio.ByteBuffer;
import java.util.Arrays;
import java.util.HashMap;
import java.util.Map;
import java.util.function.Consumer;

/** Camera QR scanner for mesh share codes: back camera, decodes frames until a QR code is found. */
public class ScanActivity extends Activity {
    static Consumer<String> result;                      // set by the caller just before starting
    private static final int W = 1280, H = 720;

    private TextureView view;
    private HandlerThread thread;
    private Handler bg;
    private CameraDevice camera;
    private ImageReader reader;
    private volatile boolean done;

    /** Start a scan; the code (or nothing, if cancelled) goes to the callback on the UI thread. */
    static void scan(Context ctx, Consumer<String> then) {
        result = then;
        ctx.startActivity(new Intent(ctx, ScanActivity.class).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK));
    }

    @Override
    protected void onCreate(Bundle b) {
        super.onCreate(b);
        FrameLayout f = new FrameLayout(this);
        view = new TextureView(this);
        f.addView(view, new FrameLayout.LayoutParams(-1, -1));
        TextView hint = new TextView(this);
        hint.setText("Point at the mesh QR code");
        hint.setTextColor(Ui.CYAN);
        hint.setTextSize(16);
        hint.setGravity(Gravity.CENTER);
        hint.setBackgroundColor(0xB005080A);
        hint.setPadding(0, 40, 0, 40);
        f.addView(hint, new FrameLayout.LayoutParams(-1, -2, Gravity.BOTTOM));
        setContentView(f);
        if (checkSelfPermission(android.Manifest.permission.CAMERA) != PackageManager.PERMISSION_GRANTED)
            requestPermissions(new String[]{android.Manifest.permission.CAMERA}, 1);
    }

    @Override
    public void onRequestPermissionsResult(int code, String[] perms, int[] res) {
        if (res.length > 0 && res[0] == PackageManager.PERMISSION_GRANTED) open();
        else finish();
    }

    @Override
    protected void onResume() {
        super.onResume();
        thread = new HandlerThread("scan");
        thread.start();
        bg = new Handler(thread.getLooper());
        if (checkSelfPermission(android.Manifest.permission.CAMERA) == PackageManager.PERMISSION_GRANTED) open();
    }

    @Override
    protected void onPause() {
        super.onPause();
        close();
    }

    private void open() {
        if (view.isAvailable()) start();
        else view.setSurfaceTextureListener(new TextureView.SurfaceTextureListener() {
            @Override public void onSurfaceTextureAvailable(SurfaceTexture s, int w, int h) { start(); }
            @Override public void onSurfaceTextureSizeChanged(SurfaceTexture s, int w, int h) { }
            @Override public boolean onSurfaceTextureDestroyed(SurfaceTexture s) { return true; }
            @Override public void onSurfaceTextureUpdated(SurfaceTexture s) { }
        });
    }

    @SuppressLint("MissingPermission")
    private void start() {
        if (camera != null) return;
        try {
            CameraManager cm = (CameraManager) getSystemService(CAMERA_SERVICE);
            String id = null;
            for (String c : cm.getCameraIdList()) {
                Integer facing = cm.getCameraCharacteristics(c).get(CameraCharacteristics.LENS_FACING);
                if (facing != null && facing == CameraCharacteristics.LENS_FACING_BACK) { id = c; break; }
            }
            if (id == null) { finish(); return; }
            reader = ImageReader.newInstance(W, H, ImageFormat.YUV_420_888, 2);
            reader.setOnImageAvailableListener(this::frame, bg);
            cm.openCamera(id, new CameraDevice.StateCallback() {
                @Override
                public void onOpened(CameraDevice d) {
                    camera = d;
                    try {
                        SurfaceTexture st = view.getSurfaceTexture();
                        st.setDefaultBufferSize(W, H);
                        Surface preview = new Surface(st);
                        CaptureRequest.Builder rb = d.createCaptureRequest(CameraDevice.TEMPLATE_PREVIEW);
                        rb.addTarget(preview);
                        rb.addTarget(reader.getSurface());
                        rb.set(CaptureRequest.CONTROL_AF_MODE, CaptureRequest.CONTROL_AF_MODE_CONTINUOUS_PICTURE);
                        d.createCaptureSession(Arrays.asList(preview, reader.getSurface()), new CameraCaptureSession.StateCallback() {
                            @Override public void onConfigured(CameraCaptureSession s) {
                                try { s.setRepeatingRequest(rb.build(), null, bg); } catch (Exception e) { finish(); }
                            }
                            @Override public void onConfigureFailed(CameraCaptureSession s) { finish(); }
                        }, bg);
                    } catch (Exception e) {
                        finish();
                    }
                }
                @Override public void onDisconnected(CameraDevice d) { d.close(); camera = null; }
                @Override public void onError(CameraDevice d, int e) { d.close(); camera = null; finish(); }
            }, bg);
        } catch (Exception e) {
            finish();
        }
    }

    private void frame(ImageReader r) {
        Image img = r.acquireLatestImage();
        if (img == null) return;
        try {
            if (done) return;
            Image.Plane y = img.getPlanes()[0];
            ByteBuffer buf = y.getBuffer();
            int rowStride = y.getRowStride(), w = img.getWidth(), h = img.getHeight();
            byte[] lum = new byte[w * h];
            for (int row = 0; row < h; row++) {
                buf.position(row * rowStride);
                buf.get(lum, row * w, w);
            }
            Map<DecodeHintType, Object> hints = new HashMap<>();
            hints.put(DecodeHintType.TRY_HARDER, Boolean.TRUE);
            Result res = new QRCodeReader().decode(new BinaryBitmap(new HybridBinarizer(
                    new PlanarYUVLuminanceSource(lum, w, h, 0, 0, w, h, false))), hints);
            String text = res.getText();
            if (text != null && text.startsWith("FEMESH")) {
                done = true;
                runOnUiThread(() -> {
                    Consumer<String> cb = result;
                    result = null;
                    finish();
                    if (cb != null) cb.accept(text);
                });
            }
        } catch (Exception ignored) {
            // no QR in this frame
        } finally {
            img.close();
        }
    }

    private void close() {
        if (camera != null) { camera.close(); camera = null; }
        if (reader != null) { reader.close(); reader = null; }
        if (thread != null) { thread.quitSafely(); thread = null; }
    }
}
