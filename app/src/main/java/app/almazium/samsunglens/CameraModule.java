package app.almazium.samsunglens;

import android.app.Activity;
import android.content.ContentValues;
import android.content.SharedPreferences;
import android.graphics.Matrix;
import android.graphics.Rect;
import android.os.Handler;
import android.net.Uri;
import android.os.Looper;
import android.os.SystemClock;
import android.util.Size;
import android.util.SparseIntArray;
import android.view.HapticFeedbackConstants;
import android.view.SurfaceView;
import android.view.TextureView;
import android.view.View;
import android.widget.HorizontalScrollView;

import java.io.File;
import java.lang.reflect.Method;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;

import de.robv.android.xposed.IXposedHookLoadPackage;
import de.robv.android.xposed.XC_MethodHook;
import de.robv.android.xposed.XposedBridge;
import de.robv.android.xposed.callbacks.XC_LoadPackage;

import static de.robv.android.xposed.XposedHelpers.callMethod;
import static de.robv.android.xposed.XposedHelpers.callStaticMethod;
import static de.robv.android.xposed.XposedHelpers.findAndHookMethod;
import static de.robv.android.xposed.XposedHelpers.findClass;
import static de.robv.android.xposed.XposedHelpers.findMethodBestMatch;
import static de.robv.android.xposed.XposedHelpers.getBooleanField;
import static de.robv.android.xposed.XposedHelpers.getIntField;
import static de.robv.android.xposed.XposedHelpers.getLongField;
import static de.robv.android.xposed.XposedHelpers.getObjectField;
import static de.robv.android.xposed.XposedHelpers.getStaticObjectField;
import static de.robv.android.xposed.XposedHelpers.removeAdditionalInstanceField;
import static de.robv.android.xposed.XposedHelpers.setAdditionalInstanceField;
import static de.robv.android.xposed.XposedHelpers.setObjectField;

public final class CameraModule implements IXposedHookLoadPackage {
    private static final String PACKAGE = "com.sec.android.app.camera";
    private static final String ZOOM_VIEW = PACKAGE + ".layer.keyscreen.zoom.ZoomView";
    private static final int UNIT = 1000;
    private static final int LIMIT = ZoomMath.LIMIT;
    private static final String OPTIONS = "almalens_zoom1000";
    private static final String EXTEND_ZOOM = "extend_zoom";


    private final Map<Long, CropShot> shots = new ConcurrentHashMap<>();
    private final Map<File, Long> postOutputs = new ConcurrentHashMap<>();
    private final ScheduledExecutorService galleryWorker = Executors.newSingleThreadScheduledExecutor(task -> {
        Thread thread = new Thread(task, "AlmaZoom gallery");
        thread.setDaemon(true);
        return thread;
    });
    private final ThreadLocal<Float> shutterCrop = new ThreadLocal<>();
    private Class<?> setting;
    private Class<?> modeMapping;
    private Class<?> resolutionMapping;
    private Object sliderTick, majorTick;
    private boolean photoHooksAttempted;
    private boolean photoHookReady;
    private boolean wheelHookReady;
    private volatile Panel current;


    private static final class CropShot {
        final float factor;
        final long expires;

        CropShot(float factor, long expires) {
            this.factor = factor;
            this.expires = expires;
        }
    }
    private record DigitalZoom(int ratio, int base) {
        float factor() { return ZoomMath.cropFactor(ratio, base); }
    }

    private static void verifyProfile(ClassLoader loader, Class<?> camera) throws ReflectiveOperationException {
        Class<?> zoom = findClass(ZOOM_VIEW, loader);
        Class<?> scroll = findClass("y9.g", loader);
        Class<?> capture = findClass("q8.l", loader);
        camera.getDeclaredField("j");
        camera.getDeclaredField("f");
        camera.getDeclaredField("q");
        camera.getDeclaredMethod("F");
        zoom.getDeclaredField("a");
        zoom.getDeclaredField("b");
        zoom.getDeclaredField("e");
        scroll.getDeclaredMethod("c", int.class);
        scroll.getDeclaredMethod("onScrollChange", View.class, int.class, int.class, int.class, int.class);
        scroll.getDeclaredMethod("getSliderLayoutVisibility");
        capture.getDeclaredMethod("m");
        findClass("q8.m0", loader).getDeclaredMethod("i0", capture);
        Class<?> saveTask = findClass("q8.v", loader);
        saveTask.getDeclaredMethod("g", ContentValues.class);
        findClass("q8.x", loader).getDeclaredField("g");
    }

