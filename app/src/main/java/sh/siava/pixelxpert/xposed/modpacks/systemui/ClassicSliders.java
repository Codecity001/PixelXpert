package sh.siava.pixelxpert.xposed.modpacks.systemui;

import static sh.siava.pixelxpert.xposed.XPrefs.Xprefs;

import android.content.Context;

import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.lang.reflect.Modifier;

import de.robv.android.xposed.XC_MethodHook;
import de.robv.android.xposed.XposedBridge;
import de.robv.android.xposed.XposedHelpers;
import io.github.libxposed.api.XposedModuleInterface;
import sh.siava.pixelxpert.xposed.XposedModPack;
import sh.siava.pixelxpert.xposed.annotations.SystemUIModPack;
import sh.siava.pixelxpert.xposed.utils.toolkit.Logger;

/** Restores the classic rounded Pixel brightness and volume slider geometry. */
@SystemUIModPack
public class ClassicSliders extends XposedModPack {
    private static final String BRIGHTNESS_KT =
            "com.android.systemui.brightness.ui.compose.BrightnessSliderKt";
    private static final String BRIGHTNESS_DIMS =
            "com.android.systemui.brightness.ui.compose.BrightnessSliderDimensions";
    private static final String VOLUME_KT =
            "com.android.systemui.volume.panel.component.volume.ui.composable.VolumeSliderKt";
    private static final String VOLUME_DIMS =
            "com.android.systemui.volume.panel.component.volume.ui.composable.VolumeSliderDimensions";

    private volatile boolean enabled;
    private volatile int brightnessRoundness = 100;
    private volatile boolean brightnessGrabber;
    private volatile int volumeRoundness = 100;
    private volatile boolean volumeGrabber;

    private final ThreadLocal<SliderState> sliderState = new ThreadLocal<>();

    public ClassicSliders(Context context) {
        super(context);
    }

    @Override
    public void onPreferenceUpdated(String... keys) {
        if (Xprefs == null) return;
        enabled = Xprefs.getBoolean("ClassicSlidersEnabled", false);
        brightnessRoundness = clamp(Xprefs.getSliderInt("ClassicBrightnessRoundness", 100));
        brightnessGrabber = Xprefs.getBoolean("ClassicBrightnessGrabber", false);
        volumeRoundness = clamp(Xprefs.getSliderInt("ClassicVolumeRoundness", 100));
        volumeGrabber = Xprefs.getBoolean("ClassicVolumeGrabber", false);
    }

    @Override
    public void onPackageLoaded(XposedModuleInterface.PackageReadyParam param) {
        ClassLoader classLoader = param.getClassLoader();
        hookSlider(classLoader, BRIGHTNESS_KT, "BrightnessSlider", BRIGHTNESS_DIMS, true);
        hookSlider(classLoader, VOLUME_KT, "VolumeSlider", VOLUME_DIMS, false);
        hookPlatformSlider(classLoader);
    }

    private void hookSlider(ClassLoader cl, String className, String methodName,
                            String dimensionsClass, boolean brightness) {
        try {
            Class<?> slider = XposedHelpers.findClass(className, cl);
            XposedBridge.hookAllMethods(slider, methodName, new XC_MethodHook() {
                @Override
                protected void beforeHookedMethod(MethodHookParam param) {
                    if (!enabled) return;
                    Object dimensions = findArgument(param.args, dimensionsClass);
                    if (dimensions == null) return;

                    SliderState state = brightness
                            ? new SliderState(brightnessRoundness, brightnessGrabber)
                            : new SliderState(volumeRoundness, volumeGrabber);
                    applyDimensions(dimensions, state);
                    sliderState.set(state.withTrackHeight(readFloat(dimensions, "trackHeight")));
                }

                @Override
                protected void afterHookedMethod(MethodHookParam param) {
                    sliderState.remove();
                }
            });
            Logger.log("Classic sliders: hooked " + methodName);
        } catch (Throwable t) {
            Logger.log("Classic sliders: " + methodName + " is unavailable", t);
        }
    }

    private void hookPlatformSlider(ClassLoader cl) {
        try {
            Class<?> platform = XposedHelpers.findClass("com.android.compose.PlatformSliderKt", cl);
            for (Method method : platform.getDeclaredMethods()) {
                String name = method.getName();
                int[] floats = floatArguments(method);
                if (floats.length < 2) continue;

                if (name.contains("PlatformSlider") && !name.contains("$default")) {
                    XposedBridge.hookMethod(method, radiusHook(floats, false));
                } else if (name.contains("TrackBackground")) {
                    XposedBridge.hookMethod(method, radiusHook(floats, true));
                }
            }
        } catch (Throwable t) {
            Logger.log("Classic sliders: PlatformSlider is unavailable", t);
        }
    }

