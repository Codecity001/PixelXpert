package sh.siava.pixelxpert.xposed.modpacks.systemui;

import android.content.Context;

import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.lang.reflect.Modifier;
import java.util.Locale;

import de.robv.android.xposed.XC_MethodHook;
import de.robv.android.xposed.XposedBridge;
import de.robv.android.xposed.XposedHelpers;
import io.github.libxposed.api.XposedModuleInterface;
import sh.siava.pixelxpert.xposed.XposedModPack;
import sh.siava.pixelxpert.xposed.annotations.SystemUIModPack;

import static sh.siava.pixelxpert.xposed.XPrefs.Xprefs;

/**
 * RestoreRoundedSliders (brightness mixer-style endpoint transition v98)
 *
 * Android 17 Pixel/SystemUIGoogle implementation, validated on CP2A.260805.005.
 *
 * Compatibility note:
 *  - not tied to Pixel 9 Pro hardware;
 *  - intended to work across Pixels whose Android 17 SystemUI keeps these hooks;
 *  - not guaranteed on arbitrary OEM/AOSP Android 17 builds because vendors may
 *    replace or rename the SystemUI slider composables/classes.
 *
 * Goals:
 *  - restore the old fully-rounded brightness/volume slider appearance;
 *  - optionally remove the Material 3 grabber and its transient drag marker;
 *  - allow brightness and volume roundness/grabber settings independently.
 *
 * The hook deliberately edits the per-slider Dimensions objects passed into
 * Compose rather than touching gesture/value logic.
 */
@SystemUIModPack
public final class ClassicSliders extends XposedModPack {
    private static final String SYSTEM_UI = "com.android.systemui";
    private static final String TAG = "RestoreRoundedSliders";

    private static final String BRIGHTNESS_KT =
            "com.android.systemui.brightness.ui.compose.BrightnessSliderKt";
    private static final String BRIGHTNESS_DIMS =
            "com.android.systemui.brightness.ui.compose.BrightnessSliderDimensions";
    private static final String VOLUME_KT =
            "com.android.systemui.volume.panel.component.volume.ui.composable.VolumeSliderKt";
    private static final String VOLUME_DIMS =
            "com.android.systemui.volume.panel.component.volume.ui.composable.VolumeSliderDimensions";

    private static final String REAL_VOLUME_CONTENT_LAMBDA =
            "com.android.systemui.volume.dialog.sliders.ui.VolumeDialogSliderViewBinder$$ExternalSyntheticLambda0";

    private static final java.util.concurrent.atomic.AtomicBoolean
            realVolumeContentHookInstalled =
                    new java.util.concurrent.atomic.AtomicBoolean(false);

    /* PixelXpert preferences are remote. Never query them on Compose's draw path. */
    private static volatile boolean classicSlidersEnabled;
    private static volatile int brightnessRoundness = 100;
    private static volatile boolean brightnessGrabber;
    private static volatile int volumeRoundness = 100;
    private static volatile boolean volumeGrabber;

    public ClassicSliders(Context context) {
        super(context);
    }

    @Override
    public void onPreferenceUpdated(String... keys) {
        reloadPreferences();
    }

    @Override
    public void onPackageLoaded(XposedModuleInterface.PackageReadyParam lpparam) {
        // Unlike the standalone module, PixelXpert is always scoped to SystemUI.
        // Only install the complete hook set when this tweak is enabled; a SystemUI
        // restart after disabling restores unmodified stock rendering.
        reloadPreferences();
        if (!classicSlidersEnabled) return;

        try {
            installSharedSliderHooks(lpparam.getClassLoader());
        } catch (Throwable t) {
            log("Shared slider hooks unavailable; continuing with direct hooks", t);
        }

        try {
            installDeferredVolumeClassWatcher(lpparam.getClassLoader());
        } catch (Throwable t) {
            log("Deferred volume class watcher unavailable", t);
        }

        try {
            hookBrightness(lpparam.getClassLoader());
        } catch (Throwable t) {
            log("Brightness hook unavailable; leaving stock UI", t);
        }

        try {
            installInflatedVolumeSliderTrace();
        } catch (Throwable t) {
            log("Inflated volume slider trace unavailable", t);
        }

        try {
            installVolumeComposeViewFieldTrace(lpparam.getClassLoader());
        } catch (Throwable t) {
            log("Volume ComposeView field trace unavailable", t);
        }

        try {
            installVolumeContentStateAssignmentTrace(lpparam.getClassLoader());
        } catch (Throwable t) {
            log("Volume content-state assignment trace unavailable", t);
        }

        try {
            installRealBindSliderTrace(lpparam.getClassLoader());
        } catch (Throwable t) {
            log("Real bindSlider trace unavailable", t);
        }

        try {
            installRealVolumeContentHook(lpparam.getClassLoader());
        } catch (Throwable t) {
            log("Real volume content hook unavailable", t);
        }

        try {
            installInnerVolumeComposableHooks(lpparam.getClassLoader());
        } catch (Throwable t) {
            log("Inner volume composable hooks unavailable", t);
        }

        try {
            installMaterial3VolumeSliderContextHook(lpparam.getClassLoader());
        } catch (Throwable t) {
            log("Material3 volume Slider context hook unavailable", t);
        }

        try {
            installDirectVolumeTrackDetection(lpparam.getClassLoader());
        } catch (Throwable t) {
            log("Direct volume Track detection unavailable", t);
        }

        try {
            installDeferredCustomVolumeSliderHook(lpparam.getClassLoader());
        } catch (Throwable t) {
            log("Deferred custom volume slider hook unavailable", t);
        }

        try {
            installVolumeVerticalSliderThumbSuppressor(lpparam.getClassLoader());
        } catch (Throwable t) {
            log("Volume VerticalSlider thumb suppressor unavailable", t);
        }

        try {
            installVolumeHorizontalSliderThumbSuppressor(lpparam.getClassLoader());
        } catch (Throwable t) {
            log("Volume horizontal Slider thumb suppressor unavailable", t);
        }

        try {
            installVolumeSliderDefaultsThumbSuppressorV83(lpparam.getClassLoader());
        } catch (Throwable t) {
            log("Volume SliderDefaults.Thumb suppressor unavailable", t);
        }

        try {
            installVolumeSliderImplThumbSuppressorV85(lpparam.getClassLoader());
        } catch (Throwable t) {
            log("Volume SliderImpl thumb suppressor unavailable", t);
        }

        try {
            hookVolume(lpparam.getClassLoader());
        } catch (Throwable t) {
            log("Volume hook unavailable; leaving stock UI", t);
        }
    }

    private static void hookBrightness(ClassLoader cl) {
        Class<?> kt = XposedHelpers.findClass(BRIGHTNESS_KT, cl);

        XposedBridge.hookAllMethods(kt, "BrightnessSlider", new XC_MethodHook() {
            @Override
            protected void beforeHookedMethod(MethodHookParam param) {
                Object dims = findArg(param.args, BRIGHTNESS_DIMS);
                if (dims == null) return;

                SliderConfig cfg = SliderConfig.brightness();
                applyDimensions(dims, cfg);
                SliderContext.set(cfg, readFloat(dims, "trackHeight"));
            }

            @Override
            protected void afterHookedMethod(MethodHookParam param) {
                SliderContext.clear();
            }
        });

        log("Brightness hook installed");
    }

    private static void hookVolume(ClassLoader cl) {
        Class<?> kt = XposedHelpers.findClass(VOLUME_KT, cl);

        XposedBridge.hookAllMethods(kt, "VolumeSlider", new XC_MethodHook() {
            @Override
            protected void beforeHookedMethod(MethodHookParam param) {
                Object dims = findArg(param.args, VOLUME_DIMS);
                if (dims == null) return;

                SliderConfig cfg = SliderConfig.volume();
                applyDimensions(dims, cfg);
                SliderContext.set(cfg, readFloat(dims, "trackHeight"));
            }

            @Override
            protected void afterHookedMethod(MethodHookParam param) {
                SliderContext.clear();
            }
        });

        log("Volume hook installed");
    }

    /**
     * Apply the settings directly to the SystemUI slider Dimensions object.
     *
     * Android 17's Material slider separates the visible thumb from the gap
     * around the thumb. Setting thumbWidth=0 removes the bar itself, but unless
     * thumbTrackGapSize (or its equivalent) is also zeroed, an empty notch/gap
     * remains. That was the visible padding in the first test build.
     */
    private static void applyDimensions(Object dims, SliderConfig cfg) {
        /*
         * Grabber ON = stock Material3 geometry during normal movement.
         *
         * Do not change thumb size, thumb gap, inside-corner radius, or any
         * other Dimensions field here. Exact 0%/100% are corrected later in
         * drawTrackPath without reintroducing endpoint animations.
         */
        if (cfg.grabber) {
            return;
        }

        Float originalTrackHeight = readFloat(dims, "trackHeight");
        float trackHeight = originalTrackHeight == null ? 0f : originalTrackHeight;

        // Thickness is independent per slider. 100 = stock Android 17 height.
        if (trackHeight > 0f) {
            trackHeight = trackHeight * cfg.thickness / 100f;
            writeFloatIfPresent(dims, "trackHeight", trackHeight);

            // Keep a thumb/handle height field aligned with the track when present.
            // Width is handled separately below.
            writeFloatIfPresent(dims, "thumbHeight", trackHeight);
        }

        float wantedRadius = trackHeight > 0f
                ? Math.max(0f, trackHeight * 0.5f * clampPercent(cfg.roundness) / 100f)
                : 0f;

        // Known/likely Pixel Compose dimension names. Different Android builds
        // use slightly different names, so apply every matching field that exists.
        if (trackHeight > 0f) {
            setRadiusField(dims, "backgroundRoundedCorner", wantedRadius);
            setRadiusField(dims, "trackRoundedCorner", wantedRadius);
            setRadiusField(dims, "activeTrackRoundedCorner", wantedRadius);
            setRadiusField(dims, "inactiveTrackRoundedCorner", wantedRadius);
            setRadiusField(dims, "trackCornerRadius", wantedRadius);
            setRadiusField(dims, "activeTrackCornerRadius", wantedRadius);
            setRadiusField(dims, "inactiveTrackCornerRadius", wantedRadius);
            setRadiusField(dims, "trackCornerSize", wantedRadius);

            // These are the OUTER track corners and should be pill-shaped.
            // The separate "inside" corners belong to the thumb/grabber gap.
            // With no grabber they must be 0, otherwise the former thumb
            // position becomes the rounded part of the slider.
            // The edge facing the grabber is straight in the classic look.
            // This stays square whether the grabber is hidden or visible.
            float insideRadius = 0f;
            setRadiusField(dims, "trackInsideCornerSize", insideRadius);
            setRadiusField(dims, "insideCornerSize", insideRadius);
            setRadiusField(dims, "insideCornerRadius", insideRadius);

            // Also catch renamed OUTER radius fields on future Pixel builds.
            // Explicitly skip any "inside" field; those were the cause of v2
            // rounding the former grabber position instead of the outer ends.
            setMatchingOuterRadiusFields(dims, wantedRadius);
        }

        if (!cfg.grabber) {
            // Hide only the visible Material 3 grabber/thumb.
            zeroFloatIfPresent(dims, "thumbWidth");
            zeroFloatIfPresent(dims, "grabberWidth");
            zeroFloatIfPresent(dims, "handleWidth");
            zeroFloatIfPresent(dims, "indicatorWidth");
        }

        /*
         * Classic geometry has no empty moat around the grabber. When the
         * grabber is visible, the active/inactive track terminates against its
         * straight edge; when hidden, the same zero gap makes the two track
         * portions continuous.
         */
        zeroFloatIfPresent(dims, "thumbTrackGapSize");
        zeroFloatIfPresent(dims, "thumbTrackGap");
        zeroFloatIfPresent(dims, "thumbGap");
        zeroFloatIfPresent(dims, "grabberGap");
        zeroFloatIfPresent(dims, "handleGap");
        zeroMatchingGrabberSpacingFields(dims);
    }

    /**
     * Hooks the two slider implementations used by current Pixel SystemUI:
     *
     *  1) SystemUI's com.android.compose.PlatformSlider
     *  2) Material3 SliderDefaults.Track
     *
     * The first controls the old-style indicator radius used by PlatformSlider.
     * The second is important on Android 17 because Material3 reserves a
     * thumbTrackGapSize around the handle. Hiding only the thumb leaves exactly
     * the empty notch seen in the first RestoreRoundedSliders test build.
     */
    private static final java.util.concurrent.atomic.AtomicBoolean
            volumeVerticalThumbSuppressorInstalled =
                    new java.util.concurrent.atomic.AtomicBoolean(false);

    private static volatile boolean volumeThumbSuppressedLogged;

    private static volatile boolean volumeStopIndicatorSuppressedLogged;
    private static volatile boolean volumeStopIndicatorFailureLogged;

    private static void logOnceVolumeStopIndicatorFailure(String s) {
        if (volumeStopIndicatorFailureLogged) return;
        volumeStopIndicatorFailureLogged = true;
        log(s);
    }

    private static void logOnceVolumeStopIndicatorSuppressed(String s) {
        if (volumeStopIndicatorSuppressedLogged) return;
        volumeStopIndicatorSuppressedLogged = true;
        log(s);
    }


    /**
     * Runtime signature previously observed on the validated Pixel build:
     *
     * VerticalSlider(
     *   0 SliderState,
     *   1 Modifier,
     *   2 boolean enabled,
     *   3 boolean reverseDirection,
     *   4 SliderColors,
     *   5 MutableInteractionSourceImpl,
     *   6 Function3 thumb,
     *   7 Function3 track,
     *   8 Composer,
     *   9 int
     * )
     *
     * The horizontal white line is parameter 6: the Material3 thumb composable.
     * Replace only that composable, only for the real hardware VolumeDialog.
     */
    private static void installVolumeVerticalSliderThumbSuppressor(
            ClassLoader cl
    ) {
        if (!volumeVerticalThumbSuppressorInstalled.compareAndSet(false, true)) {
            return;
        }

        Class<?> sliderKt = XposedHelpers.findClass(
                "androidx.compose.material3.SliderKt",
                cl);

        int hooked = 0;

        for (Method m : sliderKt.getDeclaredMethods()) {
            if (!"VerticalSlider".equals(m.getName())) continue;

            Class<?>[] p = m.getParameterTypes();

            if (p.length < 10
                    || !"androidx.compose.material3.SliderState".equals(p[0].getName())
                    || !"kotlin.jvm.functions.Function3".equals(p[6].getName())
                    || !"kotlin.jvm.functions.Function3".equals(p[7].getName())) {
                continue;
            }

            XposedBridge.hookMethod(m, new XC_MethodHook() {
                @Override
                protected void beforeHookedMethod(MethodHookParam param) {
                    if (!isRealVolumeTrackStack()
                            && !isAnySystemUiVolumeStack()) {
                        return;
                    }

                    /*
                     * Register BEFORE SliderImpl performs thumb placement.
                     * SliderImpl uses coercedValueAsFraction for the real stock
                     * grabber position, so it must know this is our volume
                     * SliderState before that getter is read.
                     */
                    if (param.args != null
                            && param.args.length > 0
                            && param.args[0] != null) {
                        registerRoundedSliderStateCompat(
                                param.args[0],
                                "volume");
                    }

                    // Grabber enabled = keep the genuine stock Material3 thumb.
                    if (SliderConfig.volume().grabber) return;

                    Object originalThumb = param.args[6];
                    if (originalThumb == null) return;

                    Object noOp = makeNoOpFunction3(
                            originalThumb.getClass().getClassLoader(),
                            p[6]);

                    if (noOp != null) {
                        param.args[6] = noOp;

                        if (!volumeThumbSuppressedLogged) {
                            volumeThumbSuppressedLogged = true;
                            log("REALVOL-V98 Material3 VerticalSlider thumb suppressed");
                        }
                    }
                }
            });

            hooked++;
        }

        log("REALVOL-V98 VerticalSlider thumb hooks installed: " + hooked);
    }

    private static final java.util.concurrent.atomic.AtomicBoolean
            volumeHorizontalThumbSuppressorInstalled =
                    new java.util.concurrent.atomic.AtomicBoolean(false);

    private static volatile boolean volumeHorizontalThumbSuppressedLogged;

    /**
     * The expanded volume mixer uses horizontal Material3 Slider(...) rather
     * than VerticalSlider(...). Its thumb is the first of the final two
     * Function3 composables (thumb, track). Suppress that thumb when the Volume
     * "Show grabber" option is off. This also removes the blinking marker that
     * otherwise remained on the mixer rows while dragging.
     */
    private static void installVolumeHorizontalSliderThumbSuppressor(
            ClassLoader cl
    ) {
        if (!volumeHorizontalThumbSuppressorInstalled.compareAndSet(false, true)) {
            return;
        }

        Class<?> sliderKt = XposedHelpers.findClass(
                "androidx.compose.material3.SliderKt",
                cl);

        int hooked = 0;

        for (Method m : sliderKt.getDeclaredMethods()) {
            if (!"Slider".equals(m.getName())) continue;

            Class<?>[] p = m.getParameterTypes();
            if (p.length == 0
                    || !"androidx.compose.material3.SliderState".equals(p[0].getName())) {
                continue;
            }

            java.util.ArrayList<Integer> function3 = new java.util.ArrayList<>();
            for (int i = 0; i < p.length; i++) {
                if ("kotlin.jvm.functions.Function3".equals(p[i].getName())) {
                    function3.add(i);
                }
            }

            if (function3.size() < 2) continue;

            final int thumbIndex = function3.get(function3.size() - 2);

            XposedBridge.hookMethod(m, new XC_MethodHook() {
                @Override
                protected void beforeHookedMethod(MethodHookParam param) {
                    if (param.args == null || thumbIndex >= param.args.length) return;

                    Object state =
                            param.args.length > 0
                                    ? param.args[0]
                                    : null;

                    /*
                     * Register before SliderImpl lays out the real stock thumb.
                     * The outer BrightnessSlider / VolumeSlider hook keeps a
                     * SliderContext active around this call.
                     */
                    SliderContext.State currentContext =
                            SliderContext.get();

                    if (state != null
                            && currentContext != null
                            && currentContext.config != null
                            && currentContext.config.name != null) {
                        registerRoundedSliderStateCompat(
                                state,
                                currentContext.config.name);
                    } else if (state != null
                            && (isVolumeDialogCallStack()
                                || isAnySystemUiVolumeStack())) {
                        registerRoundedSliderStateCompat(
                                state,
                                "volume");
                    }

                    String registered =
                            roundedSliderStatesCompat.get(
                                    state);

                    /*
                     * Grabber enabled: keep the genuine stock thumb. The early
                     * registration is the only change we need for endpoint
                     * positioning.
                     */
                    if (("brightness".equals(registered)
                                && SliderConfig.brightness().grabber)
                            || ("volume".equals(registered)
                                && SliderConfig.volume().grabber)) {
                        return;
                    }

                    boolean registeredVolume =
                            "volume".equals(
                                    registered);

                    if (!registeredVolume
                            && !isVolumeDialogCallStack()
                            && !isAnySystemUiVolumeStack()) {
                        return;
                    }

                    Object originalThumb = param.args[thumbIndex];
                    if (originalThumb == null) return;

                    Object noOp = makeNoOpFunction3(
                            originalThumb.getClass().getClassLoader(),
                            p[thumbIndex]);

                    if (noOp != null) {
                        param.args[thumbIndex] = noOp;

                        if (!volumeHorizontalThumbSuppressedLogged) {
                            volumeHorizontalThumbSuppressedLogged = true;
                            log("REALVOL-V98 horizontal mixer thumb suppressed");
                        }
                    }
                }
            });

            hooked++;
        }

        log("REALVOL-V98 horizontal Slider thumb hooks installed: " + hooked);
    }

