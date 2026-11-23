package com.pareja.gps;

import android.Manifest;
import android.app.Activity;
import android.content.Context;
import android.content.pm.PackageManager;
import android.graphics.Canvas;
import android.graphics.Color;
import android.graphics.ImageFormat;
import android.graphics.Matrix;
import android.graphics.Paint;
import android.graphics.Path;
import android.graphics.Rect;
import android.graphics.RectF;
import android.graphics.SurfaceTexture;
import android.graphics.drawable.GradientDrawable;
import android.hardware.camera2.CameraAccessException;
import android.hardware.camera2.CameraCaptureSession;
import android.hardware.camera2.CameraCharacteristics;
import android.hardware.camera2.CameraDevice;
import android.hardware.camera2.CameraExtensionCharacteristics;
import android.hardware.camera2.CameraExtensionSession;
import android.hardware.camera2.CameraManager;
import android.hardware.camera2.CaptureRequest;
import android.hardware.camera2.CaptureResult;
import android.hardware.camera2.TotalCaptureResult;
import android.hardware.camera2.params.ExtensionSessionConfiguration;
import android.hardware.camera2.params.MeteringRectangle;
import android.hardware.camera2.params.OutputConfiguration;
import android.hardware.camera2.params.StreamConfigurationMap;
import android.media.Image;
import android.media.ImageReader;
import android.os.Build;
import android.os.Bundle;
import android.os.Handler;
import android.os.HandlerThread;
import android.os.Looper;
import android.util.Log;
import android.util.Range;
import android.util.Size;
import android.view.Gravity;
import android.view.MotionEvent;
import android.view.OrientationEventListener;
import android.view.ScaleGestureDetector;
import android.view.Surface;
import android.view.TextureView;
import android.view.View;
import android.view.Window;
import android.view.WindowManager;
import android.widget.FrameLayout;
import android.widget.LinearLayout;
import android.widget.TextView;
import android.widget.Toast;

import java.io.File;
import java.io.FileOutputStream;
import java.nio.ByteBuffer;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.List;
import java.util.concurrent.Executor;

/**
 * Cámara nativa del chat (Camera2, sin librerías extra).
 * - Usa la cámara principal de cada lado (la primera que da el sistema), no la gran angular.
 * - Si el celular lo permite (Android 12+), saca la foto con el procesamiento del propio fabricante
 *   (extensión "Auto" o "HDR": la misma que usa la app de cámara del celular / Instagram en iPhone).
 * - Si no, foto a resolución completa con reducción de ruido y nitidez en alta calidad.
 * Devuelve la ruta del JPEG a ParejaNativePlugin.camera() por un listener estático.
 */
public class ParejaCamActivity extends Activity {
    private static final String TAG = "ParejaCam";

    public interface Listener { void done(String action, String path, String error); }
    static Listener listener;
    private boolean delivered = false;

    // ----- Estado de la cámara -----
    private CameraManager manager;
    private HandlerThread bgThread;
    private Handler bg;
    private final Handler ui = new Handler(Looper.getMainLooper());
    private Executor uiExec;

    private String facing = "environment";
    private String camId;
    private CameraCharacteristics chars;
    private CameraDevice device;
    private CameraCaptureSession session;          // sesión normal
    private CameraExtensionSession extSession;     // sesión con procesamiento del fabricante
    private ImageReader reader;
    private Surface previewSurface;
    private Size previewSize;
    private CaptureRequest.Builder previewReq;

    private int extMode = -1;          // extensión disponible (-1 = ninguna)
    private boolean extZoomOk = false;
    private boolean usingExt = false;
    private boolean flashAvail = false, flashOn = false;
    private float zoom = 1f, zoomMin = 1f, zoomMax = 1f;
    private boolean afContinuous = false;
    private int maxAfRegions = 0, maxAeRegions = 0;
    private int deviceDeg = 0;
    private boolean busy = false;
    private int sessionGen = 0;
    private Runnable focusReset;

    // ----- Vistas -----
    private FrameLayout root;
    private TextureView tv;
    private View flashFx, focusRing;
    private IconView btnFlash, btnClose, btnFlip, btnGal;
    private ShutterView shutter;
    private TextView badge, busyText;
    private LinearLayout zoomRow;
    private OrientationEventListener orient;
    private ScaleGestureDetector pinch;

