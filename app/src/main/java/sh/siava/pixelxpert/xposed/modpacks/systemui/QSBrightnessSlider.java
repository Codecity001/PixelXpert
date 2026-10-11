package sh.siava.pixelxpert.xposed.modpacks.systemui;

import static de.robv.android.xposed.XposedHelpers.callMethod;
import static de.robv.android.xposed.XposedHelpers.getStaticObjectField;
import static de.robv.android.xposed.XposedHelpers.getObjectField;
import static sh.siava.pixelxpert.xposed.XPrefs.Xprefs;

import android.content.Context;
import android.content.res.Resources;
import android.database.ContentObserver;
import android.graphics.Color;
import android.graphics.drawable.Drawable;
import android.graphics.drawable.LayerDrawable;
import android.graphics.drawable.ShapeDrawable;
import android.graphics.drawable.shapes.OvalShape;
import android.os.Handler;
import android.os.Looper;
import android.os.SystemClock;
import android.provider.Settings;
import android.view.Gravity;
import android.view.HapticFeedbackConstants;
import android.view.MotionEvent;
import android.view.View;
import android.view.ViewConfiguration;
import android.view.ViewGroup;
import android.widget.FrameLayout;
import android.widget.LinearLayout;

import androidx.appcompat.content.res.AppCompatResources;
import androidx.core.content.res.ResourcesCompat;

import java.lang.reflect.Constructor;
import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.lang.reflect.Proxy;
import java.util.WeakHashMap;

import io.github.libxposed.api.XposedModuleInterface;
import sh.siava.pixelxpert.Constants;
import sh.siava.pixelxpert.R;
import sh.siava.pixelxpert.xposed.XposedModPack;
import sh.siava.pixelxpert.xposed.annotations.SystemUIModPack;
import sh.siava.pixelxpert.xposed.utils.reflection.ReflectedClass;

@SystemUIModPack
public class QSBrightnessSlider extends XposedModPack {
	private boolean brightnessBelowTiles = false;
	private boolean brightnessInQqs = false;
	private boolean autoBrightnessToggle = false;
	private long mLastAutoToggleClick = 0;
	private Object QSTV = null;
	private final WeakHashMap<View, Boolean> activeToggleViews = new WeakHashMap<>();

	private Class<?> function0Class = null;
	private Class<?> function1Class = null;
	private Class<?> function2Class = null;
	private Class<?> function3Class = null;
	private Class<?> modifierClass = null;
	private Class<?> composerClass = null;
	private Method androidViewMethod = null;
	private Method paddingMethod = null;
	private Method sizeMethod = null;
	private Method alignMethod = null;
	private Object boxScopeInstance = null;
	private Object centerEndAlignment = null;

	private Object kotlinUnit = null;
	private Object modifierCompanion = null;
	private Object sharedElementKey = null;
	private Method brightnessContainerMethod = null;
	private Constructor<?> containerColorsConstructor = null;
	private Method rememberViewModelMethod = null;
	private Object sceneBrightnessElementKey = null;

	private Object qsFragment = null;
	private Object qqsScope = null;
	private Object qsScope = null;

	private final WeakHashMap<Object, Object> qsBrightnessSlots = new WeakHashMap<>();
	private final WeakHashMap<Object, Object> qqsTilesSlots = new WeakHashMap<>();
	private final WeakHashMap<Object, android.util.Pair<Object, Object>> mediaRowSlots = new WeakHashMap<>();
	private final WeakHashMap<Object, android.util.Pair<Object, Object>> sceneMediaRowSlots = new WeakHashMap<>();
	private final java.util.Set<Object> arrangedFirstSlots = java.util.Collections.newSetFromMap(new WeakHashMap<>());
	private Object emptyComposableSlot = null;
	private Object emptyScopedComposableSlot = null;

	private Object shadeSceneViewModel = null;
	private Object shadeScope = null;
	private final WeakHashMap<Object, Object> shadeQqsSlots = new WeakHashMap<>();

	private String className(String pkg, String cls) {
		return pkg + "." + cls;
	}

	public QSBrightnessSlider(Context context) {
		super(context);
	}

	@Override
	public void onPreferenceUpdated(String... key) {
		if (Xprefs == null) return;
		brightnessBelowTiles = Xprefs.getBoolean("qs_brightness_slider_bottom", false);
		brightnessInQqs = Xprefs.getBoolean("qqs_brightness_slider", false);
		autoBrightnessToggle = Xprefs.getBoolean("QSAutoBrightnessToggle", false);

		qsBrightnessSlots.clear();
		qqsTilesSlots.clear();
		shadeQqsSlots.clear();
		updateAllToggleViews();
	}

	@Override
	public void onPackageLoaded(XposedModuleInterface.PackageReadyParam PRParam) throws Throwable {
		resolveComposeApis();
		registerBrightnessModeObserver();
		hookQsFragmentCompose();
		hookSceneContainer();
		hookComposeBrightnessSlider();
		hookBrightnessSliderView();
	}

