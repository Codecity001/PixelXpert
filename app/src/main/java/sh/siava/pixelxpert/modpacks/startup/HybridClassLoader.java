package sh.siava.pixelxpert.modpacks.startup;

import android.content.Context;

import java.net.URL;

/**
 * NOTICE: Do NOT use any androidx annotations here.
 */
public class HybridClassLoader extends ClassLoader {

    private static final String TAG = "HybridClassLoader";

    /**
     * The bootstrap class loader for Android, effectively NonNull.
     */
    private static final ClassLoader BOOT_CLASS_LOADER = Context.class.getClassLoader();
    private final ClassLoader clPreload;
    private final ClassLoader clBase;

    public HybridClassLoader(ClassLoader x, ClassLoader ctx) {
        super(BOOT_CLASS_LOADER);
        clPreload = x;
        clBase = ctx;
    }

    /**
     * 把宿主和模块共有的 package 扔这里.
     *
     * @param name NonNull, class name
     * @return true if conflicting
     */
    public static boolean isConflictingClass(String name) {
        if (name.startsWith("androidx.compose.ui.")) {
            return false;
        }

        return name.startsWith("androidx.")
                || name.startsWith("android.support.")
                || name.startsWith("kotlin.")
                || name.startsWith("kotlinx.")
                || name.startsWith("com.tencent.mmkv.")
                || name.startsWith("com.android.tools.r8.")
                || name.startsWith("com.google.android.")
                || name.startsWith("com.google.gson.")
                || name.startsWith("com.google.common.")
                || name.startsWith("com.google.protobuf.")
                || name.startsWith("com.microsoft.appcenter.")
                || name.startsWith("org.intellij.lang.annotations.")
                || name.startsWith("org.jetbrains.annotations.")
                || name.startsWith("com.google.errorprone.annotations.")
                || name.startsWith("org.jf.dexlib2.")
                || name.startsWith("org.jf.util.")
                || name.startsWith("javax.annotation.")
                || name.startsWith("_COROUTINE.");
    }

    @Override
    protected Class<?> loadClass(String name, boolean resolve) throws ClassNotFoundException {
        try {
            return BOOT_CLASS_LOADER.loadClass(name);
        } catch (ClassNotFoundException ignored) {
        }
        if (name != null && isConflictingClass(name)) {
            //Nevertheless, this will not interfere with the host application,
            //classes in host application SHOULD find with their own ClassLoader, eg Class.forName()
            //use shipped androidx and kotlin lib.
            throw new ClassNotFoundException(name);
        }
        // The ClassLoader for some apk-modifying frameworks are terrible, XposedBridge.class.getClassLoader()
        // is the sane as Context.getClassLoader(), which mess up with 3rd lib, can cause the ART to crash.
        if (clPreload != null) {
            try {
                return clPreload.loadClass(name);
            } catch (ClassNotFoundException ignored) {
            }
        }
        if (clBase != null) {
            try {
                return clBase.loadClass(name);
            } catch (ClassNotFoundException ignored) {
            }
        }
        throw new ClassNotFoundException(name);
    }

    @Override
    public URL getResource(String name) {
        URL ret = clPreload.getResource(name);
        if (ret != null) {
            return ret;
        }
        return clBase.getResource(name);
    }
}

