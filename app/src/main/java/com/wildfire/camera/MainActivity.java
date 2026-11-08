package com.wildfire.camera;

import android.app.Activity;
import android.app.AlertDialog;
import android.content.DialogInterface;
import android.content.res.Configuration;
import android.graphics.ImageFormat;
import android.graphics.Rect;
import android.hardware.Camera;
import android.media.AudioManager;
import android.media.CamcorderProfile;
import android.media.MediaRecorder;
import android.media.MediaScannerConnection;
import android.media.SoundPool;
import android.os.Build;
import android.os.Bundle;
import android.os.Environment;
import android.os.Handler;
import android.util.Log;
import android.view.Menu;
import android.view.MenuItem;
import android.view.OrientationEventListener;
import android.view.View;
import android.view.animation.Animation;
import android.view.animation.AnimationUtils;
import android.view.animation.RotateAnimation;
import android.widget.Button;
import android.widget.CompoundButton;
import android.widget.FrameLayout;
import android.widget.Toast;
import android.widget.ToggleButton;

import java.io.File;
import java.io.FileOutputStream;
import java.lang.reflect.Method;
import java.text.SimpleDateFormat;
import java.util.ArrayList;
import java.util.Date;
import java.util.List;
import java.util.Locale;

public class MainActivity extends Activity implements CameraPreview.TouchListener {

    private static final String TAG = "WildfireCam";

    // Best-effort Qualcomm/HTC legacy touch-AF keys. HALs differ: edit here if your ROM uses others.
    private static final String QC_TOUCH_SUPPORTED = "touch-af-aec-values";
    private static final String QC_TOUCH_MODE      = "touch-af-aec";
    private static final String QC_TOUCH_ON        = "touch-af-aec-on";
    private static final String QC_TOUCH_X         = "touchAfAec-x";
    private static final String QC_TOUCH_Y         = "touchAfAec-y";
    private static final String QC_TOUCH_DX        = "touchAfAec-dx";
    private static final String QC_TOUCH_DY        = "touchAfAec-dy";

    private Camera camera;
    private CameraPreview preview;
    private MediaRecorder recorder;
    private ToggleButton modeToggle;
    private Button captureButton, settingsButton;
    private View focusRing, shutterContainer, shutterTop, shutterBottom;
    private View[] rotatables;

    private OrientationEventListener orientationListener;
    private SoundPool soundPool;
    private int shutterSoundId;
    private boolean soundLoaded;

    private final Handler handler = new Handler();

    // State
    private boolean videoMode, recording, busy;
    private boolean touchFocus = true;      // true = touch/auto, false = average/infinity
    private int jpegQuality = 100;
    private String savedIso;
    private int savedEv = 0;
    private int deviceOrientation;          // 0/90/180/270 as reported by sensor
    private int uiAngle;                    // current rotation applied to buttons
    private File currentVideo;

    @Override
    public void onCreate(Bundle b) {
        super.onCreate(b);
        setContentView(R.layout.main);

        preview = (CameraPreview) findViewById(R.id.preview);
        preview.setTouchListener(this);
        modeToggle = (ToggleButton) findViewById(R.id.mode_toggle);
        captureButton = (Button) findViewById(R.id.capture_button);
        settingsButton = (Button) findViewById(R.id.settings_button);
        focusRing = findViewById(R.id.focus_ring);
        shutterContainer = findViewById(R.id.shutter_container);
        shutterTop = findViewById(R.id.shutter_top);
        shutterBottom = findViewById(R.id.shutter_bottom);
        rotatables = new View[] { modeToggle, captureButton, settingsButton };

        modeToggle.setOnCheckedChangeListener(new CompoundButton.OnCheckedChangeListener() {
            public void onCheckedChanged(CompoundButton v, boolean checked) {
                videoMode = checked;
                captureButton.setText(checked ? "REC" : "SHOOT");
            }
        });
        captureButton.setOnClickListener(new View.OnClickListener() {
            public void onClick(View v) {
                if (busy || camera == null) return;
                if (!videoMode) takePhoto();
                else if (recording) stopRecording();
                else startRecording();
            }
        });
        settingsButton.setOnClickListener(new View.OnClickListener() {
            public void onClick(View v) { openOptionsMenu(); }
        });

        // Optional custom shutter sound: res/raw/shutter.(ogg|wav|mp3)
        int rawId = getResources().getIdentifier("shutter", "raw", getPackageName());
        if (rawId != 0) {
            soundPool = new SoundPool(2, AudioManager.STREAM_SYSTEM, 0);
            shutterSoundId = soundPool.load(this, rawId, 1);
            soundLoaded = true; // load is quick; if not ready yet, play() just returns 0
        }

        orientationListener = new OrientationEventListener(this) {
            @Override
            public void onOrientationChanged(int deg) {
                if (deg == ORIENTATION_UNKNOWN) return;
                int snapped = (((deg + 45) / 90) * 90) % 360;
                if (snapped != deviceOrientation) {
                    deviceOrientation = snapped;
                    rotateUi(-snapped);
                }
            }
        };
    }