    private XC_MethodHook radiusHook(int[] floatArguments, boolean allRadii) {
        return new XC_MethodHook() {
            @Override
            protected void beforeHookedMethod(MethodHookParam param) {
                SliderState state = sliderState.get();
                if (state == null || state.trackHeight <= 0f) return;
                float radius = state.trackHeight * .5f * state.roundness / 100f;
                if (allRadii) {
                    for (int index : floatArguments) param.args[index] = radius;
                } else {
                    // The value is the first float parameter; the corner radius is the last.
                    param.args[floatArguments[floatArguments.length - 1]] = radius;
                }
            }
        };
    }

    private static void applyDimensions(Object dimensions, SliderState state) {
        if (state.grabber) return;

        Float heightValue = readFloat(dimensions, "trackHeight");
        float height = heightValue == null ? 0f : heightValue;
        float radius = height * .5f * state.roundness / 100f;

        for (String field : new String[]{
                "backgroundRoundedCorner", "trackRoundedCorner", "activeTrackRoundedCorner",
                "inactiveTrackRoundedCorner", "trackCornerRadius", "activeTrackCornerRadius",
                "inactiveTrackCornerRadius", "trackCornerSize"}) {
            writeFloatIfPresent(dimensions, field, radius);
        }
        for (String field : new String[]{"trackInsideCornerSize", "insideCornerSize", "insideCornerRadius",
                "thumbWidth", "grabberWidth", "handleWidth", "indicatorWidth", "thumbTrackGapSize",
                "thumbTrackGap", "thumbGap", "grabberGap", "handleGap"}) {
            writeFloatIfPresent(dimensions, field, 0f);
        }

        // Pixel releases rename dimension fields. Cover corresponding float fields without
        // touching unrelated geometry or the actual slider value.
        forEachFloatField(dimensions, field -> {
            String name = field.getName().toLowerCase();
            if (name.contains("inside") && name.contains("corner")) writeFloat(dimensions, field, 0f);
            if ((name.contains("thumb") || name.contains("grabber") || name.contains("handle"))
                    && (name.contains("gap") || name.contains("spacing") || name.contains("padding"))) {
                writeFloat(dimensions, field, 0f);
            }
            if (!name.contains("inside") && name.contains("corner")
                    && (name.contains("track") || name.contains("background"))) {
                writeFloat(dimensions, field, radius);
            }
        });
    }

    private interface FieldConsumer { void accept(Field field); }

    private static void forEachFloatField(Object object, FieldConsumer action) {
        for (Class<?> type = object.getClass(); type != null; type = type.getSuperclass()) {
            for (Field field : type.getDeclaredFields()) {
                if (!Modifier.isStatic(field.getModifiers())
                        && (field.getType() == float.class || field.getType() == Float.class)) action.accept(field);
            }
        }
    }

    private static Object findArgument(Object[] args, String className) {
        for (Object arg : args) if (arg != null && className.equals(arg.getClass().getName())) return arg;
        return null;
    }

    private static int[] floatArguments(Method method) {
        Class<?>[] types = method.getParameterTypes();
        int count = 0;
        for (Class<?> type : types) if (type == float.class) count++;
        int[] result = new int[count];
        for (int i = 0, j = 0; i < types.length; i++) if (types[i] == float.class) result[j++] = i;
        return result;
    }

    private static Float readFloat(Object object, String name) {
        try { return findField(object.getClass(), name).getFloat(object); } catch (Throwable ignored) { return null; }
    }

    private static void writeFloatIfPresent(Object object, String name, float value) {
        try { writeFloat(object, findField(object.getClass(), name), value); } catch (Throwable ignored) { }
    }

    private static void writeFloat(Object object, Field field, float value) {
        try { field.setAccessible(true); field.setFloat(object, value); } catch (Throwable ignored) { }
    }

    private static Field findField(Class<?> type, String name) throws NoSuchFieldException {
        for (Class<?> current = type; current != null; current = current.getSuperclass()) {
            try { return current.getDeclaredField(name); } catch (NoSuchFieldException ignored) { }
        }
        throw new NoSuchFieldException(name);
    }

    private static int clamp(int value) { return Math.max(0, Math.min(100, value)); }

    private static final class SliderState {
        final int roundness;
        final boolean grabber;
        final float trackHeight;

        SliderState(int roundness, boolean grabber) { this(roundness, grabber, 0f); }
        SliderState(int roundness, boolean grabber, float trackHeight) {
            this.roundness = roundness;
            this.grabber = grabber;
            this.trackHeight = trackHeight;
        }
        SliderState withTrackHeight(Float height) {
            return new SliderState(roundness, grabber, height == null ? 0f : height);
        }
    }
}
