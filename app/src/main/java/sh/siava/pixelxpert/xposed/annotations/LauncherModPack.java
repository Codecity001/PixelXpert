package sh.siava.pixelxpert.xposed.annotations;

import java.lang.annotation.ElementType;
import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;
import java.lang.annotation.Target;

import sh.siava.pixelxpert.annotations.BaseModPack;

@Retention(RetentionPolicy.RUNTIME)
@Target(ElementType.TYPE)
@BaseModPack(targetPackage = "com.google.android.apps.nexuslauncher")
public @interface LauncherModPack { }