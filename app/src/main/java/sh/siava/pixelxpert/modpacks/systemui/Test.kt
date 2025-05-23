package sh.siava.pixelxpert.modpacks.systemui

import android.content.Context
import androidx.compose.ui.Modifier

import androidx.compose.ui.input.pointer.PointerEventType
import androidx.compose.ui.input.pointer.pointerInput
import de.robv.android.xposed.XposedBridge.log
import de.robv.android.xposed.callbacks.XC_LoadPackage
import sh.siava.pixelxpert.modpacks.XPLauncher
import sh.siava.pixelxpert.modpacks.XposedModPack
import sh.siava.pixelxpert.modpacks.utils.toolkit.ReflectedClass

class Test (context: Context?): XposedModPack (context) {

    override fun updatePrefs(vararg Key: String?) {
    }

    override fun handleLoadPackage(lpParam: XC_LoadPackage.LoadPackageParam?) {
        log("start")

        var d = Modifier.pointerInput(Unit) {
            awaitPointerEventScope {
                while (true) {
                    val event = awaitPointerEvent()
                    when (event.type) {
                        PointerEventType.Press -> {
                            log("press")
                        }

                        PointerEventType.Release -> {
                            log("release");
                        }
                    }
                }
            }
        }
        log("hirrr ");
        log("PIXELXPERT - Modifier ClassLoader: " + Modifier.javaClass.classLoader);


        var dd = ReflectedClass.of("com.android.systemui.qs.panels.ui.compose.infinitegrid.TileKt");
        dd.before("Tile").run(ReflectedClass.ReflectionConsumer { param ->
            if(param.args[6] == null)
            {
                return@ReflectionConsumer
            }
//			dumpClass(param.args[6].javaClass);

            var mm = param.args[6] as Modifier.Companion
            var mn = mm.then(d)
            param.args[6] = mn

        })
    }

    override fun listensTo(packageName: String?): Boolean {
        return true
    }
}