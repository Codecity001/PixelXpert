package sh.siava.pixelxpert.xposed.modpacks.systemui

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.graphics.drawable.Icon
import android.os.CountDownTimer
import android.os.PowerManager
import android.os.SystemClock
import android.os.VibrationAttributes
import android.os.VibrationEffect
import android.provider.Settings
import android.service.quicksettings.Tile
import android.view.HapticFeedbackConstants
import android.view.View
import sh.siava.pixelxpert.xposed.utils.reflection.HookHelper.callMethod
import sh.siava.pixelxpert.xposed.utils.reflection.HookHelper.getObjectField
import sh.siava.pixelxpert.xposed.utils.reflection.HookHelper.setObjectField
import io.github.libxposed.api.XposedModuleInterface
import sh.siava.pixelxpert.BuildConfig
import sh.siava.pixelxpert.R
import sh.siava.pixelxpert.service.tileServices.CaffeineTileService
import sh.siava.pixelxpert.xposed.XPLauncher
import sh.siava.pixelxpert.xposed.XposedModPack
import sh.siava.pixelxpert.xposed.annotations.SystemUIModPack
import sh.siava.pixelxpert.xposed.utils.SystemUtils
import sh.siava.pixelxpert.xposed.utils.reflection.ReflectedClass
import sh.siava.pixelxpert.xposed.utils.toolkit.Logger

@SystemUIModPack
class CaffeineTile(context: Context) : XposedModPack(context) {

    companion object {
        private const val TAG = "CaffeineTile"
        private val DURATIONS = intArrayOf(
            5 * 60,   // 5 min
            10 * 60,  // 10 min
            30 * 60,  // 30 min
            -1        // infinity
        )
        private val INFINITE_DURATION_INDEX = DURATIONS.size - 1
    }

    private var mTile: Any? = null
    private var mWakeLock: PowerManager.WakeLock? = null
    private var mSecondsRemaining = 0
    private var mDuration = -1 // -1 means off/uninitialized. Index into DURATIONS
    private var mCountdownTimer: CountDownTimer? = null
    private var mLastClickTime: Long = -1
    private var mScreenOffReceiver: BroadcastReceiver? = null

    override fun onPreferenceUpdated(vararg key: String) {}

    override fun onPackageLoaded(param: XposedModuleInterface.PackageReadyParam) {
        try {
            val customTileClass = ReflectedClass.of("com.android.systemui.qs.external.CustomTile")
            val qsFactoryImplClass = ReflectedClass.of("com.android.systemui.qs.tileimpl.QSFactoryImpl")
            val qsTileImplClass = ReflectedClass.of("com.android.systemui.qs.tileimpl.QSTileImpl")

            qsFactoryImplClass
                .after("createTile")
                .run { hookParam ->
                    val arg = hookParam.args.getOrNull(0) as? String
                    if (arg != null && arg.contains(CaffeineTileService::class.java.simpleName)) {
                        val result = hookParam.result
                        if (result != null) {
                            mTile = result
                            initCaffeine()
                            updateTile()
                        }
                    }
                }

            customTileClass
                .before("handleClick")
                .run { hookParam ->
                    if (hookParam.thisObject == mTile) {
                        val arg = hookParam.args.getOrNull(0)
                        handleTileClick(arg)
                        hookParam.result = null // Prevent invoking TileService over IPC
                    }
                }

            qsTileImplClass
                .before("handleLongClick")
                .run { hookParam ->
                    if (hookParam.thisObject == mTile) {
                        val arg = hookParam.args.getOrNull(0)
                        handleTileLongClick(arg)
                        hookParam.result = null
                    }
                }

            customTileClass
                .after("handleUpdateState")
                .run { hookParam ->
                    if (hookParam.thisObject == mTile) {
                        val state = hookParam.args.getOrNull(0) ?: return@run

                        val isHeld = mWakeLock?.isHeld == true
                        try {
                            setObjectField(state, "value", isHeld)
                        } catch (ignored: Throwable) {
                        }
                        setObjectField(state, "state", if (isHeld) Tile.STATE_ACTIVE else Tile.STATE_INACTIVE)

                        if (isHeld) {
                            setObjectField(state, "secondaryLabel", formatValueWithRemainingTime())
                        } else {
                            setObjectField(state, "secondaryLabel", null)
                        }
                    }
                }

            customTileClass
                .before("getLongClickIntent")
                .run { hookParam ->
                    if (hookParam.thisObject == mTile) {
                        var intent = Intent("android.settings.SCREEN_TIMEOUT_SETTINGS").apply {
                            addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                        }
                        if (intent.resolveActivity(mContext.packageManager) == null) {
                            intent = Intent(Settings.ACTION_DISPLAY_SETTINGS).apply {
                                addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                            }
                        }
                        hookParam.result = intent
                    }
                }

            customTileClass
                .before("handleDestroy")
                .run { hookParam ->
                    if (hookParam.thisObject == mTile) {
                        destroyCaffeine()
                    }
                }
        } catch (ignored: Throwable) {
        }
    }