    @Override
    protected void onResume() {
        super.onResume();
        openCamera();
        if (orientationListener.canDetectOrientation()) orientationListener.enable();
    }

    @Override
    protected void onPause() {
        super.onPause();
        orientationListener.disable();
        if (recording) stopRecording();
        releaseCamera();
    }

    @Override
    protected void onDestroy() {
        super.onDestroy();
        if (soundPool != null) { soundPool.release(); soundPool = null; }
    }

    @Override
    public void onConfigurationChanged(Configuration cfg) {
        super.onConfigurationChanged(cfg);   // we handle orientation ourselves (configChanges)
        preview.onRotated();
    }

    // ------------------------------------------------------------------ camera lifecycle
    private void openCamera() {
        if (camera != null) return;
        try {
            camera = Camera.open();
        } catch (RuntimeException e) {
            Toast.makeText(this, "Cannot open camera (in use?)", Toast.LENGTH_LONG).show();
            return;
        }
        applyBaseParameters();
        preview.setCamera(camera);
    }

    private void releaseCamera() {
        preview.setCamera(null);
        if (camera != null) {
            try { camera.release(); } catch (Exception ignored) { }
            camera = null;
        }
        busy = false;
    }

    private void applyBaseParameters() {
        try {
            Camera.Parameters p = camera.getParameters();

            // Largest supported picture size. We never decode to a Bitmap, so RAM stays flat.
            Camera.Size best = null;
            List<Camera.Size> pics = p.getSupportedPictureSizes();
            if (pics != null) {
                for (Camera.Size s : pics) {
                    if (best == null || (long) s.width * s.height > (long) best.width * best.height) best = s;
                }
            }
            if (best != null) p.setPictureSize(best.width, best.height);

            p.setPictureFormat(ImageFormat.JPEG);
            p.setJpegQuality(jpegQuality);              // 100 = no extra compression loss
            p.setJpegThumbnailQuality(jpegQuality);
            applyFocusMode(p);
            if (savedIso != null) p.set("iso", savedIso);
            p.setExposureCompensation(savedEv);
            camera.setParameters(p);
        } catch (RuntimeException e) {
            Log.w(TAG, "applyBaseParameters: " + e);
        }
    }

    private void applyFocusMode(Camera.Parameters p) {
        List<String> modes = p.getSupportedFocusModes();
        if (modes == null) return;
        if (touchFocus) {
            if (modes.contains(Camera.Parameters.FOCUS_MODE_AUTO))
                p.setFocusMode(Camera.Parameters.FOCUS_MODE_AUTO);
        } else {
            if (modes.contains(Camera.Parameters.FOCUS_MODE_INFINITY))
                p.setFocusMode(Camera.Parameters.FOCUS_MODE_INFINITY);
            else if (modes.contains(Camera.Parameters.FOCUS_MODE_FIXED))
                p.setFocusMode(Camera.Parameters.FOCUS_MODE_FIXED);
        }
    }

    private void safeSet(Camera.Parameters p) {
        try { camera.setParameters(p); } catch (RuntimeException e) { Log.w(TAG, "setParameters: " + e); }
    }

    /** Back camera on API 8 (sensor assumed mounted at 90 degrees). */
    private int jpegRotation() {
        return (90 - deviceOrientation + 360) % 360;
    }

