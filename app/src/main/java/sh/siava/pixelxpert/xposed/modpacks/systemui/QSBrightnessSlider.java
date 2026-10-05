package sh.siava.pixelxpert.xposed.modpacks.systemui;

import static de.robv.android.xposed.XposedHelpers.callMethod;
import static de.robv.android.xposed.XposedHelpers.getStaticObjectField;
import static de.robv.android.xposed.XposedHelpers.getObjectField;
import static sh.siava.pixelxpert.xposed.XPrefs.Xprefs;

import android.content.Context;

import java.lang.reflect.Constructor;
import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.lang.reflect.Proxy;
import java.util.WeakHashMap;

import io.github.libxposed.api.XposedModuleInterface;
import sh.siava.pixelxpert.Constants;
import sh.siava.pixelxpert.xposed.XposedModPack;
import sh.siava.pixelxpert.xposed.annotations.SystemUIModPack;
import sh.siava.pixelxpert.xposed.utils.reflection.ReflectedClass;

@SystemUIModPack
public class QSBrightnessSlider extends XposedModPack {
	private boolean brightnessBelowTiles = false;
	private boolean brightnessInQqs = false;

	private Class<?> function0Class = null;
	private Class<?> function2Class = null;
	private Class<?> function3Class = null;
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
		brightnessBelowTiles = Xprefs.getBoolean("qs_brightness_slider_bottom", false);
		brightnessInQqs = Xprefs.getBoolean("qqs_brightness_slider", false);
	}

	@Override
	public void onPackageLoaded(XposedModuleInterface.PackageReadyParam PRParam) throws Throwable {
		resolveComposeApis();
		hookQsFragmentCompose();
		hookSceneContainer();
	}

	private void hookQsFragmentCompose() {
		ReflectedClass qsFragmentClass = ReflectedClass.ofIfPossible("com.android.systemui.qs.composefragment.QSFragmentCompose");
		ReflectedClass qsLayoutClass = ReflectedClass.ofIfPossible("com.android.systemui.qs.composefragment.QSFragmentComposeKt");

		if (qsFragmentClass == null || qsLayoutClass == null) {
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
			Object tiles = param.args[1];
			if (brightness == null || tiles == null) return;

			Object brightnessSlot = brightnessInQqs ? sharedQsBrightnessSlot(brightness) : brightness;
			if (brightnessSlot == null) brightnessSlot = brightness;

			if (brightnessBelowTiles) {
				param.args[0] = tiles;
				param.args[1] = brightnessSlot;
			} else {
				param.args[0] = brightnessSlot;
			}
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
		if (qsContentKtClass != null) {
			for (Method m : qsContentKtClass.getClazz().getDeclaredMethods()) {
				if (m.getName().startsWith("QuickSettingsPanelLayout")) {
					qsContentKtClass.before(m.getName()).run(param -> {
						if (!brightnessBelowTiles || param.args.length < 2) return;
						Object brightness = param.args[0];
						if (brightness == null) return;
						param.args[0] = param.args[1];
						param.args[1] = brightness;
					});
				}
			}
		}

		ReflectedClass shadeSceneClass = ReflectedClass.ofIfPossible("com.android.systemui.shade.ui.composable.ShadeSceneKt");
		if (shadeSceneClass == null) return;

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
			function2Class = de.robv.android.xposed.XposedHelpers.findClassIfExists(className("kotlin.jvm.functions", "Function2"), cl);
			function3Class = de.robv.android.xposed.XposedHelpers.findClassIfExists(className("kotlin.jvm.functions", "Function3"), cl);
		} catch (Throwable t) {
		}

		try {
			Class<?> sysUiViewModelKt = de.robv.android.xposed.XposedHelpers.findClassIfExists(className("com.android.systemui.lifecycle", "SysUiViewModelKt"), cl);
			for (Method m : sysUiViewModelKt.getDeclaredMethods()) {
				if ("rememberViewModel".equals(m.getName())) {
					rememberViewModelMethod = m;
					break;
				}
			}
		} catch (Throwable t) {
		}

		try {
			Class<?> qsElementsClass = de.robv.android.xposed.XposedHelpers.findClassIfExists(className("com.android.systemui.qs.shared.ui", "QuickSettings$Elements"), cl);
			Field brightnessSliderField = qsElementsClass.getDeclaredField("BrightnessSlider");
			brightnessSliderField.setAccessible(true);
			sceneBrightnessElementKey = brightnessSliderField.get(null);
		} catch (Throwable t) {
		}

		try {
			Class<?> unitClass = de.robv.android.xposed.XposedHelpers.findClassIfExists(className("kotlin", "Unit"), cl);
			kotlinUnit = getStaticObjectField(unitClass, "INSTANCE");
			Class<?> modifierClass = de.robv.android.xposed.XposedHelpers.findClassIfExists(className("androidx.compose.ui", "Modifier"), cl);
			modifierCompanion = getStaticObjectField(modifierClass, "Companion");
		} catch (Throwable t) {
		}

		try {
			Class<?> elementKeyClass = de.robv.android.xposed.XposedHelpers.findClassIfExists(className("com.android.compose.animation.scene", "ElementKey"), cl);
			for (Constructor<?> c : elementKeyClass.getDeclaredConstructors()) {
				if (c.getParameterTypes().length == 6) {
					c.setAccessible(true);
					sharedElementKey = c.newInstance("PXBrightnessSlider", null, null, false, 14, null);
					break;
				}
			}
		} catch (Throwable t) {
		}

		try {
			Class<?> brightnessSliderKtClass = de.robv.android.xposed.XposedHelpers.findClassIfExists(className("com.android.systemui.brightness.ui.compose", "BrightnessSliderKt"), cl);
			for (Method m : brightnessSliderKtClass.getDeclaredMethods()) {
				if ("BrightnessSliderContainer".equals(m.getName())) {
					brightnessContainerMethod = m;
					brightnessContainerMethod.setAccessible(true);
					break;
				}
			}
		} catch (Throwable t) {
		}

		try {
			Class<?> containerColorsClass = de.robv.android.xposed.XposedHelpers.findClassIfExists(className("com.android.systemui.brightness.ui.compose", "ContainerColors"), cl);
			for (Constructor<?> c : containerColorsClass.getDeclaredConstructors()) {
				if (c.getParameterTypes().length == 2 &&
					c.getParameterTypes()[0] == long.class &&
					c.getParameterTypes()[1] == long.class) {
					containerColorsConstructor = c;
					containerColorsConstructor.setAccessible(true);
					break;
				}
			}
		} catch (Throwable t) {
		}
	}

	private Object sharedQsBrightnessSlot(Object brightness) {
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
		Method elementMethod = null;
		for (Method m : scope.getClass().getMethods()) {
			if ("Element".equals(m.getName()) && m.getParameterTypes().length == 5) {
				elementMethod = m;
				break;
			}
		}
		Object elementContent = composableContent(content::run);

		if (elementMethod == null || elementContent == null) {
			content.run();
			return;
		}

		try {
			elementMethod.invoke(scope, key, modifierCompanion, elementContent, composer, 0);
		} catch (Throwable ignored) {
			content.run();
		}
	}

	private void composeQsFragmentBrightness(Object composer) {
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
}