    @Override public void handleLoadPackage(XC_LoadPackage.LoadPackageParam pkg) {
        if (!PACKAGE.equals(pkg.packageName) || !PACKAGE.equals(pkg.processName)) return;
        ClassLoader loader = pkg.classLoader;
        try {
            setting = findClass("l9.f0", loader);
            modeMapping = findClass("te.l0", loader);
            resolutionMapping = findClass("te.c0", loader);
            Class<?> camera = findClass(PACKAGE + ".Camera", loader);
            verifyProfile(loader, camera);
            try {
                Class<?> haptic = findClass("l9.o", loader);
                sliderTick = getStaticObjectField(haptic, "SLIDER");
                majorTick = getStaticObjectField(haptic, "ZOOM_SLIDER_LARGE_TICK");
                findMethodBestMatch(camera, "o0", haptic);
            } catch (LinkageError | RuntimeException unavailable) {
                sliderTick = null;
                majorTick = null;
            }
            findAndHookMethod(camera, "onResume", new XC_MethodHook() {
                @Override protected void beforeHookedMethod(MethodHookParam p) throws Throwable {
                    Activity activity = (Activity) p.thisObject;
                    if (!photoHooksAttempted) {
                        photoHooksAttempted = true;
                        installPhotoHooks(loader);
                        installWheelHooks(loader);
                    }
                    if (!photoHookReady || !wheelHookReady) return;
                    if (current != null) current.stop();
                    Panel panel = new Panel(activity);
                    current = panel;
                    panel.handler.post(panel);
                }
            });
            findAndHookMethod(camera, "onPause", new XC_MethodHook() {
                @Override protected void beforeHookedMethod(MethodHookParam p) {
                    Panel panel = current;
                    if (panel == null || panel.activity != p.thisObject) return;
                    panel.stop();
                    current = null;
                }
            });
        } catch (Throwable error) {
            XposedBridge.log("AlmaZoom " + BuildConfig.VERSION_NAME + " CAMERA_PROFILE_UNAVAILABLE " + error);
        }
    }

    private void installWheelHooks(ClassLoader loader) {
        try {
            Class<?> scroller = findClass("y9.g", loader);
            findAndHookMethod(scroller, "onScrollChange", View.class, int.class, int.class,
                    int.class, int.class, new XC_MethodHook() {
                @Override protected void beforeHookedMethod(MethodHookParam p) {
                    Panel panel = current;
                    if (panel == null) return;
                    try {
                        int x = (Integer) p.args[1];
                        int previous = (Integer) p.args[3];
                        if (panel.scrollZoom(p.thisObject, x, previous)) {
                            p.setResult(null);
                        }
                    } catch (Throwable error) {
                        ZoomWheel wheel = panel.wheel;
                        if (wheel != null && p.thisObject == wheel.scroll && wheel.ready()
                                && (Integer) p.args[1] > wheel.nativeEnd()) {
                            p.args[1] = wheel.nativeEnd();
                        }
                        panel.resetCrop();
                        log(error);
                    }
                }
            });
            findAndHookMethod(scroller, "f", new XC_MethodHook() {
                @Override protected void afterHookedMethod(MethodHookParam p) {
                    Panel panel = current;
                    if (panel == null || p.hasThrowable() || panel.zoomView == null) return;
                    try {
                        if (getObjectField(panel.zoomView, "e") == p.thisObject) panel.updateWheel();
                    } catch (Throwable error) {
                        log(error);
                    }
                }
            });
            findAndHookMethod(scroller, "c", int.class, new XC_MethodHook() {
                @Override protected void afterHookedMethod(MethodHookParam p) {
                    Panel panel = current;
                    if (panel == null || p.hasThrowable()) return;
                    DigitalZoom selected = panel.digitalZoom;
                    ZoomWheel wheel = panel.wheel;
                    if (selected == null || wheel == null || wheel.scroll != p.thisObject
                            || Math.abs((Integer) p.args[0] - selected.base()) > 250) return;
                    if (wheel.ready()) p.setResult(wheel.positionAt(selected.ratio()));
                    else panel.alignWheel = true;
                }
            });
            findAndHookMethod(findClass(ZOOM_VIEW, loader), "F2", int.class, new XC_MethodHook() {
                @Override protected void beforeHookedMethod(MethodHookParam p) {
                    Panel panel = current;
                    if (panel == null || panel.zoomView != p.thisObject || panel.wheel == null
                            || panel.digitalZoom == null) return;
                    ZoomWheel wheel = panel.wheel;
                    p.setObjectExtra("almaOpeningWheel", wheel);
                    p.setObjectExtra("almaPreviousPositioning", wheel.positioning);
                    wheel.positioning = true;
                }

                @Override protected void afterHookedMethod(MethodHookParam p) {
                    if (p.getObjectExtra("almaOpeningWheel") instanceof ZoomWheel wheel) {
                        wheel.positioning = Boolean.TRUE.equals(p.getObjectExtra("almaPreviousPositioning"));
                    }
                }
            });
            wheelHookReady = true;
        } catch (Throwable error) {
            log(error);
        }
    }