    private static final java.util.concurrent.atomic.AtomicBoolean
            volumeSliderDefaultsThumbSuppressorInstalledV83 =
                    new java.util.concurrent.atomic.AtomicBoolean(false);

    private static volatile boolean volumeSliderDefaultsThumbSuppressedLoggedV83;

    /**
     * Some expanded-volume rows invoke SliderDefaults.Thumb from a deferred
     * Compose lambda while dragging. In that path replacing SliderKt.Slider's
     * thumb lambda is not sufficient: the pressed thumb can briefly reappear
     * for one or more frames ("blinking").
     *
     * Hook the actual Material3 Thumb composable as a final suppression layer.
     * This is scoped to the Pixel volume-dialog stack and is active only when
     * Volume -> Show grabber is OFF.
     */
    private static void installVolumeSliderDefaultsThumbSuppressorV83(
            ClassLoader cl
    ) {
        if (!volumeSliderDefaultsThumbSuppressorInstalledV83
                .compareAndSet(false, true)) {
            return;
        }

        Class<?> sliderDefaults = XposedHelpers.findClass(
                "androidx.compose.material3.SliderDefaults",
                cl);

        int hooked = 0;

        for (Method m : sliderDefaults.getDeclaredMethods()) {
            String n = m.getName();

            if (!(n.equals("Thumb")
                    || n.startsWith("Thumb-")
                    || n.contains("Thumb"))) {
                continue;
            }

            XposedBridge.hookMethod(m, new XC_MethodHook() {
                @Override
                protected void beforeHookedMethod(MethodHookParam param) {
                    SliderContext.State sliderState =
                            SliderContext.get();

                    boolean volumeContext =
                            sliderState != null
                                    && sliderState.config != null
                                    && "volume".equals(sliderState.config.name);

                    if (!volumeContext
                            && !isVolumeDialogCallStack()
                            && !isRealVolumeTrackStack()
                            && !isAnySystemUiVolumeStack()) {
                        return;
                    }

                    if (SliderConfig.volume().grabber) {
                        return;
                    }

                    /*
                     * Compose functions return Unit/void at the JVM boundary;
                     * setting a null result skips the actual thumb draw.
                     */
                    param.setResult(null);

                    if (!volumeSliderDefaultsThumbSuppressedLoggedV83) {
                        volumeSliderDefaultsThumbSuppressedLoggedV83 = true;
                        log("REALVOL-V98 SliderDefaults.Thumb suppressed");
                    }
                }
            });

            hooked++;
        }

        log("REALVOL-V98 SliderDefaults.Thumb hooks installed: " + hooked);
    }

    private static final java.util.concurrent.atomic.AtomicBoolean
            volumeSliderImplThumbSuppressorInstalledV85 =
                    new java.util.concurrent.atomic.AtomicBoolean(false);

    private static volatile boolean volumeSliderImplThumbSuppressedLoggedV85;

    /**
     * Expanded mixer rows can re-enter Material3 below public Slider(...) while
     * dragging. Identify the row by its registered SliderState rather than by
     * the transient Java call stack.
     */
    private static void installVolumeSliderImplThumbSuppressorV85(
            ClassLoader cl
    ) {
        if (!volumeSliderImplThumbSuppressorInstalledV85
                .compareAndSet(false, true)) {
            return;
        }

        Class<?> sliderKt =
                XposedHelpers.findClass(
                        "androidx.compose.material3.SliderKt",
                        cl);

        int hooked = 0;

        for (Method m : sliderKt.getDeclaredMethods()) {
            if (!m.getName().startsWith("SliderImpl")) {
                continue;
            }

            Class<?>[] p = m.getParameterTypes();

            int stateIndex = -1;
            java.util.ArrayList<Integer> function3 =
                    new java.util.ArrayList<>();

            for (int i = 0; i < p.length; i++) {
                if ("androidx.compose.material3.SliderState"
                        .equals(p[i].getName())) {
                    stateIndex = i;
                }

                if ("kotlin.jvm.functions.Function3"
                        .equals(p[i].getName())) {
                    function3.add(i);
                }
            }

            if (stateIndex < 0 || function3.size() < 2) {
                continue;
            }

            final int resolvedStateIndex = stateIndex;
            final int thumbIndex =
                    function3.get(function3.size() - 2);

            XposedBridge.hookMethod(
                    m,
                    new XC_MethodHook() {
                        @Override
                        protected void beforeHookedMethod(
                                MethodHookParam param
                        ) {
                            if (param.args == null
                                    || resolvedStateIndex >= param.args.length
                                    || thumbIndex >= param.args.length) {
                                return;
                            }

                            Object state =
                                    param.args[resolvedStateIndex];

                            /*
                             * Last timing guard: if we reach SliderImpl before
                             * Track/TrackImpl registration, register the state
                             * while SliderContext/call-stack information still
                             * identifies it.
                             */
                            SliderContext.State currentContext =
                                    SliderContext.get();

                            if (state != null
                                    && currentContext != null
                                    && currentContext.config != null
                                    && currentContext.config.name != null) {
                                registerRoundedSliderStateCompat(
                                        state,
                                        currentContext.config.name);
                            } else if (state != null
                                    && isAnySystemUiVolumeStack()) {
                                registerRoundedSliderStateCompat(
                                        state,
                                        "volume");
                            }

                            /*
                             * This hook exists to suppress mixer thumbs when
                             * Volume -> Show grabber is OFF. With grabber ON,
                             * leave the REAL stock thumb untouched.
                             */
                            if (SliderConfig.volume().grabber) {
                                return;
                            }

                            boolean registeredVolume =
                                    "volume".equals(
                                            roundedSliderStatesCompat.get(
                                                    state));

                            if (!registeredVolume
                                    && !isAnySystemUiVolumeStack()) {
                                return;
                            }

                            Object originalThumb =
                                    param.args[thumbIndex];

                            if (originalThumb == null) {
                                return;
                            }

                            Object noOp =
                                    makeNoOpFunction3(
                                            originalThumb
                                                    .getClass()
                                                    .getClassLoader(),
                                            p[thumbIndex]);

                            if (noOp == null) {
                                return;
                            }

                            param.args[thumbIndex] = noOp;

                            if (!volumeSliderImplThumbSuppressedLoggedV85) {
                                volumeSliderImplThumbSuppressedLoggedV85 = true;
                                log("REALVOL-V98 SliderImpl mixer thumb suppressed");
                            }
                        }
                    });

            hooked++;
        }

        log("REALVOL-V98 SliderImpl thumb hooks installed: " + hooked);
    }

    private static Object makeNoOpFunction2Robust(
            Object original,
            ClassLoader fallbackLoader
    ) {
        try {
            ClassLoader loader = null;

            if (original != null) {
                loader = original.getClass().getClassLoader();
            }

            if (loader == null) {
                loader = fallbackLoader;
            }

            Class<?> function2Class = null;

            /*
             * Generated/R8 synthetic lambda classes don't necessarily expose
             * Function2 via getInterfaces(), even though they are assignable to
             * it. Resolve the Kotlin interface directly instead.
             */
            for (ClassLoader candidate : new ClassLoader[] {
                    loader,
                    fallbackLoader,
                    ClassicSliders.class.getClassLoader()
            }) {
                if (candidate == null) continue;

                try {
                    function2Class = Class.forName(
                            "kotlin.jvm.functions.Function2",
                            false,
                            candidate);
                    break;
                } catch (Throwable ignored) {
                }
            }

            if (function2Class == null) {
                logOnceVolumeStopIndicatorFailure(
                        "REALVOL-V98 Function2 class not found");
                return null;
            }

            if (original != null
                    && !function2Class.isInstance(original)) {
                logOnceVolumeStopIndicatorFailure(
                        "REALVOL-V98 arg5 is not Function2: "
                                + original.getClass().getName());
                return null;
            }

            Class<?> unitClass = null;

            for (ClassLoader candidate : new ClassLoader[] {
                    function2Class.getClassLoader(),
                    loader,
                    fallbackLoader
            }) {
                if (candidate == null) continue;

                try {
                    unitClass = Class.forName(
                            "kotlin.Unit",
                            false,
                            candidate);
                    break;
                } catch (Throwable ignored) {
                }
            }

            if (unitClass == null) {
                logOnceVolumeStopIndicatorFailure(
                        "REALVOL-V98 kotlin.Unit class not found");
                return null;
            }

            Field instanceField = unitClass.getDeclaredField("INSTANCE");
            instanceField.setAccessible(true);
            final Object unit = instanceField.get(null);

            ClassLoader proxyLoader =
                    function2Class.getClassLoader() != null
                            ? function2Class.getClassLoader()
                            : loader;

            return java.lang.reflect.Proxy.newProxyInstance(
                    proxyLoader,
                    new Class<?>[] { function2Class },
                    (proxy, method, args) -> {
                        String n = method.getName();

                        if ("invoke".equals(n)) {
                            return unit;
                        }

                        if ("toString".equals(n)) {
                            return "RestoreRoundedSlidersNoStopIndicator";
                        }

                        if ("hashCode".equals(n)) {
                            return System.identityHashCode(proxy);
                        }

                        if ("equals".equals(n)) {
                            return args != null
                                    && args.length == 1
                                    && proxy == args[0];
                        }

                        return null;
                    });

        } catch (Throwable t) {
            logOnceVolumeStopIndicatorFailure(
                    "REALVOL-V98 couldn't create no-op Function2: "
                            + t.getClass().getName()
                            + ": "
                            + String.valueOf(t.getMessage()));
            return null;
        }
    }

    private static Object makeNoOpFunction2(
            ClassLoader preferredLoader,
            Class<?>[] interfaces
    ) {
        try {
            Class<?> function2Class = null;

            if (interfaces != null) {
                for (Class<?> i : interfaces) {
                    if ("kotlin.jvm.functions.Function2".equals(i.getName())) {
                        function2Class = i;
                        break;
                    }
                }
            }

            if (function2Class == null) {
                return null;
            }

            ClassLoader loader = preferredLoader != null
                    ? preferredLoader
                    : function2Class.getClassLoader();

            Class<?> unitClass = Class.forName(
                    "kotlin.Unit",
                    false,
                    function2Class.getClassLoader());

            Field instanceField = unitClass.getDeclaredField("INSTANCE");
            instanceField.setAccessible(true);
            final Object unit = instanceField.get(null);

            return java.lang.reflect.Proxy.newProxyInstance(
                    loader,
                    new Class<?>[] { function2Class },
                    (proxy, method, args) -> {
                        String n = method.getName();

                        if ("invoke".equals(n)) {
                            return unit;
                        }

                        if ("toString".equals(n)) {
                            return "RestoreRoundedSlidersNoStopIndicator";
                        }

                        if ("hashCode".equals(n)) {
                            return System.identityHashCode(proxy);
                        }

                        if ("equals".equals(n)) {
                            return args != null
                                    && args.length == 1
                                    && proxy == args[0];
                        }

                        return null;
                    });
        } catch (Throwable t) {
            log("REALVOL-V98 couldn't create no-op stop indicator", t);
            return null;
        }
    }

    private static Object makeNoOpFunction3(
            ClassLoader preferredLoader,
            Class<?> function3Class
    ) {
        try {
            ClassLoader loader = preferredLoader != null
                    ? preferredLoader
                    : function3Class.getClassLoader();

            Class<?> unitClass = Class.forName(
                    "kotlin.Unit",
                    false,
                    function3Class.getClassLoader());

            Field instanceField = unitClass.getDeclaredField("INSTANCE");
            instanceField.setAccessible(true);
            final Object unit = instanceField.get(null);

            return java.lang.reflect.Proxy.newProxyInstance(
                    loader,
                    new Class<?>[] { function3Class },
                    (proxy, method, args) -> {
                        String n = method.getName();

                        if ("invoke".equals(n)) {
                            return unit;
                        }

                        if ("toString".equals(n)) {
                            return "RestoreRoundedSlidersNoThumb";
                        }

                        if ("hashCode".equals(n)) {
                            return System.identityHashCode(proxy);
                        }

                        if ("equals".equals(n)) {
                            return args != null
                                    && args.length == 1
                                    && proxy == args[0];
                        }

                        return null;
                    });
        } catch (Throwable t) {
            log("REALVOL-V98 couldn't create no-op thumb", t);
            return null;
        }
    }

    private static final String CUSTOM_VOLUME_SLIDER_KT =
            "com.android.systemui.volume.ui.compose.slider.SliderKt";

    private static final java.util.concurrent.atomic.AtomicBoolean
            customVolumeSliderWatcherInstalled =
                    new java.util.concurrent.atomic.AtomicBoolean(false);

    private static void installDeferredCustomVolumeSliderHook(
            ClassLoader initialCl
    ) {
        if (!customVolumeSliderWatcherInstalled.compareAndSet(false, true)) {
            return;
        }

        tryInstallCustomVolumeSliderHook(
                initialCl,
                "initial");

        XposedBridge.hookAllMethods(
                ClassLoader.class,
                "loadClass",
                new XC_MethodHook() {
                    @Override
                    protected void afterHookedMethod(MethodHookParam param) {
                        if (customVolumeThumbHookInstalled.get()) return;
                        if (param.args == null || param.args.length == 0) return;
                        if (!(param.args[0] instanceof String)) return;

                        String name = (String) param.args[0];
                        if (!CUSTOM_VOLUME_SLIDER_KT.equals(name)) return;

                        Object result = param.getResult();
                        if (!(result instanceof Class<?>)) return;

                        Class<?> loaded = (Class<?>) result;
                        ClassLoader actualCl = loaded.getClassLoader();

                        if (actualCl == null) {
                            actualCl = (ClassLoader) param.thisObject;
                        }

                        log("REALVOL-WRAP observed SliderKt load via "
                                + actualCl);

                        tryInstallCustomVolumeSliderHook(
                                actualCl,
                                "loadClass");
                    }
                });

        log("REALVOL-WRAP deferred wrapper watcher installed");
    }

    private static void tryInstallCustomVolumeSliderHook(
            ClassLoader cl,
            String source
    ) {
        if (cl == null || customVolumeThumbHookInstalled.get()) return;

        try {
            XposedHelpers.findClass(
                    CUSTOM_VOLUME_SLIDER_KT,
                    cl);
        } catch (Throwable ignored) {
            return;
        }

        log("REALVOL-WRAP installing wrapper hook from "
                + source);

        installCustomVolumeSliderThumbHook(cl);
    }

    private static final java.util.concurrent.atomic.AtomicBoolean
            customVolumeThumbHookInstalled =
                    new java.util.concurrent.atomic.AtomicBoolean(false);

    private static volatile boolean customVolumeSliderSignatureLogged;

    /**
     * Pixel's hardware volume dialog wraps Material3 VerticalSlider in:
     *
     * com.android.systemui.volume.ui.compose.slider.SliderKt.Slider
     *
     * First dump/hook that wrapper directly. The visible grabber is supplied
     * from this layer, not SliderDefaults.Track.
     */
    private static volatile boolean volumeOverlaySuppressedLogged;
    private static volatile boolean volumeOverlayFailureLogged;

    private static void logOnceVolumeOverlaySuppressed(String s) {
        if (volumeOverlaySuppressedLogged) return;
        volumeOverlaySuppressedLogged = true;
        log(s);
    }

    private static void logOnceVolumeOverlayFailure(String s) {
        if (volumeOverlayFailureLogged) return;
        volumeOverlayFailureLogged = true;
        log(s);
    }

    private static Object makeNoOpKotlinFunctionForExpectedType(
            Object original,
            Class<?> expectedType,
            ClassLoader fallbackLoader
    ) {
        try {
            if (expectedType == null
                    || !expectedType.isInterface()
                    || !expectedType.getName().startsWith(
                            "kotlin.jvm.functions.Function")) {
                return null;
            }

            ClassLoader loader = expectedType.getClassLoader();

            if (loader == null && original != null) {
                loader = original.getClass().getClassLoader();
            }

            if (loader == null) {
                loader = fallbackLoader;
            }

            Class<?> unitClass =
                    Class.forName(
                            "kotlin.Unit",
                            false,
                            loader);

            Field instance =
                    unitClass.getDeclaredField(
                            "INSTANCE");

            instance.setAccessible(true);
            final Object unit = instance.get(null);

            return java.lang.reflect.Proxy.newProxyInstance(
                    loader,
                    new Class<?>[] { expectedType },
                    (proxy, method, args) -> {
                        if ("invoke".equals(method.getName())) {
                            return unit;
                        }

                        if ("toString".equals(method.getName())) {
                            return "RestoreRoundedSlidersNoOverlayV85";
                        }

                        if ("hashCode".equals(method.getName())) {
                            return System.identityHashCode(proxy);
                        }

                        if ("equals".equals(method.getName())) {
                            return args != null
                                    && args.length == 1
                                    && proxy == args[0];
                        }

                        return null;
                    });
        } catch (Throwable ignored) {
            return null;
        }
    }

    private static boolean suppressVolumeOverlayBlock(Object wrapper) {
        try {
            Field blockField = findField(wrapper.getClass(), "_block");
            blockField.setAccessible(true);

            Object original = blockField.get(wrapper);
            if (original == null) return false;

            Class<?> functionInterface =
                    findKotlinFunctionInterface(original.getClass());

            if (functionInterface == null) {
                logOnceVolumeOverlayFailure(
                        "REALVOL-V98 FunctionN interface not found");
                return false;
            }

            ClassLoader loader = functionInterface.getClassLoader();
            if (loader == null) loader = original.getClass().getClassLoader();

            Class<?> unitClass =
                    Class.forName("kotlin.Unit", false, loader);
            Field instance = unitClass.getDeclaredField("INSTANCE");
            instance.setAccessible(true);
            final Object unit = instance.get(null);

            Object noOp = java.lang.reflect.Proxy.newProxyInstance(
                    loader,
                    new Class<?>[] { functionInterface },
                    (proxy, method, args) -> {
                        if ("invoke".equals(method.getName())) return unit;
                        if ("toString".equals(method.getName()))
                            return "RestoreRoundedSlidersNoVolumeOverlay";
                        if ("hashCode".equals(method.getName()))
                            return System.identityHashCode(proxy);
                        if ("equals".equals(method.getName()))
                            return args != null && args.length == 1 && proxy == args[0];
                        return null;
                    });

            blockField.set(wrapper, noOp);
            return true;
        } catch (Throwable t) {
            logOnceVolumeOverlayFailure(
                    "REALVOL-V98 overlay suppression failed: "
                            + t.getClass().getName());
            return false;
        }
    }

