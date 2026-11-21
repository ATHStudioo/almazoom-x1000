package app.almazium.samsunglens;

import android.app.Activity;
import android.app.Application;
import android.content.pm.PackageInfo;
import android.graphics.Color;
import android.graphics.Rect;
import android.graphics.drawable.GradientDrawable;
import android.location.Location;
import android.net.Uri;
import android.os.Build;
import android.os.Handler;
import android.os.Looper;
import android.os.SystemClock;
import android.util.Range;
import android.view.Gravity;
import android.view.SurfaceControl;
import android.view.SurfaceView;
import android.view.View;
import android.view.ViewGroup;
import android.widget.FrameLayout;
import android.widget.LinearLayout;
import android.widget.SeekBar;
import android.widget.TextView;

import java.util.Locale;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

import de.robv.android.xposed.IXposedHookLoadPackage;
import de.robv.android.xposed.XC_MethodHook;
import de.robv.android.xposed.XposedBridge;
import de.robv.android.xposed.callbacks.XC_LoadPackage;

import static de.robv.android.xposed.XposedHelpers.callMethod;
import static de.robv.android.xposed.XposedHelpers.findAndHookConstructor;
import static de.robv.android.xposed.XposedHelpers.findAndHookMethod;
import static de.robv.android.xposed.XposedHelpers.findClass;
import static de.robv.android.xposed.XposedHelpers.getBooleanField;
import static de.robv.android.xposed.XposedHelpers.getLongField;
import static de.robv.android.xposed.XposedHelpers.getObjectField;
import static de.robv.android.xposed.XposedHelpers.setObjectField;

public final class XiaomiCameraModule implements IXposedHookLoadPackage {
    static final String PACKAGE = "com.android.camera";
    private static final String CAMERA_VERSION = "6.3.007270.1";
    private static final long CAMERA_VERSION_CODE = 630072701;
    private static final int PHOTO = 0xa3;
    private static final int STEPS = 1000;
    private static final long SHOT_TIMEOUT = 120_000;
    private final Map<Long, Shot> pendingShots = new ConcurrentHashMap<>();
    private final ThreadLocal<SaveJob> activeSave = new ThreadLocal<>();
    private Panel panel;
    private volatile PreviewSelection selection;
    private boolean ready;

    private record PreviewSelection(Object cameraModule, int nativeZoom, float factor) {}
    private record Shot(float factor, long expires) {}
    private record SaveJob(Object worker, long dateTaken, long key, Shot shot) {}

    @Override public void handleLoadPackage(XC_LoadPackage.LoadPackageParam pkg) {
        if (!PACKAGE.equals(pkg.packageName) || !PACKAGE.equals(pkg.processName)
                || Build.VERSION.SDK_INT < 33) return;
        try {
            ClassLoader loader = pkg.classLoader;
            Class<?> activity = findClass(PACKAGE + ".ActivityBase", loader);
            Class<?> photo = findClass(PACKAGE + ".module.Camera2Module", loader);
            Class<?> task = findClass("Qd.p", loader);
            Class<?> captureSettings = findClass("f6.N", loader);
            Class<?> storage = findClass("p4.C", loader);
            Class<?> update = findClass("p4.B", loader);
            Class<?> parallelSave = findClass("p4.o", loader);
            Class<?> simpleSave = findClass("p4.h", loader);
            activity.getDeclaredField("i0");
            activity.getDeclaredField("j0");
            activity.getDeclaredField("k0");
            activity.getDeclaredMethod("z0");
            task.getDeclaredField("f");

            installStorageHooks(storage, update);
            hookSaveWorker(parallelSave);
            hookSaveWorker(simpleSave);
            findAndHookMethod(photo, "updateParallelTaskData", task, captureSettings, new XC_MethodHook() {
                @Override protected void afterHookedMethod(MethodHookParam p) {
                    if (!p.hasThrowable()) bindShot(p.thisObject, p.args[0]);
                }
            });
            findAndHookMethod(activity, "onResume", new XC_MethodHook() {
                @Override protected void afterHookedMethod(MethodHookParam p) {
                    if (!ready || p.hasThrowable()) return;
                    Activity camera = (Activity) p.thisObject;
                    try {
                        PackageInfo installed = camera.getPackageManager().getPackageInfo(PACKAGE, 0);
                        if (!CAMERA_VERSION.equals(installed.versionName)
                                || installed.getLongVersionCode() != CAMERA_VERSION_CODE) {
                            log("UNSUPPORTED_CAMERA_VERSION " + installed.versionName);
                            return;
                        }
                        stopPanel();
                        panel = new Panel(camera, photo);
                        panel.run();
                    } catch (Exception error) {
                        log("CAMERA_START_FAILED " + error);
                    }
                }
            });
            findAndHookMethod(activity, "onPause", new XC_MethodHook() {
                @Override protected void beforeHookedMethod(MethodHookParam p) {
                    if (panel != null && panel.camera == p.thisObject) stopPanel();
                }
            });
            ready = true;
            log("PROFILE_READY");
        } catch (Throwable error) {
            log("PROFILE_UNAVAILABLE " + error);
        }
    }

