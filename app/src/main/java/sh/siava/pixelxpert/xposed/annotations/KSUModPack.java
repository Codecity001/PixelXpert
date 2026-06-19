package sh.siava.pixelxpert.xposed.annotations;

import java.lang.annotation.ElementType;
import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;
import java.lang.annotation.Target;

import sh.siava.pixelxpert.annotations.BaseModPack;
import sh.siava.pixelxpert.Constants;

@Retention(RetentionPolicy.SOURCE)
@Target(ElementType.TYPE)
@BaseModPack(targetPackage = Constants.KSU_PACKAGE)
public @interface KSUModPack { }