	private void hookQsFragmentCompose() {
		ReflectedClass qsFragmentClass = ReflectedClass.ofIfPossible("com.android.systemui.qs.composefragment.QSFragmentCompose");
		ReflectedClass qsLayoutClass = ReflectedClass.ofIfPossible("com.android.systemui.qs.composefragment.QSFragmentComposeKt");

		if (qsFragmentClass == null || qsFragmentClass.getClazz() == null || qsLayoutClass == null || qsLayoutClass.getClazz() == null) {
			return;
		}

		qsFragmentClass.before("QuickQuickSettingsElement").run(param -> {
			qsFragment = param.thisObject;
			qqsScope = param.args.length > 0 ? param.args[0] : null;
		});

		qsFragmentClass.before("QuickSettingsElement").run(param -> {
			qsFragment = param.thisObject;
			qsScope = param.args.length > 0 ? param.args[0] : null;
		});

		qsLayoutClass.before("QuickSettingsLayout").run(param -> {
			if (!brightnessBelowTiles && !brightnessInQqs) return;
			if (param.args.length < 3) return;

			Object brightness = param.args[0];
			if (brightness == null) return;
			if (arrangedFirstSlots.contains(brightness)) return;

			Object tiles = param.args[1];
			if (tiles == null) return;

			Object brightnessSlot = brightnessInQqs ? sharedQsBrightnessSlot(brightness) : brightness;
			if (brightnessSlot == null) brightnessSlot = brightness;

			if (!brightnessBelowTiles) {
				param.args[0] = brightnessSlot;
				return;
			}

			int mediaInRowIndex = -1;
			for (int i = 0; i < param.args.length; i++) {
				if (param.args[i] instanceof Boolean) {
					mediaInRowIndex = i;
					break;
				}
			}
			Boolean mediaInRow = mediaInRowIndex != -1 ? (Boolean) param.args[mediaInRowIndex] : null;
			Object media = param.args[2];
			Object emptySlot = emptySlot();

			if (mediaInRow == null || !mediaInRow || media == null || emptySlot == null) {
				arrangedFirstSlots.add(tiles);
				param.args[0] = tiles;
				param.args[1] = brightnessSlot;
				return;
			}

			Method method = (Method) param.method;
			int composerIndex = -1;
			Class<?>[] pts = method.getParameterTypes();
			for (int i = 0; i < pts.length; i++) {
				if (className("androidx.compose.runtime", "Composer").equals(pts[i].getName())) {
					composerIndex = i;
					break;
				}
			}
			Object composer = composerIndex != -1 ? param.args[composerIndex] : null;
			if (composer == null) return;

			android.util.Pair<Object, Object> cached = mediaRowSlots.get(tiles);
			Object tilesAndMediaRow = null;
			if (cached != null && cached.first == media) {
				tilesAndMediaRow = cached.second;
			} else {
				tilesAndMediaRow = composableSlot((rowComposer, changed) -> {
					composeOriginalLayout(method, emptySlot, tiles, media, true, rowComposer);
				});
				if (tilesAndMediaRow != null) {
					mediaRowSlots.put(tiles, new android.util.Pair<>(media, tilesAndMediaRow));
					arrangedFirstSlots.add(tilesAndMediaRow);
				} else {
					return;
				}
			}

			startGroup(composer, 0x1C0B5A1D);
			try {
				composeOriginalLayout(method, tilesAndMediaRow, brightnessSlot, emptySlot, false, composer);
			} finally {
				endGroup(composer);
			}
			param.setResult(null);
		});
		qsLayoutClass.before("QuickQuickSettingsLayout").run(param -> {
			if (!brightnessInQqs) return;

			Boolean mediaInRow = null;
			for (Object arg : param.args) {
				if (arg instanceof Boolean) {
					mediaInRow = (Boolean) arg;
					break;
				}
			}
			if (mediaInRow == null || mediaInRow) return;

			Object tiles = param.args.length > 0 ? param.args[0] : null;
			if (tiles == null) return;

			if (qqsTilesSlots.containsValue(tiles)) return;

			Object existingSlot = qqsTilesSlots.get(tiles);
			if (existingSlot != null) {
				param.args[0] = existingSlot;
				return;
			}

			Object slot = composableSlot((composer, changed) -> {
				if (brightnessBelowTiles) {
					callMethod(tiles, "invoke", composer, changed);
					composeQqsBrightness(composer);
				} else {
					composeQqsBrightness(composer);
					callMethod(tiles, "invoke", composer, changed);
				}
			});
			if (slot == null) return;

			qqsTilesSlots.put(tiles, slot);
			param.args[0] = slot;
		});
	}

	private void hookSceneContainer() {
		ReflectedClass qsContentKtClass = ReflectedClass.ofIfPossible("com.android.systemui.qs.ui.composable.QuickSettingsContentKt");
		if (qsContentKtClass != null && qsContentKtClass.getClazz() != null) {
			for (Method m : qsContentKtClass.getClazz().getDeclaredMethods()) {
				if (m.getName().startsWith("QuickSettingsPanelLayout")) {
					qsContentKtClass.before(m.getName()).run(param -> {
						if (!brightnessBelowTiles || param.args.length < 2) return;
						Object brightness = param.args[0];
						if (brightness == null) return;
						if (arrangedFirstSlots.contains(brightness)) return;
						Object tiles = param.args[1];
						if (tiles == null) return;

						Method method = (Method) param.method;
						int mediaInRowIndex = -1;
						Class<?>[] pts = method.getParameterTypes();
						for (int i = 0; i < pts.length; i++) {
							if (pts[i] == boolean.class) {
								mediaInRowIndex = i;
								break;
							}
						}
						Boolean mediaInRow = mediaInRowIndex != -1 ? (Boolean) param.args[mediaInRowIndex] : null;
						Object media = param.args.length > 2 ? param.args[2] : null;
						Object emptySlot = emptyScopedSlot();

						int composerIndex = -1;
						for (int i = 0; i < pts.length; i++) {
							if (className("androidx.compose.runtime", "Composer").equals(pts[i].getName())) {
								composerIndex = i;
								break;
							}
						}
						Object composer = composerIndex != -1 ? param.args[composerIndex] : null;

						if (mediaInRow == null || !mediaInRow || media == null || emptySlot == null || composer == null) {
							arrangedFirstSlots.add(tiles);
							param.args[0] = tiles;
							param.args[1] = brightness;
							return;
						}

						Object[] originalArgs = param.args.clone();
						android.util.Pair<Object, Object> cached = sceneMediaRowSlots.get(tiles);
						Object tilesAndMediaRow = null;
						final int finalMediaInRowIndex = mediaInRowIndex;
						if (cached != null && cached.first == media) {
							tilesAndMediaRow = cached.second;
						} else {
							tilesAndMediaRow = composableScopedSlot((scope, rowComposer, changed) -> {
								invokeScenePanelLayout(method, originalArgs, new Object[]{emptySlot, tiles, media}, finalMediaInRowIndex, true, modifierCompanion, rowComposer);
							});
							if (tilesAndMediaRow != null) {
								sceneMediaRowSlots.put(tiles, new android.util.Pair<>(media, tilesAndMediaRow));
								arrangedFirstSlots.add(tilesAndMediaRow);
							} else {
								return;
							}
						}

						startGroup(composer, 0x1C0B5A1E);
						try {
							invokeScenePanelLayout(method, originalArgs, new Object[]{tilesAndMediaRow, brightness, emptySlot}, mediaInRowIndex, false, null, composer);
						} finally {
							endGroup(composer);
						}
						param.setResult(null);
					});
				}
			}
		}
		ReflectedClass shadeSceneClass = ReflectedClass.ofIfPossible("com.android.systemui.shade.ui.composable.ShadeSceneKt");
		if (shadeSceneClass == null || shadeSceneClass.getClazz() == null) return;

		shadeSceneClass.before("SingleShade").run(param -> {
			for (Object arg : param.args) {
				if (arg == null) continue;
				String className = arg.getClass().getName();
				if (className("com.android.systemui.shade.ui.viewmodel", "ShadeSceneContentViewModel").equals(className)) {
					shadeSceneViewModel = arg;
				}
				if (isContentScope(arg)) {
					shadeScope = arg;
				}
			}

		});

		for (Method m : shadeSceneClass.getClazz().getDeclaredMethods()) {
			if (m.getName().startsWith("MediaAndQqsLayout")) {
				shadeSceneClass.before(m.getName()).run(param -> {
					boolean split = isSplitShade();

					Boolean mediaInRow = null;
					for (Object arg : param.args) {
						if (arg instanceof Boolean) {
							mediaInRow = (Boolean) arg;
							break;
						}
					}

					if (!brightnessInQqs) return;
					if (split) {
						return;
					}
					if (mediaInRow == null || mediaInRow) return;

					Object qqs = param.args.length > 0 ? param.args[0] : null;
					if (qqs == null) return;
					if (shadeQqsSlots.containsValue(qqs)) return;

					Object existingSlot = shadeQqsSlots.get(qqs);
					if (existingSlot != null) {
						param.args[0] = existingSlot;
						return;
					}

					Object slot = composableSlot((composer, changed) -> {
						if (brightnessBelowTiles) {
							callMethod(qqs, "invoke", composer, changed);
							composeShadeBrightness(composer);
						} else {
							composeShadeBrightness(composer);
							callMethod(qqs, "invoke", composer, changed);
						}
					});
					if (slot == null) {
						return;
					}

					shadeQqsSlots.put(qqs, slot);
					param.args[0] = slot;
				});
			}
		}
	}