    // ------------------------------------------------------------------ touch to focus
    public void onPreviewTouched(float x, float y, int w, int h) {
        if (camera == null || !touchFocus || busy) return;
        try {
            float nx = x / w, ny = y / h;           // 0..1 in view space
            float sx, sy;                           // 0..1 in sensor (landscape) space
            switch (preview.getDisplayOrientation()) {
                case 90:  sx = ny;        sy = 1f - nx; break;
                case 180: sx = 1f - nx;   sy = 1f - ny; break;
                case 270: sx = 1f - ny;   sy = nx;      break;
                default:  sx = nx;        sy = ny;
            }
            Camera.Parameters p = camera.getParameters();
            Camera.Size ps = p.getPreviewSize();

            // 1) Qualcomm / HTC legacy touch AF+AEC (Froyo/Gingerbread-era HALs)
            if (p.get(QC_TOUCH_SUPPORTED) != null) {
                p.set(QC_TOUCH_MODE, QC_TOUCH_ON);
                p.set(QC_TOUCH_X, (int) (sx * ps.width));
                p.set(QC_TOUCH_Y, (int) (sy * ps.height));
                p.set(QC_TOUCH_DX, ps.width / 8);
                p.set(QC_TOUCH_DY, ps.height / 8);
            }
            // 2) Standard focus areas via reflection (only exist on API 14+ ROMs)
            setFocusAreasReflective(p, (int) (sx * 2000 - 1000), (int) (sy * 2000 - 1000));

            applyFocusMode(p);
            safeSet(p);

            showFocusRing(x, y);
            camera.cancelAutoFocus();
            camera.autoFocus(new Camera.AutoFocusCallback() {
                public void onAutoFocus(boolean ok, Camera c) {
                    handler.postDelayed(new Runnable() {
                        public void run() { focusRing.setVisibility(View.GONE); }
                    }, 600);
                }
            });
        } catch (RuntimeException e) {
            Log.w(TAG, "touch focus failed: " + e);
        }
    }

    private void setFocusAreasReflective(Camera.Parameters p, int cx, int cy) {
        try {
            Class<?> areaCls = Class.forName("android.hardware.Camera$Area");
            Rect rect = new Rect(
                    Math.max(-1000, cx - 100), Math.max(-1000, cy - 100),
                    Math.min(1000, cx + 100), Math.min(1000, cy + 100));
            Object area = areaCls.getConstructor(Rect.class, int.class).newInstance(rect, 1000);
            ArrayList<Object> list = new ArrayList<Object>();
            list.add(area);
            Method m = p.getClass().getMethod("setFocusAreas", List.class);
            m.invoke(p, list);
        } catch (Throwable ignored) { /* not available on Froyo/Gingerbread */ }
    }

    private void showFocusRing(float x, float y) {
        int size = (int) (64 * getResources().getDisplayMetrics().density);
        FrameLayout.LayoutParams lp = new FrameLayout.LayoutParams(size, size);
        lp.leftMargin = (int) (preview.getLeft() + x - size / 2);
        lp.topMargin = (int) (preview.getTop() + y - size / 2);
        focusRing.setLayoutParams(lp);
        focusRing.setVisibility(View.VISIBLE);
    }

    // ------------------------------------------------------------------ photo
    private void takePhoto() {
        busy = true;
        try {
            Camera.Parameters p = camera.getParameters();
            p.setRotation(jpegRotation());
            p.setJpegQuality(jpegQuality);
            safeSet(p);

            playShutterSound();
            closeShutter();
            camera.takePicture(null, null, new Camera.PictureCallback() {
                public void onPictureTaken(final byte[] data, Camera c) {
                    try { c.startPreview(); } catch (Exception ignored) { }
                    busy = false;
                    savePhotoAsync(data);   // raw JPEG bytes straight to disk, no Bitmap decode
                }
            });
        } catch (RuntimeException e) {
            busy = false;
            Log.e(TAG, "takePicture failed", e);
        }
    }

    private void savePhotoAsync(final byte[] data) {
        new Thread(new Runnable() {
            public void run() {
                final File f = newMediaFile("IMG_", ".jpg");
                if (f == null) { toast("SD card not available"); return; }
                FileOutputStream out = null;
                try {
                    out = new FileOutputStream(f);
                    out.write(data);
                    out.flush();
                    scan(f);
                    toast("Saved " + f.getName());
                } catch (Exception e) {
                    toast("Save failed");
                } finally {
                    if (out != null) try { out.close(); } catch (Exception ignored) { }
                }
            }
        }).start();
    }

    // ------------------------------------------------------------------ shutter FX
    private void playShutterSound() {
        if (soundPool != null && soundLoaded) soundPool.play(shutterSoundId, 1f, 1f, 1, 0, 1f);
        // Without res/raw/shutter.*, the OS plays its own click when takePicture() fires.
    }