    private void bindShot(Object module, Object task) {
        PreviewSelection current = selection;
        if (current == null || current.cameraModule() != module || !ready || task == null) return;
        try {
            if (!eligible(module) || !atNativeLimit(module, current.nativeZoom())) return;
            if (getBooleanField(getObjectField(module, "mMultiCap"), "d")) return;
            long key = getLongField(task, "f");
            if (key <= 0) return;
            long now = SystemClock.elapsedRealtime();
            pendingShots.entrySet().removeIf(entry -> entry.getValue().expires() < now);
            if (pendingShots.putIfAbsent(key, new Shot(current.factor(), now + SHOT_TIMEOUT)) == null) {
                log("SHOT_QUEUED factor=" + current.factor());
            }
        } catch (LinkageError | RuntimeException error) {
            log("SHOT_BIND_FAILED " + error);
        }
    }

    private void hookSaveWorker(Class<?> worker) {
        findAndHookMethod(worker, "run", new XC_MethodHook() {
            @Override protected void beforeHookedMethod(MethodHookParam p) {
                p.setObjectExtra("previousXiaomiSave", activeSave.get());
                activeSave.remove();
                if (pendingShots.isEmpty()) return;
                Object task = getObjectField(p.thisObject, "d");
                if (task == null) return;
                long key = getLongField(task, "f");
                Shot shot = pendingShots.get(key);
                if (shot != null && shot.expires() >= SystemClock.elapsedRealtime()) {
                    activeSave.set(new SaveJob(p.thisObject, getLongField(p.thisObject, "p"), key, shot));
                }
            }

            @Override protected void afterHookedMethod(MethodHookParam p) {
                SaveJob previous = (SaveJob) p.getObjectExtra("previousXiaomiSave");
                if (previous == null) activeSave.remove();
                else activeSave.set(previous);
            }
        });
    }

    private void installStorageHooks(Class<?> storage, Class<?> update) {
        findAndHookMethod(storage, "a", Application.class, String.class, long.class, Location.class,
                int.class, byte[].class, boolean.class, int.class, int.class, boolean.class,
                boolean.class, long.class, int.class, boolean.class, int.class, new XC_MethodHook() {
            @Override protected void beforeHookedMethod(MethodHookParam p) {
                crop(p.args, 0, 2, 4, 5, 7, 8);
            }
        });
        findAndHookConstructor(update, Application.class, byte[].class, boolean.class, Uri.class,
                String.class, Location.class, int.class, int.class, int.class, long.class,
                int.class, boolean.class, new XC_MethodHook() {
            @Override protected void beforeHookedMethod(MethodHookParam p) {
                crop(p.args, 0, 9, 6, 1, 7, 8);
            }
        });
    }