    private void installPhotoHooks(ClassLoader loader) {
        try {
            Class<?> captureInfo = findClass("q8.l", loader);
            Class<?> controller = findClass("q8.m0", loader);
            Class<?> saveTask = findClass("q8.v", loader);
            Class<?> captureManager = findClass("q8.o", loader);
            Class<?> input = findClass("l9.p", loader);
            Class<?> captureType = findClass("z8.g0", loader);
            findAndHookMethod(captureManager, "x", input, captureType, boolean.class, new XC_MethodHook() {
                @Override protected void beforeHookedMethod(MethodHookParam p) {
                    p.setObjectExtra("almaPreviousShutterCrop", shutterCrop.get());
                    shutterCrop.set("SINGLE_CAPTURE".equals(name(p.args[1])) ? currentCaptureFactor() : 1f);
                }

                @Override protected void afterHookedMethod(MethodHookParam p) {
                    if (p.getObjectExtra("almaPreviousShutterCrop") instanceof Float old) {
                        shutterCrop.set(old);
                    } else {
                        shutterCrop.remove();
                    }
                }
            });
            findAndHookMethod(captureManager, "e", input, captureType, boolean.class, new XC_MethodHook() {
                @Override protected void beforeHookedMethod(MethodHookParam p) {
                    if (!"SINGLE_CAPTURE".equals(name(p.args[1]))) return;
                    Float locked = shutterCrop.get();
                    float factor = locked != null ? locked : currentCaptureFactor();
                    if (factor > 1f) p.setObjectExtra("almaZoomFactor", factor);
                }

                @Override protected void afterHookedMethod(MethodHookParam p) {
                    if (p.hasThrowable()) return;
                    if (p.getObjectExtra("almaZoomFactor") instanceof Float factor) {
                        rememberShot(p.getResult(), factor);
                    }
                }
            });
            findAndHookMethod(controller, "i0", captureInfo, new XC_MethodHook() {
                @Override protected void beforeHookedMethod(MethodHookParam p) {
                    rememberShot(p.args[0], currentCaptureFactor());
                }
            });
            installPostProcessingHooks(loader, captureManager, captureType);
            findAndHookMethod(saveTask, "g", ContentValues.class, new XC_MethodHook() {
                @Override protected void beforeHookedMethod(MethodHookParam p) {
                    if (shots.isEmpty()) return;
                    try {
                        Object image = jpegSaveImage(p.thisObject);
                        if (image == null) return;
                        long stamp = getLongField(image, "g");
                        File file = new File((String) callMethod(p.thisObject, "d"));
                        JpegCrop.Result crop = cropForPublication(stamp, file, "SAVE_TASK");
                        if (crop == null) return;
                        ContentValues values = (ContentValues) p.args[0];
                        values.put("width", crop.width());
                        values.put("height", crop.height());
                        p.setObjectExtra("almaPublishedStamp", stamp);
                    } catch (Throwable error) {
                        log(error);
                    }
                }

                @Override protected void afterHookedMethod(MethodHookParam p) {
                    if (p.getObjectExtra("almaPublishedStamp") instanceof Long stamp) {
                        trace(stamp, "SAVE_TASK_RETURNED", "ok=" + !p.hasThrowable());
                    }
                }
            });
            photoHookReady = true;
        } catch (Throwable error) {
            log(error);
        }
    }

