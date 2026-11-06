package com.talkreel.ar;

import android.Manifest;
import android.app.Activity;
import android.content.pm.PackageManager;
import android.opengl.GLSurfaceView;
import android.os.Bundle;
import android.view.Gravity;
import android.view.View;
import android.widget.FrameLayout;
import android.widget.TextView;
import android.widget.Toast;
import com.google.ar.core.ArCoreApk;
import com.google.ar.core.Config;
import com.google.ar.core.Session;
import com.google.ar.core.exceptions.CameraNotAvailableException;
import com.google.ar.core.exceptions.UnavailableApkTooOldException;
import com.google.ar.core.exceptions.UnavailableArcoreNotInstalledException;
import com.google.ar.core.exceptions.UnavailableDeviceNotCompatibleException;
import com.google.ar.core.exceptions.UnavailableSdkTooOldException;
import com.google.ar.core.exceptions.UnavailableUserDeclinedInstallationException;
import java.util.EnumSet;

/** Dedicated ARCore Augmented Faces live-preview studio. */
public final class ArCoreFaceActivity extends Activity {
    private static final int CAMERA_PERMISSION_REQUEST = 41;
    private GLSurfaceView surfaceView;
    private ArCoreFaceRenderer renderer;
    private Session session;
    private boolean installRequested;

    @Override protected void onCreate(Bundle state) {
        super.onCreate(state);
        getWindow().getDecorView().setSystemUiVisibility(View.SYSTEM_UI_FLAG_FULLSCREEN | View.SYSTEM_UI_FLAG_LAYOUT_STABLE);
        FrameLayout root = new FrameLayout(this);
        surfaceView = new GLSurfaceView(this);
        surfaceView.setEGLContextClientVersion(2);
        renderer = new ArCoreFaceRenderer();
        surfaceView.setRenderer(renderer);
        surfaceView.setRenderMode(GLSurfaceView.RENDERMODE_CONTINUOUSLY);
        root.addView(surfaceView, new FrameLayout.LayoutParams(-1, -1));
        TextView header = new TextView(this);
        header.setText("TALKREEL  /  AR STUDIO\nARCore face tracking · Live preview");
        header.setTextColor(0xffffffff); header.setTextSize(14); header.setPadding(20,18,20,18);
        header.setBackgroundColor(0x9908121e);
        root.addView(header, new FrameLayout.LayoutParams(-1,-2,Gravity.TOP));
        TextView footer = new TextView(this);
        footer.setText("468-point 3D face mesh · Front camera\nLive preview only — AR video export is not enabled yet");
        footer.setTextColor(0xffd8e8f4); footer.setTextSize(12); footer.setPadding(20,14,20,18);
        footer.setBackgroundColor(0x9908121e);
        root.addView(footer, new FrameLayout.LayoutParams(-1,-2,Gravity.BOTTOM));
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
                Config config = new Config(session);
                config.setAugmentedFaceMode(Config.AugmentedFaceMode.MESH3D);
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
        super.onPause();
        if (session != null) { surfaceView.onPause(); session.pause(); }
    }

    @Override protected void onDestroy() {
        if (session != null) { session.close(); session = null; }
        super.onDestroy();
    }
}