    private static Class<?> findKotlinFunctionInterface(Class<?> cls) {
        Class<?> c = cls;

        while (c != null) {
            for (Class<?> i : c.getInterfaces()) {
                if (i.getName().startsWith("kotlin.jvm.functions.Function")) {
                    return i;
                }
                Class<?> nested = findKotlinFunctionInterface(i);
                if (nested != null) return nested;
            }
            c = c.getSuperclass();
        }

        return null;
    }

    private static void dumpVolumeWrapperComposable(
            int index,
            Object composable
    ) {
        if (composable == null) return;

        log("REALVOL-COMP arg[" + index + "] wrapper="
                + composable.getClass().getName());

        Class<?> c = composable.getClass();

        while (c != null) {
            for (Field f : c.getDeclaredFields()) {
                try {
                    f.setAccessible(true);
                    Object value = f.get(composable);

                    if (value == null) continue;

                    String fn = f.getName();
                    String runtime = value.getClass().getName();

                    if ("_block".equals(fn)
                            || runtime.startsWith("com.android.systemui")
                            || runtime.startsWith("com.google.android.systemui")) {

                        log("REALVOL-COMP arg[" + index + "] "
                                + c.getName()
                                + "#"
                                + fn
                                + "="
                                + runtime);

                        dumpVolumeComposableMethods(
                                index,
                                value);
                    }
                } catch (Throwable ignored) {
                }
            }

            c = c.getSuperclass();
        }
    }

    private static void dumpVolumeComposableMethods(
            int index,
            Object object
    ) {
        if (object == null) return;

        Class<?> cls = object.getClass();

        for (Method m : cls.getDeclaredMethods()) {
            String n = m.getName();

            if ("invoke".equals(n)
                    || n.contains("invoke")
                    || n.contains("Thumb")
                    || n.contains("Track")
                    || n.contains("Indicator")) {

                log("REALVOL-COMP arg[" + index + "] method "
                        + methodSignatureForVolumeDiag(m));
            }
        }

        Class<?> enclosing = cls.getEnclosingClass();
        if (enclosing != null) {
            log("REALVOL-COMP arg[" + index + "] enclosing="
                    + enclosing.getName());
        }
    }

    private static void installCustomVolumeSliderThumbHook(
            ClassLoader cl
    ) {
        if (!customVolumeThumbHookInstalled.compareAndSet(false, true)) {
            return;
        }

        Class<?> kt = XposedHelpers.findClass(
                "com.android.systemui.volume.ui.compose.slider.SliderKt",
                cl);

        int hooked = 0;

        for (Method m : kt.getDeclaredMethods()) {
            String mn = m.getName();

            if (mn.contains("Slider")) {
                log("REALVOL-WRAP method "
                        + methodSignatureForVolumeDiag(m));
            }

            if (!mn.contains("Slider")) continue;

            log("REALVOL-THUMB method "
                    + methodSignatureForVolumeDiag(m));

            XposedBridge.hookMethod(m, new XC_MethodHook() {
                @Override
                protected void beforeHookedMethod(MethodHookParam param) {
                    if (!isRealVolumeWrapperStack()
                            && !isAnySystemUiVolumeStack()) {
                        return;
                    }

                    if (!customVolumeSliderSignatureLogged) {
                        customVolumeSliderSignatureLogged = true;
                        log("REALVOL-THUMB wrapper invoked: "
                                + methodSignatureForVolumeDiag(m));

                        if (param.args != null) {
                            for (int i = 0; i < param.args.length; i++) {
                                Object arg = param.args[i];
                                log("REALVOL-THUMB arg[" + i + "]="
                                        + (arg == null
                                                ? "null"
                                                : arg.getClass().getName()));

                                if ((i == 14 || i == 15) && arg != null) {
                                    dumpVolumeWrapperComposable(i, arg);
                                }
                            }
                        }
                    }

                    // The second SystemUI composable is the idle/interaction
                    // grabber overlay. Suppress it only when the setting is off.
                    if (!SliderConfig.volume().grabber
                            && param.args != null
                            && param.args.length > 15
                            && param.args[15] != null) {

                        boolean suppressed = false;

                        Class<?>[] parameterTypes =
                                m.getParameterTypes();

                        if (parameterTypes.length > 15) {
                            Object directNoOp =
                                    makeNoOpKotlinFunctionForExpectedType(
                                            param.args[15],
                                            parameterTypes[15],
                                            m.getDeclaringClass().getClassLoader());

                            if (directNoOp != null) {
                                param.args[15] = directNoOp;
                                suppressed = true;
                            }
                        }

                        if (!suppressed) {
                            suppressed =
                                    suppressVolumeOverlayBlock(
                                            param.args[15]);
                        }

                        if (suppressed) {
                            logOnceVolumeOverlaySuppressed(
                                    "REALVOL-V98 interaction overlay suppressed");
                        }
                    }
                }
            });

            hooked++;
        }

        log("REALVOL-THUMB wrapper Slider methods hooked: " + hooked);
    }

    private static final java.util.concurrent.atomic.AtomicBoolean
            directVolumeTrackHookInstalled =
                    new java.util.concurrent.atomic.AtomicBoolean(false);

    private static volatile boolean directVolumeTrackHitLogged;
    private static volatile boolean directVolumeTrackStackLogged;

    /**
     * The hardware-volume UI does not call the public Material3 Slider(...)
     * overloads on this build. Detect it at SliderDefaults.Track/TrackImpl,
     * which is the geometry layer that brightness already uses successfully.
     */
    private static void installDirectVolumeTrackDetection(
            ClassLoader cl
    ) {
        if (!directVolumeTrackHookInstalled.compareAndSet(false, true)) {
            return;
        }

        Class<?> defaults = XposedHelpers.findClass(
                "androidx.compose.material3.SliderDefaults",
                cl);

        int hooked = 0;

        for (Method m : defaults.getDeclaredMethods()) {
            String name = m.getName();

            if (!name.startsWith("Track")) continue;

            XposedBridge.hookMethod(m, new XC_MethodHook() {
                private final ThreadLocal<Boolean> volumeCall =
                        new ThreadLocal<>();

                @Override
                protected void beforeHookedMethod(MethodHookParam param) {
                    boolean isVolume = isRealVolumeTrackStack();

                    volumeCall.set(isVolume);

                    if (!isVolume) return;

                    SliderConfig volumeConfig = SliderConfig.volume();

                    SliderContext.set(
                            volumeConfig,
                            0f);

                    /*
                     * Cache the actual Material3 active/inactive track colors.
                     * Needed only for the rare exact-0 frame where Material3
                     * has not emitted an active drawTrackPath callback yet.
                     */
                    if (param.args != null
                            && param.args.length > 4
                            && param.args[4] != null) {
                        Object active =
                                readSliderTrackColorV80(
                                        param.args[4],
                                        true);
                        Object inactive =
                                readSliderTrackColorV80(
                                        param.args[4],
                                        false);

                        if (active != null) {
                            volumeV80ActiveTrackColor = active;
                        }

                        if (inactive != null) {
                            volumeV80InactiveTrackColor = inactive;
                        }
                    }

                    /*
                     * Runtime signature observed on the validated Pixel build:
                     *
                     * Track-mnvyFg4(
                     *   0 SliderState,
                     *   1 float trackCornerSize,
                     *   2 Modifier,
                     *   3 boolean enabled,
                     *   4 SliderColors,
                     *   5 Function2,
                     *   6 Function3,
                     *   7 float thumbTrackGapSize,
                     *   8 float trackInsideCornerSize,
                     *   ...
                     * )
                     *
                     * Remove the reserved thumb/grabber notch directly on the
                     * real volume track. This does not alter SliderState/value.
                     */
                    if (param.args != null
                            && param.args.length > 8
                            && param.args[7] instanceof Float
                            && param.args[8] instanceof Float) {

                        float fraction =
                                param.args.length > 0
                                        ? readSliderFraction(param.args[0])
                                        : Float.NaN;

                        boolean minimumClamped =
                                param.args.length > 0
                                        && isMinimumFillClampedCompat(
                                                param.args[0]);

                        boolean endpoint =
                                minimumClamped
                                        || (!Float.isNaN(fraction)
                                                && (fraction <= 0.0005f
                                                        || fraction >= 0.9995f));

                        /*
                         * Grabber ON + 1-99%:
                         * leave Material3's stock gap/inside-corner untouched.
                         *
                         * Grabber OFF:
                         * stable v80 continuous classic track.
                         *
                         * Grabber ON + endpoint:
                         * remove the gap immediately so the static circle/full
                         * pill meets the stock grabber without an animation.
                         */
                        if (!volumeConfig.grabber || endpoint) {
                            param.args[7] = 0f;
                            param.args[8] = 0f;
                        }

                        if (!volumeConfig.grabber) {
                            /*
                             * Track-mnvyFg4 arg 5 is drawStopIndicator(Function2).
                             */
                            if (param.args.length > 5 && param.args[5] != null) {
                                Object noStopIndicator =
                                        makeNoOpFunction2Robust(
                                                param.args[5],
                                                m.getDeclaringClass().getClassLoader());

                                if (noStopIndicator != null) {
                                    param.args[5] = noStopIndicator;
                                    logOnceVolumeStopIndicatorSuppressed(
                                            "REALVOL-V98 stop indicator suppressed");
                                }
                            }
                        }
                    }

                    /*
                     * Find SliderState directly in Track/TrackImpl arguments and
                     * register it as volume for the existing classic draw-path
                     * renderer.
                     */
                    if (param.args != null) {
                        for (Object arg : param.args) {
                            if (arg == null) continue;

                            if ("androidx.compose.material3.SliderState"
                                    .equals(arg.getClass().getName())) {
                                try {
                                    registerRoundedSliderStateCompat(
                                            arg,
                                            "volume");
                                } catch (Throwable ignored) {
                                }
                            }
                        }
                    }

                    if (!directVolumeTrackHitLogged) {
                        directVolumeTrackHitLogged = true;
                        log("REALVOL-TRACK detected volume Track: "
                                + methodSignatureForVolumeDiag(m));
                    }

                    if (!directVolumeTrackStackLogged) {
                        directVolumeTrackStackLogged = true;
                        dumpRealVolumeTrackStack();
                    }
                }

                @Override
                protected void afterHookedMethod(MethodHookParam param) {
                    Boolean ours = volumeCall.get();
                    volumeCall.remove();

                    if (Boolean.TRUE.equals(ours)) {
                        SliderContext.clear();
                    }
                }
            });

            hooked++;
        }

        log("REALVOL-TRACK Track/TrackImpl methods hooked: "
                + hooked);
    }

    private static boolean isRealVolumeWrapperStack() {
        StackTraceElement[] stack =
                Thread.currentThread().getStackTrace();

        for (StackTraceElement frame : stack) {
            String cn = frame.getClassName();

            if (cn.startsWith(
                    "com.android.systemui.volume.dialog.sliders")
                    || cn.startsWith(
                    "com.android.systemui.volume.ui.compose.slider")
                    || cn.contains("VolumeDialogSlider")) {
                return true;
            }
        }

        return false;
    }

    /**
     * PixelXpert is loaded after SystemUI has already composed Quick Settings on
     * some builds. In that case the original outer BrightnessSlider hook did
     * not see the cached SliderState to register it. Identify only that draw
     * path here, so the original endpoint renderer can take over its 0/100%
     * frames just as it does in the standalone module.
     */
    private static boolean isBrightnessSliderStack() {
        for (StackTraceElement frame : Thread.currentThread().getStackTrace()) {
            String className = frame.getClassName();
            if (className.startsWith("com.android.systemui.brightness.")) {
                return true;
            }
        }
        return false;
    }

    private static boolean isRealVolumeTrackStack() {
        StackTraceElement[] stack =
                Thread.currentThread().getStackTrace();

        for (StackTraceElement frame : stack) {
            String cn = frame.getClassName();

            if (cn.startsWith(
                    "com.android.systemui.volume.dialog.sliders")
                    || cn.contains(
                            "VolumeDialogSliderViewBinder")
                    || cn.contains(
                            "VolumeDialogSlider")) {
                return true;
            }
        }

        return false;
    }

    private static void dumpRealVolumeTrackStack() {
        StackTraceElement[] stack =
                Thread.currentThread().getStackTrace();

        for (int i = 0; i < stack.length; i++) {
            String cn = stack[i].getClassName();

            if (cn.startsWith("com.android.systemui")
                    || cn.startsWith("com.google.android.systemui")
                    || cn.startsWith("androidx.compose.material3")
                    || cn.contains("volume")
                    || cn.contains("Volume")) {
                log("REALVOL-TRACK stack #"
                        + i
                        + " "
                        + stack[i]);
            }
        }
    }

    private static final java.util.concurrent.atomic.AtomicBoolean
            material3VolumeSliderHookInstalled =
                    new java.util.concurrent.atomic.AtomicBoolean(false);

    /**
     * Generated SystemUI binder methods are aggressively optimized and may not
     * retain explicit Composer methods. Material3 Slider itself is a stable
     * hook point.
     *
     * Detect the real hardware-volume slider by its call stack and wrap that
     * Slider(...) invocation in SliderContext.volume().
     */
    private static void installMaterial3VolumeSliderContextHook(
            ClassLoader cl
    ) {
        if (!material3VolumeSliderHookInstalled.compareAndSet(false, true)) {
            return;
        }

        Class<?> sliderKt = XposedHelpers.findClass(
                "androidx.compose.material3.SliderKt",
                cl);

        int hooked = 0;

        for (Method m : sliderKt.getDeclaredMethods()) {
            if (!"Slider".equals(m.getName())) continue;

            XposedBridge.hookMethod(m, new XC_MethodHook() {
                private final ThreadLocal<Boolean> ours =
                        new ThreadLocal<>();

                @Override
                protected void beforeHookedMethod(MethodHookParam param) {
                    if (!isVolumeDialogCallStack()) {
                        ours.set(Boolean.FALSE);
                        return;
                    }

                    ours.set(Boolean.TRUE);

                    SliderContext.set(
                            SliderConfig.volume(),
                            0f);

                    logOnceMaterial3VolumeSliderHit(
                            "REALVOL-M3 Material3 Slider detected from VolumeDialog: "
                                    + methodSignatureForVolumeDiag(m));

                    /*
                     * If a SliderState is directly present in this overload,
                     * register it immediately for the final Track renderer.
                     */
                    if (param.args != null) {
                        for (Object arg : param.args) {
                            if (arg == null) continue;

                            if ("androidx.compose.material3.SliderState"
                                    .equals(arg.getClass().getName())) {
                                try {
                                    registerRoundedSliderStateCompat(
                                            arg,
                                            "volume");
                                } catch (Throwable ignored) {
                                }
                            }
                        }
                    }
                }

                @Override
                protected void afterHookedMethod(MethodHookParam param) {
                    Boolean active = ours.get();
                    ours.remove();

                    if (Boolean.TRUE.equals(active)) {
                        SliderContext.clear();
                    }
                }
            });

            hooked++;
        }

        log("REALVOL-M3 Material3 Slider overloads hooked: "
                + hooked);
    }

    private static boolean isAnySystemUiVolumeStack() {
        StackTraceElement[] stack =
                Thread.currentThread().getStackTrace();

        for (StackTraceElement frame : stack) {
            String cn = frame.getClassName();

            if (cn.startsWith("com.android.systemui.volume.")
                    || cn.startsWith("com.google.android.systemui.volume.")
                    || cn.contains(".volume.")
                    || cn.contains("VolumeDialog")) {
                return true;
            }
        }

        return false;
    }

    private static boolean isVolumeDialogCallStack() {
        StackTraceElement[] stack =
                Thread.currentThread().getStackTrace();

        for (StackTraceElement frame : stack) {
            String cn = frame.getClassName();

            if (cn.startsWith(
                    "com.android.systemui.volume.dialog.sliders")
                    || cn.contains(
                            "VolumeDialogSliderViewBinder")) {
                return true;
            }
        }

        return false;
    }

    private static volatile boolean material3VolumeSliderHitLogged;

    private static void logOnceMaterial3VolumeSliderHit(
            String message
    ) {
        if (material3VolumeSliderHitLogged) return;
        material3VolumeSliderHitLogged = true;
        log(message);
    }

    private static final java.util.concurrent.atomic.AtomicBoolean
            innerVolumeComposableHooksInstalled =
                    new java.util.concurrent.atomic.AtomicBoolean(false);

    /**
     * The outer ComposableLambdaImpl can return before nested composition work
     * runs. Hook the generated binder methods that actually accept Composer.
     * Those execute while the Material3 / platform slider is being emitted.
     */
    private static void installInnerVolumeComposableHooks(
            ClassLoader cl
    ) {
        if (!innerVolumeComposableHooksInstalled.compareAndSet(false, true)) {
            return;
        }

        Class<?> binder = XposedHelpers.findClass(
                "com.android.systemui.volume.dialog.sliders.ui.VolumeDialogSliderViewBinder",
                cl);

        int hooked = 0;

        for (Method m : binder.getDeclaredMethods()) {
            Class<?>[] params = m.getParameterTypes();

            boolean hasComposer = false;
            for (Class<?> p : params) {
                if ("androidx.compose.runtime.Composer".equals(p.getName())) {
                    hasComposer = true;
                    break;
                }
            }

            if (!hasComposer) {
                continue;
            }

            log("REALVOL-INNER composable method "
                    + methodSignatureForVolumeDiag(m));

            XposedBridge.hookMethod(m, new XC_MethodHook() {
                @Override
                protected void beforeHookedMethod(MethodHookParam param) {
                    SliderConfig cfg = SliderConfig.volume();
                    SliderContext.set(cfg, 0f);

                    logOnceInnerVolumeHit(
                            "REALVOL-INNER composable context active: "
                                    + m.getName());
                }

                @Override
                protected void afterHookedMethod(MethodHookParam param) {
                    SliderContext.clear();
                }
            });

            hooked++;
        }

        log("REALVOL-INNER Composer methods hooked: " + hooked);
    }

    private static volatile boolean innerVolumeHitLogged;

    private static void logOnceInnerVolumeHit(String message) {
        if (innerVolumeHitLogged) return;
        innerVolumeHitLogged = true;
        log(message);
    }