    private void installPostProcessingHooks(ClassLoader loader, Class<?> manager, Class<?> captureType) {
        Class<?> scanner = findClass("com.samsung.android.camera.core2.processor.postSaving.module."
                + "PostSavingModuleScanFile", loader);
        Class<?> validation = findClass("com.samsung.android.camera.core2.processor.postSaving.module."
                + "PostSavingModuleValidationCheck", loader);
        Class<?> savingInfo = findClass("com.samsung.android.camera.core2.container.SavingInfoContainer$SavingInfo", loader);
        findAndHookMethod(manager, "f", captureType, long.class, new XC_MethodHook() {
            @Override protected void afterHookedMethod(MethodHookParam p) {
                long stamp = (Long) p.args[1];
                if (pendingShot(stamp) == null || p.hasThrowable() || !(p.getResult() instanceof List<?> outputs)
                        || outputs.isEmpty() || !(outputs.get(0) instanceof String path)) return;
                File output = new File(path);
                if (output.isAbsolute()) {
                    postOutputs.put(output, stamp);
                    trace(stamp, "FINAL_PATH_REGISTERED", "");
                }
            }
        });
        findAndHookMethod(validation, "runModule", savingInfo, new XC_MethodHook() {
            @Override protected void beforeHookedMethod(MethodHookParam p) {
                if (postOutputs.isEmpty()) return;
                Object info = p.args[0];
                if (!"MAIN".equals(name(getObjectField(info, "a")))) return;
                File file = (File) getObjectField(info, "c");
                Long stamp = file == null ? null : postOutputs.get(file);
                if (stamp != null) setAdditionalInstanceField(info, "almaFinalStamp", stamp);
            }
        });
        // This scanner runs after the final ImageWrite and before Galaxy Gallery is told to swap out the draft.
        findAndHookMethod(scanner, "runModule", savingInfo, new XC_MethodHook() {
            @Override protected void beforeHookedMethod(MethodHookParam p) {
                if (shots.isEmpty()) return;
                Object info = p.args[0];
                if (!"MAIN".equals(name(getObjectField(info, "a")))) return;
                File file = (File) getObjectField(info, "c");
                if (file == null) return;
                Long stamp = postOutputs.remove(file);
                Object validatedStamp = removeAdditionalInstanceField(info, "almaFinalStamp");
                if (stamp == null && validatedStamp instanceof Long original) stamp = original;
                if (stamp == null) return;
                JpegCrop.Result crop = cropForPublication(stamp, file, "SAMSUNG_FINAL_SCAN");
                if (crop != null) p.setObjectExtra("almaPublishedStamp", stamp);
            }

            @Override protected void afterHookedMethod(MethodHookParam p) {
                if (p.getObjectExtra("almaPublishedStamp") instanceof Long stamp) {
                    Object scanned = getObjectField(p.args[0], "k");
                    trace(stamp, "SAMSUNG_SCAN_RETURNED", "uriPresent=" + (scanned instanceof Uri)
                            + " ok=" + !p.hasThrowable());
                }
            }
        });
    }

    private JpegCrop.Result cropForPublication(long stamp, File file, String route) {
        CropShot shot = pendingShot(stamp);
        if (shot == null || !shots.remove(stamp, shot)) return null;
        long started = SystemClock.elapsedRealtime();
        try {
            JpegCrop.Result crop = JpegCrop.replaceProcessed(file, shot.factor);
            if (crop == null) {
                trace(stamp, "CROP_SKIPPED", "route=" + route + " bytes=" + file.length());
                return null;
            }
            trace(stamp, "CROPPED_BEFORE_PUBLICATION", "route=" + route + " factor=" + shot.factor
                    + " input=" + crop.inputWidth() + "x" + crop.inputHeight()
                    + " output=" + crop.width() + "x" + crop.height()
                    + " beforeBytes=" + crop.inputBytes() + " finalBytes=" + file.length()
                    + " ms=" + (SystemClock.elapsedRealtime() - started));
            return crop;
        } catch (Exception error) {
            trace(stamp, "CROP_FAILED", "route=" + route + " error=" + error.getClass().getSimpleName()
                    + " detail=" + error.getMessage());
            return null;
        }
    }

    private float currentCaptureFactor() {
        Panel panel = current;
        if (panel == null) return 1f;
        try {
            return panel.captureFactor();
        } catch (Throwable error) {
            log(error);
            return 1f;
        }
    }

    private static Object jpegSaveImage(Object task) {
        Object image = getObjectField(task, "b");
        return "SINGLE_CAPTURE".equals(name(getObjectField(image, "f")))
                && "JPEG".equals(name(getObjectField(image, "b")))
                ? image : null;
    }

    private CropShot pendingShot(long stamp) {
        CropShot shot = shots.get(stamp);
        return shot != null && shot.expires >= SystemClock.elapsedRealtime() ? shot : null;
    }