	private void composeShadeBrightness(Object composer) {
		Object containerViewModel = rememberShadeContainerViewModel(composer);
		if (containerViewModel == null) return;
		Object brightnessViewModel = getObjectFieldSilently(containerViewModel, "brightnessSliderViewModel");
		if (brightnessViewModel == null) return;

		Object scope = shadeScope;
		Object key = sceneBrightnessElementKey != null ? sceneBrightnessElementKey : sharedElementKey;


		if (scope != null && key != null) {
			composeElement(scope, key, composer, () -> {
				composeBrightnessContainer(composer, brightnessViewModel);
			});
		} else {
			composeBrightnessContainer(composer, brightnessViewModel);
		}
	}

	private Object rememberShadeContainerViewModel(Object composer) {
		if (rememberViewModelMethod == null) return null;
		Object factory = getObjectFieldSilently(shadeSceneViewModel, "qsContainerViewModelFactory");
		if (factory == null) return null;

		Object provider = function0Proxy(() -> {
			try {
				return callMethod(factory, "create", false);
			} catch (Throwable t) {
				return null;
			}
		});
		if (provider == null) return null;

		Class<?>[] parameterTypes = rememberViewModelMethod.getParameterTypes();
		int composerIndex = -1;
		for (int i = 0; i < parameterTypes.length; i++) {
			if (className("androidx.compose.runtime", "Composer").equals(parameterTypes[i].getName())) {
				composerIndex = i;
				break;
			}
		}
		if (composerIndex == -1) return null;

		int defaultMask = 0;
		boolean stringFilled = false;
		Object[] args = new Object[parameterTypes.length];

		for (int index = 0; index < parameterTypes.length; index++) {
			Class<?> type = parameterTypes[index];
			if (index == composerIndex) {
				args[index] = composer;
			} else if (index > composerIndex) {
				args[index] = 0;
			} else if (type == String.class && !stringFilled) {
				stringFilled = true;
				args[index] = "iconify_qqs_brightness";
			} else if (className("kotlin.jvm.functions", "Function0").equals(type.getName())) {
				args[index] = provider;
			} else {
				defaultMask |= (1 << index);
				args[index] = null;
			}
		}

		if (parameterTypes.length - composerIndex - 1 == 2) {
			args[parameterTypes.length - 1] = defaultMask;
		}

		try {
			return rememberViewModelMethod.invoke(null, args);
		} catch (Throwable t) {
			return null;
		}
	}

	private boolean isContentScope(Object arg) {
		if (arg == null) return false;
		for (Class<?> iface : arg.getClass().getInterfaces()) {
			if (className("com.android.compose.animation.scene", "ContentScope").equals(iface.getName())) {
				return true;
			}
		}
		for (Method m : arg.getClass().getMethods()) {
			if ("Element".equals(m.getName()) && m.getParameterTypes().length == 5) {
				return true;
			}
		}
		return false;
	}

	private boolean isSplitShade() {
		int id = mContext.getResources().getIdentifier(
			"config_use_split_notification_shade",
			"bool",
			Constants.SYSTEM_UI_PACKAGE
		);
		return id != 0 && mContext.getResources().getBoolean(id);
	}

	private ClassLoader sysUiClassLoader() {
		ReflectedClass anchor = ReflectedClass.ofIfPossible("com.android.systemui.shade.ui.composable.ShadeSceneKt");
		if (anchor == null || anchor.getClazz() == null) return mContext.getClassLoader();
		return anchor.getClazz().getClassLoader();
	}