    /**
     * The real hardware-button volume slider is rendered by this synthetic
     * Compose lambda. Wrap the lambda invocation in SliderContext.volume() so
     * the existing Material3 Track/TrackImpl hooks apply the same geometry that
     * already works for brightness.
     */
    private static void installRealVolumeContentHook(
            ClassLoader initialCl
    ) {
        tryInstallRealVolumeContentHook(
                initialCl,
                "initial");

        XposedBridge.hookAllMethods(
                ClassLoader.class,
                "loadClass",
                new XC_MethodHook() {
                    @Override
                    protected void afterHookedMethod(MethodHookParam param) {
                        if (realVolumeContentHookInstalled.get()) return;
                        if (param.args == null || param.args.length == 0) return;
                        if (!(param.args[0] instanceof String)) return;

                        String name = (String) param.args[0];
                        if (!REAL_VOLUME_CONTENT_LAMBDA.equals(name)) return;

                        Object result = param.getResult();
                        if (!(result instanceof Class<?>)) return;

                        Class<?> loaded = (Class<?>) result;
                        ClassLoader actualCl = loaded.getClassLoader();

                        if (actualCl == null) {
                            actualCl = (ClassLoader) param.thisObject;
                        }

                        tryInstallRealVolumeContentHook(
                                actualCl,
                                "loadClass");
                    }
                });

        log("REALVOL-FIX content-lambda watcher installed");
    }

    private static void tryInstallRealVolumeContentHook(
            ClassLoader cl,
            String source
    ) {
        if (cl == null || realVolumeContentHookInstalled.get()) return;

        Class<?> lambdaClass;
        try {
            lambdaClass = XposedHelpers.findClass(
                    REAL_VOLUME_CONTENT_LAMBDA,
                    cl);
        } catch (Throwable ignored) {
            return;
        }

        if (!realVolumeContentHookInstalled.compareAndSet(false, true)) {
            return;
        }

        int hooks = 0;

        for (Method m : lambdaClass.getDeclaredMethods()) {
            if (!"invoke".equals(m.getName())) continue;

            XposedBridge.hookMethod(m, new XC_MethodHook() {
                @Override
                protected void beforeHookedMethod(MethodHookParam param) {
                    SliderConfig cfg = SliderConfig.volume();

                    /*
                     * The exact track height is learned later from Material3's
                     * TrackImpl invocation. A 0 placeholder is fine here; the
                     * geometry hook computes the real radius from runtime args.
                     */
                    SliderContext.set(cfg, 0f);

                    logOnceRealVolumeComposeHit(
                            "REALVOL-FIX volume Compose context active");
                }

                @Override
                protected void afterHookedMethod(MethodHookParam param) {
                    SliderContext.clear();
                }
            });

            hooks++;
        }

        log("REALVOL-FIX content hook installed from "
                + source
                + ": methods="
                + hooks);
    }

    private static volatile boolean realVolumeComposeHitLogged;

    private static void logOnceRealVolumeComposeHit(String message) {
        if (realVolumeComposeHitLogged) return;
        realVolumeComposeHitLogged = true;
        log(message);
    }

    private static final java.util.concurrent.atomic.AtomicBoolean
            realBindSliderLogged =
                    new java.util.concurrent.atomic.AtomicBoolean(false);

    private static void installRealBindSliderTrace(ClassLoader cl) {
        final String binderName =
                "com.android.systemui.volume.dialog.sliders.ui.VolumeDialogSlidersViewBinder";

        Class<?> binder = XposedHelpers.findClass(
                binderName,
                cl);

        int hooks = 0;

        for (Method m : binder.getDeclaredMethods()) {
            String name = m.getName();

            if (!name.contains("bindSlider")) continue;

            log("REALVOL-BIND method "
                    + methodSignatureForVolumeDiag(m));

            XposedBridge.hookMethod(m, new XC_MethodHook() {
                @Override
                protected void beforeHookedMethod(MethodHookParam param) {
                    if (realBindSliderLogged.get()) return;

                    if (!realBindSliderLogged.compareAndSet(false, true)) {
                        return;
                    }

                    log("REALVOL-BIND invoked "
                            + methodSignatureForVolumeDiag(m));

                    if (param.thisObject != null) {
                        log("REALVOL-BIND this="
                                + param.thisObject.getClass().getName());
                    }

                    if (param.args != null) {
                        for (int i = 0; i < param.args.length; i++) {
                            Object arg = param.args[i];

                            if (arg == null) {
                                log("REALVOL-BIND arg[" + i + "]=null");
                                continue;
                            }

                            Class<?> ac = arg.getClass();

                            log("REALVOL-BIND arg[" + i + "]="
                                    + ac.getName()
                                    + " value="
                                    + safeValueForBind(arg));

                            dumpInterestingBindObject(
                                    "REALVOL-BIND arg[" + i + "]",
                                    arg);
                        }
                    }
                }
            });

            hooks++;
        }

        log("REALVOL-BIND hooks installed: " + hooks);
    }

    private static String safeValueForBind(Object o) {
        if (o == null) return "null";

        try {
            String s = String.valueOf(o);
            if (s.length() > 300) {
                return s.substring(0, 300) + "...";
            }
            return s;
        } catch (Throwable ignored) {
            return "<toString failed>";
        }
    }

    private static void dumpInterestingBindObject(
            String prefix,
            Object object
    ) {
        if (object == null) return;

        Class<?> c = object.getClass();
        String cn = c.getName();

        boolean interestingRoot =
                cn.startsWith("com.android.systemui")
                        || cn.startsWith("com.google.android.systemui")
                        || cn.startsWith("androidx.compose")
                        || cn.startsWith("kotlin.jvm.functions");

        if (!interestingRoot) return;

        Class<?> current = c;
        int depth = 0;

        while (current != null && depth < 4) {
            for (Field f : current.getDeclaredFields()) {
                String fn = f.getName().toLowerCase(Locale.ROOT);

                if (!(fn.contains("slider")
                        || fn.contains("state")
                        || fn.contains("value")
                        || fn.contains("dimension")
                        || fn.contains("track")
                        || fn.contains("thumb")
                        || fn.contains("content")
                        || fn.contains("viewmodel")
                        || fn.contains("model"))) {
                    continue;
                }

                try {
                    f.setAccessible(true);
                    Object value = f.get(object);

                    log(prefix
                            + " field "
                            + current.getName()
                            + "#"
                            + f.getName()
                            + ":"
                            + f.getType().getName()
                            + "="
                            + (value == null
                                ? "null"
                                : value.getClass().getName()
                                    + " "
                                    + safeValueForBind(value)));
                } catch (Throwable ignored) {
                }
            }

            current = current.getSuperclass();
            depth++;
        }
    }

    private static final java.util.Set<Object> volumeContentStates =
            java.util.Collections.newSetFromMap(
                    java.util.Collections.synchronizedMap(
                            new java.util.WeakHashMap<>()));

    private static final java.util.concurrent.atomic.AtomicBoolean
            volumeContentAssignmentLogged =
                    new java.util.concurrent.atomic.AtomicBoolean(false);

    /**
     * ComposeView.content is a MutableState whose value is still null when the
     * view first attaches. Remember that exact state object, then intercept its
     * later setValue(...) call. That gives us the real composable content
     * lambda at assignment time.
     */
    private static void installVolumeContentStateAssignmentTrace(
            ClassLoader cl
    ) {
        Class<?> stateClass = XposedHelpers.findClass(
                "androidx.compose.runtime.ParcelableSnapshotMutableState",
                cl);

        int hooks = 0;

        for (Method m : stateClass.getDeclaredMethods()) {
            if (!"setValue".equals(m.getName())) continue;

            XposedBridge.hookMethod(m, new XC_MethodHook() {
                @Override
                protected void beforeHookedMethod(MethodHookParam param) {
                    if (!volumeContentStates.contains(param.thisObject)) {
                        return;
                    }

                    if (volumeContentAssignmentLogged.get()) return;

                    Object value =
                            param.args != null && param.args.length > 0
                                    ? param.args[0]
                                    : null;

                    if (value == null) {
                        log("REALVOL-ASSIGN volume content setValue(null)");
                        return;
                    }

                    if (!volumeContentAssignmentLogged.compareAndSet(false, true)) {
                        return;
                    }

                    log("REALVOL-ASSIGN content assigned class="
                            + value.getClass().getName());

                    dumpLambdaMetadata(value.getClass());
                    dumpCapturedLambdaFields(value);
                    unwrapComposableLambda(value);

                    StackTraceElement[] stack =
                            Thread.currentThread().getStackTrace();

                    for (int i = 0; i < stack.length; i++) {
                        StackTraceElement frame = stack[i];
                        String cn = frame.getClassName();

                        if (cn.startsWith("com.android.systemui")
                                || cn.startsWith("com.google.android.systemui")
                                || cn.contains("volume")
                                || cn.contains("Volume")) {
                            log("REALVOL-ASSIGN stack #"
                                    + i
                                    + " "
                                    + frame);
                        }
                    }
                }
            });

            hooks++;
        }

        log("REALVOL-ASSIGN MutableState.setValue hooks installed: "
                + hooks);
    }

    private static void rememberVolumeContentState(Object composeView) {
        if (composeView == null) return;

        Class<?> c = composeView.getClass();

        while (c != null) {
            try {
                Field f = c.getDeclaredField("content");
                f.setAccessible(true);

                Object state = f.get(composeView);

                if (state != null) {
                    volumeContentStates.add(state);

                    log("REALVOL-ASSIGN remembered content state="
                            + state.getClass().getName());
                }

                return;
            } catch (NoSuchFieldException ignored) {
                c = c.getSuperclass();
            } catch (Throwable t) {
                log("REALVOL-ASSIGN failed remembering content state", t);
                return;
            }
        }
    }

    private static final java.util.concurrent.atomic.AtomicBoolean
            volumeComposeFieldLogged =
                    new java.util.concurrent.atomic.AtomicBoolean(false);

    /**
     * setContent() may be inlined/optimized on SystemUIGoogle. Instead hook
     * AbstractComposeView composition lifecycle methods and inspect the concrete
     * ComposeView's private fields once volume_dialog_slider is creating its
     * composition.
     */
    private static void installVolumeComposeViewFieldTrace(
            ClassLoader cl
    ) {
        Class<?> abstractComposeView = XposedHelpers.findClass(
                "androidx.compose.ui.platform.AbstractComposeView",
                cl);

        int hooks = 0;

        for (Method m : abstractComposeView.getDeclaredMethods()) {
            String name = m.getName();

            if (!name.contains("ensureCompositionCreated")
                    && !name.contains("createComposition")
                    && !name.contains("onAttachedToWindow")) {
                continue;
            }

            XposedBridge.hookMethod(m, new XC_MethodHook() {
                @Override
                protected void beforeHookedMethod(MethodHookParam param) {
                    traceVolumeComposeView(param.thisObject, "before:" + m.getName());
                }

                @Override
                protected void afterHookedMethod(MethodHookParam param) {
                    traceVolumeComposeView(param.thisObject, "after:" + m.getName());
                }
            });

            hooks++;
        }

        /*
         * ComposeView itself may also have a generated Content(...) composable
         * method. Hook anything named Content regardless of mangling.
         */
        try {
            Class<?> composeView = XposedHelpers.findClass(
                    "androidx.compose.ui.platform.ComposeView",
                    cl);

            for (Method m : composeView.getDeclaredMethods()) {
                if (!m.getName().contains("Content")) continue;

                XposedBridge.hookMethod(m, new XC_MethodHook() {
                    @Override
                    protected void beforeHookedMethod(MethodHookParam param) {
                        traceVolumeComposeView(
                                param.thisObject,
                                "ComposeView:" + m.getName());
                    }
                });

                hooks++;
            }
        } catch (Throwable t) {
            log("REALVOL-CONTENT ComposeView Content hook unavailable");
        }

        log("REALVOL-CONTENT composition lifecycle hooks installed: "
                + hooks);
    }

    private static void traceVolumeComposeView(
            Object object,
            String source
    ) {
        if (volumeComposeFieldLogged.get()) return;
        if (!(object instanceof android.view.View)) return;

        android.view.View view = (android.view.View) object;

        if (!isVolumeDialogComposeView(view)) {
            return;
        }

        /*
         * Remember this state's identity every time we hit the correct
         * ComposeView, even if the verbose field dump has already run.
         */
        rememberVolumeContentState(object);

        if (!volumeComposeFieldLogged.compareAndSet(false, true)) {
            return;
        }

        log("REALVOL-CONTENT hit " + source);
        log("REALVOL-CONTENT view class="
                + view.getClass().getName());

        dumpComposeViewFields(object);

        StackTraceElement[] stack =
                Thread.currentThread().getStackTrace();

        for (int i = 0; i < stack.length; i++) {
            StackTraceElement frame = stack[i];
            String cn = frame.getClassName();

            if (cn.startsWith("com.android.systemui")
                    || cn.startsWith("com.google.android.systemui")
                    || cn.contains("volume")
                    || cn.contains("Volume")) {
                log("REALVOL-CONTENT stack #" + i + " " + frame);
            }
        }
    }

    private static boolean isVolumeDialogComposeView(
            android.view.View view
    ) {
        try {
            int id = view.getId();
            if (id == android.view.View.NO_ID) return false;

            String name =
                    view.getResources().getResourceEntryName(id);

            return "volume_dialog_slider".equals(name);
        } catch (Throwable ignored) {
            return false;
        }
    }

    private static void dumpComposeViewFields(Object object) {
        if (object == null) return;

        Class<?> c = object.getClass();

        while (c != null) {
            for (Field f : c.getDeclaredFields()) {
                String fieldName = f.getName();

                if (!"content".equals(fieldName)) {
                    continue;
                }

                try {
                    f.setAccessible(true);
                    Object state = f.get(object);

                    log("REALVOL-STATE content field type="
                            + f.getType().getName());

                    if (state == null) {
                        log("REALVOL-STATE content state is null");
                        return;
                    }

                    log("REALVOL-STATE runtime class="
                            + state.getClass().getName());

                    dumpComposeStateInternals(state);
                    return;

                } catch (Throwable t) {
                    log("REALVOL-STATE failed reading ComposeView.content", t);
                    return;
                }
            }

            c = c.getSuperclass();
        }

        log("REALVOL-STATE ComposeView.content field not found");
    }

    private static void dumpComposeStateInternals(Object state) {
        if (state == null) return;

        Class<?> c = state.getClass();

        while (c != null) {
            log("REALVOL-STATE inspect class=" + c.getName());

            for (Method m : c.getDeclaredMethods()) {
                try {
                    log("REALVOL-STATE method "
                            + methodSignatureForVolumeDiag(m));
                } catch (Throwable ignored) {
                }
            }

            for (Field f : c.getDeclaredFields()) {
                try {
                    f.setAccessible(true);
                    Object value = f.get(state);

                    log("REALVOL-STATE field "
                            + c.getName()
                            + "#"
                            + f.getName()
                            + ":"
                            + f.getType().getName()
                            + "="
                            + (value == null
                                ? "null"
                                : value.getClass().getName()));
                } catch (Throwable ignored) {
                }
            }

            c = c.getSuperclass();
        }

        /*
         * Also test every zero-arg non-void method and log the runtime class
         * returned. We do not assume the accessor is literally getValue().
         */
        c = state.getClass();

        while (c != null) {
            for (Method m : c.getDeclaredMethods()) {
                if (m.getParameterTypes().length != 0) continue;
                if (m.getReturnType() == void.class) continue;

                String n = m.getName();

                if (n.equals("hashCode")
                        || n.equals("toString")
                        || n.equals("getClass")
                        || n.equals("clone")) {
                    continue;
                }

                try {
                    m.setAccessible(true);
                    Object value = m.invoke(state);

                    log("REALVOL-STATE invoke "
                            + c.getName()
                            + "#"
                            + n
                            + "() -> "
                            + (value == null
                                ? "null"
                                : value.getClass().getName()));

                    if (value != null
                            && value != state
                            && !(value instanceof Number)
                            && !(value instanceof Boolean)
                            && !(value instanceof String)
                            && !value.getClass().getName().startsWith("androidx.compose.runtime")) {

                        log("REALVOL-STATE candidate content="
                                + value.getClass().getName());

                        dumpLambdaMetadata(value.getClass());
                        dumpCapturedLambdaFields(value);
                    }

                } catch (Throwable t) {
                    log("REALVOL-STATE invoke failed "
                            + c.getName()
                            + "#"
                            + n
                            + "(): "
                            + t.getClass().getName());
                }
            }

            c = c.getSuperclass();
        }
    }

    private static void unwrapComposableLambda(Object wrapper) {
        if (wrapper == null) return;

        Class<?> c = wrapper.getClass();

        while (c != null) {
            for (Field f : c.getDeclaredFields()) {
                try {
                    f.setAccessible(true);
                    Object value = f.get(wrapper);

                    if (value == null) continue;

                    String fieldName = f.getName();
                    String typeName = f.getType().getName();
                    String runtimeName = value.getClass().getName();

                    log("REALVOL-LAMBDA field "
                            + c.getName()
                            + "#"
                            + fieldName
                            + ":"
                            + typeName
                            + "="
                            + runtimeName);

                    boolean looksLikeFunction =
                            typeName.startsWith("kotlin.jvm.functions.")
                                    || runtimeName.contains("Function")
                                    || runtimeName.contains("Lambda")
                                    || runtimeName.contains("Composable")
                                    || runtimeName.startsWith("com.android.systemui")
                                    || runtimeName.startsWith("com.google.android.systemui");

                    if (!looksLikeFunction) continue;

                    log("REALVOL-LAMBDA candidate="
                            + runtimeName);

                    dumpLambdaMetadata(value.getClass());
                    dumpCapturedLambdaFields(value);

                    /*
                     * One extra level is useful because ComposableLambdaImpl
                     * often stores another wrapper/function object internally.
                     */
                    dumpNestedFunctionFields(value, 1);

                } catch (Throwable ignored) {
                }
            }

            c = c.getSuperclass();
        }
    }

    private static void dumpNestedFunctionFields(
            Object object,
            int depth
    ) {
        if (object == null || depth > 3) return;

        Class<?> c = object.getClass();

        while (c != null
                && !c.getName().equals("java.lang.Object")) {

            for (Field f : c.getDeclaredFields()) {
                try {
                    f.setAccessible(true);
                    Object value = f.get(object);

                    if (value == null || value == object) continue;

                    String typeName = f.getType().getName();
                    String runtimeName = value.getClass().getName();

                    boolean interesting =
                            typeName.startsWith("kotlin.jvm.functions.")
                                    || runtimeName.contains("Lambda")
                                    || runtimeName.contains("Function")
                                    || runtimeName.contains("Composable")
                                    || runtimeName.startsWith("com.android.systemui")
                                    || runtimeName.startsWith("com.google.android.systemui");

                    if (!interesting) continue;

                    log("REALVOL-LAMBDA nested[" + depth + "] "
                            + c.getName()
                            + "#"
                            + f.getName()
                            + "="
                            + runtimeName);

                    dumpLambdaMetadata(value.getClass());
                    dumpCapturedLambdaFields(value);

                    dumpNestedFunctionFields(
                            value,
                            depth + 1);

                } catch (Throwable ignored) {
                }
            }

            c = c.getSuperclass();
        }
    }