    // =================================================================================
    @Override
    protected void onCreate(Bundle saved) {
        super.onCreate(saved);
        requestWindowFeature(Window.FEATURE_NO_TITLE);
        Window w = getWindow();
        w.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON | WindowManager.LayoutParams.FLAG_FULLSCREEN);
        w.setStatusBarColor(Color.BLACK);
        w.setNavigationBarColor(Color.BLACK);
        String f = getIntent().getStringExtra("facing");
        if ("user".equals(f)) facing = "user";
        manager = (CameraManager) getSystemService(Context.CAMERA_SERVICE);
        uiExec = r -> ui.post(r);
        buildUi();
        orient = new OrientationEventListener(this) {
            @Override public void onOrientationChanged(int d) {
                if (d == ORIENTATION_UNKNOWN) return;
                int r = ((d + 45) / 90 * 90) % 360;
                if (r != deviceDeg) { deviceDeg = r; rotateIcons(); }
            }
        };
    }

    @Override
    protected void onResume() {
        super.onResume();
        if (orient.canDetectOrientation()) orient.enable();
        startBg();
        if (checkSelfPermission(Manifest.permission.CAMERA) != PackageManager.PERMISSION_GRANTED) {
            requestPermissions(new String[] { Manifest.permission.CAMERA }, 7);
            return;
        }
        if (tv.isAvailable()) openCamera(); else tv.setSurfaceTextureListener(stl);
    }

    @Override
    protected void onPause() {
        orient.disable();
        closeCamera();
        stopBg();
        super.onPause();
    }

    @Override
    protected void onDestroy() {
        deliver("cancel", null, null);
        super.onDestroy();
    }

    @Override
    public void onBackPressed() { deliver("cancel", null, null); finish(); }

    @Override
    public void onRequestPermissionsResult(int code, String[] perms, int[] res) {
        if (code != 7) return;
        if (res.length > 0 && res[0] == PackageManager.PERMISSION_GRANTED) {
            if (tv.isAvailable()) openCamera(); else tv.setSurfaceTextureListener(stl);
        } else {
            deliver("error", null, "Permite la Cámara en los ajustes de la app");
            finish();
        }
    }

    private void deliver(String action, String path, String err) {
        if (delivered) return;
        delivered = true;
        Listener l = listener;
        listener = null;
        if (l != null) l.done(action, path, err);
    }

    private void startBg() {
        if (bgThread != null) return;
        bgThread = new HandlerThread("ParejaCam");
        bgThread.start();
        bg = new Handler(bgThread.getLooper());
    }

    private void stopBg() {
        if (bgThread == null) return;
        bgThread.quitSafely();
        try { bgThread.join(800); } catch (InterruptedException ignored) { }
        bgThread = null; bg = null;
    }

    private final TextureView.SurfaceTextureListener stl = new TextureView.SurfaceTextureListener() {
        @Override public void onSurfaceTextureAvailable(SurfaceTexture s, int w, int h) { openCamera(); }
        @Override public void onSurfaceTextureSizeChanged(SurfaceTexture s, int w, int h) { fitPreview(); }
        @Override public boolean onSurfaceTextureDestroyed(SurfaceTexture s) { return true; }
        @Override public void onSurfaceTextureUpdated(SurfaceTexture s) { }
    };

    // =================================================================================
    // Elegir cámara y tamaños

    private String pickCamera() throws CameraAccessException {
        int want = "user".equals(facing) ? CameraCharacteristics.LENS_FACING_FRONT : CameraCharacteristics.LENS_FACING_BACK;
        // El sistema lista primero la principal de cada lado (0 trasera, 1 frontal); las demás lentes vienen después
        for (String id : manager.getCameraIdList()) {
            Integer lf = manager.getCameraCharacteristics(id).get(CameraCharacteristics.LENS_FACING);
            if (lf != null && lf == want) return id;
        }
        String[] all = manager.getCameraIdList();
        return all.length > 0 ? all[0] : null;
    }

    private static boolean is43(Size s) {
        float r = (float) Math.max(s.getWidth(), s.getHeight()) / Math.min(s.getWidth(), s.getHeight());
        return Math.abs(r - 4f / 3f) < 0.02f;
    }

    private static Size largest(List<Size> sizes, boolean only43) {
        Size best = null;
        for (Size s : sizes) {
            if (only43 && !is43(s)) continue;
            if (best == null || (long) s.getWidth() * s.getHeight() > (long) best.getWidth() * best.getHeight()) best = s;
        }
        if (best == null && only43) return largest(sizes, false);
        return best;
    }

    /** Vista previa con la misma forma que la foto, lo más grande posible sin pasar de 1920×1440. */
    private static Size pickPreview(List<Size> sizes, Size photo) {
        float want = (float) photo.getWidth() / photo.getHeight();
        Size best = null;
        for (Size s : sizes) {
            if (s.getWidth() > 1920 || s.getHeight() > 1440) continue;
            if (Math.abs((float) s.getWidth() / s.getHeight() - want) > 0.02f) continue;
            if (best == null || s.getWidth() * s.getHeight() > best.getWidth() * best.getHeight()) best = s;
        }
        if (best != null) return best;
        for (Size s : sizes) {
            if (s.getWidth() > 1920 || s.getHeight() > 1440) continue;
            if (best == null || s.getWidth() * s.getHeight() > best.getWidth() * best.getHeight()) best = s;
        }
        return best != null ? best : sizes.get(0);
    }

    // =================================================================================
    // Abrir / cerrar

    private void openCamera() {
        if (device != null || bg == null) return;
        try {
            camId = pickCamera();
            if (camId == null) { fail("Este celular no tiene cámara"); return; }
            chars = manager.getCameraCharacteristics(camId);
            Boolean fa = chars.get(CameraCharacteristics.FLASH_INFO_AVAILABLE);
            flashAvail = fa != null && fa;
            if (!flashAvail) flashOn = false;
            int[] afModes = chars.get(CameraCharacteristics.CONTROL_AF_AVAILABLE_MODES);
            afContinuous = contains(afModes, CaptureRequest.CONTROL_AF_MODE_CONTINUOUS_PICTURE);
            Integer mr = chars.get(CameraCharacteristics.CONTROL_MAX_REGIONS_AF);
            maxAfRegions = mr == null ? 0 : mr;
            mr = chars.get(CameraCharacteristics.CONTROL_MAX_REGIONS_AE);
            maxAeRegions = mr == null ? 0 : mr;
            zoomMin = 1f; zoomMax = 1f;
            if (Build.VERSION.SDK_INT >= 30) {
                Range<Float> zr = chars.get(CameraCharacteristics.CONTROL_ZOOM_RATIO_RANGE);
                if (zr != null) { zoomMin = zr.getLower(); zoomMax = Math.min(zr.getUpper(), 10f); }
            }
            zoom = Math.max(1f, zoomMin);
            detectExtension();
            ui.post(this::refreshUi);
            manager.openCamera(camId, new CameraDevice.StateCallback() {
                @Override public void onOpened(CameraDevice d) { device = d; startSession(); }
                @Override public void onDisconnected(CameraDevice d) { d.close(); if (device == d) device = null; }
                @Override public void onError(CameraDevice d, int e) {
                    d.close(); if (device == d) device = null;
                    Log.w(TAG, "error de cámara " + e);
                    ui.post(() -> fail("No se pudo abrir la cámara (" + e + ")"));
                }
            }, bg);
        } catch (SecurityException | CameraAccessException e) {
            Log.w(TAG, "abrir", e);
            fail("No se pudo abrir la cámara");
        }
    }

    private void detectExtension() {
        extMode = -1; extZoomOk = false;
        if (Build.VERSION.SDK_INT < 31) return;
        try {
            CameraExtensionCharacteristics ec = manager.getCameraExtensionCharacteristics(camId);
            List<Integer> sup = ec.getSupportedExtensions();
            int[] pref = { CameraExtensionCharacteristics.EXTENSION_AUTOMATIC, CameraExtensionCharacteristics.EXTENSION_HDR };
            for (int e : pref) {
                if (!sup.contains(e)) continue;
                if (ec.getExtensionSupportedSizes(e, ImageFormat.JPEG).isEmpty()) continue;
                if (ec.getExtensionSupportedSizes(e, SurfaceTexture.class).isEmpty()) continue;
                extMode = e;
                break;
            }
            if (extMode >= 0 && Build.VERSION.SDK_INT >= 34) {
                extZoomOk = ec.getAvailableCaptureRequestKeys(extMode).contains(CaptureRequest.CONTROL_ZOOM_RATIO);
            }
        } catch (Throwable t) {
            Log.w(TAG, "extensiones", t);
            extMode = -1;
        }
    }

    private boolean wantExt() {
        return extMode >= 0 && !flashOn && (Math.abs(zoom - 1f) < 0.01f || extZoomOk);
    }

    private void closeSession() {
        sessionGen++;
        try { if (session != null) session.close(); } catch (Throwable ignored) { }
        try { if (extSession != null) extSession.close(); } catch (Throwable ignored) { }
        session = null; extSession = null;
        if (reader != null) { reader.close(); reader = null; }
    }

    private void closeCamera() {
        closeSession();
        if (device != null) { device.close(); device = null; }
        busy = false;
    }

    private void restartSession() {
        if (device == null || bg == null) return;
        bg.post(() -> { closeSession(); startSession(); });
    }

    // =================================================================================
    // Sesión: con extensión del fabricante o normal

    private void startSession() {
        if (device == null) return;
        usingExt = wantExt();
        final int gen = ++sessionGen;
        try {
            List<Size> jpegSizes, prevSizes;
            if (usingExt) {
                CameraExtensionCharacteristics ec = manager.getCameraExtensionCharacteristics(camId);
                jpegSizes = ec.getExtensionSupportedSizes(extMode, ImageFormat.JPEG);
                prevSizes = ec.getExtensionSupportedSizes(extMode, SurfaceTexture.class);
            } else {
                StreamConfigurationMap map = chars.get(CameraCharacteristics.SCALER_STREAM_CONFIGURATION_MAP);
                jpegSizes = Arrays.asList(map.getOutputSizes(ImageFormat.JPEG));
                prevSizes = Arrays.asList(map.getOutputSizes(SurfaceTexture.class));
            }
            Size photo = largest(jpegSizes, true);
            previewSize = pickPreview(prevSizes, photo);
            reader = ImageReader.newInstance(photo.getWidth(), photo.getHeight(), ImageFormat.JPEG, 2);
            reader.setOnImageAvailableListener(this::onJpeg, bg);
            SurfaceTexture st = tv.getSurfaceTexture();
            if (st == null) return;
            st.setDefaultBufferSize(previewSize.getWidth(), previewSize.getHeight());
            if (previewSurface != null) previewSurface.release();
            previewSurface = new Surface(st);
            ui.post(() -> { fitPreview(); refreshUi(); });
            Log.i(TAG, "cámara " + camId + " foto " + photo + " vista " + previewSize + " ext " + (usingExt ? extMode : -1));

            if (usingExt) {
                List<OutputConfiguration> outs = new ArrayList<>();
                outs.add(new OutputConfiguration(previewSurface));
                outs.add(new OutputConfiguration(reader.getSurface()));
                ExtensionSessionConfiguration cfg = new ExtensionSessionConfiguration(extMode, outs, uiExec,
                    new CameraExtensionSession.StateCallback() {
                        @Override public void onConfigured(CameraExtensionSession s) {
                            if (gen != sessionGen) { try { s.close(); } catch (Throwable ignored) { } return; }
                            extSession = s;
                            repeat();
                        }
                        @Override public void onConfigureFailed(CameraExtensionSession s) {
                            Log.w(TAG, "extensión no se pudo configurar; uso la normal");
                            if (gen != sessionGen) return;
                            extMode = -1;
                            restartSession();
                        }
                    });
                device.createExtensionSession(cfg);
            } else {
                device.createCaptureSession(Arrays.asList(previewSurface, reader.getSurface()),
                    new CameraCaptureSession.StateCallback() {
                        @Override public void onConfigured(CameraCaptureSession s) {
                            if (gen != sessionGen) { s.close(); return; }
                            session = s;
                            repeat();
                        }
                        @Override public void onConfigureFailed(CameraCaptureSession s) {
                            if (gen == sessionGen) ui.post(() -> fail("No se pudo iniciar la cámara"));
                        }
                    }, bg);
            }
        } catch (Throwable t) {
            Log.w(TAG, "sesión", t);
            if (usingExt) { extMode = -1; restartSession(); return; }
            ui.post(() -> fail("No se pudo iniciar la cámara"));
        }
    }

    private void baseControls(CaptureRequest.Builder b, boolean still) {
        if (usingExt) {
            // Con la extensión del fabricante solo se pasan las claves que acepta; el resto lo decide él
            if (Build.VERSION.SDK_INT >= 30 && extZoomOk && zoomMax > zoomMin) b.set(CaptureRequest.CONTROL_ZOOM_RATIO, zoom);
            return;
        }
        b.set(CaptureRequest.CONTROL_MODE, CaptureRequest.CONTROL_MODE_AUTO);
        if (afContinuous) b.set(CaptureRequest.CONTROL_AF_MODE, CaptureRequest.CONTROL_AF_MODE_CONTINUOUS_PICTURE);
        if (!usingExt) {
            b.set(CaptureRequest.CONTROL_AE_MODE, flashOn && flashAvail
                ? CaptureRequest.CONTROL_AE_MODE_ON_ALWAYS_FLASH : CaptureRequest.CONTROL_AE_MODE_ON);
            b.set(CaptureRequest.CONTROL_AWB_MODE, CaptureRequest.CONTROL_AWB_MODE_AUTO);
        }
        if (Build.VERSION.SDK_INT >= 30 && zoomMax > zoomMin && (!usingExt || extZoomOk)) {
            b.set(CaptureRequest.CONTROL_ZOOM_RATIO, zoom);
        }
        if (still && !usingExt) {
            int[] nr = chars.get(CameraCharacteristics.NOISE_REDUCTION_AVAILABLE_NOISE_REDUCTION_MODES);
            if (contains(nr, CaptureRequest.NOISE_REDUCTION_MODE_HIGH_QUALITY))
                b.set(CaptureRequest.NOISE_REDUCTION_MODE, CaptureRequest.NOISE_REDUCTION_MODE_HIGH_QUALITY);
            int[] ed = chars.get(CameraCharacteristics.EDGE_AVAILABLE_EDGE_MODES);
            if (contains(ed, CaptureRequest.EDGE_MODE_HIGH_QUALITY))
                b.set(CaptureRequest.EDGE_MODE, CaptureRequest.EDGE_MODE_HIGH_QUALITY);
            int[] ab = chars.get(CameraCharacteristics.COLOR_CORRECTION_AVAILABLE_ABERRATION_MODES);
            if (contains(ab, CaptureRequest.COLOR_CORRECTION_ABERRATION_MODE_HIGH_QUALITY))
                b.set(CaptureRequest.COLOR_CORRECTION_ABERRATION_MODE, CaptureRequest.COLOR_CORRECTION_ABERRATION_MODE_HIGH_QUALITY);
            int[] ois = chars.get(CameraCharacteristics.LENS_INFO_AVAILABLE_OPTICAL_STABILIZATION);
            if (contains(ois, CaptureRequest.LENS_OPTICAL_STABILIZATION_MODE_ON))
                b.set(CaptureRequest.LENS_OPTICAL_STABILIZATION_MODE, CaptureRequest.LENS_OPTICAL_STABILIZATION_MODE_ON);
        }
    }

    private void repeat() {
        try {
            if (device == null || previewSurface == null) return;
            previewReq = device.createCaptureRequest(CameraDevice.TEMPLATE_PREVIEW);
            previewReq.addTarget(previewSurface);
            baseControls(previewReq, false);
            if (usingExt && extSession != null) {
                extSession.setRepeatingRequest(previewReq.build(), uiExec, new CameraExtensionSession.ExtensionCaptureCallback() { });
            } else if (session != null) {
                session.setRepeatingRequest(previewReq.build(), null, bg);
            }
        } catch (Throwable t) {
            Log.w(TAG, "vista previa", t);
        }
    }

    // =================================================================================
    // Sacar la foto

    private int jpegOrientation() {
        Integer so = chars.get(CameraCharacteristics.SENSOR_ORIENTATION);
        int sensor = so == null ? 90 : so;
        int dev = deviceDeg;
        if ("user".equals(facing)) dev = -dev;
        return (sensor + dev + 360) % 360;
    }

    private void shoot() {
        if (busy || device == null || (session == null && extSession == null)) return;
        busy = true;
        shutter.setBusy(true);
        flashFx.setAlpha(0.85f);
        flashFx.animate().alpha(0f).setDuration(320).start();
        if (usingExt) {
            busyText.setVisibility(View.VISIBLE);
            stillCapture();
        } else if (flashOn && flashAvail) {
            precaptureThenShoot();
        } else {
            stillCapture();
        }
        ui.postDelayed(() -> { if (busy) busyText.setVisibility(View.VISIBLE); }, 900);
    }

    /** Con flash: se mide la luz con el pre-flash antes de la foto (si no, sale quemada u oscura). */
    private void precaptureThenShoot() {
        try {
            final int gen = sessionGen;
            CaptureRequest.Builder b = device.createCaptureRequest(CameraDevice.TEMPLATE_PREVIEW);
            b.addTarget(previewSurface);
            baseControls(b, false);
            b.set(CaptureRequest.CONTROL_AE_PRECAPTURE_TRIGGER, CaptureRequest.CONTROL_AE_PRECAPTURE_TRIGGER_START);
            final boolean[] fired = { false };
            final Runnable go = () -> { if (!fired[0] && gen == sessionGen) { fired[0] = true; stillCapture(); } };
            session.setRepeatingRequest(previewReq.build(), new CameraCaptureSession.CaptureCallback() {
                boolean seenPre = false;
                @Override public void onCaptureCompleted(CameraCaptureSession s, CaptureRequest r, TotalCaptureResult res) {
                    Integer ae = res.get(CaptureResult.CONTROL_AE_STATE);
                    if (ae == null) { go.run(); return; }
                    // Espera a que termine la medición con pre-flash (si el celular no la informa, salta el tiempo límite)
                    if (ae == CaptureResult.CONTROL_AE_STATE_PRECAPTURE) seenPre = true;
                    else if (seenPre) go.run();
                }
            }, bg);
            session.capture(b.build(), null, bg);
            bg.postDelayed(go, 1200); // por si el celular no avisa
        } catch (Throwable t) {
            Log.w(TAG, "pre-flash", t);
            stillCapture();
        }
    }

    private void stillCapture() {
        try {
            CaptureRequest.Builder b = device.createCaptureRequest(CameraDevice.TEMPLATE_STILL_CAPTURE);
            b.addTarget(reader.getSurface());
            baseControls(b, true);
            b.set(CaptureRequest.JPEG_ORIENTATION, jpegOrientation());
            b.set(CaptureRequest.JPEG_QUALITY, (byte) 95);
            if (usingExt) {
                extSession.capture(b.build(), uiExec, new CameraExtensionSession.ExtensionCaptureCallback() {
                    @Override public void onCaptureFailed(CameraExtensionSession s, CaptureRequest r) {
                        Log.w(TAG, "falló la foto con extensión; uso la normal");
                        busy = false; shutter.setBusy(false); busyText.setVisibility(View.GONE);
                        extMode = -1;
                        restartSession();
                        Toast.makeText(ParejaCamActivity.this, "Probá de nuevo", Toast.LENGTH_SHORT).show();
                    }
                });
            } else {
                session.stopRepeating();
                session.capture(b.build(), new CameraCaptureSession.CaptureCallback() {
                    @Override public void onCaptureFailed(CameraCaptureSession s, CaptureRequest r, android.hardware.camera2.CaptureFailure f) {
                        ui.post(() -> { busy = false; shutter.setBusy(false); busyText.setVisibility(View.GONE); });
                        repeat();
                    }
                }, bg);
            }
        } catch (Throwable t) {
            Log.w(TAG, "foto", t);
            ui.post(() -> { busy = false; shutter.setBusy(false); busyText.setVisibility(View.GONE); });
            repeat();
        }
    }

    private void onJpeg(ImageReader r) {
        Image img = null;
        try {
            img = r.acquireLatestImage();
            if (img == null) return;
            ByteBuffer buf = img.getPlanes()[0].getBuffer();
            byte[] bytes = new byte[buf.remaining()];
            buf.get(bytes);
            File dir = new File(getCacheDir(), "camara");
            if (!dir.exists()) dir.mkdirs();
            File[] old = dir.listFiles();
            if (old != null) for (File f : old) f.delete();
            File out = new File(dir, "foto_" + System.currentTimeMillis() + ".jpg");
            try (FileOutputStream os = new FileOutputStream(out)) { os.write(bytes); }
            final String path = out.getAbsolutePath();
            ui.post(() -> { deliver("photo", path, null); finish(); });
        } catch (Throwable t) {
            Log.w(TAG, "guardar", t);
            ui.post(() -> {
                busy = false; shutter.setBusy(false); busyText.setVisibility(View.GONE);
                Toast.makeText(this, "No se pudo guardar la foto", Toast.LENGTH_SHORT).show();
            });
            repeat();
        } finally {
            if (img != null) img.close();
        }
    }

    private void fail(String msg) {
        Toast.makeText(this, msg, Toast.LENGTH_LONG).show();
        deliver("error", null, msg);
        finish();
    }

    // =================================================================================
    // Zoom y enfoque

    private void setZoom(float z) {
        if (zoomMax <= zoomMin || busy) return;
        float nz = Math.max(zoomMin, Math.min(zoomMax, z));
        boolean extBefore = wantExt();
        zoom = nz;
        refreshZoomUi();
        if (device == null) return;
        if (wantExt() != extBefore || usingExt != wantExt()) { restartSession(); return; }
        if (previewReq == null) return;
        try {
            if (Build.VERSION.SDK_INT >= 30) previewReq.set(CaptureRequest.CONTROL_ZOOM_RATIO, zoom);
            if (usingExt && extSession != null) {
                extSession.setRepeatingRequest(previewReq.build(), uiExec, new CameraExtensionSession.ExtensionCaptureCallback() { });
            } else if (session != null) {
                session.setRepeatingRequest(previewReq.build(), null, bg);
            }
        } catch (Throwable t) { Log.w(TAG, "zoom", t); }
    }

    /** Tocar para enfocar (sesión normal): convierte el punto de la pantalla a coordenadas del sensor. */
    private void tapFocus(float x, float y) {
        showFocusRing(x, y);
        if (usingExt || session == null || previewReq == null || maxAfRegions <= 0 || previewSize == null) return;
        try {
            float W = tv.getWidth(), H = tv.getHeight();
            float pw = previewSize.getHeight(), ph = previewSize.getWidth(); // en vertical
            float k = Math.max(W / pw, H / ph);
            float u = (x - (W - pw * k) / 2f) / (pw * k);
            float v = (y - (H - ph * k) / 2f) / (ph * k);
            u = clamp01(u); v = clamp01(v);
            boolean front = "user".equals(facing);
            if (front) u = 1f - u;
            Integer so = chars.get(CameraCharacteristics.SENSOR_ORIENTATION);
            int sensor = so == null ? 90 : so;
            float sx, sy;
            switch (sensor) {
                case 90: sx = v; sy = 1f - u; break;
                case 270: sx = 1f - v; sy = u; break;
                case 180: sx = 1f - u; sy = 1f - v; break;
                default: sx = u; sy = v;
            }
            Rect act = chars.get(CameraCharacteristics.SENSOR_INFO_ACTIVE_ARRAY_SIZE);
            int half = (int) (Math.min(act.width(), act.height()) * 0.08f);
            int cx = act.left + (int) (sx * act.width()), cy = act.top + (int) (sy * act.height());
            Rect r = new Rect(Math.max(act.left, cx - half), Math.max(act.top, cy - half),
                Math.min(act.right - 1, cx + half), Math.min(act.bottom - 1, cy + half));
            MeteringRectangle[] m = { new MeteringRectangle(r, MeteringRectangle.METERING_WEIGHT_MAX - 1) };
            session.stopRepeating();
            CaptureRequest.Builder cancel = device.createCaptureRequest(CameraDevice.TEMPLATE_PREVIEW);
            cancel.addTarget(previewSurface);
            baseControls(cancel, false);
            cancel.set(CaptureRequest.CONTROL_AF_TRIGGER, CaptureRequest.CONTROL_AF_TRIGGER_CANCEL);
            session.capture(cancel.build(), null, bg);
            previewReq.set(CaptureRequest.CONTROL_AF_MODE, CaptureRequest.CONTROL_AF_MODE_AUTO);
            previewReq.set(CaptureRequest.CONTROL_AF_REGIONS, m);
            if (maxAeRegions > 0) previewReq.set(CaptureRequest.CONTROL_AE_REGIONS, m);
            session.setRepeatingRequest(previewReq.build(), null, bg);
            previewReq.set(CaptureRequest.CONTROL_AF_TRIGGER, CaptureRequest.CONTROL_AF_TRIGGER_START);
            session.capture(previewReq.build(), null, bg);
            previewReq.set(CaptureRequest.CONTROL_AF_TRIGGER, CaptureRequest.CONTROL_AF_TRIGGER_IDLE);
            // A los 4 s vuelve al enfoque automático continuo
            if (focusReset != null) ui.removeCallbacks(focusReset);
            final int gen = sessionGen;
            focusReset = () -> { if (gen == sessionGen && !busy) repeat(); };
            ui.postDelayed(focusReset, 4000);
        } catch (Throwable t) { Log.w(TAG, "enfoque", t); }
    }

    private static float clamp01(float f) { return Math.max(0f, Math.min(1f, f)); }

    private static boolean contains(int[] a, int v) {
        if (a == null) return false;
        for (int x : a) if (x == v) return true;
        return false;
    }

    // =================================================================================
    // Interfaz (hecha en código para no depender de recursos)

    private int dp(float v) { return Math.round(v * getResources().getDisplayMetrics().density); }

    private static GradientDrawable round(int color) {
        GradientDrawable g = new GradientDrawable();
        g.setShape(GradientDrawable.OVAL);
        g.setColor(color);
        return g;
    }

    private void buildUi() {
        root = new FrameLayout(this);
        root.setBackgroundColor(Color.BLACK);
        tv = new TextureView(this);
        root.addView(tv, new FrameLayout.LayoutParams(-1, -1));

        flashFx = new View(this);
        flashFx.setBackgroundColor(Color.WHITE);
        flashFx.setAlpha(0f);
        root.addView(flashFx, new FrameLayout.LayoutParams(-1, -1));

        focusRing = new View(this) {
            final Paint p = new Paint(Paint.ANTI_ALIAS_FLAG);
            @Override protected void onDraw(Canvas c) {
                p.setStyle(Paint.Style.STROKE); p.setStrokeWidth(dp(2)); p.setColor(0xFFFDE047);
                c.drawCircle(getWidth() / 2f, getHeight() / 2f, getWidth() / 2f - dp(2), p);
            }
        };
        focusRing.setAlpha(0f);
        root.addView(focusRing, new FrameLayout.LayoutParams(dp(72), dp(72)));

        // Barra de arriba
        FrameLayout top = new FrameLayout(this);
        btnClose = new IconView(this, IconView.CLOSE);
        btnFlash = new IconView(this, IconView.FLASH_OFF);
        top.addView(btnClose, lp(dp(44), dp(44), Gravity.START | Gravity.CENTER_VERTICAL));
        top.addView(btnFlash, lp(dp(44), dp(44), Gravity.END | Gravity.CENTER_VERTICAL));
        badge = new TextView(this);
        badge.setTextColor(Color.WHITE);
        badge.setTextSize(12);
        badge.setPadding(dp(12), dp(5), dp(12), dp(5));
        GradientDrawable bb = new GradientDrawable(); bb.setCornerRadius(dp(20)); bb.setColor(0x66000000);
        badge.setBackground(bb);
        badge.setVisibility(View.GONE);
        top.addView(badge, lp(-2, -2, Gravity.CENTER));
        top.setPadding(dp(16), dp(12), dp(16), dp(12));
        FrameLayout.LayoutParams topLp = new FrameLayout.LayoutParams(-1, -2, Gravity.TOP);
        root.addView(top, topLp);

        // Abajo: zoom + controles
        LinearLayout bottom = new LinearLayout(this);
        bottom.setOrientation(LinearLayout.VERTICAL);
        bottom.setGravity(Gravity.CENTER_HORIZONTAL);
        zoomRow = new LinearLayout(this);
        zoomRow.setOrientation(LinearLayout.HORIZONTAL);
        zoomRow.setGravity(Gravity.CENTER);
        bottom.addView(zoomRow, new LinearLayout.LayoutParams(-2, -2));
        LinearLayout ctrls = new LinearLayout(this);
        ctrls.setOrientation(LinearLayout.HORIZONTAL);
        ctrls.setGravity(Gravity.CENTER_VERTICAL);
        btnGal = new IconView(this, IconView.GALLERY);
        btnFlip = new IconView(this, IconView.FLIP);
        shutter = new ShutterView(this);
        LinearLayout.LayoutParams side = new LinearLayout.LayoutParams(dp(52), dp(52));
        LinearLayout.LayoutParams mid = new LinearLayout.LayoutParams(dp(78), dp(78));
        mid.leftMargin = dp(44); mid.rightMargin = dp(44);
        ctrls.addView(btnGal, side);
        ctrls.addView(shutter, mid);
        ctrls.addView(btnFlip, new LinearLayout.LayoutParams(dp(52), dp(52)));
        LinearLayout.LayoutParams cl = new LinearLayout.LayoutParams(-2, -2);
        cl.topMargin = dp(16);
        bottom.addView(ctrls, cl);
        bottom.setPadding(0, dp(16), 0, dp(28));
        GradientDrawable fade = new GradientDrawable(GradientDrawable.Orientation.BOTTOM_TOP, new int[] { 0x99000000, 0x00000000 });
        bottom.setBackground(fade);
        root.addView(bottom, new FrameLayout.LayoutParams(-1, -2, Gravity.BOTTOM));

        busyText = new TextView(this);
        busyText.setText("Procesando…");
        busyText.setTextColor(Color.WHITE);
        busyText.setTextSize(14);
        busyText.setPadding(dp(16), dp(8), dp(16), dp(8));
        GradientDrawable bt = new GradientDrawable(); bt.setCornerRadius(dp(20)); bt.setColor(0x99000000);
        busyText.setBackground(bt);
        busyText.setVisibility(View.GONE);
        root.addView(busyText, lp(-2, -2, Gravity.CENTER));

        // Respetar barra de estado y de navegación
        root.setOnApplyWindowInsetsListener((v, ins) -> {
            top.setPadding(dp(16), dp(12) + ins.getSystemWindowInsetTop(), dp(16), dp(12));
            bottom.setPadding(0, dp(16), 0, dp(28) + ins.getSystemWindowInsetBottom());
            return ins;
        });

        btnClose.setOnClickListener(v -> { deliver("cancel", null, null); finish(); });
        btnGal.setOnClickListener(v -> { deliver("gallery", null, null); finish(); });
        btnFlash.setOnClickListener(v -> {
            if (busy) return;
            flashOn = !flashOn;
            refreshUi();
            restartSession();
        });
        btnFlip.setOnClickListener(v -> {
            if (busy) return;
            facing = "user".equals(facing) ? "environment" : "user";
            btnFlip.animate().rotationBy(180f).setDuration(300).start();
            if (bg != null) bg.post(() -> { closeCamera(); ui.post(this::openCamera); });
        });
        shutter.setOnClickListener(v -> shoot());

        pinch = new ScaleGestureDetector(this, new ScaleGestureDetector.SimpleOnScaleGestureListener() {
            @Override public boolean onScale(ScaleGestureDetector d) { setZoom(zoom * d.getScaleFactor()); return true; }
        });
        tv.setOnTouchListener((v, e) -> {
            pinch.onTouchEvent(e);
            if (e.getActionMasked() == MotionEvent.ACTION_UP && !pinch.isInProgress() && e.getPointerCount() == 1
                && e.getEventTime() - e.getDownTime() < 300) {
                tapFocus(e.getX(), e.getY());
            }
            return true;
        });
        setContentView(root);
    }

    private static FrameLayout.LayoutParams lp(int w, int h, int g) { return new FrameLayout.LayoutParams(w, h, g); }

    private void showFocusRing(float x, float y) {
        focusRing.setX(x - dp(36)); focusRing.setY(y - dp(36));
        focusRing.animate().cancel();
        focusRing.setAlpha(1f); focusRing.setScaleX(1.4f); focusRing.setScaleY(1.4f);
        focusRing.animate().scaleX(1f).scaleY(1f).setDuration(250)
            .withEndAction(() -> focusRing.animate().alpha(0f).setStartDelay(700).setDuration(300).start()).start();
    }

    private void fitPreview() {
        if (previewSize == null || tv.getWidth() == 0) return;
        float W = tv.getWidth(), H = tv.getHeight();
        float pw = previewSize.getHeight(), ph = previewSize.getWidth(); // en vertical
        float k = Math.max(W / pw, H / ph);
        Matrix m = new Matrix();
        m.setScale(pw * k / W, ph * k / H, W / 2f, H / 2f);
        tv.setTransform(m);
    }

    private void refreshUi() {
        btnFlash.setVisibility(flashAvail ? View.VISIBLE : View.INVISIBLE);
        btnFlash.setKind(flashOn ? IconView.FLASH_ON : IconView.FLASH_OFF);
        if (usingExt) {
            badge.setText(extMode == CameraExtensionCharacteristics.EXTENSION_HDR ? "HDR" : "Mejora automática");
            badge.setVisibility(View.VISIBLE);
        } else badge.setVisibility(View.GONE);
        refreshZoomUi();
    }

    private void refreshZoomUi() {
        zoomRow.removeAllViews();
        if (zoomMax <= zoomMin) return;
        List<Float> stops = new ArrayList<>();
        if (zoomMin <= 0.65f) stops.add(Math.max(zoomMin, 0.5f));
        stops.add(1f);
        if (zoomMax >= 2f) stops.add(2f);
        if (stops.size() < 2) return;
        Collections.sort(stops);
        for (int i = 0; i < stops.size(); i++) {
            final float s = stops.get(i);
            float next = i + 1 < stops.size() ? stops.get(i + 1) : Float.MAX_VALUE;
            boolean on = zoom >= s - 0.01f && zoom < next - 0.01f;
            TextView t = new TextView(this);
            String lbl = on ? fmt(zoom) : fmt(s);
            t.setText(lbl + "×");
            t.setTextSize(12);
            t.setTextColor(on ? 0xFFFDE047 : Color.WHITE);
            t.setGravity(Gravity.CENTER);
            t.setBackground(round(0x5C000000));
            LinearLayout.LayoutParams l = new LinearLayout.LayoutParams(dp(on ? 42 : 36), dp(on ? 42 : 36));
            l.leftMargin = dp(5); l.rightMargin = dp(5);
            t.setOnClickListener(v -> setZoom(s));
            zoomRow.addView(t, l);
        }
    }

    private static String fmt(float z) {
        float r = Math.round(z * 10f) / 10f;
        String s = (r == (int) r) ? String.valueOf((int) r) : String.valueOf(r);
        return s.replace('.', ',');
    }

    private void rotateIcons() {
        float rot = -deviceDeg;
        if (rot < -180) rot += 360;
        for (View v : new View[] { btnFlash, btnClose, btnGal, btnFlip }) v.animate().rotation(rot).setDuration(250).start();
    }

    // ----- Botón de disparo -----
    static class ShutterView extends View {
        private final Paint p = new Paint(Paint.ANTI_ALIAS_FLAG);
        private boolean busy = false;
        ShutterView(Context c) { super(c); setClickable(true); }
        void setBusy(boolean b) { busy = b; invalidate(); }
        @Override protected void onDraw(Canvas c) {
            float w = getWidth(), r = w / 2f;
            float d = getResources().getDisplayMetrics().density;
            p.setStyle(Paint.Style.STROKE); p.setStrokeWidth(4 * d); p.setColor(Color.WHITE);
            c.drawCircle(r, r, r - 2 * d, p);
            p.setStyle(Paint.Style.FILL); p.setColor(busy ? 0x88FFFFFF : Color.WHITE);
            c.drawCircle(r, r, r - (isPressed() ? 11 : 8) * d, p);
        }
        @Override protected void drawableStateChanged() { super.drawableStateChanged(); invalidate(); }
    }

    // ----- Íconos dibujados (sin emojis ni recursos) -----
    static class IconView extends View {
        static final int CLOSE = 0, FLASH_ON = 1, FLASH_OFF = 2, FLIP = 3, GALLERY = 4;
        private int kind;
        private final Paint p = new Paint(Paint.ANTI_ALIAS_FLAG);
        private final Path path = new Path();
        IconView(Context c, int k) {
            super(c); kind = k; setClickable(true);
            setBackground(round(0x33FFFFFF));
        }
        void setKind(int k) { kind = k; invalidate(); }
        @Override protected void onDraw(Canvas c) {
            float s = getWidth(), u = s / 24f;
            c.save();
            c.translate(s / 2f - 12 * u, s / 2f - 12 * u);
            p.setColor(kind == FLASH_ON ? 0xFFFDE047 : Color.WHITE);
            p.setStrokeWidth(2 * u);
            p.setStrokeCap(Paint.Cap.ROUND);
            p.setStrokeJoin(Paint.Join.ROUND);
            p.setStyle(Paint.Style.STROKE);
            path.reset();
            switch (kind) {
                case CLOSE:
                    c.drawLine(7 * u, 7 * u, 17 * u, 17 * u, p);
                    c.drawLine(17 * u, 7 * u, 7 * u, 17 * u, p);
                    break;
                case FLASH_ON:
                case FLASH_OFF:
                    path.moveTo(13 * u, 3 * u); path.lineTo(6 * u, 13.5f * u); path.lineTo(11.5f * u, 13.5f * u);
                    path.lineTo(10.5f * u, 21 * u); path.lineTo(18 * u, 10 * u); path.lineTo(12.5f * u, 10 * u); path.close();
                    if (kind == FLASH_ON) { p.setStyle(Paint.Style.FILL_AND_STROKE); }
                    c.drawPath(path, p);
                    if (kind == FLASH_OFF) { p.setStyle(Paint.Style.STROKE); c.drawLine(4 * u, 4 * u, 20 * u, 20 * u, p); }
                    break;
                case FLIP:
                    c.drawArc(new RectF(5 * u, 5 * u, 19 * u, 19 * u), 200, 150, false, p);
                    c.drawArc(new RectF(5 * u, 5 * u, 19 * u, 19 * u), 20, 150, false, p);
                    path.moveTo(17.5f * u, 4.5f * u); path.lineTo(18.6f * u, 8.4f * u); path.lineTo(14.7f * u, 8.6f * u);
                    path.moveTo(6.5f * u, 19.5f * u); path.lineTo(5.4f * u, 15.6f * u); path.lineTo(9.3f * u, 15.4f * u);
                    c.drawPath(path, p);
                    break;
                case GALLERY:
                    c.drawRoundRect(new RectF(4 * u, 5 * u, 20 * u, 19 * u), 2.5f * u, 2.5f * u, p);
                    c.drawCircle(9 * u, 10 * u, 1.6f * u, p);
                    path.moveTo(4.5f * u, 17 * u); path.lineTo(10 * u, 12.5f * u); path.lineTo(13.5f * u, 15.5f * u);
                    path.lineTo(16 * u, 13.5f * u); path.lineTo(19.5f * u, 16.5f * u);
                    c.drawPath(path, p);
                    break;
            }
            c.restore();
        }
    }
}
