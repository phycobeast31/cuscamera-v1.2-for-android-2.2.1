package com.wildfire.camera;

import android.app.Activity;
import android.app.AlertDialog;
import android.content.DialogInterface;
import android.content.pm.ActivityInfo;
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
import android.widget.Button;
import android.widget.CompoundButton;
import android.widget.FrameLayout;
import android.widget.ImageButton;
import android.widget.Toast;
import android.widget.ToggleButton;

import java.io.File;
import java.io.FileOutputStream;
import java.lang.reflect.Method;
import java.text.SimpleDateFormat;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Comparator;
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
    private ImageButton captureButton;
    private Button settingsButton;
    private View focusRing, shutterContainer, shutterTop, shutterBottom;

    private OrientationEventListener orientationListener;
    private SoundPool soundPool;
    private int shutterSoundId;

    private final Handler handler = new Handler();

    // State (survives layout re-inflation on rotation)
    private boolean videoMode, recording, busy;
    private boolean touchFocus = true;      // true = touch/auto, false = average/infinity
    private int jpegQuality = 100;
    private String savedIso;
    private int savedEv = 0;
    private int savedPicW, savedPicH;       // 0 = largest
    private boolean videoLow;               // use LOW camcorder profile
    private int videoW, videoH;             // 0 = profile default
    private int deviceOrientation;          // 0/90/180/270 from sensor (used for JPEG/video rotation)
    private File currentVideo;

    @Override
    public void onCreate(Bundle b) {
        super.onCreate(b);
        bindViews();

        // Optional custom shutter sound: res/raw/shutter.(ogg|wav|mp3)
        int rawId = getResources().getIdentifier("shutter", "raw", getPackageName());
        if (rawId != 0) {
            soundPool = new SoundPool(2, AudioManager.STREAM_SYSTEM, 0);
            shutterSoundId = soundPool.load(this, rawId, 1);
        }

        orientationListener = new OrientationEventListener(this) {
            @Override
            public void onOrientationChanged(int deg) {
                if (deg == ORIENTATION_UNKNOWN) return;
                deviceOrientation = (((deg + 45) / 90) * 90) % 360;
            }
        };
    }

    /** (Re)inflate the layout for the current orientation and wire everything up. */
    private void bindViews() {
        setContentView(R.layout.main);   // picks layout/ or layout-land/

        preview = (CameraPreview) findViewById(R.id.preview);
        preview.setTouchListener(this);
        modeToggle = (ToggleButton) findViewById(R.id.mode_toggle);
        captureButton = (ImageButton) findViewById(R.id.capture_button);
        settingsButton = (Button) findViewById(R.id.settings_button);
        focusRing = findViewById(R.id.focus_ring);
        shutterContainer = findViewById(R.id.shutter_container);
        shutterTop = findViewById(R.id.shutter_top);
        shutterBottom = findViewById(R.id.shutter_bottom);

        modeToggle.setChecked(videoMode);
        modeToggle.setEnabled(!recording);
        updateCaptureIcon();

        modeToggle.setOnCheckedChangeListener(new CompoundButton.OnCheckedChangeListener() {
            public void onCheckedChanged(CompoundButton v, boolean checked) {
                videoMode = checked;
                updateCaptureIcon();
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

        if (camera != null) preview.setCamera(camera);
    }

    private void updateCaptureIcon() {
        if (recording)      captureButton.setImageResource(R.drawable.ic_stop);
        else if (videoMode) captureButton.setImageResource(R.drawable.ic_video_rec);
        else                captureButton.setImageResource(R.drawable.ic_camera);
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
        super.onConfigurationChanged(cfg);
        preview.setCamera(null);   // detach from the old preview view
        bindViews();               // new layout (portrait / landscape), reattaches camera
    }

    private void lockOrientation(boolean lock) {
        if (lock) {
            boolean land = getResources().getConfiguration().orientation
                    == Configuration.ORIENTATION_LANDSCAPE;
            setRequestedOrientation(land ? ActivityInfo.SCREEN_ORIENTATION_LANDSCAPE
                                         : ActivityInfo.SCREEN_ORIENTATION_PORTRAIT);
        } else {
            setRequestedOrientation(ActivityInfo.SCREEN_ORIENTATION_SENSOR);
        }
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

            Camera.Size chosen = null, largest = null;
            List<Camera.Size> pics = p.getSupportedPictureSizes();
            if (pics != null) {
                for (Camera.Size s : pics) {
                    if (largest == null || (long) s.width * s.height > (long) largest.width * largest.height)
                        largest = s;
                    if (s.width == savedPicW && s.height == savedPicH) chosen = s;
                }
            }
            if (chosen == null) chosen = largest;
            if (chosen != null) p.setPictureSize(chosen.width, chosen.height);

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

    /** Qualcomm legacy "cam-mode": 1 = camcorder, 0 = camera. Ignored by HALs that don't know it. */
    private void setVideoHints(boolean video) {
        if (camera == null) return;
        try {
            Camera.Parameters p = camera.getParameters();
            p.set("cam-mode", video ? "1" : "0");
            camera.setParameters(p);
        } catch (RuntimeException e) { Log.w(TAG, "cam-mode: " + e); }
    }

    /** Back camera on API 8 (sensor assumed mounted at 90 degrees). */
    private int jpegRotation() {
        return (90 - deviceOrientation + 360) % 360;
    }

    // ------------------------------------------------------------------ touch to focus
    public void onPreviewTouched(float x, float y, int w, int h) {
        if (camera == null || !touchFocus || busy) return;
        try {
            float nx = x / w, ny = y / h;
            float sx, sy;
            switch (preview.getDisplayOrientation()) {
                case 90:  sx = ny;        sy = 1f - nx; break;
                case 180: sx = 1f - nx;   sy = 1f - ny; break;
                case 270: sx = 1f - ny;   sy = nx;      break;
                default:  sx = nx;        sy = ny;
            }
            Camera.Parameters p = camera.getParameters();
            Camera.Size ps = p.getPreviewSize();

            if (p.get(QC_TOUCH_SUPPORTED) != null) {
                p.set(QC_TOUCH_MODE, QC_TOUCH_ON);
                p.set(QC_TOUCH_X, (int) (sx * ps.width));
                p.set(QC_TOUCH_Y, (int) (sy * ps.height));
                p.set(QC_TOUCH_DX, ps.width / 8);
                p.set(QC_TOUCH_DY, ps.height / 8);
            }
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
        if (soundPool != null) soundPool.play(shutterSoundId, 1f, 1f, 1, 0, 1f);
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
        final View container = shutterContainer;
        bot.setAnimationListener(new Animation.AnimationListener() {
            public void onAnimationStart(Animation a) { }
            public void onAnimationRepeat(Animation a) { }
            public void onAnimationEnd(Animation a) {
                container.post(new Runnable() {
                    public void run() { container.setVisibility(View.GONE); }
                });
            }
        });
        shutterTop.startAnimation(top);
        shutterBottom.startAnimation(bot);
    }

    // ------------------------------------------------------------------ video
    private void startRecording() {
        camera.cancelAutoFocus();
        setVideoHints(true);
        String lastErr = "unknown error";
        // Try progressively more conservative settings until one works.
        for (int attempt = 0; attempt < 5; attempt++) {
            try {
                if (tryRecorder(attempt)) {
                    recording = true;
                    lockOrientation(true);      // don't re-inflate layout mid-recording
                    modeToggle.setEnabled(false);
                    updateCaptureIcon();
                    return;
                }
            } catch (Exception e) {
                lastErr = String.valueOf(e.getMessage() != null ? e.getMessage() : e);
                Log.e(TAG, "video attempt " + attempt + " failed", e);
            }
            cleanupRecorder();
            if (currentVideo != null) { currentVideo.delete(); currentVideo = null; }
        }
        setVideoHints(false);
        preview.resetPreviewSize();
        toast("Video failed: " + lastErr);
    }

    /** @return true if recording started, false if this attempt isn't applicable. */
    private boolean tryRecorder(int attempt) throws Exception {
        CamcorderProfile prof = null;
        int w, h;
        boolean mp4 = false;
        if (attempt <= 1) {
            int q = (attempt == 0 && !videoLow) ? CamcorderProfile.QUALITY_HIGH
                                                : CamcorderProfile.QUALITY_LOW;
            prof = CamcorderProfile.get(q);
            if (prof == null) return false;
            w = prof.videoFrameWidth;
            h = prof.videoFrameHeight;
            if (attempt == 0 && videoW > 0) { w = videoW; h = videoH; }
            mp4 = (prof.fileFormat == MediaRecorder.OutputFormat.MPEG_4);
        } else if (attempt == 2) { w = 320; h = 240; }
        else                     { w = 176; h = 144; }

        preview.setExactPreview(w, h);      // many Qualcomm HALs need preview == video size
        camera.unlock();

        recorder = new MediaRecorder();
        recorder.setCamera(camera);
        if (attempt < 4) recorder.setAudioSource(MediaRecorder.AudioSource.MIC);
        recorder.setVideoSource(MediaRecorder.VideoSource.CAMERA);

        if (prof != null) {
            recorder.setProfile(prof);
            if (attempt == 0 && videoW > 0) recorder.setVideoSize(w, h);
        } else {
            recorder.setOutputFormat(MediaRecorder.OutputFormat.THREE_GPP);
            if (attempt < 4) recorder.setAudioEncoder(MediaRecorder.AudioEncoder.AMR_NB);
            recorder.setVideoEncoder(attempt == 2 ? MediaRecorder.VideoEncoder.MPEG_4_SP
                                                  : MediaRecorder.VideoEncoder.H263);
            recorder.setVideoFrameRate(15);
            recorder.setVideoSize(w, h);
        }

        currentVideo = newMediaFile("VID_", mp4 ? ".mp4" : ".3gp");
        if (currentVideo == null) throw new RuntimeException("SD card not available");
        recorder.setOutputFile(currentVideo.getAbsolutePath());
        recorder.setPreviewDisplay(preview.getHolder().getSurface());
        if (Build.VERSION.SDK_INT >= 9) recorder.setOrientationHint(jpegRotation());
        recorder.prepare();
        recorder.start();
        return true;
    }

    private void stopRecording() {
        try { recorder.stop(); } catch (RuntimeException e) { Log.w(TAG, "stop: " + e); }
        cleanupRecorder();
        setVideoHints(false);
        preview.resetPreviewSize();
        lockOrientation(false);
        updateCaptureIcon();
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
        menu.add(0, 5, 1, "Resolution");
        menu.add(0, 2, 2, "ISO");
        menu.add(0, 3, 3, "Exposure (EV)");
        menu.add(0, 4, 4, "Quality");
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
            case 5: if (videoMode) dlgVideoRes(); else dlgPhotoRes(); break;
        }
        return true;
    }

    private static final Comparator<Camera.Size> BY_AREA_DESC = new Comparator<Camera.Size>() {
        public int compare(Camera.Size a, Camera.Size b) {
            return (b.width * b.height) - (a.width * a.height);
        }
    };

    private void dlgPhotoRes() {
        Camera.Parameters p = camera.getParameters();
        List<Camera.Size> sup = p.getSupportedPictureSizes();
        if (sup == null || sup.isEmpty()) { toast("No size list available"); return; }
        final List<Camera.Size> l = new ArrayList<Camera.Size>(sup);
        Collections.sort(l, BY_AREA_DESC);
        String[] items = new String[l.size()];
        Camera.Size cur = p.getPictureSize();
        int sel = 0;
        for (int i = 0; i < l.size(); i++) {
            Camera.Size s = l.get(i);
            items[i] = String.format(Locale.US, "%dx%d  (%.1f MP)", s.width, s.height,
                    s.width * (double) s.height / 1000000.0);
            if (s.width == cur.width && s.height == cur.height) sel = i;
        }
        new AlertDialog.Builder(this).setTitle("Photo resolution")
            .setSingleChoiceItems(items, sel, new DialogInterface.OnClickListener() {
                public void onClick(DialogInterface d, int which) {
                    Camera.Size s = l.get(which);
                    savedPicW = s.width; savedPicH = s.height;
                    Camera.Parameters q = camera.getParameters();
                    q.setPictureSize(s.width, s.height);
                    safeSet(q);
                    preview.setCamera(camera);   // re-pick matching preview aspect
                    d.dismiss();
                }
            }).show();
    }

    private void dlgVideoRes() {
        Camera.Parameters p = camera.getParameters();
        final List<Camera.Size> l = new ArrayList<Camera.Size>();
        List<Camera.Size> sup = p.getSupportedPreviewSizes();
        if (sup != null) for (Camera.Size s : sup) if (s.width <= 720) l.add(s);
        Collections.sort(l, BY_AREA_DESC);
        final int extra = 2;
        String[] items = new String[l.size() + extra];
        items[0] = "Auto - best profile";
        items[1] = "Low (small files)";
        int sel = videoLow ? 1 : 0;
        for (int i = 0; i < l.size(); i++) {
            Camera.Size s = l.get(i);
            items[i + extra] = s.width + "x" + s.height;
            if (videoW == s.width && videoH == s.height) sel = i + extra;
        }
        new AlertDialog.Builder(this).setTitle("Video resolution")
            .setSingleChoiceItems(items, sel, new DialogInterface.OnClickListener() {
                public void onClick(DialogInterface d, int which) {
                    if (which == 0)      { videoLow = false; videoW = videoH = 0; }
                    else if (which == 1) { videoLow = true;  videoW = videoH = 0; }
                    else {
                        Camera.Size s = l.get(which - extra);
                        videoLow = false; videoW = s.width; videoH = s.height;
                    }
                    d.dismiss();
                    toast("Video: " + (which == 0 ? "auto" : which == 1 ? "low"
                            : videoW + "x" + videoH));
                }
            }).show();
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