    private static void dumpCapturedLambdaFields(Object lambda) {
        if (lambda == null) return;

        Class<?> c = lambda.getClass();

        while (c != null
                && !c.getName().startsWith("kotlin.jvm.internal")) {

            for (Field f : c.getDeclaredFields()) {
                try {
                    f.setAccessible(true);
                    Object value = f.get(lambda);

                    if (value == null) continue;

                    String valueClass = value.getClass().getName();

                    log("REALVOL-STATE captured "
                            + c.getName()
                            + "#"
                            + f.getName()
                            + ":"
                            + f.getType().getName()
                            + "="
                            + valueClass);

                    if (valueClass.startsWith("com.android.systemui")
                            || valueClass.startsWith("com.google.android.systemui")) {
                        dumpLambdaMetadata(value.getClass());
                    }
                } catch (Throwable ignored) {
                }
            }

            c = c.getSuperclass();
        }
    }

    private static void dumpLambdaMetadata(Class<?> cls) {
        if (cls == null) return;

        log("REALVOL-CONTENT lambda class=" + cls.getName());

        Class<?> enclosingClass = cls.getEnclosingClass();
        if (enclosingClass != null) {
            log("REALVOL-CONTENT enclosing class="
                    + enclosingClass.getName());
        }

        Method enclosingMethod = cls.getEnclosingMethod();
        if (enclosingMethod != null) {
            log("REALVOL-CONTENT enclosing method="
                    + methodSignatureForVolumeDiag(enclosingMethod));
        }

        for (Method m : cls.getDeclaredMethods()) {
            String n = m.getName();

            if ("invoke".equals(n)
                    || "invokeSuspend".equals(n)
                    || n.contains("invoke")) {
                log("REALVOL-CONTENT lambda method="
                        + methodSignatureForVolumeDiag(m));
            }
        }
    }

    private static final java.util.concurrent.atomic.AtomicBoolean
            inflatedVolumeSliderLogged =
                    new java.util.concurrent.atomic.AtomicBoolean(false);

    /**
     * Locate the real hardware-volume slider by resource name instead of class.
     * This avoids every classloader/shading issue we've hit so far.
     */
    private static void installInflatedVolumeSliderTrace() {
        Class<?> inflaterClass = android.view.LayoutInflater.class;

        for (Method m : inflaterClass.getDeclaredMethods()) {
            if (!"inflate".equals(m.getName())) continue;

            Class<?>[] p = m.getParameterTypes();
            if (p.length < 1 || p[0] != int.class) continue;

            XposedBridge.hookMethod(m, new XC_MethodHook() {
                @Override
                protected void afterHookedMethod(MethodHookParam param) {
                    if (inflatedVolumeSliderLogged.get()) return;

                    Object result = param.getResult();
                    if (!(result instanceof android.view.View)) return;

                    android.view.View root = (android.view.View) result;
                    android.view.View slider =
                            findViewByEntryName(
                                    root,
                                    "volume_dialog_slider");

                    if (slider == null) return;

                    if (!inflatedVolumeSliderLogged.compareAndSet(false, true)) {
                        return;
                    }

                    log("REALVOL-INFLATE found volume_dialog_slider");
                    log("REALVOL-INFLATE class="
                            + slider.getClass().getName());
                    log("REALVOL-INFLATE classLoader="
                            + slider.getClass().getClassLoader());
                    log("REALVOL-INFLATE superclass="
                            + (slider.getClass().getSuperclass() == null
                                    ? "null"
                                    : slider.getClass().getSuperclass().getName()));

                    dumpRealVolumeView(slider);
                }
            });
        }

        log("REALVOL-INFLATE LayoutInflater hooks installed");
    }

    private static android.view.View findViewByEntryName(
            android.view.View view,
            String wanted
    ) {
        if (view == null) return null;

        try {
            int id = view.getId();

            if (id != android.view.View.NO_ID) {
                String entry =
                        view.getResources().getResourceEntryName(id);

                if (wanted.equals(entry)) {
                    return view;
                }
            }
        } catch (Throwable ignored) {
        }

        if (view instanceof android.view.ViewGroup) {
            android.view.ViewGroup group =
                    (android.view.ViewGroup) view;

            for (int i = 0; i < group.getChildCount(); i++) {
                android.view.View found =
                        findViewByEntryName(
                                group.getChildAt(i),
                                wanted);

                if (found != null) return found;
            }
        }

        return null;
    }

    private static void dumpRealVolumeView(android.view.View slider) {
        Class<?> c = slider.getClass();

        while (c != null) {
            log("REALVOL-INFLATE inspect class=" + c.getName());

            for (Method m : c.getDeclaredMethods()) {
                String n = m.getName().toLowerCase(Locale.ROOT);

                if (n.contains("track")
                        || n.contains("thumb")
                        || n.contains("corner")
                        || n.contains("radius")
                        || n.contains("draw")
                        || n.contains("progress")
                        || n.contains("value")) {

                    log("REALVOL-INFLATE method "
                            + methodSignatureForVolumeDiag(m));
                }
            }

            for (Field f : c.getDeclaredFields()) {
                String n = f.getName().toLowerCase(Locale.ROOT);

                if (n.contains("track")
                        || n.contains("thumb")
                        || n.contains("corner")
                        || n.contains("radius")
                        || n.contains("progress")
                        || n.contains("drawable")) {

                    try {
                        f.setAccessible(true);
                        Object value = f.get(slider);

                        log("REALVOL-INFLATE field "
                                + c.getName()
                                + "#"
                                + f.getName()
                                + ":"
                                + f.getType().getName()
                                + "="
                                + String.valueOf(value));
                    } catch (Throwable ignored) {
                    }
                }
            }

            c = c.getSuperclass();
        }

        if (slider instanceof android.widget.ProgressBar) {
            android.widget.ProgressBar pb =
                    (android.widget.ProgressBar) slider;

            try {
                log("REALVOL-INFLATE progressDrawable="
                        + classNameForRealVol(pb.getProgressDrawable()));
            } catch (Throwable ignored) {
            }
        }

        if (slider instanceof android.widget.SeekBar) {
            android.widget.SeekBar seek =
                    (android.widget.SeekBar) slider;

            try {
                log("REALVOL-INFLATE thumb="
                        + classNameForRealVol(seek.getThumb()));
            } catch (Throwable ignored) {
            }
        }
    }

    private static String classNameForRealVol(Object o) {
        return o == null ? "null" : o.getClass().getName();
    }

    private static final java.util.concurrent.atomic.AtomicBoolean
            volumeWatcherInstalled =
                    new java.util.concurrent.atomic.AtomicBoolean(false);

    private static final java.util.concurrent.atomic.AtomicBoolean
            volumeDiagnosticsInstalled =
                    new java.util.concurrent.atomic.AtomicBoolean(false);

    private static final String VOLUME_SLIDER_CLASS =
            "com.android.systemui.volume.panel.component.volume.ui.composable.VolumeSliderKt";

    private static final String PLATFORM_SLIDER_COLORS_CLASS =
            "com.android.compose.PlatformSliderColors";

    /**
     * The volume UI lives in a classloader that is not ready when the initial
     * SystemUI package callback fires on the validated Pixel build.
     *
     * Watch ClassLoader.loadClass and install the diagnostics the moment the
     * actual volume class appears. The name check happens before any reflective
     * work, so the overhead is negligible after the hook is installed.
     */
    private static void installDeferredVolumeClassWatcher(ClassLoader initialCl) {
        if (!volumeWatcherInstalled.compareAndSet(false, true)) {
            return;
        }

        // Try once immediately in case the class is already available.
        tryInstallVolumeDiagnostics(initialCl, "initial");

        XposedBridge.hookAllMethods(
                ClassLoader.class,
                "loadClass",
                new XC_MethodHook() {
                    @Override
                    protected void afterHookedMethod(MethodHookParam param) {
                        if (volumeDiagnosticsInstalled.get()) return;
                        if (param.args == null || param.args.length == 0) return;
                        if (!(param.args[0] instanceof String)) return;

                        String name = (String) param.args[0];

                        if (!VOLUME_SLIDER_CLASS.equals(name)
                                && !PLATFORM_SLIDER_COLORS_CLASS.equals(name)
                                && !name.contains("VolumeSliderDimensions")) {
                            return;
                        }

                        Object loaded = param.getResult();
                        if (!(loaded instanceof Class<?>)) return;

                        ClassLoader actualCl =
                                ((Class<?>) loaded).getClassLoader();

                        if (actualCl == null) {
                            actualCl = (ClassLoader) param.thisObject;
                        }

                        log("VOLUME-DIAG observed class load: "
                                + name
                                + " via "
                                + actualCl);

                        tryInstallVolumeDiagnostics(
                                actualCl,
                                "loadClass:" + name);
                    }
                });

        log("Deferred volume class watcher installed");
    }

    private static void tryInstallVolumeDiagnostics(
            ClassLoader cl,
            String source
    ) {
        if (cl == null || volumeDiagnosticsInstalled.get()) return;

        Class<?> volumeSlider;
        try {
            volumeSlider = XposedHelpers.findClass(
                    VOLUME_SLIDER_CLASS,
                    cl);
        } catch (Throwable ignored) {
            return;
        }

        if (!volumeDiagnosticsInstalled.compareAndSet(false, true)) {
            return;
        }

        log("VOLUME-DIAG installing from " + source
                + " using loader " + cl);

        installVolumeDiagnostics(cl);

        /*
         * Diagnostic-only for now.
         *
         * Once we have the actual late-loaded volume class signatures from this
         * Pixel build, the real volume geometry hook will be added explicitly.
         */
        log("Deferred volume diagnostics installed");
    }

    /**
     * Volume diagnostic / first-pass support.
     *
     * Pixel Android 17 uses a dedicated SystemUI VolumeSlider composable with
     * VolumeSliderDimensions plus Material3 SliderColors / PlatformSliderColors.
     * Dump the runtime fields/methods once so the next iteration can target the
     * exact geometry instead of assuming it matches brightness.
     */
    private static void installVolumeDiagnostics(ClassLoader cl) {
        log("=== volume runtime diagnostic begin ===");

        dumpVolumeClass(
                cl,
                VOLUME_SLIDER_CLASS);

        dumpVolumeClass(
                cl,
                PLATFORM_SLIDER_COLORS_CLASS);

        /*
         * Discover VolumeSliderDimensions from the actual VolumeSlider method
         * signature. This is safer if Google moves/renames the dimensions class
         * while keeping it as a parameter.
         */
        try {
            Class<?> volumeKt = XposedHelpers.findClass(
                    VOLUME_SLIDER_CLASS,
                    cl);

            for (Method m : volumeKt.getDeclaredMethods()) {
                if (!m.getName().contains("VolumeSlider")) continue;

                for (Class<?> p : m.getParameterTypes()) {
                    String pn = p.getName();

                    if (pn.contains("VolumeSliderDimensions")) {
                        dumpVolumeClass(cl, pn);
                    }
                }
            }
        } catch (Throwable t) {
            log("VOLUME-DIAG could not discover dimensions type", t);
        }

        log("=== volume runtime diagnostic end ===");
    }

    private static void dumpVolumeClass(ClassLoader cl, String fqcn) {
        Class<?> cls;
        try {
            cls = XposedHelpers.findClass(fqcn, cl);
        } catch (Throwable t) {
            log("VOLUME-DIAG class missing: " + fqcn);
            return;
        }

        log("VOLUME-DIAG class: " + fqcn);

        try {
            for (java.lang.reflect.Field f : cls.getDeclaredFields()) {
                log("VOLUME-DIAG field "
                        + fqcn + "#"
                        + f.getName() + ":"
                        + f.getType().getName());
            }
        } catch (Throwable t) {
            log("VOLUME-DIAG field dump failed for " + fqcn, t);
        }

        try {
            for (Method m : cls.getDeclaredMethods()) {
                String n = m.getName();
                if (n.contains("Volume")
                        || n.contains("Slider")
                        || n.contains("Track")
                        || n.contains("Corner")
                        || n.contains("Radius")
                        || n.startsWith("get")
                        || n.startsWith("component")) {
                    log("VOLUME-DIAG method " + methodSignatureForVolumeDiag(m));
                }
            }
        } catch (Throwable t) {
            log("VOLUME-DIAG method dump failed for " + fqcn, t);
        }
    }

    private static String methodSignatureForVolumeDiag(Method m) {
        StringBuilder sb = new StringBuilder();
        sb.append(m.getDeclaringClass().getName())
                .append("#")
                .append(m.getName())
                .append("(");

        Class<?>[] p = m.getParameterTypes();
        for (int i = 0; i < p.length; i++) {
            if (i > 0) sb.append(", ");
            sb.append(i).append(":").append(p[i].getName());
        }

        sb.append(") -> ").append(m.getReturnType().getName());
        return sb.toString();
    }

    private static void installSharedSliderHooks(ClassLoader cl) {
        installPlatformSliderHooks(cl);
        installMaterial3TrackHooks(cl);
    }

    /**
     * SystemUI PlatformSlider hook.
     *
     * Dp is represented as primitive float at the JVM boundary. PlatformSlider
     * itself has two primitive-float parameters: the slider value first and the
     * dragging corner radius later. We therefore patch the LAST float parameter,
     * never the first one, so the actual brightness/volume value is untouched.
     *
     * TrackBackground has exactly the active/idle Dp radii as float parameters;
     * setting both makes the indicator remain a pill both idle and while dragging.
     */
    private static void installPlatformSliderHooks(ClassLoader cl) {
        Class<?> platformKt;
        try {
            platformKt = XposedHelpers.findClass("com.android.compose.PlatformSliderKt", cl);
        } catch (Throwable ignored) {
            log("PlatformSliderKt not present on this build");
            return;
        }

        int platformCount = 0;
        int backgroundCount = 0;

        for (Method m : platformKt.getDeclaredMethods()) {
            String name = m.getName();

            if (name.contains("PlatformSlider") && !name.contains("$default")) {
                final int[] floatIndices = primitiveFloatIndices(m);
                if (floatIndices.length >= 2) {
                    XposedBridge.hookMethod(m, new XC_MethodHook() {
                        @Override
                        protected void beforeHookedMethod(MethodHookParam param) {
                            SliderContext.State state = SliderContext.get();
                            if (state == null || state.trackHeight <= 0f) return;

                            float wantedRadius = wantedRadius(state);
                            int radiusIndex = floatIndices[floatIndices.length - 1];
                            param.args[radiusIndex] = wantedRadius;

                            logOncePlatformHit("PlatformSlider radius hook active: " + m.getName());
                        }
                    });
                    platformCount++;
                }
            }

            if (name.contains("TrackBackground")) {
                final int[] floatIndices = primitiveFloatIndices(m);
                if (floatIndices.length >= 2) {
                    XposedBridge.hookMethod(m, new XC_MethodHook() {
                        @Override
                        protected void beforeHookedMethod(MethodHookParam param) {
                            SliderContext.State state = SliderContext.get();
                            if (state == null || state.trackHeight <= 0f) return;

                            float wantedRadius = wantedRadius(state);

                            // TrackBackground's Dp floats are the active + idle radii.
                            for (int index : floatIndices) {
                                param.args[index] = wantedRadius;
                            }

                            logOnceBackgroundHit(
                                    "PlatformSlider TrackBackground pill hook active: " + m.getName());
                        }
                    });
                    backgroundCount++;
                }
            }
        }

        log("PlatformSlider hooks installed: platform="
                + platformCount + ", trackBackground=" + backgroundCount);
    }