	private void resolveComposeApis() {
		ClassLoader cl = sysUiClassLoader();
		try {
			function0Class = de.robv.android.xposed.XposedHelpers.findClassIfExists(className("kotlin.jvm.functions", "Function0"), cl);
			function1Class = de.robv.android.xposed.XposedHelpers.findClassIfExists(className("kotlin.jvm.functions", "Function1"), cl);
			function2Class = de.robv.android.xposed.XposedHelpers.findClassIfExists(className("kotlin.jvm.functions", "Function2"), cl);
			function3Class = de.robv.android.xposed.XposedHelpers.findClassIfExists(className("kotlin.jvm.functions", "Function3"), cl);
			modifierClass = de.robv.android.xposed.XposedHelpers.findClassIfExists(className("androidx.compose.ui", "Modifier"), cl);
			composerClass = de.robv.android.xposed.XposedHelpers.findClassIfExists(className("androidx.compose.runtime", "Composer"), cl);
		} catch (Throwable ignored) {
		}

		try {
			Class<?> paddingKtClass = de.robv.android.xposed.XposedHelpers.findClassIfExists(className("androidx.compose.foundation.layout", "PaddingKt"), cl);
			if (paddingKtClass != null) {
				for (Method m : paddingKtClass.getDeclaredMethods()) {
					if (m.getName().startsWith("padding-qDBjuR0")) {
						paddingMethod = m;
						paddingMethod.setAccessible(true);
						break;
					}
				}
			}
		} catch (Throwable ignored) {}

		try {
			Class<?> sizeKtClass = de.robv.android.xposed.XposedHelpers.findClassIfExists(className("androidx.compose.foundation.layout", "SizeKt"), cl);
			if (sizeKtClass != null) {
				for (Method m : sizeKtClass.getDeclaredMethods()) {
					if (m.getName().startsWith("size-3ABfNKs")) {
						sizeMethod = m;
						sizeMethod.setAccessible(true);
						break;
					}
				}
			}
		} catch (Throwable ignored) {}

		try {
			Class<?> boxScopeInstanceClass = de.robv.android.xposed.XposedHelpers.findClassIfExists(className("androidx.compose.foundation.layout", "BoxScopeInstance"), cl);
			if (boxScopeInstanceClass != null) {
				Field instanceField = boxScopeInstanceClass.getDeclaredField("INSTANCE");
				instanceField.setAccessible(true);
				boxScopeInstance = instanceField.get(null);
				for (Method m : boxScopeInstanceClass.getDeclaredMethods()) {
					if ("align".equals(m.getName()) && m.getParameterTypes().length == 2) {
						alignMethod = m;
						alignMethod.setAccessible(true);
						break;
					}
				}
			}
		} catch (Throwable ignored) {}

		try {
			Class<?> alignmentCompanionClass = de.robv.android.xposed.XposedHelpers.findClassIfExists(className("androidx.compose.ui", "Alignment$Companion"), cl);
			if (alignmentCompanionClass != null) {
				Field centerEndField = alignmentCompanionClass.getDeclaredField("CenterEnd");
				centerEndField.setAccessible(true);
				centerEndAlignment = centerEndField.get(null);
			}
		} catch (Throwable ignored) {}

		try {
			Class<?> androidViewKtClass = de.robv.android.xposed.XposedHelpers.findClassIfExists(className("androidx.compose.ui.viewinterop", "AndroidView_androidKt"), cl);
			if (androidViewKtClass != null) {
				for (Method m : androidViewKtClass.getDeclaredMethods()) {
					if ("AndroidView".equals(m.getName()) && m.getParameterTypes().length == 6) {
						androidViewMethod = m;
						androidViewMethod.setAccessible(true);
						break;
					}
				}
				if (androidViewMethod == null) {
					for (Method m : androidViewKtClass.getDeclaredMethods()) {
						if ("AndroidView".equals(m.getName()) && m.getParameterTypes().length == 8) {
							androidViewMethod = m;
							androidViewMethod.setAccessible(true);
							break;
						}
					}
				}
			}
		} catch (Throwable ignored) {}

		try {
			Class<?> sysUiViewModelKt = de.robv.android.xposed.XposedHelpers.findClassIfExists(className("com.android.systemui.lifecycle", "SysUiViewModelKt"), cl);
			if (sysUiViewModelKt != null) {
				for (Method m : sysUiViewModelKt.getDeclaredMethods()) {
					if ("rememberViewModel".equals(m.getName())) {
						rememberViewModelMethod = m;
						break;
					}
				}
			}
		} catch (Throwable t) {
		}

		try {
			Class<?> qsElementsClass = de.robv.android.xposed.XposedHelpers.findClassIfExists(className("com.android.systemui.qs.shared.ui", "QuickSettings$Elements"), cl);
			if (qsElementsClass != null) {
				Field brightnessSliderField = qsElementsClass.getDeclaredField("BrightnessSlider");
				brightnessSliderField.setAccessible(true);
				sceneBrightnessElementKey = brightnessSliderField.get(null);
			}
		} catch (Throwable t) {
		}

		try {
			Class<?> unitClass = de.robv.android.xposed.XposedHelpers.findClassIfExists(className("kotlin", "Unit"), cl);
			if (unitClass != null) {
				kotlinUnit = getStaticObjectField(unitClass, "INSTANCE");
			}
			if (modifierClass != null) {
				modifierCompanion = getStaticObjectField(modifierClass, "Companion");
			}
		} catch (Throwable t) {
		}

		try {
			Class<?> elementKeyClass = de.robv.android.xposed.XposedHelpers.findClassIfExists(className("com.android.compose.animation.scene", "ElementKey"), cl);
			if (elementKeyClass != null) {
				for (Constructor<?> c : elementKeyClass.getDeclaredConstructors()) {
					if (c.getParameterTypes().length == 6) {
						c.setAccessible(true);
						sharedElementKey = c.newInstance("PXBrightnessSlider", null, null, false, 14, null);
						break;
					}
				}
			}
		} catch (Throwable t) {
		}

		try {
			Class<?> brightnessSliderKtClass = de.robv.android.xposed.XposedHelpers.findClassIfExists(className("com.android.systemui.brightness.ui.compose", "BrightnessSliderKt"), cl);
			if (brightnessSliderKtClass != null) {
				for (Method m : brightnessSliderKtClass.getDeclaredMethods()) {
					if ("BrightnessSliderContainer".equals(m.getName())) {
						brightnessContainerMethod = m;
						brightnessContainerMethod.setAccessible(true);
						break;
					}
				}
			}
		} catch (Throwable t) {
		}

		try {
			Class<?> containerColorsClass = de.robv.android.xposed.XposedHelpers.findClassIfExists(className("com.android.systemui.brightness.ui.compose", "ContainerColors"), cl);
			if (containerColorsClass != null) {
				for (Constructor<?> c : containerColorsClass.getDeclaredConstructors()) {
					if (c.getParameterTypes().length == 2 &&
						c.getParameterTypes()[0] == long.class &&
						c.getParameterTypes()[1] == long.class) {
						containerColorsConstructor = c;
						containerColorsConstructor.setAccessible(true);
						break;
					}
				}
			}
		} catch (Throwable t) {
		}
	}

