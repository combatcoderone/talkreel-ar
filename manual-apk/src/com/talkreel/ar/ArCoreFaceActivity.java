package com.talkreel.ar;

import android.Manifest;
import android.app.Activity;
import android.content.pm.PackageManager;
import android.opengl.GLSurfaceView;
import android.os.Bundle;
import android.view.Gravity;
import android.view.View;
import android.widget.FrameLayout;
import android.widget.LinearLayout;
import android.widget.TextView;
import android.widget.Toast;
import com.google.ar.core.ArCoreApk;
import com.google.ar.core.CameraConfig;
import com.google.ar.core.CameraConfigFilter;
import com.google.ar.core.Config;
import com.google.ar.core.Session;
import com.google.ar.core.exceptions.CameraNotAvailableException;
import com.google.ar.core.exceptions.UnavailableApkTooOldException;
import com.google.ar.core.exceptions.UnavailableArcoreNotInstalledException;
import com.google.ar.core.exceptions.UnavailableDeviceNotCompatibleException;
import com.google.ar.core.exceptions.UnavailableSdkTooOldException;
import com.google.ar.core.exceptions.UnavailableUserDeclinedInstallationException;
import java.util.EnumSet;
import java.util.List;

/** Dedicated ARCore Augmented Faces live-preview studio. */
public final class ArCoreFaceActivity extends Activity {
    private static final int CAMERA_PERMISSION_REQUEST = 41;
    private GLSurfaceView surfaceView;
    private ArCoreFaceRenderer renderer;
    private Session session;
    private TextView trackingStatus;
    private boolean installRequested;

    @Override protected void onCreate(Bundle state) {
        super.onCreate(state);
        getWindow().getDecorView().setSystemUiVisibility(View.SYSTEM_UI_FLAG_FULLSCREEN | View.SYSTEM_UI_FLAG_LAYOUT_STABLE);
        FrameLayout root = new FrameLayout(this);
        surfaceView = new GLSurfaceView(this);
        surfaceView.setEGLContextClientVersion(2);
        renderer = new ArCoreFaceRenderer();
        renderer.setStatusListener(new ArCoreFaceRenderer.StatusListener() {
            @Override public void onTrackingStatus(final String status) {
                runOnUiThread(new Runnable() {
                    @Override public void run() {
                        if (trackingStatus != null) trackingStatus.setText(status);
                    }
                });
            }
        });
        surfaceView.setRenderer(renderer);
        surfaceView.setRenderMode(GLSurfaceView.RENDERMODE_CONTINUOUSLY);
        root.addView(surfaceView, new FrameLayout.LayoutParams(-1, -1));

        LinearLayout top = new LinearLayout(this);
        top.setOrientation(LinearLayout.VERTICAL);
        top.setPadding(18,14,18,12);
        top.setBackgroundColor(0xAA08121E);
        TextView heading = new TextView(this);
        heading.setText("TALKREEL  /  AR STUDIO");
        heading.setTextColor(0xffffffff); heading.setTextSize(16); heading.setLetterSpacing(0.08f);
        TextView subheading = new TextView(this);
        subheading.setText("FACE TRACKING  ·  LIVE PREVIEW");
        subheading.setTextColor(0xff9bc7dc); subheading.setTextSize(10);
        subheading.setPadding(0,4,0,0);
        top.addView(heading); top.addView(subheading);
        root.addView(top,new FrameLayout.LayoutParams(-1,-2,Gravity.TOP));

        trackingStatus = new TextView(this);
        trackingStatus.setText("Waiting for camera permission");
        trackingStatus.setTextColor(0xffffffff);
        trackingStatus.setTextSize(12);
        trackingStatus.setPadding(16,10,16,10);
        trackingStatus.setBackgroundColor(0x9908121e);
        trackingStatus.setGravity(Gravity.CENTER);
        trackingStatus.setAccessibilityLiveRegion(View.ACCESSIBILITY_LIVE_REGION_POLITE);
        FrameLayout.LayoutParams trackingParams = new FrameLayout.LayoutParams(-1,-2,Gravity.TOP);
        trackingParams.topMargin = 82;
        root.addView(trackingStatus,trackingParams);

        LinearLayout controls = new LinearLayout(this);
        controls.setGravity(Gravity.CENTER);
        controls.setPadding(12,8,12,8);
        controls.setBackgroundColor(0xAA08121E);
        String[] names={"Contour","Neon","Studio"};
        for(int i=0;i<names.length;i++) {
            final int style=i;
            TextView chip=new TextView(this);
            chip.setText(names[i]);
            chip.setTextColor(0xffffffff);
            chip.setTextSize(13);
            chip.setGravity(Gravity.CENTER);
            chip.setContentDescription("Apply " + names[i] + " AR face effect");
            chip.setPadding(18,12,18,12);
            chip.setBackgroundColor(i==1?0xff176b91:0xff263747);
            LinearLayout.LayoutParams lp=new LinearLayout.LayoutParams(0,-2,1f);
            lp.setMargins(5,0,5,0);
            controls.addView(chip,lp);
            chip.setOnClickListener(v -> {
                renderer.setEffectStyle(style);
                for(int k=0;k<controls.getChildCount();k++) {
                    controls.getChildAt(k).setBackgroundColor(k==style?0xff176b91:0xff263747);
                }
            });
        }
        FrameLayout.LayoutParams controlsParams=new FrameLayout.LayoutParams(-1,-2,Gravity.BOTTOM);
        controlsParams.bottomMargin=74;
        root.addView(controls,controlsParams);

        TextView footer = new TextView(this);
        footer.setText("468-point ARCore face mesh  ·  Front camera\nPreview only — AR effects are not yet included in exported recordings");
        footer.setTextColor(0xffd8e8f4); footer.setTextSize(11);
        footer.setPadding(18,12,18,14); footer.setBackgroundColor(0xAA08121E);
        root.addView(footer,new FrameLayout.LayoutParams(-1,-2,Gravity.BOTTOM));
        setContentView(root);
    }