    /**
     * Material3 Track hook.
     *
     * There are two relevant single-value Track overloads on current Material3:
     *
     *   Track(SliderState, ..., thumbTrackGapSize, trackInsideCornerSize)
     *
     * and the expressive overload:
     *
     *   Track(SliderState, trackCornerSize, ...,
     *         thumbTrackGapSize, trackInsideCornerSize)
     *
     * The important distinction is:
     *
     *   trackCornerSize       = OUTER left/right ends
     *   thumbTrackGapSize     = empty gap around the thumb
     *   trackInsideCornerSize = corners facing the thumb/gap
     *
     * v2 incorrectly made trackInsideCornerSize pill-shaped. That is why the
     * former grabber position became rounded. For the classic no-grabber look:
     *
     *   outer corner = trackHeight / 2 (scaled by roundness)
     *   gap          = 0
     *   inside corner= 0
     */
    private static void installMaterial3TrackHooks(ClassLoader cl) {
        Class<?> defaults;
        try {
            defaults = XposedHelpers.findClass("androidx.compose.material3.SliderDefaults", cl);
        } catch (Throwable t) {
            log("Material3 SliderDefaults not present; skipping gap/corner hook");
            return;
        }

        installBrightnessDrawScopeHook(cl);

        int hooked = 0;
        int trackImplHooked = 0;

        for (Method m : defaults.getDeclaredMethods()) {
            String name = m.getName();

            /*
             * Endpoint-only correction.
             *
             * Keep Android 17's native segmented animation completely intact.
             * At exactly 0% or 100%, however, only one drawTrackPath remains and
             * it spans the full Canvas width. In that one case force BOTH ends
             * of that path to the full pill radius.
             *
             * We do not touch partial-width paths, so there is no curved shared
             * seam and therefore no "two pills pushing apart" artifact.
             */
            if (name.startsWith("drawTrackPath")) {
                final int[] pathFloats = primitiveFloatIndices(m);

                if (m.getParameterTypes().length >= 7 && pathFloats.length >= 2) {
                    XposedBridge.hookMethod(m, new XC_MethodHook() {
                        @Override
                        protected void beforeHookedMethod(MethodHookParam param) {
                            ClassicDrawContext.State ctx =
                                    ClassicDrawContext.get();
                            if (ctx == null) return;

                            long scopeSize = readDrawScopeSize(param.args[0]);
                            float canvasWidth = unpackFirstFloat(scopeSize);
                            float canvasHeight = unpackSecondFloat(scopeSize);

                            long packedSize;
                            long packedOffset;
                            try {
                                packedOffset = ((Number) param.args[2]).longValue();
                                packedSize = ((Number) param.args[3]).longValue();
                            } catch (Throwable ignored) {
                                return;
                            }

                            float nativeOffsetX = unpackFirstFloat(packedOffset);
                            float nativeOffsetY = unpackSecondFloat(packedOffset);
                            float nativeWidth = unpackFirstFloat(packedSize);
                            float nativeHeight = unpackSecondFloat(packedSize);

                            int startRadiusIndex = pathFloats[pathFloats.length - 2];
                            int endRadiusIndex = pathFloats[pathFloats.length - 1];
                            float f = clamp01(ctx.fraction);
                            float rawF = clamp01(ctx.rawFraction);

                            /*
                             * A volume slider is not necessarily vertical:
                             * the compact hardware dialog is vertical, while
                             * the expanded mixer rows are horizontal.
                             */
                            boolean vertical = false;
                            if (param.args.length > 1 && param.args[1] != null) {
                                String orientation = String.valueOf(param.args[1])
                                        .toLowerCase(Locale.ROOT);
                                vertical = orientation.contains("vertical");
                            }

                            boolean showGrabber =
                                    "volume".equals(ctx.sliderName)
                                            ? SliderConfig.volume().grabber
                                            : SliderConfig.brightness().grabber;

                            Object pathColor =
                                    param.args.length > 4
                                            ? param.args[4]
                                            : null;

                            Object knownActiveColor =
                                    "volume".equals(ctx.sliderName)
                                            ? volumeV80ActiveTrackColor
                                            : brightnessV84ActiveTrackColor;

                            if (showGrabber) {
                                boolean minimumClamped =
                                        f > rawF + 0.0005f;

                                boolean atZero =
                                        rawF <= 0.0005f
                                                || minimumClamped;

                                boolean atFull =
                                        rawF >= 0.9995f;

                                /*
                                 * Grabber ON, normal 1-99%.
                                 *
                                 * Preserve stock geometry/fill direction and
                                 * override ONLY the radius belonging to an
                                 * actual PHYSICAL outer canvas edge.
                                 *
                                 * This is the behavior that already makes the
                                 * expanded volume mixer transition cleanly near
                                 * 0/100: as the final active/inactive segment
                                 * shrinks, its OUTER end remains rounded while
                                 * the grabber-facing edge remains stock.
                                 *
                                 * v98 applies that exact same rule to the
                                 * horizontal brightness slider.
                                 */
                                if (!atZero && !atFull) {
                                    boolean supportedSlider =
                                            "volume".equals(ctx.sliderName)
                                                    || "brightness".equals(ctx.sliderName);

                                    if (supportedSlider) {
                                        float thickness =
                                                vertical
                                                        ? nativeWidth
                                                        : nativeHeight;

                                        if (thickness > 0f) {
                                            float outerRadius =
                                                    thickness * 0.5f
                                                            * clampPercent(ctx.roundness)
                                                            / 100f;

                                            float tolerance =
                                                    Math.max(
                                                            1f,
                                                            thickness * 0.12f);

                                            if (vertical) {
                                                boolean touchesTop =
                                                        Math.abs(nativeOffsetY)
                                                                <= tolerance;

                                                boolean touchesBottom =
                                                        Math.abs(
                                                                nativeOffsetY
                                                                        + nativeHeight
                                                                        - canvasHeight)
                                                                <= tolerance;

                                                if (touchesTop) {
                                                    param.args[startRadiusIndex] =
                                                            outerRadius;
                                                }

                                                if (touchesBottom) {
                                                    param.args[endRadiusIndex] =
                                                            outerRadius;
                                                }
                                            } else {
                                                boolean touchesLeft =
                                                        Math.abs(nativeOffsetX)
                                                                <= tolerance;

                                                boolean touchesRight =
                                                        Math.abs(
                                                                nativeOffsetX
                                                                        + nativeWidth
                                                                        - canvasWidth)
                                                                <= tolerance;

                                                /*
                                                 * Horizontal mixer AND
                                                 * brightness now share exactly
                                                 * the same physical-edge rule.
                                                 */
                                                if (touchesLeft) {
                                                    param.args[startRadiusIndex] =
                                                            outerRadius;
                                                }

                                                if (touchesRight) {
                                                    param.args[endRadiusIndex] =
                                                            outerRadius;
                                                }
                                            }

                                            if ("volume".equals(ctx.sliderName)) {
                                                logOnceVolumeOuterCornersV96(
                                                        "REALVOL-V98 volume physical outer corners forced");
                                            } else {
                                                logOnceBrightnessOuterCornersV98(
                                                        "REALVOL-V98 brightness mixer-style outer corners active");
                                            }
                                        }
                                    }

                                    V84_DRAW_ZERO_CIRCLE.remove();
                                    return;
                                }

                                boolean activeByColor =
                                        knownActiveColor != null
                                                && pathColor != null
                                                && knownActiveColor.equals(pathColor);

                                /*
                                 * If color identity is unavailable, use the
                                 * native segment size as a fallback classifier.
                                 */
                                boolean activePath;

                                if (knownActiveColor != null && pathColor != null) {
                                    activePath = activeByColor;
                                } else if (vertical) {
                                    if (atZero) {
                                        activePath =
                                                nativeHeight
                                                        <= Math.max(
                                                                nativeWidth * 1.5f,
                                                                canvasHeight * 0.25f);
                                    } else {
                                        activePath =
                                                nativeHeight
                                                        >= canvasHeight * 0.75f;
                                    }
                                } else {
                                    if (atZero) {
                                        activePath =
                                                nativeWidth
                                                        <= Math.max(
                                                                nativeHeight * 1.5f,
                                                                canvasWidth * 0.25f);
                                    } else {
                                        activePath =
                                                nativeWidth
                                                        >= canvasWidth * 0.75f;
                                    }
                                }

                                float thickness =
                                        vertical
                                                ? nativeWidth
                                                : nativeHeight;

                                if (!(thickness > 0f)) {
                                    return;
                                }

                                float endpointRadius =
                                        thickness * 0.5f
                                                * clampPercent(ctx.roundness)
                                                / 100f;

                                if (atZero) {
                                    /*
                                     * v90 finally fixes the REAL stock grabber
                                     * position by registering SliderState before
                                     * SliderImpl lays it out.
                                     *
                                     * Material3 still does not produce the old
                                     * minimum active circle here, though. So do
                                     * ONLY the track part ourselves:
                                     *
                                     *   1. keep the genuine stock thumb/grabber;
                                     *   2. suppress Material3's tiny/animated
                                     *      ACTIVE segment in this minimum zone;
                                     *   3. snap INACTIVE to one full rounded
                                     *      background pill;
                                     *   4. afterHook draws the stable old-style
                                     *      active circle at the true 0% edge.
                                     *
                                     * No custom grabber is drawn or substituted.
                                     */
                                    if (activePath) {
                                        param.setResult(null);
                                        return;
                                    }

                                    if (vertical) {
                                        param.args[2] =
                                                packFloats(
                                                        nativeOffsetX,
                                                        0f);
                                        param.args[3] =
                                                packFloats(
                                                        thickness,
                                                        canvasHeight);
                                    } else {
                                        param.args[2] =
                                                packFloats(
                                                        0f,
                                                        nativeOffsetY);
                                        param.args[3] =
                                                packFloats(
                                                        canvasWidth,
                                                        thickness);
                                    }

                                    param.args[startRadiusIndex] = endpointRadius;
                                    param.args[endRadiusIndex] = endpointRadius;

                                    if (knownActiveColor != null
                                            && ctx.roundness >= 100) {
                                        V84_DRAW_ZERO_CIRCLE.set(ctx.sliderName);
                                    } else {
                                        V84_DRAW_ZERO_CIRCLE.remove();
                                    }

                                    return;
                                }

                                /*
                                 * Exact 100%:
                                 * suppress any lingering INACTIVE segment and
                                 * snap ACTIVE directly to a full rounded pill.
                                 */
                                if (!activePath) {
                                    param.setResult(null);
                                    return;
                                }

                                if (vertical) {
                                    param.args[2] =
                                            packFloats(
                                                    nativeOffsetX,
                                                    0f);
                                    param.args[3] =
                                            packFloats(
                                                    thickness,
                                                    canvasHeight);
                                } else {
                                    param.args[2] =
                                            packFloats(
                                                    0f,
                                                    nativeOffsetY);
                                    param.args[3] =
                                            packFloats(
                                                    canvasWidth,
                                                    thickness);
                                }

                                param.args[startRadiusIndex] = endpointRadius;
                                param.args[endRadiusIndex] = endpointRadius;
                                V84_DRAW_ZERO_CIRCLE.remove();
                                return;
                            }

                            if (vertical) {
                                if (!(canvasHeight > 0f) || !(nativeWidth > 0f)) {
                                    return;
                                }

                                /*
                                 * Keep geometry for the same minimum-fill
                                 * calculation used by brightness:
                                 *
                                 *   minFraction = trackWidth / trackHeight
                                 */
                                ClassicDrawContext.updateGeometry(
                                        canvasHeight,
                                        nativeWidth);

                                float radius =
                                        nativeWidth * 0.5f
                                                * clampPercent(ctx.roundness)
                                                / 100f;

                                /*
                                 * v70 + V78 packed colors finally identify the
                                 * paths unambiguously:
                                 *
                                 * first/native path:
                                 *   dark #23262d = INACTIVE
                                 *   height ~= H * (1 - f)
                                 *
                                 * second/native path:
                                 *   blue #b6c6ed = ACTIVE
                                 *   height ~= H * f
                                 *
                                 * Its raw y=0 is LOGICAL slider-start; the
                                 * VerticalSlider orientation/reverse transform
                                 * places it at the physical bottom.
                                 */
                                float expectedActiveHeight =
                                        canvasHeight * f;

                                float expectedInactiveHeight =
                                        canvasHeight * (1f - f);

                                float activeError =
                                        Math.abs(
                                                nativeHeight
                                                        - expectedActiveHeight);

                                float inactiveError =
                                        Math.abs(
                                                nativeHeight
                                                        - expectedInactiveHeight);

                                boolean activePath =
                                        activeError < inactiveError;

                                /*
                                 * At exactly 50% the heights are identical.
                                 * The active callback is the logical y=0 path
                                 * (confirmed by v70's paired calls).
                                 */
                                if (Math.abs(activeError - inactiveError) <= 1.0f) {
                                    activePath =
                                            Math.abs(nativeOffsetY)
                                                    <= Math.max(
                                                            1f,
                                                            nativeWidth * 0.10f);
                                }

                                if (f <= 0.0005f) {
                                    /*
                                     * After the visual clamp normally f will
                                     * never be exactly zero once geometry is
                                     * known. If it is, the full path is inactive.
                                     */
                                    activePath =
                                            nativeHeight
                                                    <= canvasHeight * 0.25f;
                                } else if (f >= 0.9995f) {
                                    activePath =
                                            nativeHeight
                                                    >= canvasHeight * 0.75f;
                                }

                                if (activePath) {
                                    Object color =
                                            param.args.length > 4
                                                    ? param.args[4]
                                                    : null;

                                    if (color != null) {
                                        volumeV80ActiveTrackColor = color;
                                    }

                                    /*
                                     * EXACT brightness formula, vertical:
                                     *
                                     * horizontal:
                                     *   activeWidth=max(trackHeight, W*f)
                                     *
                                     * vertical:
                                     *   activeHeight=max(trackWidth, H*f)
                                     *
                                     * Keep logical y=0. Do NOT bottom-align it
                                     * manually; Orientation handles that.
                                     */
                                    float activeHeight =
                                            ctx.roundness >= 100
                                                    ? Math.max(
                                                            nativeWidth,
                                                            canvasHeight * f)
                                                    : canvasHeight * f;

                                    activeHeight =
                                            Math.min(
                                                    canvasHeight,
                                                    activeHeight);

                                    param.args[2] =
                                            packFloats(
                                                    nativeOffsetX,
                                                    0f);

                                    param.args[3] =
                                            packFloats(
                                                    nativeWidth,
                                                    activeHeight);

                                    param.args[startRadiusIndex] = radius;
                                    param.args[endRadiusIndex] = radius;
                                } else {
                                    Object color =
                                            param.args.length > 4
                                                    ? param.args[4]
                                                    : null;

                                    if (color != null) {
                                        volumeV80InactiveTrackColor = color;
                                    }

                                    /*
                                     * EXACT brightness background strategy:
                                     * one full stationary rounded pill.
                                     *
                                     * Native order is inactive first, active
                                     * second, so the blue fill naturally draws
                                     * on top — no manual reordering required.
                                     */
                                    param.args[2] =
                                            packFloats(
                                                    nativeOffsetX,
                                                    0f);

                                    param.args[3] =
                                            packFloats(
                                                    nativeWidth,
                                                    canvasHeight);

                                    param.args[startRadiusIndex] = radius;
                                    param.args[endRadiusIndex] = radius;

                                    if (ctx.roundness >= 100
                                            && rawF <= 0.0005f
                                            && volumeV80ActiveTrackColor != null) {
                                        V84_DRAW_ZERO_CIRCLE.set("volume");
                                    } else {
                                        V84_DRAW_ZERO_CIRCLE.remove();
                                    }
                                }

                                logOnceVolumeV80Renderer(
                                        "REALVOL-V98 literal brightness-parity renderer active");
                                return;
                            }

                            // Proven horizontal brightness behavior, unchanged.
                            if (!(canvasWidth > 0f)) return;
                            ClassicDrawContext.updateGeometry(canvasWidth, nativeHeight);

                            float radius = nativeHeight * 0.5f
                                    * clampPercent(ctx.roundness) / 100f;
                            float activeError = Math.abs(nativeWidth - canvasWidth * f);
                            float inactiveError = Math.abs(
                                    nativeWidth - canvasWidth * (1f - f));
                            boolean activePath = activeError < inactiveError;

                            if (Math.abs(activeError - inactiveError) <= 1.0f) {
                                activePath = Math.abs(nativeOffsetX) <= 0.5f;
                            }
                            if (f <= 0.0005f) {
                                activePath = nativeWidth <= canvasWidth * 0.25f;
                            } else if (f >= 0.9995f) {
                                activePath = nativeWidth >= canvasWidth * 0.75f;
                            }

                            if (activePath) {
                                if (param.args.length > 4
                                        && param.args[4] != null) {
                                    if ("volume".equals(ctx.sliderName)) {
                                        volumeV80ActiveTrackColor = param.args[4];
                                    } else if ("brightness".equals(ctx.sliderName)) {
                                        brightnessV84ActiveTrackColor = param.args[4];
                                    }
                                }

                                float activeWidth =
                                        ctx.roundness >= 100
                                                ? Math.max(
                                                        nativeHeight,
                                                        canvasWidth * f)
                                                : canvasWidth * f;

                                activeWidth =
                                        Math.max(
                                                0f,
                                                Math.min(
                                                        canvasWidth,
                                                        activeWidth));

                                param.args[2] =
                                        packFloats(
                                                0f,
                                                nativeOffsetY);

                                param.args[3] =
                                        packFloats(
                                                activeWidth,
                                                nativeHeight);

                                /*
                                 * Desired classic grabber geometry:
                                 *
                                 *  0%    -> O   (normal minimum circle)
                                 *  1-99% -> (====|  rounded outer edge,
                                 *                    square at grabber
                                 *  100%  -> (====)  full rounded pill
                                 */
                                param.args[startRadiusIndex] = radius;
                                param.args[endRadiusIndex] = radius;
                            } else {
                                param.args[2] = packFloats(0f, nativeOffsetY);
                                param.args[3] = packFloats(canvasWidth, nativeHeight);
                                param.args[startRadiusIndex] = radius;
                                param.args[endRadiusIndex] = radius;
                            }

                            logOnceClassicRendererHit(
                                    "Classic persistent 0%-circle renderer active");                        }

                        @Override
                        protected void afterHookedMethod(MethodHookParam param) {
                            String sliderName =
                                    V84_DRAW_ZERO_CIRCLE.get();
                            V84_DRAW_ZERO_CIRCLE.remove();

                            if (sliderName == null) return;

                            ClassicDrawContext.State ctx =
                                    ClassicDrawContext.get();

                            if (ctx == null
                                    || !sliderName.equals(ctx.sliderName)) {
                                return;
                            }

                            Object activeColor =
                                    "volume".equals(sliderName)
                                            ? volumeV80ActiveTrackColor
                                            : brightnessV84ActiveTrackColor;

                            if (activeColor == null) return;

                            long packedSize;
                            long packedOffset;

                            try {
                                packedOffset =
                                        ((Number) param.args[2]).longValue();
                                packedSize =
                                        ((Number) param.args[3]).longValue();
                            } catch (Throwable ignored) {
                                return;
                            }

                            boolean vertical = false;

                            if (param.args.length > 1
                                    && param.args[1] != null) {
                                vertical =
                                        String.valueOf(param.args[1])
                                                .toLowerCase(Locale.ROOT)
                                                .contains("vertical");
                            }

                            float nativeX =
                                    unpackFirstFloat(packedOffset);
                            float nativeY =
                                    unpackSecondFloat(packedOffset);
                            float nativeWidth =
                                    unpackFirstFloat(packedSize);
                            float nativeHeight =
                                    unpackSecondFloat(packedSize);

                            float thickness =
                                    vertical
                                            ? nativeWidth
                                            : nativeHeight;

                            if (!(thickness > 0f)) return;

                            float radius =
                                    thickness * 0.5f
                                            * clampPercent(ctx.roundness)
                                            / 100f;

                            Object[] args =
                                    param.args.clone();

                            if (vertical) {
                                /*
                                 * Logical y=0 maps to the physical BOTTOM,
                                 * exactly matching the stable no-grabber circle.
                                 * The genuine stock grabber is independently
                                 * positioned by v90's early visual-fraction clamp
                                 * at the circle's TOP edge.
                                 */
                                args[2] =
                                        packFloats(
                                                nativeX,
                                                0f);

                                args[3] =
                                        packFloats(
                                                thickness,
                                                thickness);
                            } else {
                                args[2] =
                                        packFloats(
                                                0f,
                                                nativeY);

                                args[3] =
                                        packFloats(
                                                thickness,
                                                thickness);
                            }

                            args[4] = activeColor;

                            int sIdx =
                                    pathFloats[pathFloats.length - 2];

                            int eIdx =
                                    pathFloats[pathFloats.length - 1];

                            args[sIdx] = radius;
                            args[eIdx] = radius;

                            try {
                                XposedBridge.invokeOriginalMethod(
                                        param.method,
                                        param.thisObject,
                                        args);

                                logOnceVolumeV80ZeroCircle(
                                        "REALVOL-V98 static 0% endpoint circle drawn");
                            } catch (Throwable t) {
                                log("REALVOL-V98 endpoint circle draw failed: " + t);
                            }
                        }
                    });
                }
                continue;
            }


            /*
             * Android 17's expressive TrackImpl signature is:
             *
             *   0 SliderState
             *   1 float trackCornerSize
             *   2 Modifier
             *   3 boolean enabled
             *   4 SliderColors
             *   5 Function2 drawStopIndicator
             *   6 Function3 drawTick
             *   7 float thumbTrackGapSize
             *   8 float trackInsideCornerSize
             *   9 boolean enableCornerShrinking
             *  10 Composer
             *  11 int
             *  12 int
             *
             * v4 guessed the boolean by ordinal. On the Pixel build that could
             * hit "enabled", which explains the disabled/grey slider colour.
             *
             * v5 only hooks when the exact semantic type pattern is present and
             * changes parameter 9 -- never parameter 3.
             */
            if (name.startsWith("TrackImpl")) {
                Class<?>[] implTypes = m.getParameterTypes();

                if (isExactTrackImplSignature(implTypes)) {
                    final int shrinkingIndex = 9;

                    XposedBridge.hookMethod(m, new XC_MethodHook() {
                        @Override
                        protected void beforeHookedMethod(MethodHookParam param) {
                            SliderContext.State state = SliderContext.get();
                            if (state == null || state.trackHeight <= 0f) return;

                            /*
                             * Exact Android 17 TrackImpl parameters:
                             *   1 = trackCornerSize
                             *   7 = thumbTrackGapSize
                             *   8 = trackInsideCornerSize
                             *   9 = enableCornerShrinking
                             *
                             * drawTrack() uses trackInsideCornerSize for the edge
                             * facing the current slider value. With the grabber
                             * removed we normally keep that edge square so the
                             * active/inactive tracks meet cleanly. At exactly 0%
                             * or 100%, however, that same "inside" edge becomes
                             * the visible OUTER endpoint. It therefore needs the
                             * full pill radius at the endpoints.
                             */
                            float radius = wantedRadius(state);

                            if (state.config != null) {
                                registerRoundedSliderStateCompat(
                                        param.args[0],
                                        state.config.name);

                                /*
                                 * Cache SliderColors so exact 0% can render the
                                 * active minimum circle even if Material3 emits
                                 * no active drawTrackPath at that endpoint.
                                 */
                                if (param.args.length > 4
                                        && param.args[4] != null) {
                                    Object activeColor =
                                            readSliderTrackColorV80(
                                                    param.args[4],
                                                    true);
                                    Object inactiveColor =
                                            readSliderTrackColorV80(
                                                    param.args[4],
                                                    false);

                                    if ("volume".equals(state.config.name)) {
                                        if (activeColor != null) {
                                            volumeV80ActiveTrackColor = activeColor;
                                        }
                                        if (inactiveColor != null) {
                                            volumeV80InactiveTrackColor = inactiveColor;
                                        }
                                    } else if ("brightness".equals(state.config.name)) {
                                        if (activeColor != null) {
                                            brightnessV84ActiveTrackColor = activeColor;
                                        }
                                    }
                                }
                            }

                            float trackFraction =
                                    readSliderFraction(param.args[0]);

                            boolean minimumClamped =
                                    isMinimumFillClampedCompat(
                                            param.args[0]);

                            boolean endpoint =
                                    minimumClamped
                                            || (!Float.isNaN(trackFraction)
                                                    && (trackFraction <= 0.0005f
                                                            || trackFraction >= 0.9995f));

                            /*
                             * Grabber ON from 1-99%:
                             *
                             * Keep ALL stock Material3 behavior except its
                             * EXTERNAL corner size. For brightness this path is
                             * sufficient; for volume, drawTrackPath v96 also
                             * enforces the physical canvas-edge radii because
                             * Pixel's volume track does not visually honor this
                             * parameter throughout 1-99%.
                             *
                             *   horizontal -> far left/right remain rounded
                             *   vertical   -> physical top/bottom remain rounded
                             *
                             * Gap, inside corner, thumb placement, fraction,
                             * corner-shrinking behavior at the thumb, and fill
                             * direction remain stock.
                             */
                            if (state.config != null
                                    && state.config.grabber
                                    && !endpoint) {
                                param.args[1] = radius;
                                return;
                            }

                            /*
                             * Remaining mixer blink fix:
                             * suppress drawStopIndicator directly at TrackImpl,
                             * because the dragging composition can recreate this
                             * callback after the outer Track hook ran.
                             */
                            boolean volumeNoGrabber =
                                    (state.config != null
                                            && "volume".equals(state.config.name)
                                            && !state.config.grabber)
                                            || (state.config == null
                                                && !SliderConfig.volume().grabber
                                                && isAnySystemUiVolumeStack());

                            if (volumeNoGrabber
                                    && param.args.length > 5
                                    && param.args[5] != null) {
                                Object noStopIndicator =
                                        makeNoOpFunction2Robust(
                                                param.args[5],
                                                m.getDeclaringClass().getClassLoader());

                                if (noStopIndicator != null) {
                                    param.args[5] = noStopIndicator;
                                    logOnceVolumeStopIndicatorSuppressed(
                                            "REALVOL-V98 TrackImpl stop indicator suppressed");
                                }
                            }

                            if (state.config != null
                                    && state.config.grabber
                                    && endpoint) {
                                /*
                                 * STATIC endpoint override.
                                 *
                                 * No Material3 corner-shrinking animation at
                                 * 0/100; drawTrackPath below snaps immediately
                                 * to the fully rounded endpoint geometry.
                                 */
                                param.args[1] = radius;
                                param.args[7] = 0f;
                                param.args[8] = 0f;
                                param.args[shrinkingIndex] = false;
                                return;
                            }

                            /*
                             * Stable no-grabber v80 behavior.
                             */
                            param.args[1] = 0f;
                            param.args[7] = 0f;
                            param.args[8] = 0f;
                            param.args[shrinkingIndex] = true;

                            logOnceShrinkingHit(
                                    "Classic renderer registered: "
                                            + m.getName());
                        }
                    });

                    trackImplHooked++;
                } else {
                    logOnceTrackImplMismatch(
                            "TrackImpl found but signature did not match; "
                                    + "not touching booleans");
                }
                continue;
            }

            if (!name.startsWith("Track") || name.contains("$default")) continue;

            Class<?>[] types = m.getParameterTypes();
            if (types.length == 0) continue;
            if (!"androidx.compose.material3.SliderState".equals(types[0].getName())) continue;

            final int[] floatIndices = primitiveFloatIndices(m);
            if (floatIndices.length < 2) continue;

            /*
             * Regular Track has two Dp/float parameters at the tail:
             *   gap, insideCorner.
             *
             * Expressive Track has three:
             *   outerCorner, gap, insideCorner.
             */
            final boolean hasExplicitOuterCorner = floatIndices.length >= 3;

            XposedBridge.hookMethod(m, new XC_MethodHook() {
                @Override
                protected void beforeHookedMethod(MethodHookParam param) {
                    SliderContext.State state = SliderContext.get();
                    if (state == null || state.trackHeight <= 0f) return;

                    if (state.config != null
                            && param.args.length > 4
                            && param.args[4] != null) {
                        Object activeColor =
                                readSliderTrackColorV80(
                                        param.args[4],
                                        true);

                        if ("brightness".equals(state.config.name)
                                && activeColor != null) {
                            brightnessV84ActiveTrackColor = activeColor;
                        } else if ("volume".equals(state.config.name)
                                && activeColor != null) {
                            volumeV80ActiveTrackColor = activeColor;
                        }
                    }

                    float outerRadius = wantedRadius(state);

                    int gapIndex = floatIndices[floatIndices.length - 2];
                    int insideCornerIndex = floatIndices[floatIndices.length - 1];

                    float fraction =
                            readSliderFraction(param.args[0]);

                    boolean minimumClamped =
                            isMinimumFillClampedCompat(
                                    param.args[0]);

                    boolean endpoint =
                            minimumClamped
                                    || (!Float.isNaN(fraction)
                                            && (fraction <= 0.0005f
                                                    || fraction >= 0.9995f));

                    if (state.config != null
                            && state.config.grabber
                            && !endpoint) {
                        /*
                         * Same native approach as TrackImpl: only override the
                         * public overload's explicit trackCornerSize when it is
                         * present. Do not touch gap/inside-corner parameters.
                         */
                        if (hasExplicitOuterCorner) {
                            int outerCornerIndex = floatIndices[0];
                            param.args[outerCornerIndex] = outerRadius;
                        }

                        return;
                    }

                    if (hasExplicitOuterCorner) {
                        int outerCornerIndex = floatIndices[0];
                        param.args[outerCornerIndex] = outerRadius;
                    }

                    param.args[gapIndex] = 0f;
                    param.args[insideCornerIndex] = 0f;

                    logOnceMaterialHit(
                            "Material3 Track hook active: " + m.getName()
                                    + " floats=" + floatIndices.length
                                    + " explicitOuter=" + hasExplicitOuterCorner);
                }
            });

            hooked++;
        }

        log("Material3 Slider hooks installed: Track=" + hooked
                + ", TrackImpl=" + trackImplHooked);
    }