    private void crop(Object[] args, int appIndex, int dateIndex, int orientationIndex,
                      int jpegIndex, int widthIndex, int heightIndex) {
        SaveJob save = activeSave.get();
        if (save == null || save.dateTaken() != (Long) args[dateIndex]
                || !(args[jpegIndex] instanceof byte[] jpeg)
                || !pendingShots.remove(save.key(), save.shot())) return;
        try {
            Application app = (Application) args[appIndex];
            JpegCrop.Encoded encoded = JpegCrop.cropBytes(jpeg, save.shot().factor(), app.getCacheDir());
            if (encoded == null) {
                log("JPEG_CROP_SKIPPED");
                return;
            }
            int orientation = (Integer) args[orientationIndex];
            JpegCrop.Result dims = encoded.dimensions();
            boolean rotated = Math.floorMod(orientation, 180) == 90;
            if ((Integer) args[widthIndex] == dims.inputWidth()
                    && (Integer) args[heightIndex] == dims.inputHeight()) rotated = false;
            setObjectField(save.worker(), "e", encoded.jpeg());
            args[jpegIndex] = encoded.jpeg();
            args[widthIndex] = rotated ? dims.height() : dims.width();
            args[heightIndex] = rotated ? dims.width() : dims.height();
            log("JPEG_CROPPED " + dims.width() + "x" + dims.height() + " factor=" + save.shot().factor());
        } catch (Exception | LinkageError error) {
            log("JPEG_CROP_FAILED " + error);
        }
    }

    private static boolean eligible(Object module) {
        return module != null && (Integer) callMethod(module, "getModuleIndex") == PHOTO
                && !Boolean.TRUE.equals(callMethod(module, "isCaptureIntent"))
                && !Boolean.TRUE.equals(callMethod(module, "isHeicPreferred"))
                && !Boolean.TRUE.equals(callMethod(callMethod(module, "getCameraManager"), "C0"));
    }

    private static int nativeLimit(Object module) {
        Object range = callMethod(callMethod(module, "getZoomManager"), "I");
        if (!(range instanceof Range<?> limits) || !(limits.getUpper() instanceof Float maximum)
                || !Float.isFinite(maximum) || maximum <= 1f || maximum >= 1000f) return 0;
        return Math.round(maximum * 1000f);
    }

    private static boolean atNativeLimit(Object module, int limit) {
        float ratio = (Float) callMethod(callMethod(module, "getZoomManager"), "n0");
        return Float.isFinite(ratio) && Math.abs(ratio * 1000f - limit) <= 50f;
    }

    private void stopPanel() {
        if (panel == null) return;
        panel.stop();
        panel = null;
    }

    private static void log(String event) {
        XposedBridge.log("AlmaZoom Xiaomi " + event);
    }

    private final class Panel implements Runnable {
        final Activity camera;
        final Class<?> photo;
        final Handler timer = new Handler(Looper.getMainLooper());
        final LinearLayout controls;
        final TextView value;
        final SeekBar slider;
        SurfaceControl buffer;
        int base;
        int bufferWidth;
        int bufferHeight;
        float appliedFactor = 1f;
        boolean reportedError;

        Panel(Activity camera, Class<?> photo) {
            this.camera = camera;
            this.photo = photo;
            controls = new LinearLayout(camera);
            controls.setOrientation(LinearLayout.VERTICAL);
            controls.setGravity(Gravity.CENTER_HORIZONTAL);
            controls.setPadding(dp(10), 0, dp(10), 0);
            GradientDrawable background = new GradientDrawable();
            background.setCornerRadius(dp(20));
            background.setColor(0xdc16181b);
            controls.setBackground(background);
            value = new TextView(camera);
            value.setTextColor(Color.WHITE);
            value.setGravity(Gravity.CENTER);
            value.setTextSize(14);
            controls.addView(value, new LinearLayout.LayoutParams(-1, dp(36)));
            slider = new SeekBar(camera);
            slider.setMax(STEPS);
            slider.setContentDescription("Digital zoom");
            slider.setVisibility(View.GONE);
            controls.addView(slider, new LinearLayout.LayoutParams(-1, dp(42)));
            value.setOnClickListener(view -> slider.setVisibility(slider.isShown() ? View.GONE : View.VISIBLE));
            slider.setOnSeekBarChangeListener(new SeekBar.OnSeekBarChangeListener() {
                @Override public void onProgressChanged(SeekBar bar, int progress, boolean fromUser) {
                    if (fromUser) refresh();
                }
                @Override public void onStartTrackingTouch(SeekBar bar) {}
                @Override public void onStopTrackingTouch(SeekBar bar) {}
            });
            FrameLayout root = camera.findViewById(android.R.id.content);
            FrameLayout.LayoutParams layout = new FrameLayout.LayoutParams(dp(180), -2,
                    Gravity.TOP | Gravity.CENTER_HORIZONTAL);
            layout.topMargin = dp(80);
            controls.setVisibility(View.GONE);
            root.addView(controls, layout);
        }