    private void rememberShot(Object info, float factor) {
        if (info == null || factor <= 1f) return;
        try {
            if (!"SINGLE_CAPTURE".equals(name(callMethod(info, "b")))) return;
            long stamp = ((Number) callMethod(info, "m")).longValue();
            if (stamp <= 0) return;
            long now = SystemClock.elapsedRealtime();
            shots.entrySet().removeIf(entry -> entry.getValue().expires < now);
            postOutputs.entrySet().removeIf(entry -> !shots.containsKey(entry.getValue()));
            CropShot shot = new CropShot(factor, now + 180000);
            if (shots.putIfAbsent(stamp, shot) == null) {
                trace(stamp, "SHUTTER", "factor=" + factor + " route=" + name(callMethod(info, "n")));
                galleryWorker.schedule(() -> {
                    if (shots.get(stamp) == shot) trace(stamp, "WAITING_FOR_JPEG", "elapsedMs=8000");
                }, 8, TimeUnit.SECONDS);
            }
        } catch (Throwable error) {
            log(error);
        }
    }

    private static String name(Object value) {
        return value instanceof Enum<?> ? ((Enum<?>) value).name() : "";
    }

    private static void trace(long stamp, String stage, String details) {
        XposedBridge.log("AlmaZoom " + BuildConfig.VERSION_NAME + " shot=" + Long.toUnsignedString(stamp, 36)
                + " " + stage + (details.isEmpty() ? "" : " " + details));
    }

    private static void log(Throwable error) {
        XposedBridge.log("AlmaZoom: " + error);
    }

    private int readSetting(Object settings, String key) {
        return (Integer) callMethod(settings, "d", getStaticObjectField(setting, key));
    }

    private boolean ordinaryResolution(Object cameraSettings) {
        if (cameraSettings == null) return false;
        Size size = (Size) callStaticMethod(resolutionMapping, "v0",
                readSetting(cameraSettings, "BACK_CAMERA_RESOLUTION"));
        return size.getWidth() > 0 && size.getHeight() > 0
                && !JpegCrop.isHighResolution(size.getWidth(), size.getHeight());
    }

    private final class Panel implements Runnable {
        final Activity activity;
        final Handler handler = new Handler(Looper.getMainLooper());
        final StockZoomLabels stockLabels = new StockZoomLabels();
        final ToolbarIcon toolbar;
        volatile DigitalZoom digitalZoom;
        final SharedPreferences options;
        ZoomWheel wheel;
        boolean alignWheel, wheelWasVisible;
        final Runnable renderDigital = this::renderDigitalFrame;
        boolean digitalFrameQueued;
        View zoomView;
        Object settings, engine;
        PreviewCrop previewCrop;
        volatile float visibleCropFactor = 1f;
        int lastZoom;
        long pendingUntil, unfocusedSince;
        String lastError;

        Panel(Activity activity) {
            this.activity = activity;
            options = activity.getSharedPreferences(OPTIONS, 0);
            toolbar = new ToolbarIcon(activity, this::toggleZoom);
        }

        @Override public void run() {
            if (current != this) return;
            try {
                update();
                lastError = null;
            } catch (Throwable error) {
                hide();
                String description = error.toString();
                if (!description.equals(lastError)) log(error);
                lastError = description;
            }
            if (current == this) handler.postDelayed(this, 220);
        }

        private void update() throws Exception {
            if (activity.isFinishing() || activity.isDestroyed()) { hide(); return; }
            Object provider = getObjectField(activity, "q");
            settings = getObjectField(activity, "j");
            if (provider == null || settings == null) { hide(); return; }
            if (!"SHOOTING_MODE_PHOTO".equals(name(callStaticMethod(modeMapping, "i", getIntField(provider, "d"))))
                    || (Integer) callMethod(settings, "f") != 0
                    || readSetting(settings, "QUICK_TAKE_RECORDING_STATUS") != 0
                    || focusLost()) {
                hide();
                return;
            }

            Object layers = callMethod(activity, "F");
            if (layers == null) { hide(); return; }
            Object keyScreen = callMethod(layers, "getKeyScreenLayerManager");
            if (keyScreen == null) { hide(); return; }
            toolbar.update(options.getBoolean(EXTEND_ZOOM, true));
            Object zoom = callMethod(keyScreen, "getZoomManager");
            if (!(zoom instanceof View view) || !ZOOM_VIEW.equals(view.getClass().getName())) {
                hideZoom();
                return;
            }
            if (zoomView != view) {
                resetCrop();
                removeWheel();
                zoomView = view;
            }
            Object binding = getObjectField(zoomView, "a");
            if (binding == null) { hide(); return; }
            Object presenter = getObjectField(zoomView, "b");
            if (presenter == null) { hide(); return; }
            engine = getObjectField(presenter, "b");
            if (engine == null) { hide(); return; }
            if (!digitalConfigured()) {
                disableDigitalZoom();
                return;
            }
            // The capture request can be queued after preview has entered its capture state.
            if (!"PREVIEWING".equals(name(getObjectField(engine, "B")))) return;
            if (!view.isShown() && digitalZoom == null) { hideZoom(); return; }
            lastZoom = readSetting(settings, "ZOOM_VALUE");
            updateWheel();
            if (!digitalFrameQueued) updateDigital(binding);
            boolean wheelVisible = wheel != null && wheel.scroll.isShown()
                    && (Integer) callMethod(wheel.scroll, "getSliderLayoutVisibility") == View.VISIBLE;
            DigitalZoom selected = digitalZoom;
            if (selected != null && wheelVisible && (alignWheel || !wheelWasVisible) && wheel.ready()) {
                wheel.showRatio(selected.ratio());
                alignWheel = false;
            }
            wheelWasVisible = wheelVisible;
        }