    private static float readSliderFraction(Object sliderState) {
        if (sliderState == null) return Float.NaN;

        // Current Material3 exposes this Kotlin property as a no-arg JVM getter.
        try {
            Method getter = sliderState.getClass().getDeclaredMethod(
                    "getCoercedValueAsFraction");
            getter.setAccessible(true);
            Object result = getter.invoke(sliderState);
            if (result instanceof Float) return (Float) result;
            if (result instanceof Number) return ((Number) result).floatValue();
        } catch (Throwable ignored) {
        }

        // Be tolerant of Kotlin/JVM name mangling on future Android builds.
        try {
            for (Method m : sliderState.getClass().getDeclaredMethods()) {
                String n = m.getName().toLowerCase(Locale.ROOT);
                if (m.getParameterTypes().length == 0
                        && n.contains("coercedvalueasfraction")) {
                    m.setAccessible(true);
                    Object result = m.invoke(sliderState);
                    if (result instanceof Float) return (Float) result;
                    if (result instanceof Number) {
                        return ((Number) result).floatValue();
                    }
                }
            }
        } catch (Throwable ignored) {
        }

        /*
         * Last fallback: derive the fraction from the public value and valueRange
         * getters if the internal fraction getter is unavailable.
         */
        try {
            Object valueObj = XposedHelpers.callMethod(sliderState, "getValue");
            Object rangeObj = XposedHelpers.callMethod(sliderState, "getValueRange");

            if (!(valueObj instanceof Number) || rangeObj == null) {
                return Float.NaN;
            }

            float value = ((Number) valueObj).floatValue();
            Object startObj = XposedHelpers.callMethod(rangeObj, "getStart");
            Object endObj = XposedHelpers.callMethod(rangeObj, "getEndInclusive");

            if (!(startObj instanceof Number) || !(endObj instanceof Number)) {
                return Float.NaN;
            }

            float start = ((Number) startObj).floatValue();
            float end = ((Number) endObj).floatValue();
            if (end == start) return 0f;

            float fraction = (value - start) / (end - start);
            return Math.max(0f, Math.min(1f, fraction));
        } catch (Throwable ignored) {
            return Float.NaN;
        }
    }

    private static final java.util.Map<Object, String> roundedSliderStatesCompat =
            java.util.Collections.synchronizedMap(
                    new java.util.WeakHashMap<>());

    private static final java.util.Map<Object, Boolean>
            roundedSliderMinimumClampedCompat =
                    java.util.Collections.synchronizedMap(
                            new java.util.WeakHashMap<>());

    private static boolean isMinimumFillClampedCompat(Object state) {
        if (state == null) return false;

        return Boolean.TRUE.equals(
                roundedSliderMinimumClampedCompat.get(state));
    }

    private static void registerRoundedSliderStateCompat(
            Object state,
            String sliderName
    ) {
        if (state == null || sliderName == null) return;

        roundedSliderStatesCompat.put(state, sliderName);

        // Preserve existing brightness behavior too.
        if ("brightness".equals(sliderName)) {
            brightnessSliderStates.add(state);
        }
    }

    private static final java.util.Set<Object> brightnessSliderStates =
            java.util.Collections.newSetFromMap(
                    java.util.Collections.synchronizedMap(
                            new java.util.WeakHashMap<>()));

    private static void registerBrightnessSliderState(Object state) {
        if (state != null) {
            brightnessSliderStates.add(state);
        }
    }

    /**
     * The Canvas draw code reads SliderState.coercedValueAsFraction immediately
     * before drawing the track. Use that as the beginning of one draw frame.
     *
     * The context stores:
     *   - the exact live brightness fraction
     *   - requested roundness
     *   - drawTrackPath call index for this frame
     */
    private static void installBrightnessDrawScopeHook(ClassLoader cl) {
        Class<?> stateClass;
        try {
            stateClass = XposedHelpers.findClass(
                    "androidx.compose.material3.SliderState",
                    cl);
        } catch (Throwable t) {
            log("SliderState unavailable for brightness draw scoping");
            return;
        }

        int count = 0;

        for (Method m : stateClass.getDeclaredMethods()) {
            String n = m.getName().toLowerCase(Locale.ROOT);

            if (m.getParameterTypes().length == 0
                    && n.contains("coercedvalueasfraction")) {

                XposedBridge.hookMethod(m, new XC_MethodHook() {
                    @Override
                    protected void afterHookedMethod(MethodHookParam param) {
                        String roundedSliderName =
                                roundedSliderStatesCompat.get(param.thisObject);

                        boolean brightnessState =
                                brightnessSliderStates.contains(param.thisObject);

                        if (roundedSliderName == null
                                && !brightnessState
                                && classicSlidersEnabled
                                && isBrightnessSliderStack()) {
                            registerRoundedSliderStateCompat(
                                    param.thisObject,
                                    "brightness");
                            roundedSliderName = "brightness";
                            brightnessState = true;
                        }

                        if (roundedSliderName == null && !brightnessState) {
                            ClassicDrawContext.clear();
                            return;
                        }

                        if (roundedSliderName == null) {
                            roundedSliderName = "brightness";
                        }

                        Object result = param.getResult();
                        if (!(result instanceof Number)) {
                            ClassicDrawContext.clear();
                            return;
                        }

                        int roundness =
                                "volume".equals(roundedSliderName)
                                        ? settingCompat(
                                                "restoreroundedsliders_volume_roundness",
                                                "roundsliders_volume_roundness",
                                                100)
                                        : settingCompat(
                                                "restoreroundedsliders_brightness_roundness",
                                                "roundsliders_brightness_roundness",
                                                100);

                        float rawFraction =
                                ((Number) result).floatValue();

                        ClassicDrawContext.begin(
                                rawFraction,
                                roundness,
                                roundedSliderName);

                        ClassicDrawContext.bindSliderState(
                                param.thisObject);

                        /*
                         * Keep the exact same minimum-circle visual clamp in
                         * BOTH grabber modes.
                         *
                         * Because SliderState is now registered before
                         * SliderImpl layout, the REAL stock grabber reads this
                         * same clamped visual fraction and moves to the circle's
                         * far edge:
                         *
                         *   brightness -> grabber on RIGHT of the 0% circle
                         *   volume     -> grabber on TOP of the 0% circle
                         *
                         * Only the visual fraction is changed. rawFraction
                         * remains the real underlying value.
                         */
                        boolean showGrabber =
                                "volume".equals(roundedSliderName)
                                        ? SliderConfig.volume().grabber
                                        : SliderConfig.brightness().grabber;

                        /*
                         * Only Volume + grabber OFF gets progressive growth
                         * through the minimum-circle zone.
                         *
                         * Brightness and grabber-enabled volume keep their
                         * existing behavior unchanged.
                         */
                        float visualFraction =
                                roundness >= 100
                                        ? ("volume".equals(roundedSliderName)
                                                && !showGrabber
                                                ? ClassicDrawContext
                                                        .volumeProgressiveVisualFraction(
                                                                rawFraction)
                                                : ClassicDrawContext
                                                        .visualFraction(
                                                                rawFraction))
                                        : clamp01(rawFraction);

                        boolean minimumClamped =
                                visualFraction > rawFraction + 0.0005f;

                        if ("volume".equals(roundedSliderName)
                                && !showGrabber) {
                            logOnceProgressiveLowVolumeV97(
                                    rawFraction,
                                    visualFraction);
                        }

                        if (minimumClamped) {
                            logPersistentClampV95(
                                    roundedSliderName,
                                    rawFraction,
                                    visualFraction);
                        }

                        roundedSliderMinimumClampedCompat.put(
                                param.thisObject,
                                minimumClamped);

                        if (minimumClamped) {
                            param.setResult(visualFraction);

                            if ("volume".equals(roundedSliderName)) {
                                logOnceVolumeV80ZeroClamp(
                                        "REALVOL-V98 minimum circle + grabber clamped");
                            }
                        }

                        ClassicDrawContext.beginVisual(
                                visualFraction,
                                rawFraction,
                                roundness,
                                roundedSliderName);

                        ClassicDrawContext.bindSliderState(
                                param.thisObject);
                    }
                });

                count++;
            }
        }

        log("Brightness live fraction hooks installed: " + count);
    }

    private static volatile Object volumeV80ActiveTrackColor;
    private static volatile Object volumeV80InactiveTrackColor;
    private static volatile Object brightnessV84ActiveTrackColor;

    /*
     * Non-null only for an exact 0% endpoint background draw. afterHookedMethod
     * then adds the active minimum circle on top.
     */
    private static final ThreadLocal<String> V84_DRAW_ZERO_CIRCLE =
            new ThreadLocal<>();

    private static Object readSliderTrackColorV80(
            Object colors,
            boolean active
    ) {
        if (colors == null) return null;

        Class<?> c = colors.getClass();

        while (c != null) {
            for (Field f : c.getDeclaredFields()) {
                try {
                    String n = f.getName().toLowerCase(Locale.ROOT);

                    if (!n.contains("trackcolor")) continue;
                    if (n.contains("disabled")) continue;

                    boolean inactive = n.contains("inactive");

                    if (active && inactive) continue;
                    if (!active && !inactive) continue;

                    f.setAccessible(true);
                    Object value = f.get(colors);

                    if (value instanceof Number) {
                        return value;
                    }
                } catch (Throwable ignored) {
                }
            }

            c = c.getSuperclass();
        }

        return null;
    }

    private static volatile boolean loggedProgressiveLowVolumeV97;

    private static void logOnceProgressiveLowVolumeV97(
            float rawFraction,
            float visualFraction
    ) {
        if (loggedProgressiveLowVolumeV97) return;
        if (!(rawFraction > 0.0005f)) return;
        if (!(visualFraction > rawFraction + 0.0005f)) return;

        loggedProgressiveLowVolumeV97 = true;

        log("REALVOL-V98 progressive low-volume fill active: raw="
                + rawFraction
                + " visual="
                + visualFraction);
    }

    private static volatile boolean loggedBrightnessOuterCornersV98;

    private static void logOnceBrightnessOuterCornersV98(String s) {
        if (loggedBrightnessOuterCornersV98) return;
        loggedBrightnessOuterCornersV98 = true;
        log(s);
    }

    private static volatile boolean loggedVolumeOuterCornersV96;

    private static void logOnceVolumeOuterCornersV96(String s) {
        if (loggedVolumeOuterCornersV96) return;
        loggedVolumeOuterCornersV96 = true;
        log(s);
    }

    private static volatile boolean loggedPersistentClampV95;

    private static void logPersistentClampV95(
            String sliderName,
            float rawFraction,
            float visualFraction
    ) {
        if (loggedPersistentClampV95) return;
        if (!(visualFraction > rawFraction + 0.0005f)) return;

        loggedPersistentClampV95 = true;

        log("REALVOL-V98 persistent minimum clamp active: "
                + sliderName
                + " raw="
                + rawFraction
                + " visual="
                + visualFraction);
    }

    private static final class ClassicDrawContext {
        private static final ThreadLocal<State> TLS = new ThreadLocal<>();