	private Object sharedQsBrightnessSlot(Object brightness) {
		if (brightness == null) return null;
		if (qsBrightnessSlots.containsValue(brightness)) return brightness;
		Object existingSlot = qsBrightnessSlots.get(brightness);
		if (existingSlot != null) return existingSlot;

		if (qsScope == null || sharedElementKey == null) return null;

		Object slot = composableSlot((composer, changed) -> {
			composeElement(qsScope, sharedElementKey, composer, () -> {
				callMethod(brightness, "invoke", composer, changed);
			});
		});
		if (slot == null) return null;

		qsBrightnessSlots.put(brightness, slot);
		return slot;
	}

	private void composeQqsBrightness(Object composer) {
		if (qqsScope != null && sharedElementKey != null) {
			composeElement(qqsScope, sharedElementKey, composer, () -> composeQsFragmentBrightness(composer));
		} else {
			composeQsFragmentBrightness(composer);
		}
	}

	private void composeElement(Object scope, Object key, Object composer, Runnable content) {
		if (scope == null) {
			if (content != null) content.run();
			return;
		}
		Method elementMethod = null;
		for (Method m : scope.getClass().getMethods()) {
			if ("Element".equals(m.getName()) && m.getParameterTypes().length == 5) {
				elementMethod = m;
				break;
			}
		}
		Object elementContent = composableContent(content != null ? content::run : () -> {});

		if (elementMethod == null || elementContent == null) {
			if (content != null) content.run();
			return;
		}

		try {
			elementMethod.invoke(scope, key, modifierCompanion, elementContent, composer, 0);
		} catch (Throwable ignored) {
			if (content != null) content.run();
		}
	}

	private void composeQsFragmentBrightness(Object composer) {
		if (qsFragment == null) return;
		Object viewModel = getObjectFieldSilently(qsFragment, "viewModel");
		viewModel = getObjectFieldSilently(viewModel, "containerViewModel");
		viewModel = getObjectFieldSilently(viewModel, "brightnessSliderViewModel");
		if (viewModel == null) return;
		composeBrightnessContainer(composer, viewModel);
	}

	private void composeBrightnessContainer(Object composer, Object viewModel) {
		if (brightnessContainerMethod == null || viewModel == null) return;
		try {
			Object colors = containerColors();
			if (colors == null) return;

			Class<?>[] parameterTypes = brightnessContainerMethod.getParameterTypes();
			int composerIndex = -1;
			for (int i = 0; i < parameterTypes.length; i++) {
				if (className("androidx.compose.runtime", "Composer").equals(parameterTypes[i].getName())) {
					composerIndex = i;
					break;
				}
			}
			if (composerIndex == -1) return;

			int defaultMask = 0;
			Object[] args = new Object[parameterTypes.length];

			for (int index = 0; index < parameterTypes.length; index++) {
				Class<?> type = parameterTypes[index];
				if (index == composerIndex) {
					args[index] = composer;
				} else if (index > composerIndex) {
					args[index] = 0;
				} else if (className("com.android.systemui.brightness.ui.viewmodel", "BrightnessSliderViewModel").equals(type.getName())) {
					args[index] = viewModel;
				} else if (className("androidx.compose.ui", "Modifier").equals(type.getName())) {
					args[index] = modifierCompanion;
				} else if (className("com.android.systemui.brightness.ui.compose", "ContainerColors").equals(type.getName())) {
					args[index] = colors;
				} else if (type == boolean.class) {
					defaultMask |= (1 << index);
					args[index] = false;
				} else {
					defaultMask |= (1 << index);
					args[index] = null;
				}
			}

			if (parameterTypes.length - composerIndex - 1 == 2) {
				args[parameterTypes.length - 1] = defaultMask;
			}

			brightnessContainerMethod.invoke(null, args);
		} catch (Throwable ignored) {
		}
	}

	private Object containerColors() {
		if (containerColorsConstructor == null) return null;
		int mirrorColorId = mContext.getResources().getIdentifier(
			"shade_panel_fallback",
			"color",
			Constants.SYSTEM_UI_PACKAGE
		);
		int mirrorColor = 0;
		if (mirrorColorId != 0) {
			mirrorColor = mContext.getColor(mirrorColorId);
		}

		try {
			return containerColorsConstructor.newInstance(
				colorOf(0),
				colorOf(mirrorColor)
			);
		} catch (Throwable t) {
			return null;
		}
	}

	private long colorOf(int color) {
		return (((long) color) & 0xFFFFFFFFL) << 32;
	}

	private interface Function0Block {
		Object invoke();
	}

	private Object function0Proxy(Function0Block block) {
		if (function0Class == null) return null;
		return Proxy.newProxyInstance(
			function0Class.getClassLoader(),
			new Class<?>[]{function0Class},
			(proxy, method, args) -> {
				String methodName = method.getName();
				if ("invoke".equals(methodName)) {
					return block.invoke();
				} else if ("equals".equals(methodName)) {
					return proxy == (args != null && args.length > 0 ? args[0] : null);
				} else if ("hashCode".equals(methodName)) {
					return System.identityHashCode(proxy);
				} else if ("toString".equals(methodName)) {
					return "PXViewModelFactory";
				}
				return null;
			}
		);
	}