        private boolean digitalConfigured() {
            return photoHookReady && options.getBoolean(EXTEND_ZOOM, true)
                    && ordinaryResolution(settings) && readSetting(settings, "HEIF") == 0;
        }

        private float captureFactor() {
            DigitalZoom selected = digitalZoom;
            if (selected == null || !options.getBoolean(EXTEND_ZOOM, true)) return 1f;
            Object liveSettings = getObjectField(activity, "j");
            return ordinaryResolution(liveSettings) && (Integer) callMethod(liveSettings, "f") == 0
                    && readSetting(liveSettings, "HEIF") == 0
                    && Math.abs(readSetting(liveSettings, "ZOOM_VALUE") - selected.base()) <= 250
                    ? visibleCropFactor : 1f;
        }

        private void updateWheel() {
            if (!wheelHookReady || engine == null || zoomView == null) return;
            Object candidate = getObjectField(zoomView, "e");
            if (!(candidate instanceof HorizontalScrollView scroll)) return;
            Object slider = getObjectField(scroll, "g");
            Object positions = getObjectField(scroll, "d");
            if (!(slider instanceof View tickView) || !(positions instanceof SparseIntArray nativePositions)) return;
            if (wheel != null && (wheel.scroll != scroll || wheel.ticks != tickView
                    || wheel.nativePositions != nativePositions)) {
                wheel.remove();
                wheel = null;
                alignWheel = digitalZoom != null;
            }
            if (!digitalConfigured()) {
                disableDigitalZoom();
                return;
            }
            if (wheel == null) wheel = new ZoomWheel(scroll, tickView, nativePositions);
            if (wheel.hardwareMaximum() <= 0 || wheel.hardwareMaximum() >= LIMIT
                    || !PreviewCrop.supported(findPreview())) return;
            if (wheel.install() && digitalZoom != null) alignWheel = true;
        }

        private boolean scrollZoom(Object widget, int x, int previous) throws Exception {
            if (wheel == null || widget != wheel.scroll) return false;
            if (wheel.positioning) return true;
            if (!wheel.ready() || x < 0 || (Integer) callMethod(widget, "getSliderLayoutVisibility") != View.VISIBLE
                    || getBooleanField(widget, "m")) return false;
            int end = wheel.nativeEnd();
            if (!digitalConfigured()) {
                disableDigitalZoom();
                return x > end;
            }
            int maximum = wheel.hardwareMaximum();
            if (((Integer) callMethod(engine, "B")) != maximum) {
                disableDigitalZoom();
                return x > end;
            }
            int digitalStart = wheel.digitalStart();
            DigitalZoom selected = digitalZoom;
            if (x <= end) {
                if (selected != null) {
                    if (!getBooleanField(widget, "n")) {
                        alignWheel = true;
                        return true;
                    }
                    resetCrop();
                }
                return false;
            }
            if (selected == null && x <= digitalStart) {
                if (Math.abs(readSetting(settings, "ZOOM_VALUE") - maximum) > 250) {
                    callMethod(settings, "V", maximum, getStaticObjectField(setting, "ZOOM_VALUE"));
                    callMethod(zoomView, "N2", maximum);
                }
                wheel.moveHighlight(x);
                return true;
            }

            int ratio = wheel.ratioAt(x);
            digitalZoom = new DigitalZoom(ratio, maximum);
            if (selected == null || selected.base() != maximum) {
                pendingUntil = SystemClock.elapsedRealtime() + 1800;
                if (Math.abs(readSetting(settings, "ZOOM_VALUE") - maximum) > 250) {
                    callMethod(settings, "V", maximum, getStaticObjectField(setting, "ZOOM_VALUE"));
                }
            }
            alignWheel = false;
            wheel.moveHighlight(x);
            if (x != previous) playZoomTick(wheel.hapticTick(x, previous));
            if (!digitalFrameQueued) {
                digitalFrameQueued = true;
                zoomView.postOnAnimation(renderDigital);
            }
            return true;
        }