    private fun initCaffeine() {
        if (mWakeLock == null) {
            val pm = mContext.getSystemService(PowerManager::class.java)
            if (pm != null) {
                @Suppress("DEPRECATION")
                mWakeLock = pm.newWakeLock(PowerManager.FULL_WAKE_LOCK, "PixelXpert:CaffeineTile")
            }
        }
        if (mScreenOffReceiver == null) {
            mScreenOffReceiver = object : BroadcastReceiver() {
                override fun onReceive(context: Context, intent: Intent) {
                    if (Intent.ACTION_SCREEN_OFF == intent.action) {
                        stopCountDown()
                        if (mWakeLock?.isHeld == true) {
                            mWakeLock?.release()
                        }
                        mDuration = -1
                        updateTile()
                    }
                }
            }
            val filter = IntentFilter(Intent.ACTION_SCREEN_OFF)
            mContext.registerReceiver(mScreenOffReceiver, filter, null, null)
        }
    }

    private fun destroyCaffeine() {
        stopCountDown()
        if (mScreenOffReceiver != null) {
            try {
                mContext.unregisterReceiver(mScreenOffReceiver)
            } catch (ignored: Exception) {
            }
            mScreenOffReceiver = null
        }
        if (mWakeLock?.isHeld == true) {
            mWakeLock?.release()
        }
    }

    private fun handleTileClick(arg: Any?) {
        triggerHapticFeedback(arg)

        val wakeLock = mWakeLock ?: return

        if (wakeLock.isHeld && mLastClickTime != -1L && SystemClock.elapsedRealtime() - mLastClickTime < 5000) {
            // cycle duration
            mDuration++
            if (mDuration >= DURATIONS.size) {
                // all durations cycled, turn it off
                mDuration = -1
                stopCountDown()
                if (wakeLock.isHeld) {
                    wakeLock.release()
                }
            } else {
                // change duration
                startCountDown(DURATIONS[mDuration].toLong())
                if (!wakeLock.isHeld) {
                    wakeLock.acquire()
                }
            }
        } else {
            // toggle
            if (wakeLock.isHeld) {
                wakeLock.release()
                stopCountDown()
            } else {
                wakeLock.acquire()
                mDuration = 0
                startCountDown(DURATIONS[mDuration].toLong())
            }
        }
        mLastClickTime = SystemClock.elapsedRealtime()
        updateTile()
    }

    private fun handleTileLongClick(arg: Any?) {
        triggerHapticFeedback(arg)

        val wakeLock = mWakeLock ?: return

        if (wakeLock.isHeld) {
            if (mDuration == INFINITE_DURATION_INDEX) {
                return
            }
        } else {
            wakeLock.acquire()
        }
        mDuration = INFINITE_DURATION_INDEX
        startCountDown(DURATIONS[INFINITE_DURATION_INDEX].toLong())
        updateTile()
    }

    private fun startCountDown(duration: Long) {
        stopCountDown()
        mSecondsRemaining = duration.toInt()
        if (duration == -1L) {
            // infinity timing, no need to start timer
            return
        }
        mCountdownTimer = object : CountDownTimer(duration * 1000, 1000) {
            override fun onTick(millisUntilFinished: Long) {
                mSecondsRemaining = (millisUntilFinished / 1000).toInt()
                updateTile()
            }

            override fun onFinish() {
                if (mWakeLock?.isHeld == true) {
                    mWakeLock?.release()
                }
                mDuration = -1
                updateTile()
            }
        }.start()
    }

    private fun stopCountDown() {
        mCountdownTimer?.cancel()
        mCountdownTimer = null
    }

    private fun formatValueWithRemainingTime(): String {
        if (mSecondsRemaining == -1) {
            return "\u221E" // infinity
        }
        return String.format("%02d:%02d", mSecondsRemaining / 60 % 60, mSecondsRemaining % 60)
    }

    private fun triggerHapticFeedback(arg: Any?) {
        var performed = false
        try {
            var view: View? = null
            if (arg is View) {
                view = arg
            } else if (arg != null) {
                try {
                    val v = callMethod(arg, "asView")
                    if (v is View) {
                        view = v
                    }
                } catch (ignored: Throwable) {
                }
            }

            if (view != null) {
                performed = view.performHapticFeedback(HapticFeedbackConstants.CONFIRM)
                if (!performed) {
                    performed = view.performHapticFeedback(HapticFeedbackConstants.CONTEXT_CLICK)
                }
            }
        } catch (ignored: Throwable) {
        }

        if (!performed) {
            try {
                SystemUtils.vibrate(VibrationEffect.EFFECT_CLICK, VibrationAttributes.USAGE_TOUCH)
            } catch (ignored: Throwable) {
            }
        }
    }

    private fun updateTile() {
        val tileObj = mTile ?: return

        try {
            val tile = getObjectField(tileObj, "mTile") as? Tile ?: return

            val isHeld = mWakeLock?.isHeld == true
            tile.icon = Icon.createWithResource(BuildConfig.APPLICATION_ID, R.drawable.ic_qs_caffeine)
            tile.state = if (isHeld) Tile.STATE_ACTIVE else Tile.STATE_INACTIVE

            val formatted = formatValueWithRemainingTime()
            if (isHeld) {
                tile.subtitle = formatted
            } else {
                tile.subtitle = null
            }

            val label = XPLauncher.moduleResources.getString(R.string.caffeine_tile_title)
            if (isHeld) {
                tile.contentDescription = "$label: $formatted"
            } else {
                tile.contentDescription = "$label: Off"
            }

            callMethod(tileObj, "refreshState", null as Any?)
        } catch (t: Throwable) {
            Logger.log("CaffeineTile: failed to updateTile: $t")
        }
    }
}