	private void startGroup(Object composer, int key) {
		try {
			callMethod(composer, "startReplaceGroup", key);
		} catch (Throwable t) {
			try {
				callMethod(composer, "startReplaceableGroup", key);
			} catch (Throwable ignored) {}
		}
	}

	private void endGroup(Object composer) {
		try {
			callMethod(composer, "endReplaceGroup");
		} catch (Throwable t) {
			try {
				callMethod(composer, "endReplaceableGroup");
			} catch (Throwable ignored) {}
		}
	}

	private Object emptySlot() {
		if (emptyComposableSlot != null) return emptyComposableSlot;
		Object slot = composableSlot((composer, changed) -> {});
		if (slot != null) {
			emptyComposableSlot = slot;
			arrangedFirstSlots.add(slot);
		}
		return slot;
	}

	private Object emptyScopedSlot() {
		if (emptyScopedComposableSlot != null) return emptyScopedComposableSlot;
		Object slot = composableScopedSlot((scope, composer, changed) -> {});
		if (slot != null) {
			emptyScopedComposableSlot = slot;
			arrangedFirstSlots.add(slot);
		}
		return slot;
	}

	private void composeOriginalLayout(Method method, Object brightness, Object tiles, Object media, boolean mediaInRow, Object composer) {
		Object[] slots = new Object[]{brightness, tiles, media};
		int slotIndex = 0;
		Class<?>[] pts = method.getParameterTypes();
		Object[] args = new Object[pts.length];
		for (int i = 0; i < pts.length; i++) {
			Class<?> type = pts[i];
			String name = type.getName();
			if (className("kotlin.jvm.functions", "Function2").equals(name)) {
				args[i] = slotIndex < slots.length ? slots[slotIndex++] : null;
			} else if (type == boolean.class) {
				args[i] = mediaInRow;
			} else if (className("androidx.compose.runtime", "Composer").equals(name)) {
				args[i] = composer;
			} else if (type == int.class) {
				args[i] = 0;
			} else {
				args[i] = null;
			}
		}
		try {
			de.robv.android.xposed.XposedBridge.invokeOriginalMethod(method, null, args);
		} catch (Throwable ignored) {}
	}

	private void invokeScenePanelLayout(Method method, Object[] originalArgs, Object[] slots, int mediaInRowIndex, boolean mediaInRow, Object modifier, Object composer) {
		Object[] args = originalArgs.clone();
		for (int i = 0; i < slots.length && i < args.length; i++) {
			args[i] = slots[i];
		}
		args[mediaInRowIndex] = mediaInRow;
		if (modifier != null) {
			Class<?>[] pts = method.getParameterTypes();
			for (int i = 0; i < pts.length; i++) {
				if (className("androidx.compose.ui", "Modifier").equals(pts[i].getName())) {
					args[i] = modifier;
					break;
				}
			}
		}
		int composerIndex = -1;
		Class<?>[] pts = method.getParameterTypes();
		for (int i = 0; i < pts.length; i++) {
			if (className("androidx.compose.runtime", "Composer").equals(pts[i].getName())) {
				composerIndex = i;
				break;
			}
		}
		if (composerIndex != -1) {
			args[composerIndex] = composer;
			for (int i = composerIndex + 1; i < args.length; i++) {
				args[i] = 0;
			}
		}
		try {
			de.robv.android.xposed.XposedBridge.invokeOriginalMethod(method, null, args);
		} catch (Throwable ignored) {}
	}

	private interface ComposableContentScopedBlock {
		void invoke(Object contentScope, Object composer, Object changed);
	}

	private Object composableScopedSlot(ComposableContentScopedBlock block) {
		if (function3Class == null) return null;
		return Proxy.newProxyInstance(
			function3Class.getClassLoader(),
			new Class<?>[]{function3Class},
			(proxy, method, args) -> {
				String methodName = method.getName();
				if ("invoke".equals(methodName)) {
					block.invoke(args[0], args[1], args[2]);
					return kotlinUnit;
				} else if ("equals".equals(methodName)) {
					return proxy == (args != null && args.length > 0 ? args[0] : null);
				} else if ("hashCode".equals(methodName)) {
					return System.identityHashCode(proxy);
				} else if ("toString".equals(methodName)) {
					return "PXBrightnessElement";
				}
				return null;
			}
		);
	}

	private interface ComposableSlotBlock {
		void invoke(Object composer, Object changed);
	}

	private Object composableSlot(ComposableSlotBlock block) {
		if (function2Class == null) return null;
		return Proxy.newProxyInstance(
			function2Class.getClassLoader(),
			new Class<?>[]{function2Class},
			(proxy, method, args) -> {
				String methodName = method.getName();
				if ("invoke".equals(methodName)) {
					block.invoke(args[0], args[1]);
					return kotlinUnit;
				} else if ("equals".equals(methodName)) {
					return proxy == (args != null && args.length > 0 ? args[0] : null);
				} else if ("hashCode".equals(methodName)) {
					return System.identityHashCode(proxy);
				} else if ("toString".equals(methodName)) {
					return "PXBrightnessSlot";
				}
				return null;
			}
		);
	}

	private interface ComposableContentBlock {
		void invoke();
	}

	private Object composableContent(ComposableContentBlock block) {
		if (function3Class == null) return null;
		return Proxy.newProxyInstance(
			function3Class.getClassLoader(),
			new Class<?>[]{function3Class},
			(proxy, method, args) -> {
				String methodName = method.getName();
				if ("invoke".equals(methodName)) {
					block.invoke();
					return kotlinUnit;
				} else if ("equals".equals(methodName)) {
					return proxy == (args != null && args.length > 0 ? args[0] : null);
				} else if ("hashCode".equals(methodName)) {
					return System.identityHashCode(proxy);
				} else if ("toString".equals(methodName)) {
					return "PXBrightnessElement";
				}
				return null;
			}
		);
	}