    @Override protected void onResume() {
        super.onResume();
        if (checkSelfPermission(Manifest.permission.CAMERA) != PackageManager.PERMISSION_GRANTED) {
            requestPermissions(new String[]{Manifest.permission.CAMERA}, CAMERA_PERMISSION_REQUEST);
            return;
        }
        resumeArSession();
    }

    private void resumeArSession() {
        try {
            ArCoreApk.InstallStatus status = ArCoreApk.getInstance().requestInstall(this, !installRequested);
            if (status == ArCoreApk.InstallStatus.INSTALL_REQUESTED) { installRequested = true; return; }
            if (session == null) {
                session = new Session(this, EnumSet.of(Session.Feature.FRONT_CAMERA));
                // Explicitly select a front-facing ARCore camera configuration.
                CameraConfigFilter filter = new CameraConfigFilter(session)
                    .setFacingDirection(CameraConfig.FacingDirection.FRONT);
                List<CameraConfig> configs = session.getSupportedCameraConfigs(filter);
                if (configs == null || configs.isEmpty()) {
                    session.close(); session=null;
                    showError("ARCore did not expose a supported front camera configuration.");
                    return;
                }
                session.setCameraConfig(configs.get(0));
                Config config = new Config(session);
                config.setAugmentedFaceMode(Config.AugmentedFaceMode.MESH3D);
                config.setFocusMode(Config.FocusMode.AUTO);
                config.setUpdateMode(Config.UpdateMode.LATEST_CAMERA_IMAGE);
                session.configure(config);
                renderer.setSession(session);
            }
            session.resume();
            surfaceView.onResume();
        } catch (UnavailableDeviceNotCompatibleException e) {
            showError("This device is not certified for ARCore.");
        } catch (UnavailableSdkTooOldException e) {
            showError("TalkReel AR must be updated to use this ARCore feature.");
        } catch (UnavailableApkTooOldException e) {
            showError("Update Google Play Services for AR, then reopen AR Studio.");
        } catch (UnavailableArcoreNotInstalledException e) {
            showError("Google Play Services for AR is required for AR Studio.");
        } catch (UnavailableUserDeclinedInstallationException e) {
            showError("ARCore installation was declined. AR Studio cannot start.");
        } catch (CameraNotAvailableException e) {
            showError("Camera is busy. Close other camera apps and retry.");
        } catch (Exception e) {
            showError("ARCore could not start: " + e.getMessage());
        }
    }

    private void showError(String message) {
        if(session!=null) { try { session.pause(); } catch(Exception ignored) {} try { session.close(); } catch(Exception ignored) {} session=null; }
        Toast.makeText(this, message, Toast.LENGTH_LONG).show();
        TextView error = new TextView(this);
        error.setText(message + "\n\nClose this screen and retry after resolving the issue.");
        error.setTextColor(0xffffffff); error.setTextSize(16); error.setGravity(Gravity.CENTER);
        error.setPadding(28,28,28,28); setContentView(error);
    }

    @Override public void onRequestPermissionsResult(int requestCode, String[] permissions, int[] results) {
        super.onRequestPermissionsResult(requestCode, permissions, results);
        if (requestCode == CAMERA_PERMISSION_REQUEST && results.length > 0 && results[0] == PackageManager.PERMISSION_GRANTED) {
            resumeArSession();
        } else if (requestCode == CAMERA_PERMISSION_REQUEST) {
            showError("Camera permission is required for AR face tracking.");
        }
    }

    @Override protected void onPause() {
        if(surfaceView!=null) surfaceView.onPause();
        if(session!=null) session.pause();
        super.onPause();
    }

    @Override protected void onDestroy() {
        if (session != null) { session.close(); session = null; }
        super.onDestroy();
    }
}