        /*
         * [0] = logical slider length
         * [1] = track thickness
         *
         * Weak keys avoid retaining dead Compose SliderState objects.
         */
        private static final java.util.Map<Object, float[]> GEOMETRY =
                java.util.Collections.synchronizedMap(
                        new java.util.WeakHashMap<>());

        /*
         * The PixelXpert module is installed after SystemUI has started. Its
         * volume dialog therefore creates a fresh SliderState each time it is
         * opened, unlike the standalone module's already-instrumented path.
         * Keep the latest real volume geometry so the very first low-volume
         * frame of a new dialog can calculate the same progressive fill rather
         * than briefly falling back to the raw fraction at the bottom.
         */
        private static volatile float[] lastVolumeGeometry;


        static void begin(float fraction, int roundness, String sliderName) {
            State previous = TLS.get();
            State next = new State(fraction, fraction, roundness, sliderName);

            if (previous != null) {
                next.canvasWidth = previous.canvasWidth;
                next.trackHeight = previous.trackHeight;
                next.sliderState = previous.sliderState;
            }

            TLS.set(next);
        }

        static void bindSliderState(Object sliderState) {
            State state = TLS.get();
            if (state == null || sliderState == null) return;

            state.sliderState = sliderState;

            float[] remembered = GEOMETRY.get(sliderState);

            if (remembered != null && remembered.length >= 2) {
                if (!(state.canvasWidth > 0f)) {
                    state.canvasWidth = remembered[0];
                }

                if (!(state.trackHeight > 0f)) {
                    state.trackHeight = remembered[1];
                }
            }

            if ("volume".equals(state.sliderName)) {
                float[] lastKnown = lastVolumeGeometry;
                if (lastKnown != null && lastKnown.length >= 2) {
                    if (!(state.canvasWidth > 0f)) {
                        state.canvasWidth = lastKnown[0];
                    }
                    if (!(state.trackHeight > 0f)) {
                        state.trackHeight = lastKnown[1];
                    }
                }
            }
        }

        static void beginVisual(
                float visualFraction,
                float rawFraction,
                int roundness,
                String sliderName
        ) {
            State previous = TLS.get();
            State next = new State(
                    visualFraction,
                    rawFraction,
                    roundness,
                    sliderName);

            if (previous != null) {
                next.canvasWidth = previous.canvasWidth;
                next.trackHeight = previous.trackHeight;
                next.sliderState = previous.sliderState;
            }

            TLS.set(next);
        }

        static State get() {
            return TLS.get();
        }

        static void updateGeometry(float canvasWidth, float trackHeight) {
            State state = TLS.get();
            if (state == null) return;

            if (canvasWidth > 0f) state.canvasWidth = canvasWidth;
            if (trackHeight > 0f) state.trackHeight = trackHeight;

            if (state.sliderState != null
                    && state.canvasWidth > 0f
                    && state.trackHeight > 0f) {
                float[] geometry = new float[] {
                        state.canvasWidth,
                        state.trackHeight
                };
                GEOMETRY.put(state.sliderState, geometry);

                if ("volume".equals(state.sliderName)) {
                    lastVolumeGeometry = geometry;
                }
            }
        }

        static float visualFraction(float rawFraction) {
            State state = TLS.get();
            if (state == null) return clamp01(rawFraction);

            float result = clamp01(rawFraction);

            /*
             * Old Android minimum visual fill:
             * one circle whose diameter == track height.
             *
             * minFraction = trackHeight / sliderWidth
             */
            if (state.canvasWidth > 0f && state.trackHeight > 0f) {
                float minFraction = Math.min(
                        1f,
                        state.trackHeight / state.canvasWidth);

                result = Math.max(result, minFraction);
            }

            return result;
        }

        static float volumeProgressiveVisualFraction(float rawFraction) {
            State state = TLS.get();
            if (state == null) return clamp01(rawFraction);

            float raw = clamp01(rawFraction);

            if (!(state.canvasWidth > 0f)
                    || !(state.trackHeight > 0f)) {
                return raw;
            }

            float minFraction =
                    Math.min(
                            1f,
                            state.trackHeight / state.canvasWidth);

            /*
             * Preserve the exact finished 0% circle.
             */
            if (raw <= 0.0005f) {
                return minFraction;
            }

            /*
             * Avoid the low-volume plateau.
             *
             * Instead of clamping every raw value below minFraction to the
             * identical circle, gradually grow from the circle and converge
             * back to the real fraction at 2 * minFraction.
             *
             * This changes VISUAL fill only; Android's actual volume value and
             * discrete volume steps are untouched.
             */
            float transition =
                    Math.min(
                            1f,
                            minFraction * 2f);

            if (transition <= minFraction + 0.0005f) {
                return Math.max(raw, minFraction);
            }

            if (raw < transition) {
                float t =
                        raw / transition;

                return clamp01(
                        minFraction
                                + t * (transition - minFraction));
            }

            return raw;
        }

        static void clear() {
            TLS.remove();
        }

        static final class State {
            final float fraction;
            final float rawFraction;
            final int roundness;
            final String sliderName;
            float canvasWidth;
            float trackHeight;
            Object sliderState;

            State(
                    float fraction,
                    float rawFraction,
                    int roundness,
                    String sliderName
            ) {
                this.fraction = fraction;
                this.rawFraction = rawFraction;
                this.roundness = roundness;
                this.sliderName = sliderName;
            }
        }
    }

    private static float clamp01(float value) {
        return Math.max(0f, Math.min(1f, value));
    }

    private static float unpackFirstFloat(long packed) {
        return Float.intBitsToFloat((int) (packed >> 32));
    }

    private static float unpackSecondFloat(long packed) {
        return Float.intBitsToFloat((int) packed);
    }

    private static long packFloats(float first, float second) {
        return ((long) Float.floatToRawIntBits(first) << 32)
                | (Float.floatToRawIntBits(second) & 0xffffffffL);
    }

    private static long readDrawScopeSize(Object drawScope) {
        if (drawScope == null) return 0L;
        try {
            for (Method m : drawScope.getClass().getMethods()) {
                String n = m.getName().toLowerCase(Locale.ROOT);
                if (m.getParameterTypes().length == 0 && n.startsWith("getsize")) {
                    m.setAccessible(true);
                    Object result = m.invoke(drawScope);
                    if (result instanceof Number) return ((Number) result).longValue();
                }
            }
        } catch (Throwable ignored) {
        }
        return 0L;
    }

    private static float readDrawScopeWidth(Object drawScope) {
        if (drawScope == null) return Float.NaN;

        try {
            for (Method m : drawScope.getClass().getMethods()) {
                String n = m.getName().toLowerCase(Locale.ROOT);

                if (m.getParameterTypes().length == 0
                        && n.startsWith("getsize")) {
                    m.setAccessible(true);
                    Object result = m.invoke(drawScope);

                    if (result instanceof Number) {
                        return unpackFirstFloat(
                                ((Number) result).longValue());
                    }
                }
            }
        } catch (Throwable ignored) {
        }

        return Float.NaN;
    }

    private static boolean isExactTrackImplSignature(Class<?>[] t) {
        if (t == null || t.length < 13) return false;

        /*
         * Exact Pixel Android 17 runtime signature observed on CP2A.260805.005:
         *
         *  0 SliderState
         *  1 float trackCornerSize
         *  2 Modifier
         *  3 boolean enabled
         *  4 SliderColors
         *  5 Function2 drawStopIndicator
         *  6 Function3 drawTick
         *  7 float thumbTrackGapSize
         *  8 float trackInsideCornerSize
         *  9 boolean enableCornerShrinking
         * 10 Composer
         * 11 int
         * 12 int
         */
        return "androidx.compose.material3.SliderState".equals(t[0].getName())
                && t[1] == float.class
                && "androidx.compose.ui.Modifier".equals(t[2].getName())
                && t[3] == boolean.class
                && "androidx.compose.material3.SliderColors".equals(t[4].getName())
                && kotlinFunctionType(t[5], "kotlin.jvm.functions.Function2")
                && kotlinFunctionType(t[6], "kotlin.jvm.functions.Function3")
                && t[7] == float.class
                && t[8] == float.class
                && t[9] == boolean.class
                && "androidx.compose.runtime.Composer".equals(t[10].getName())
                && t[11] == int.class
                && t[12] == int.class;
    }

    private static boolean kotlinFunctionType(Class<?> type, String expected) {
        return type != null && expected.equals(type.getName());
    }

    private static int[] primitiveFloatIndices(Method m) {
        Class<?>[] types = m.getParameterTypes();
        int count = 0;
        for (Class<?> type : types) {
            if (type == float.class) count++;
        }

        int[] out = new int[count];
        int p = 0;
        for (int i = 0; i < types.length; i++) {
            if (types[i] == float.class) out[p++] = i;
        }
        return out;
    }


    private static float wantedRadius(SliderContext.State state) {
        return state.trackHeight * 0.5f
                * clampPercent(state.config.roundness) / 100f;
    }

    private static volatile boolean loggedPlatformHit;
    private static volatile boolean loggedBackgroundHit;
    private static volatile boolean loggedMaterialHit;
    private static volatile boolean loggedShrinkingHit;
    private static volatile boolean loggedTrackImplMismatch;
    private static volatile boolean loggedEndpointPathHit;
    private static volatile boolean loggedClassicRendererHit;

    private static void logOncePlatformHit(String s) {
        if (loggedPlatformHit) return;
        loggedPlatformHit = true;
        log(s);
    }

    private static void logOnceBackgroundHit(String s) {
        if (loggedBackgroundHit) return;
        loggedBackgroundHit = true;
        log(s);
    }

    private static void logOnceMaterialHit(String s) {
        if (loggedMaterialHit) return;
        loggedMaterialHit = true;
        log(s);
    }

    private static void logOnceShrinkingHit(String s) {
        if (loggedShrinkingHit) return;
        loggedShrinkingHit = true;
        log(s);
    }

    private static void logOnceTrackImplMismatch(String s) {
        if (loggedTrackImplMismatch) return;
        loggedTrackImplMismatch = true;
        log(s);
    }

    private static void logOnceEndpointPathHit(String s) {
        if (loggedEndpointPathHit) return;
        loggedEndpointPathHit = true;
        log(s);
    }

    private static volatile boolean loggedVolumeVerticalRendererHit;

    private static volatile boolean loggedVolumeV80Renderer;
    private static volatile boolean loggedVolumeV80ZeroClamp;
    private static volatile boolean loggedVolumeV80ZeroCircle;

    private static void logOnceVolumeV80Renderer(String s) {
        if (loggedVolumeV80Renderer) return;
        loggedVolumeV80Renderer = true;
        log(s);
    }

    private static void logOnceVolumeV80ZeroClamp(String s) {
        if (loggedVolumeV80ZeroClamp) return;
        loggedVolumeV80ZeroClamp = true;
        log(s);
    }

    private static void logOnceVolumeV80ZeroCircle(String s) {
        if (loggedVolumeV80ZeroCircle) return;
        loggedVolumeV80ZeroCircle = true;
        log(s);
    }

    private static void logOnceVolumeVerticalRendererHit(String s) {
        if (loggedVolumeVerticalRendererHit) return;
        loggedVolumeVerticalRendererHit = true;
        log(s);
    }

    private static void logOnceClassicRendererHit(String s) {
        if (loggedClassicRendererHit) return;
        loggedClassicRendererHit = true;
        log(s);
    }

    private static boolean approximately(float value, float target, float tolerance) {
        return Math.abs(value - target) <= tolerance;
    }

    private static void setMatchingOuterRadiusFields(Object o, float value) {
        forEachField(o.getClass(), f -> {
            if (!isFloatField(f)) return;

            String n = f.getName().toLowerCase(Locale.ROOT);

            // Never turn the thumb-side/internal seam into a rounded end.
            if (n.contains("inside")
                    || n.contains("thumb")
                    || n.contains("grabber")
                    || n.contains("handle")
                    || n.contains("indicator")) {
                return;
            }

            boolean looksLikeOuterRadius =
                    n.contains("radius")
                            || (n.contains("corner")
                                && (n.contains("track") || n.contains("background")));

            if (!looksLikeOuterRadius) return;

            try {
                f.setAccessible(true);
                f.setFloat(o, value);
            } catch (Throwable ignored) {
                // Known explicit fields above still provide the normal path.
            }
        });
    }

    private static void zeroMatchingGrabberSpacingFields(Object o) {
        forEachField(o.getClass(), f -> {
            if (!isFloatField(f)) return;

            String n = f.getName().toLowerCase(Locale.ROOT);
            boolean belongsToGrabber =
                    n.contains("thumb")
                            || n.contains("grabber")
                            || n.contains("handle")
                            || n.contains("indicator");

            boolean isSpacing =
                    n.contains("gap")
                            || n.contains("padding")
                            || n.contains("spacing")
                            || n.contains("space");

            if (!(belongsToGrabber && isSpacing)) return;

            try {
                f.setAccessible(true);
                f.setFloat(o, 0f);
            } catch (Throwable ignored) {
                // Best effort only.
            }
        });
    }

    private interface FieldAction {
        void apply(Field f);
    }

    private static void forEachField(Class<?> type, FieldAction action) {
        Class<?> c = type;
        while (c != null) {
            Field[] fields;
            try {
                fields = c.getDeclaredFields();
            } catch (Throwable ignored) {
                c = c.getSuperclass();
                continue;
            }

            for (Field f : fields) {
                try {
                    if (Modifier.isStatic(f.getModifiers())) continue;
                    action.apply(f);
                } catch (Throwable ignored) {
                    // Continue with the remaining fields.
                }
            }

            c = c.getSuperclass();
        }
    }

    private static boolean isFloatField(Field f) {
        return f.getType() == float.class || f.getType() == Float.class;
    }

    private static void setRadiusField(Object o, String name, float value) {
        writeFloatIfPresent(o, name, value);
    }

    private static void zeroFloatIfPresent(Object o, String name) {
        writeFloatIfPresent(o, name, 0f);
    }

    private static void writeFloatIfPresent(Object o, String name, float value) {
        if (hasField(o, name)) writeFloat(o, name, value);
    }

    private static Object findArg(Object[] args, String fqcn) {
        if (args == null) return null;
        for (Object arg : args) {
            if (arg != null && fqcn.equals(arg.getClass().getName())) return arg;
        }
        return null;
    }

    private static int setting(String key, int def) {
        return switch (key) {
            case "restoreroundedsliders_brightness_roundness",
                    "roundsliders_brightness_roundness" ->
                    brightnessRoundness;
            case "restoreroundedsliders_brightness_grabber",
                    "roundsliders_brightness_grabber" ->
                    brightnessGrabber ? 1 : 0;
            case "restoreroundedsliders_volume_roundness",
                    "roundsliders_volume_roundness" ->
                    volumeRoundness;
            case "restoreroundedsliders_volume_grabber",
                    "roundsliders_volume_grabber" ->
                    volumeGrabber ? 1 : 0;
            default -> def;
        };
    }

    private static void reloadPreferences() {
        if (Xprefs == null) return;
        classicSlidersEnabled = Xprefs.getBoolean("ClassicSlidersEnabled", false);
        brightnessRoundness = Xprefs.getSliderInt("ClassicBrightnessRoundness", 100);
        brightnessGrabber = Xprefs.getBoolean("ClassicBrightnessGrabber", false);
        volumeRoundness = Xprefs.getSliderInt("ClassicVolumeRoundness", 100);
        volumeGrabber = Xprefs.getBoolean("ClassicVolumeGrabber", false);
    }

    /**
     * Read the new RestoreRoundedSliders key first while remaining compatible
     * with the existing RoundSliders WebUI/settings from the prototype build.
     */
    private static int settingCompat(String newKey, String oldKey, int def) {
        final int sentinel = Integer.MIN_VALUE;
        int value = setting(newKey, sentinel);
        if (value != sentinel) return value;
        return setting(oldKey, def);
    }

    private static int clampPercent(int value) {
        return Math.max(0, Math.min(100, value));
    }

    private static int clampThickness(int value) {
        // Wide enough for the requested percentage slider but prevents absurd
        // dimensions that could make SystemUI difficult to use.
        return Math.max(25, Math.min(200, value));
    }

    private static boolean hasField(Object o, String name) {
        try {
            findField(o.getClass(), name);
            return true;
        } catch (Throwable ignored) {
            return false;
        }
    }

    private static Float readFloat(Object o, String name) {
        try {
            Field f = findField(o.getClass(), name);
            f.setAccessible(true);
            return f.getFloat(o);
        } catch (Throwable ignored) {
            return null;
        }
    }

    private static void writeFloat(Object o, String name, float value) {
        try {
            Field f = findField(o.getClass(), name);
            f.setAccessible(true);
            f.setFloat(o, value);
        } catch (Throwable t) {
            log("Could not write " + name, t);
        }
    }

    private static Field findField(Class<?> type, String name) throws NoSuchFieldException {
        Class<?> c = type;
        while (c != null) {
            try {
                return c.getDeclaredField(name);
            } catch (NoSuchFieldException ignored) {
                c = c.getSuperclass();
            }
        }
        throw new NoSuchFieldException(name);
    }

    public static void log(String s) {
        XposedBridge.log(TAG + ": " + s);
    }

    public static void log(String s, Throwable t) {
        XposedBridge.log(TAG + ": " + s);
        XposedBridge.log(t);
    }

    private static final class SliderConfig {
        final String name;
        final int thickness;
        final int roundness;
        final boolean grabber;

        SliderConfig(String name, int thickness, int roundness, boolean grabber) {
            this.name = name;
            this.thickness = clampThickness(thickness);
            this.roundness = clampPercent(roundness);
            this.grabber = grabber;
        }

        static SliderConfig brightness() {
            return new SliderConfig(
                    "brightness",
                    100,
                    settingCompat(
                            "restoreroundedsliders_brightness_roundness",
                            "roundsliders_brightness_roundness",
                            100),
                    settingCompat(
                            "restoreroundedsliders_brightness_grabber",
                            "roundsliders_brightness_grabber",
                            0) != 0
            );
        }

        static SliderConfig volume() {
            return new SliderConfig(
                    "volume",
                    100,
                    settingCompat(
                            "restoreroundedsliders_volume_roundness",
                            "roundsliders_volume_roundness",
                            100),
                    settingCompat(
                            "restoreroundedsliders_volume_grabber",
                            "roundsliders_volume_grabber",
                            0) != 0
            );
        }
    }

    private static final class SliderContext {
        private static final ThreadLocal<State> TLS = new ThreadLocal<>();

        static void set(SliderConfig config, Float trackHeight) {
            TLS.set(new State(config, trackHeight == null ? 0f : trackHeight));
        }

        static State get() {
            return TLS.get();
        }

        static void clear() {
            TLS.remove();
        }

        static final class State {
            final SliderConfig config;
            final float trackHeight;

            State(SliderConfig config, float trackHeight) {
                this.config = config;
                this.trackHeight = trackHeight;
            }
        }
    }
}