	private Object getObjectFieldSilently(Object obj, String fieldName) {
		if (obj == null) return null;
		try {
			return getObjectField(obj, fieldName);
		} catch (Throwable t) {
			return null;
		}
	}

	private void hookComposeBrightnessSlider() {
		ReflectedClass brightnessSliderKt = ReflectedClass.ofIfPossible("com.android.systemui.brightness.ui.compose.BrightnessSliderKt");
		if (brightnessSliderKt == null || brightnessSliderKt.getClazz() == null) return;

		brightnessSliderKt.after("BrightnessSlider").run(param -> {
			if (!autoBrightnessToggle || androidViewMethod == null || modifierCompanion == null ||
				sizeMethod == null || alignMethod == null || boxScopeInstance == null || centerEndAlignment == null) {
				return;
			}

			Object composer = null;
			for (Object arg : param.args) {
				if (arg != null && composerClass != null && composerClass.isInstance(arg)) {
					composer = arg;
					break;
				}
			}
			if (composer == null) return;

			try {
				Object btnModifier = modifierCompanion;
				btnModifier = sizeMethod.invoke(null, btnModifier, 44f);
				btnModifier = alignMethod.invoke(boxScopeInstance, btnModifier, centerEndAlignment);

				Object factory = function1Proxy(context -> createToggleView((Context) context));
				if (factory == null) return;

				Class<?>[] pts = androidViewMethod.getParameterTypes();
				if (pts.length == 6) {
					androidViewMethod.invoke(null, factory, btnModifier, null, composer, 0, 4);
				} else if (pts.length == 8) {
					androidViewMethod.invoke(null, factory, btnModifier, null, null, null, composer, 0, 4 | 8 | 16);
				}
			} catch (Throwable ignored) {}
		});
	}

	private void hookBrightnessSliderView() {
		ReflectedClass brightnessSliderViewClass = ReflectedClass.ofIfPossible("com.android.systemui.settings.brightness.BrightnessSliderView");
		if (brightnessSliderViewClass != null && brightnessSliderViewClass.getClazz() != null) {
			brightnessSliderViewClass.after("onFinishInflate").run(param -> {
				View slider = (View) getObjectFieldSilently(param.thisObject, "mSlider");
				if (slider == null) return;

				slider.post(() -> {
					try {
						ViewGroup parent = (ViewGroup) slider.getParent();
						if (parent == null) return;
						if (parent.findViewWithTag("px_brightness_toggle") != null) return;

						Resources res = slider.getContext().getResources();
						View toggleView = createToggleView(slider.getContext());
						toggleView.setTag("px_brightness_toggle");

						int toggleSize = res.getDimensionPixelSize(
							res.getIdentifier("brightness_mirror_height", "dimen", Constants.SYSTEM_UI_PACKAGE)
						);
						if (toggleSize <= 0) {
							toggleSize = (int) (44 * res.getDisplayMetrics().density);
						}

						FrameLayout.LayoutParams toggleViewParams = new FrameLayout.LayoutParams(
							toggleSize,
							toggleSize,
							Gravity.END | Gravity.CENTER_VERTICAL
						);
						toggleView.setLayoutParams(toggleViewParams);
						parent.addView(toggleView);

						toggleView.setVisibility(autoBrightnessToggle ? View.VISIBLE : View.GONE);
					} catch (Throwable ignored) {}
				});
			});
		}

		ReflectedClass qsTileViewImplClass = ReflectedClass.ofIfPossible("com.android.systemui.qs.tileimpl.QSTileViewImpl");
		if (qsTileViewImplClass != null && qsTileViewImplClass.getClazz() != null) {
			qsTileViewImplClass.afterConstruction().run(param -> {
				QSTV = param.thisObject;
				updateAllToggleViews();
			});
		}
	}

	private void registerBrightnessModeObserver() {
		try {
			mContext.getContentResolver().registerContentObserver(
				Settings.System.getUriFor("screen_brightness_mode"),
				false,
				new ContentObserver(new Handler(Looper.getMainLooper())) {
					@Override
					public void onChange(boolean selfChange) {
						updateAllToggleViews();
					}
				}
			);
		} catch (Throwable ignored) {}
	}

	private void updateAllToggleViews() {
		new Handler(Looper.getMainLooper()).post(() -> {
			for (View v : activeToggleViews.keySet()) {
				if (v != null) {
					v.setVisibility(autoBrightnessToggle ? View.VISIBLE : View.GONE);
					if (autoBrightnessToggle) {
						setAutoBrightnessIcon(v);
					}
				}
			}
		});
	}

	private View createToggleView(Context context) {
		View toggleView = new View(context) {
			@Override
			protected void onConfigurationChanged(android.content.res.Configuration newConfig) {
				super.onConfigurationChanged(newConfig);
				setAutoBrightnessIcon(this);
			}
		};
		activeToggleViews.put(toggleView, Boolean.TRUE);

		int touchSlop = ViewConfiguration.get(context).getScaledTouchSlop();
		toggleView.setOnTouchListener(new View.OnTouchListener() {
			private float downX, downY;
			private boolean isDragging = false;

			@Override
			public boolean onTouch(View v, MotionEvent event) {
				switch (event.getActionMasked()) {
					case MotionEvent.ACTION_DOWN:
						downX = event.getX();
						downY = event.getY();
						isDragging = false;
						return true;

					case MotionEvent.ACTION_MOVE:
						if (!isDragging) {
							float dist = (float) Math.hypot(event.getX() - downX, event.getY() - downY);
							if (dist > touchSlop) {
								isDragging = true;
							}
						}
						return true;

					case MotionEvent.ACTION_UP:
						if (!isDragging) {
							if (SystemClock.uptimeMillis() > mLastAutoToggleClick + 500) {
								mLastAutoToggleClick = SystemClock.uptimeMillis();
								try {
									v.performHapticFeedback(HapticFeedbackConstants.CONTEXT_CLICK);
								} catch (Throwable ignored) {}
								toggleAutoBrightness();
							}
						}
						return true;

					case MotionEvent.ACTION_CANCEL:
						isDragging = false;
						return true;
				}
				return false;
			}
		});

		toggleView.setVisibility(autoBrightnessToggle ? View.VISIBLE : View.GONE);
		setAutoBrightnessIcon(toggleView);
		return toggleView;
	}