    private void closeShutter() {
        shutterTop.clearAnimation();
        shutterBottom.clearAnimation();
        Animation top = AnimationUtils.loadAnimation(this, R.anim.shutter_top_close);
        Animation bot = AnimationUtils.loadAnimation(this, R.anim.shutter_bottom_close);
        bot.setAnimationListener(new Animation.AnimationListener() {
            public void onAnimationStart(Animation a) { }
            public void onAnimationRepeat(Animation a) { }
            public void onAnimationEnd(Animation a) { openShutter(); }
        });
        shutterTop.startAnimation(top);
        shutterBottom.startAnimation(bot);
        shutterContainer.setVisibility(View.VISIBLE);
    }

    private void openShutter() {
        Animation top = AnimationUtils.loadAnimation(this, R.anim.shutter_top_open);
        Animation bot = AnimationUtils.loadAnimation(this, R.anim.shutter_bottom_open);
        bot.setAnimationListener(new Animation.AnimationListener() {
            public void onAnimationStart(Animation a) { }
            public void onAnimationRepeat(Animation a) { }
            public void onAnimationEnd(Animation a) {
                shutterContainer.post(new Runnable() {
                    public void run() { shutterContainer.setVisibility(View.GONE); }
                });
            }
        });
        shutterTop.startAnimation(top);
        shutterBottom.startAnimation(bot);
    }

    // ------------------------------------------------------------------ video
    private void startRecording() {
        try {
            camera.cancelAutoFocus();
            camera.unlock();
            recorder = new MediaRecorder();
            recorder.setCamera(camera);
            recorder.setAudioSource(MediaRecorder.AudioSource.CAMCORDER);
            recorder.setVideoSource(MediaRecorder.VideoSource.CAMERA);

            CamcorderProfile prof = CamcorderProfile.get(CamcorderProfile.QUALITY_HIGH);
            if (prof == null) prof = CamcorderProfile.get(CamcorderProfile.QUALITY_LOW);
            if (prof != null) {
                recorder.setProfile(prof);
            } else {  // conservative fallback for odd HALs
                recorder.setOutputFormat(MediaRecorder.OutputFormat.THREE_GPP);
                recorder.setAudioEncoder(MediaRecorder.AudioEncoder.AMR_NB);
                recorder.setVideoEncoder(MediaRecorder.VideoEncoder.H263);
                recorder.setVideoSize(176, 144);
            }
            currentVideo = newMediaFile("VID_", ".3gp");
            if (currentVideo == null) throw new RuntimeException("no storage");
            recorder.setOutputFile(currentVideo.getAbsolutePath());
            recorder.setPreviewDisplay(preview.getHolder().getSurface());
            if (Build.VERSION.SDK_INT >= 9) recorder.setOrientationHint(jpegRotation());
            recorder.prepare();
            recorder.start();

            recording = true;
            modeToggle.setEnabled(false);
            captureButton.setText("STOP");
        } catch (Exception e) {
            Log.e(TAG, "startRecording failed", e);
            toast("Video start failed");
            cleanupRecorder();
        }
    }

    private void stopRecording() {
        try { recorder.stop(); } catch (RuntimeException e) { Log.w(TAG, "stop: " + e); }
        cleanupRecorder();
        captureButton.setText("REC");
        if (currentVideo != null) { scan(currentVideo); toast("Saved " + currentVideo.getName()); }
    }

    private void cleanupRecorder() {
        if (recorder != null) {
            try { recorder.reset(); } catch (Exception ignored) { }
            recorder.release();
            recorder = null;
        }
        if (camera != null) {
            try { camera.lock(); } catch (Exception ignored) { }
        }
        recording = false;
        modeToggle.setEnabled(true);
    }

    // ------------------------------------------------------------------ settings menu
    @Override
    public boolean onCreateOptionsMenu(Menu menu) {
        menu.add(0, 1, 0, "Focus mode");
        menu.add(0, 2, 1, "ISO");
        menu.add(0, 3, 2, "Exposure (EV)");
        menu.add(0, 4, 3, "Quality");
        return true;
    }

    @Override
    public boolean onOptionsItemSelected(MenuItem item) {
        if (camera == null || recording) return true;
        switch (item.getItemId()) {
            case 1: dlgFocus();   break;
            case 2: dlgIso();     break;
            case 3: dlgEv();      break;
            case 4: dlgQuality(); break;
        }
        return true;
    }