        private void playZoomTick(int tick) {
            if (tick == 0) return;
            try {
                if (readSetting(settings, "TOUCH_VIBRATIONS") != 1) return;
                Object nativeTick = tick == 2 ? majorTick : sliderTick;
                if (nativeTick != null) {
                    callMethod(activity, "o0", nativeTick);
                } else {
                    zoomView.performHapticFeedback(tick == 2
                            ? HapticFeedbackConstants.CONTEXT_CLICK : HapticFeedbackConstants.CLOCK_TICK);
                }
            } catch (LinkageError | RuntimeException unavailable) {
                // Haptics are optional. An unavailable preference or driver must not interrupt zoom.
            }
        }

        private void renderDigitalFrame() {
            digitalFrameQueued = false;
            if (current != this || zoomView == null || digitalZoom == null) return;
            try {
                Object binding = getObjectField(zoomView, "a");
                lastZoom = readSetting(settings, "ZOOM_VALUE");
                updateDigital(binding);
            } catch (Throwable error) {
                resetCrop();
                log(error);
            }
        }

        private void updateDigital(Object binding) throws Exception {
            DigitalZoom selected = digitalZoom;
            if (selected == null) return;
            if (!digitalConfigured()) {
                resetCrop();
                return;
            }
            if (lastZoom <= 0 || Math.abs(lastZoom - selected.base()) > 250) {
                clearPreviewCrop();
                if (SystemClock.elapsedRealtime() > pendingUntil) resetCrop();
                return;
            }
            float factor = selected.factor();
            if (factor <= 1f) {
                clearPreviewCrop();
                return;
            }
            stockLabels.show(binding, selected.base(), format(selected.ratio()));
            View preview = findPreview();
            if (preview == null || !applyCrop(preview, factor)) {
                clearPreviewCrop();
                return;
            }
            visibleCropFactor = factor;
            pendingUntil = 0;
        }

        private boolean focusLost() {
            if (activity.hasWindowFocus()) {
                unfocusedSince = 0;
                return false;
            }
            long now = SystemClock.elapsedRealtime();
            if (unfocusedSince == 0) unfocusedSince = now;
            return now - unfocusedSince >= 450;
        }

        private void toggleZoom() {
            boolean enabled = !options.getBoolean(EXTEND_ZOOM, true);
            options.edit().putBoolean(EXTEND_ZOOM, enabled).apply();
            try {
                if (!enabled) disableDigitalZoom();
                toolbar.announce(enabled, findPreview());
            } catch (Throwable error) {
                log(error);
                toolbar.announce(enabled, null);
            }
        }

        private View findPreview() {
            Object controller = getObjectField(activity, "f");
            if (controller == null) return null;
            Object holder = getObjectField(controller, "g");
            if (holder != null) {
                for (String field : new String[]{"d", "f", "e"}) {
                    Object candidate = getObjectField(holder, field);
                    if (candidate instanceof View view && usable(view)) return view;
                }
            }
            Object primary = getObjectField(controller, "f");
            return primary instanceof View view && usable(view) ? view : null;
        }

        private boolean usable(View view) {
            if (!view.isShown() || view.getWidth() < 20 || view.getHeight() < 20) return false;
            if (view instanceof TextureView texture) return texture.isAvailable();
            return view instanceof SurfaceView surface && surface.getHolder().getSurface().isValid();
        }

        private boolean applyCrop(View preview, float factor) throws Exception {
            if (!PreviewCrop.supported(preview)) return false;
            if (previewCrop == null || previewCrop.view != preview) {
                if (previewCrop != null) previewCrop.restore();
                previewCrop = new PreviewCrop(preview);
            }
            previewCrop.apply(factor);
            return true;
        }