	private void setAutoBrightnessIcon(View brightnessToggle) {
		if (brightnessToggle == null) return;
		Context context = brightnessToggle.getContext();
		if (context == null) context = mContext;
		boolean enabled = isAutoBrightnessEnabled();

		if (!enabled) {
			brightnessToggle.setBackground(null);
			return;
		}

		OvalShape backgroundShape = new OvalShape();
		ShapeDrawable backgroundDrawable = new ShapeDrawable(backgroundShape);
		backgroundDrawable.setTint(getBTBackgroundColor(context, true));

		Drawable iconDrawable = ResourcesCompat.getDrawable(
			mContext.getResources(),
			R.drawable.ic_brightness_auto,
			context.getTheme()
		);
		if (iconDrawable != null) {
			iconDrawable = iconDrawable.mutate();
			iconDrawable.setTint(getBTIconColor(context, true));
		}

		LayerDrawable toggleDrawable;
		if (iconDrawable != null) {
			toggleDrawable = new LayerDrawable(new Drawable[]{backgroundDrawable, iconDrawable});
			int inset = (int) (10 * context.getResources().getDisplayMetrics().density);
			toggleDrawable.setLayerInset(1, inset, inset, inset, inset);
		} else {
			toggleDrawable = new LayerDrawable(new Drawable[]{backgroundDrawable});
		}

		brightnessToggle.setBackground(toggleDrawable);
	}

	public int getBTIconColor(Context context, boolean enabled) {
		if (QSTV != null) {
			try {
				if (enabled) {
					return de.robv.android.xposed.XposedHelpers.getIntField(QSTV, "colorLabelActive");
				} else {
					return de.robv.android.xposed.XposedHelpers.getIntField(QSTV, "colorLabelInactive");
				}
			} catch (Throwable ignored) {}
		}
		int color = 0;
		if (enabled) {
			color = getAttrColor(context, android.R.attr.textColorPrimaryInverse);
			if (color == 0) color = getAttrColor(context, android.R.attr.colorBackground);
			if (color == 0) color = Color.BLACK;
		} else {
			color = getAttrColor(context, android.R.attr.textColorPrimary);
			if (color == 0) color = Color.WHITE;
		}
		return color;
	}

	public int getBTBackgroundColor(Context context, boolean enabled) {
		if (QSTV != null) {
			try {
				if (enabled) {
					return de.robv.android.xposed.XposedHelpers.getIntField(QSTV, "colorActive");
				} else {
					return de.robv.android.xposed.XposedHelpers.getIntField(QSTV, "colorInactive");
				}
			} catch (Throwable ignored) {}
		}
		int color = 0;
		if (enabled) {
			color = getAttrColor(context, android.R.attr.colorAccent);
			if (color == 0) color = getAttrColor(context, android.R.attr.colorPrimary);
			if (color == 0) color = Color.WHITE;
		} else {
			color = getAttrColorByName(context, "colorSurfaceVariant");
			if (color == 0) color = getAttrColor(context, android.R.attr.colorButtonNormal);
			if (color == 0) color = Color.DKGRAY;
		}
		return color;
	}

	private int getAttrColorByName(Context context, String attrName) {
		if (context == null) return 0;
		try {
			int attrId = context.getResources().getIdentifier(attrName, "attr", context.getPackageName());
			if (attrId == 0) {
				attrId = context.getResources().getIdentifier(attrName, "attr", "android");
			}
			if (attrId != 0) {
				return getAttrColor(context, attrId);
			}
		} catch (Throwable ignored) {}
		return 0;
	}

	private int getAttrColor(Context context, int attr) {
		if (context == null) return 0;
		try {
			android.util.TypedValue typedValue = new android.util.TypedValue();
			if (context.getTheme().resolveAttribute(attr, typedValue, true)) {
				if (typedValue.type >= android.util.TypedValue.TYPE_FIRST_COLOR_INT && typedValue.type <= android.util.TypedValue.TYPE_LAST_COLOR_INT) {
					return typedValue.data;
				} else if (typedValue.resourceId != 0) {
					return context.getColor(typedValue.resourceId);
				}
			}
		} catch (Throwable ignored) {}
		return 0;
	}

	private boolean isAutoBrightnessEnabled() {
		try {
			return Settings.System.getInt(mContext.getContentResolver(), Settings.System.SCREEN_BRIGHTNESS_MODE, 0) == Settings.System.SCREEN_BRIGHTNESS_MODE_AUTOMATIC;
		} catch (Throwable t) {
			return false;
		}
	}

	private void toggleAutoBrightness() {
		boolean target = !isAutoBrightnessEnabled();
		try {
			Settings.System.putInt(
				mContext.getContentResolver(),
				Settings.System.SCREEN_BRIGHTNESS_MODE,
				target ? Settings.System.SCREEN_BRIGHTNESS_MODE_AUTOMATIC : Settings.System.SCREEN_BRIGHTNESS_MODE_MANUAL
			);
		} catch (Throwable ignored) {}
	}

	private interface Function1Block {
		Object invoke(Object p1);
	}

	private Object function1Proxy(Function1Block block) {
		if (function1Class == null) return null;
		return Proxy.newProxyInstance(
			function1Class.getClassLoader(),
			new Class<?>[]{function1Class},
			(proxy, method, args) -> {
				String methodName = method.getName();
				if ("invoke".equals(methodName)) {
					return block.invoke(args != null && args.length > 0 ? args[0] : null);
				} else if ("equals".equals(methodName)) {
					return proxy == (args != null && args.length > 0 ? args[0] : null);
				} else if ("hashCode".equals(methodName)) {
					return System.identityHashCode(proxy);
				} else if ("toString".equals(methodName)) {
					return "PXFunction1";
				}
				return null;
			}
		);
	}
}
