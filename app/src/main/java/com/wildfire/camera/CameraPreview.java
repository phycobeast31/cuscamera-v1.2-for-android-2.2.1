package com.wildfire.camera;

import android.content.Context;
import android.hardware.Camera;
import android.util.AttributeSet;
import android.util.Log;
import android.view.Display;
import android.view.MotionEvent;
import android.view.Surface;
import android.view.SurfaceHolder;
import android.view.SurfaceView;
import android.view.WindowManager;

import java.util.List;

/** SurfaceView that owns the preview lifecycle for a camera opened by the Activity. */
public class CameraPreview extends SurfaceView implements SurfaceHolder.Callback {

    public interface TouchListener {
        /** x/y in view pixels, w/h = view size. */
        void onPreviewTouched(float x, float y, int w, int h);
    }

    private static final String TAG = "CameraPreview";

    private Camera camera;
    private final SurfaceHolder holder;
    private boolean surfaceReady;
    private boolean previewing;
    private Camera.Size previewSize;
    private int displayOrientation;
    private TouchListener touchListener;

    public CameraPreview(Context c, AttributeSet a) {
        super(c, a);
        holder = getHolder();
        holder.addCallback(this);
        holder.setType(SurfaceHolder.SURFACE_TYPE_PUSH_BUFFERS); // required on 2.x
    }

    public void setTouchListener(TouchListener l) { touchListener = l; }

    public int getDisplayOrientation() { return displayOrientation; }

    /** Attach (or detach with null) a camera. The Activity keeps ownership/release duty. */
    public void setCamera(Camera cam) {
        stopPreview();
        camera = cam;
        if (camera != null) {
            chooseSizes();
            displayOrientation = computeDisplayOrientation();
            requestLayout();
            if (surfaceReady) startPreview();
        }
    }

    /** Preview size matching the picture aspect ratio, capped at 640 wide for speed. */
    private void chooseSizes() {
        Camera.Parameters p = camera.getParameters();
        Camera.Size pic = p.getPictureSize();
        double target = (double) pic.width / pic.height;
        Camera.Size best = null;
        double bestDiff = Double.MAX_VALUE;
        List<Camera.Size> sizes = p.getSupportedPreviewSizes();
        if (sizes == null) { previewSize = p.getPreviewSize(); return; }
        for (Camera.Size s : sizes) {
            if (s.width > 640) continue;
            double diff = Math.abs((double) s.width / s.height - target);
            if (best == null || diff < bestDiff - 0.001
                    || (Math.abs(diff - bestDiff) <= 0.001 && s.width > best.width)) {
                best = s; bestDiff = diff;
            }
        }
        previewSize = (best != null) ? best : p.getPreviewSize();
    }

    private int computeDisplayOrientation() {
        WindowManager wm = (WindowManager) getContext().getSystemService(Context.WINDOW_SERVICE);
        Display d = wm.getDefaultDisplay();
        switch (d.getRotation()) {           // API 8
            case Surface.ROTATION_0:   return 90;
            case Surface.ROTATION_90:  return 0;
            case Surface.ROTATION_180: return 270;
            case Surface.ROTATION_270: return 180;
        }
        return 90;
    }

    public void startPreview() {
        if (camera == null || !surfaceReady) return;
        stopPreview();
        try {
            Camera.Parameters p = camera.getParameters();
            if (previewSize != null) p.setPreviewSize(previewSize.width, previewSize.height);
            camera.setParameters(p);
        } catch (RuntimeException e) {
            Log.w(TAG, "setPreviewSize failed: " + e);
        }
        try {
            displayOrientation = computeDisplayOrientation();
            camera.setDisplayOrientation(displayOrientation); // API 8
            camera.setPreviewDisplay(holder);
            camera.startPreview();
            previewing = true;
        } catch (Exception e) {
            Log.e(TAG, "startPreview failed", e);
        }
    }

    public void stopPreview() {
        if (camera != null && previewing) {
            try { camera.stopPreview(); } catch (Exception ignored) { }
        }
        previewing = false;
    }

    /** Call after a configuration (rotation) change. */
    public void onRotated() {
        displayOrientation = computeDisplayOrientation();
        requestLayout();
        if (camera != null && surfaceReady) startPreview();
    }

    // ---- SurfaceHolder.Callback ----
    public void surfaceCreated(SurfaceHolder h) {
        surfaceReady = true;
        if (camera != null) startPreview();
    }

    public void surfaceChanged(SurfaceHolder h, int format, int w, int hgt) {
        surfaceReady = true;
        if (camera != null) startPreview();
    }

    public void surfaceDestroyed(SurfaceHolder h) {
        surfaceReady = false;
        stopPreview();
    }

    // ---- Aspect-correct measuring ----
    @Override
    protected void onMeasure(int wSpec, int hSpec) {
        int w = resolveSize(getSuggestedMinimumWidth(), wSpec);
        int h = resolveSize(getSuggestedMinimumHeight(), hSpec);
        if (previewSize != null) {
            boolean swap = (displayOrientation == 90 || displayOrientation == 270);
            double pw = swap ? previewSize.height : previewSize.width;
            double ph = swap ? previewSize.width : previewSize.height;
            double ratio = pw / ph;
            if (w > h * ratio) w = (int) (h * ratio + 0.5);
            else h = (int) (w / ratio + 0.5);
        }
        setMeasuredDimension(w, h);
    }

    // ---- Touch to focus ----
    @Override
    public boolean onTouchEvent(MotionEvent e) {
        if (e.getAction() == MotionEvent.ACTION_UP && touchListener != null) {
            touchListener.onPreviewTouched(e.getX(), e.getY(), getWidth(), getHeight());
        }
        return true;
    }
}