    private void dlgFocus() {
        String[] items = { "Touch / Auto focus", "Average / Infinity focus" };
        new AlertDialog.Builder(this).setTitle("Focus mode")
            .setSingleChoiceItems(items, touchFocus ? 0 : 1, new DialogInterface.OnClickListener() {
                public void onClick(DialogInterface d, int which) {
                    touchFocus = (which == 0);
                    Camera.Parameters p = camera.getParameters();
                    applyFocusMode(p);
                    safeSet(p);
                    d.dismiss();
                    toast(touchFocus ? "Tap preview to focus" : "Infinity focus");
                }
            }).show();
    }

    private void dlgIso() {
        Camera.Parameters p = camera.getParameters();
        String vals = p.get("iso-values");
        if (vals == null) { toast("ISO not supported by this camera HAL"); return; }
        final String[] items = vals.split(",");
        String cur = p.get("iso");
        int sel = 0;
        for (int i = 0; i < items.length; i++) if (items[i].equals(cur)) sel = i;
        new AlertDialog.Builder(this).setTitle("ISO")
            .setSingleChoiceItems(items, sel, new DialogInterface.OnClickListener() {
                public void onClick(DialogInterface d, int which) {
                    savedIso = items[which];
                    Camera.Parameters q = camera.getParameters();
                    q.set("iso", savedIso);
                    safeSet(q);
                    d.dismiss();
                }
            }).show();
    }

    private void dlgEv() {
        Camera.Parameters p = camera.getParameters();
        final int min = p.getMinExposureCompensation();
        final int max = p.getMaxExposureCompensation();
        if (min == 0 && max == 0) { toast("EV not supported"); return; }
        float step = p.getExposureCompensationStep();
        final String[] items = new String[max - min + 1];
        for (int i = min; i <= max; i++)
            items[i - min] = String.format(Locale.US, "%+.1f EV", i * step);
        new AlertDialog.Builder(this).setTitle("Exposure")
            .setSingleChoiceItems(items, p.getExposureCompensation() - min, new DialogInterface.OnClickListener() {
                public void onClick(DialogInterface d, int which) {
                    savedEv = which + min;
                    Camera.Parameters q = camera.getParameters();
                    q.setExposureCompensation(savedEv);
                    safeSet(q);
                    d.dismiss();
                }
            }).show();
    }

    private void dlgQuality() {
        final int[] vals = { 100, 95, 85, 70 };
        String[] items = { "JPEG 100% (maximum)", "JPEG 95%", "JPEG 85%", "JPEG 70%" };
        int sel = 0;
        for (int i = 0; i < vals.length; i++) if (vals[i] == jpegQuality) sel = i;
        new AlertDialog.Builder(this).setTitle("Photo quality")
            .setSingleChoiceItems(items, sel, new DialogInterface.OnClickListener() {
                public void onClick(DialogInterface d, int which) {
                    jpegQuality = vals[which];
                    Camera.Parameters q = camera.getParameters();
                    q.setJpegQuality(jpegQuality);
                    safeSet(q);
                    d.dismiss();
                }
            }).show();
    }

    // ------------------------------------------------------------------ UI rotation (API 8: no View.setRotation)
    private void rotateUi(int target) {
        int from = uiAngle;
        int delta = ((target - from) % 360 + 540) % 360 - 180;   // shortest path
        int to = from + delta;
        for (View v : rotatables) {
            RotateAnimation a = new RotateAnimation(from, to,
                    Animation.RELATIVE_TO_SELF, 0.5f, Animation.RELATIVE_TO_SELF, 0.5f);
            a.setDuration(200);
            a.setFillAfter(true);
            v.startAnimation(a);
        }
        uiAngle = to;
    }

    // ------------------------------------------------------------------ helpers
    private File newMediaFile(String prefix, String ext) {
        if (!Environment.MEDIA_MOUNTED.equals(Environment.getExternalStorageState())) return null;
        File dir = new File(Environment.getExternalStoragePublicDirectory(
                Environment.DIRECTORY_DCIM), "Camera");
        if (!dir.exists() && !dir.mkdirs()) return null;
        String ts = new SimpleDateFormat("yyyyMMdd_HHmmss", Locale.US).format(new Date());
        return new File(dir, prefix + ts + ext);
    }

    private void scan(File f) {
        MediaScannerConnection.scanFile(this, new String[] { f.getAbsolutePath() }, null, null);
    }

    private void toast(final String s) {
        runOnUiThread(new Runnable() {
            public void run() { Toast.makeText(MainActivity.this, s, Toast.LENGTH_SHORT).show(); }
        });
    }
}