        private void resetCrop() {
            if (zoomView != null) zoomView.removeCallbacks(renderDigital);
            digitalFrameQueued = false;
            digitalZoom = null;
            pendingUntil = 0;
            alignWheel = false;
            wheelWasVisible = false;
            stockLabels.restore();
            clearPreviewCrop();
        }

        private void clearPreviewCrop() {
            visibleCropFactor = 1f;
            if (previewCrop != null) {
                try { previewCrop.restore(); }
                catch (Throwable error) { log(error); }
                previewCrop = null;
            }
        }

        private void hide() {
            hideZoom();
            toolbar.hide();
        }

        private void hideZoom() {
            disableDigitalZoom();
        }

        void stop() {
            handler.removeCallbacks(this);
            hide();
            toolbar.remove();
        }

        private void disableDigitalZoom() {
            resetCrop();
            removeWheel();
        }

        private void removeWheel() {
            if (wheel == null) return;
            ZoomWheel installed = wheel;
            installed.remove();
            wheel = null;
        }
    }

    private static String format(int zoom) {
        return String.format(Locale.US, "%.3f", (double) zoom / UNIT)
                .replaceFirst("\\.?0+$", "").replace('.', ',');
    }

    private static final class PreviewCrop {
        final View view;
        final Matrix base = new Matrix();
        final Matrix requestedTransform = new Matrix();
        final Matrix observedTransform = new Matrix();
        final Rect originalClip;
        final float scaleX, scaleY, pivotX, pivotY;
        final Method clipping;
        final boolean originallyClipped;
        float lastFactor;
        int lastWidth, lastHeight;

        PreviewCrop(View view) throws Exception {
            this.view = view;
            originalClip = view.getClipBounds();
            scaleX = view.getScaleX();
            scaleY = view.getScaleY();
            pivotX = view.getPivotX();
            pivotY = view.getPivotY();
            if (view instanceof TextureView texture) {
                texture.getTransform(base);
                clipping = null;
                originallyClipped = false;
            } else {
                clipping = findMethodBestMatch(SurfaceView.class, "setEnableSurfaceClipping", boolean.class);
                originallyClipped = getBooleanField(view, "mClipSurfaceToBounds");
                clipping.invoke(view, true);
            }
        }

        static boolean supported(View view) {
            if (view instanceof TextureView) return true;
            if (!(view instanceof SurfaceView)) return false;
            try {
                findMethodBestMatch(SurfaceView.class, "setEnableSurfaceClipping", boolean.class);
                getBooleanField(view, "mClipSurfaceToBounds");
                return true;
            } catch (LinkageError | RuntimeException unavailable) {
                return false;
            }
        }

        void apply(float factor) {
            int w = view.getWidth();
            int h = view.getHeight();
            boolean sameCrop = lastFactor == factor && lastWidth == w && lastHeight == h;
            if (view instanceof TextureView texture) {
                texture.getTransform(observedTransform);
                boolean current = lastFactor > 1f && observedTransform.equals(requestedTransform);
                if (sameCrop && current) return;
                if (!current) base.set(observedTransform);
                requestedTransform.set(base);
                requestedTransform.postScale(factor, factor, w / 2f, h / 2f);
                texture.setTransform(requestedTransform);
            } else {
                applySurface(factor, w, h, sameCrop);
            }
            lastFactor = factor;
            lastWidth = w;
            lastHeight = h;
        }

        private void applySurface(float factor, int w, int h, boolean sameCrop) {
            int cropW = ZoomMath.cropSize(w, factor);
            int cropH = ZoomMath.cropSize(h, factor);
            int left = (w - cropW) / 2;
            int top = (h - cropH) / 2;
            Rect clip = new Rect(left, top, left + cropW, top + cropH);
            if (sameCrop && view.getScaleX() == scaleX * factor && view.getScaleY() == scaleY * factor
                    && clip.equals(view.getClipBounds())) return;
            view.setClipBounds(clip);
            view.setPivotX(w / 2f);
            view.setPivotY(h / 2f);
            view.setScaleX(scaleX * factor);
            view.setScaleY(scaleY * factor);
        }

        void restore() throws Exception {
            if (view instanceof TextureView texture) {
                texture.getTransform(observedTransform);
                if (observedTransform.equals(requestedTransform)) texture.setTransform(base);
            }
            else {
                view.setScaleX(scaleX);
                view.setScaleY(scaleY);
                view.setPivotX(pivotX);
                view.setPivotY(pivotY);
                view.setClipBounds(originalClip);
                clipping.invoke(view, originallyClipped);
            }
        }
    }
}