        @Override public void run() {
            refresh();
            timer.postDelayed(this, 135);
        }

        private void refresh() {
            try {
                if (!applySelection()) hide();
            } catch (LinkageError | RuntimeException error) {
                hide();
                if (!reportedError) log("PREVIEW_FAILED " + error);
                reportedError = true;
            }
        }

        private boolean applySelection() {
            Object module = callMethod(camera, "z0");
            if (!photo.isInstance(module) || !camera.hasWindowFocus() || !eligible(module)) return false;
            int maximum = nativeLimit(module);
            if (maximum == 0 || !atNativeLimit(module, maximum)) return false;
            View previewContainer = (View) getObjectField(camera, "i0");
            if (previewContainer == null || !(previewContainer.getTag() instanceof Boolean directPreview)) return false;
            // Xiaomi marks the active direct or GPU preview on the container.
            Object view = getObjectField(camera, directPreview ? "k0" : "j0");
            if (!(view instanceof SurfaceView surface) || !surface.isShown()) return false;
            Object layer = getObjectField(surface, "mBlastSurfaceControl");
            if (!(layer instanceof SurfaceControl child) || !child.isValid()) return false;
            Rect bounds = surface.getHolder().getSurfaceFrame();
            int width = bounds.width();
            int height = bounds.height();
            if (width <= 0 || height <= 0) return false;

            if (maximum != base || child != buffer) {
                resetBuffer();
                slider.setProgress(0);
                base = maximum;
                buffer = child;
            }
            int zoom = ZoomMath.digitalRatio(slider.getProgress(), 0, STEPS, maximum);
            float factor = ZoomMath.cropFactor(zoom, maximum);
            if (factor != appliedFactor || (factor > 1f && (width != bufferWidth || height != bufferHeight))) {
                transform(child, width, height, factor);
                if (factor > 1f && appliedFactor == 1f) {
                    log("PREVIEW_ACTIVE " + (directPreview ? "direct" : "gpu") + " max=" + maximum / 1000f);
                }
                appliedFactor = factor;
            }
            bufferWidth = width;
            bufferHeight = height;
            selection = factor > 1f ? new PreviewSelection(module, maximum, factor) : null;
            String text = "AlmaZoom  " + String.format(Locale.getDefault(), "%.1f×", zoom / 1000f);
            if (!text.contentEquals(value.getText())) value.setText(text);
            controls.setVisibility(View.VISIBLE);
            reportedError = false;
            return true;
        }

        private void resetBuffer() {
            selection = null;
            try {
                if (appliedFactor > 1f && buffer != null && buffer.isValid()) {
                    transform(buffer, bufferWidth, bufferHeight, 1f);
                }
            } catch (LinkageError | RuntimeException error) {
                log("PREVIEW_RESET_FAILED " + error);
            } finally {
                buffer = null;
                appliedFactor = 1f;
            }
        }

        private void hide() {
            resetBuffer();
            controls.setVisibility(View.GONE);
            slider.setVisibility(View.GONE);
        }

        void stop() {
            timer.removeCallbacks(this);
            resetBuffer();
            if (controls.getParent() instanceof ViewGroup parent) parent.removeView(controls);
        }

        private int dp(int value) {
            return Math.round(value * camera.getResources().getDisplayMetrics().density);
        }
    }

    private static void transform(SurfaceControl layer, int width, int height, float factor) {
        try (SurfaceControl.Transaction transaction = new SurfaceControl.Transaction()) {
            transaction.setScale(layer, factor, factor);
            transaction.setPosition(layer, ZoomMath.centeredOffset(width, factor), ZoomMath.centeredOffset(height, factor));
            transaction.apply();
        }
    }
}
