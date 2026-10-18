package sh.siava.pixelxpert.xposed.modpacks.systemui

import android.content.Context
import android.content.Intent
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.drawable.Icon
import android.media.AudioDeviceCallback
import android.media.AudioDeviceInfo
import android.media.AudioManager.STREAM_MUSIC
import android.service.quicksettings.Tile
import androidx.core.content.res.ResourcesCompat
import sh.siava.pixelxpert.xposed.utils.reflection.HookHelper.callMethod
import sh.siava.pixelxpert.xposed.utils.reflection.HookHelper.getObjectField
import sh.siava.pixelxpert.xposed.utils.reflection.HookHelper.setObjectField
import io.github.libxposed.api.XposedModuleInterface
import sh.siava.pixelxpert.BuildConfig
import sh.siava.pixelxpert.R
import sh.siava.pixelxpert.service.tileServices.VolumeTileService
import sh.siava.pixelxpert.xposed.XPLauncher
import sh.siava.pixelxpert.xposed.XposedModPack
import sh.siava.pixelxpert.xposed.annotations.SystemUIModPack
import sh.siava.pixelxpert.xposed.utils.AlertSlider
import sh.siava.pixelxpert.xposed.utils.SystemUtils
import sh.siava.pixelxpert.xposed.utils.SystemUtils.AudioManager
import sh.siava.pixelxpert.xposed.utils.SystemUtils.registerVolumeChangeListener
import sh.siava.pixelxpert.xposed.utils.reflection.ReflectedClass
import kotlin.math.roundToInt

@SystemUIModPack
class VolumeTile(context: Context) : XposedModPack(context) {

    companion object {
        private const val ICON_PADDING_TOP_PERCENT = 0.125f
        private const val ICON_PADDING_BOTTOM_PERCENT = 0.125f
        private const val TAG = "VolumeTileXposed"

        fun createPercentageIcon(
            context: Context,
            currentVolume: Int,
            minValue: Int,
            maxValue: Int,
            filledResId: Int,
            outlineResId: Int,
            paddingTopPercent: Float,
            paddingBottomPercent: Float
        ): Icon {
            return try {
                val filledDrawable = ResourcesCompat.getDrawable(XPLauncher.moduleResources, filledResId, context.theme) ?: return Icon.createWithResource(BuildConfig.APPLICATION_ID, filledResId)
                val outlineDrawable = ResourcesCompat.getDrawable(XPLauncher.moduleResources, outlineResId, context.theme) ?: return Icon.createWithResource(BuildConfig.APPLICATION_ID, filledResId)

                val width = filledDrawable.intrinsicWidth
                val height = filledDrawable.intrinsicHeight

                val paddingTopPx = (height * paddingTopPercent).toInt()
                val paddingBottomPx = (height * paddingBottomPercent).toInt()

                val bitmap = Bitmap.createBitmap(width, height, Bitmap.Config.ARGB_8888)
                val canvas = Canvas(bitmap)

                val contentActualHeight = height - paddingTopPx - paddingBottomPx

                var percentage = 0f
                if (maxValue > minValue) {
                    percentage = (currentVolume - minValue).toFloat() / (maxValue - minValue).toFloat()
                }
                percentage = percentage.coerceIn(0f, 1f)

                val filledContentPixelHeight = (contentActualHeight * percentage).toInt()
                val outlineContentPixelHeight = contentActualHeight - filledContentPixelHeight

                val splitYAbsolute = paddingTopPx + outlineContentPixelHeight

                canvas.save()
                canvas.clipRect(0, splitYAbsolute, width, height - paddingBottomPx)
                filledDrawable.setBounds(0, 0, width, height)
                filledDrawable.draw(canvas)
                canvas.restore()

                canvas.save()
                canvas.clipRect(0, paddingTopPx, width, splitYAbsolute)
                outlineDrawable.setBounds(0, 0, width, height)
                outlineDrawable.draw(canvas)
                canvas.restore()

                Icon.createWithBitmap(bitmap)
            } catch (ignored: Throwable) {
                Icon.createWithResource(BuildConfig.APPLICATION_ID, filledResId)
            }
        }
    }

    private var mTile: Any? = null
    private var mLastDeviceBT = false
    private var mNextTileIsVolume = false

    override fun onPreferenceUpdated(vararg key: String) {}

    override fun onPackageLoaded(param: XposedModuleInterface.PackageReadyParam) {
        try {
            val customTileClass = ReflectedClass.of("com.android.systemui.qs.external.CustomTile")
            val qsFactoryImplClass = ReflectedClass.of("com.android.systemui.qs.tileimpl.QSFactoryImpl")
            val qsTileImplClass = ReflectedClass.of("com.android.systemui.qs.tileimpl.QSTileImpl")

            qsFactoryImplClass
                .before("createTile")
                .run { hookParam ->
                    val arg = hookParam.getArg(0) as? String ?: return@run
                    if (arg.contains(VolumeTileService::class.java.simpleName)) {
                        mNextTileIsVolume = true
                    }
                }

            qsFactoryImplClass
                .after("createTile")
                .run { hookParam ->
                    val arg = hookParam.args.getOrNull(0) as? String
                    if (arg != null && arg.contains(VolumeTileService::class.java.simpleName)) {
                        mNextTileIsVolume = false
                        mTile = hookParam.result
                        AudioManager()?.registerAudioDeviceCallback(object : AudioDeviceCallback() {
                            override fun onAudioDevicesAdded(addedDevices: Array<out AudioDeviceInfo>?) {
                                addedDevices?.forEach { addedDevice ->
                                    val type = addedDevice.type
                                    if (type == AudioDeviceInfo.TYPE_BLUETOOTH_A2DP || type == AudioDeviceInfo.TYPE_BLUETOOTH_SCO) {
                                        mLastDeviceBT = true
                                        updateTile()
                                        return
                                    }
                                }
                            }

                            override fun onAudioDevicesRemoved(removedDevices: Array<out AudioDeviceInfo>?) {
                                removedDevices?.forEach { removedDevice ->
                                    val type = removedDevice.type
                                    if (type == AudioDeviceInfo.TYPE_BLUETOOTH_A2DP || type == AudioDeviceInfo.TYPE_BLUETOOTH_SCO) {
                                        mLastDeviceBT = false
                                        updateTile()
                                        return
                                    }
                                }
                            }
                        }, null)

                        registerVolumeChangeListener { updateTile() }
                        updateTile() // Initial update
                    }
                }

            customTileClass
                .after("newTileState")
                .run { hookParam ->
                    if (mNextTileIsVolume) {
                        val state = hookParam.result
                        if (state != null) {
                            setObjectField(state, "handlesSecondaryClick", true)
                        }
                    }
                }

            customTileClass
                .after("handleClick")
                .run { hookParam ->
                    if (hookParam.thisObject == mTile) {
                        handleVolumeLongClick()
                    }
                }

            qsTileImplClass
                .before("handleSecondaryClick")
                .run { hookParam ->
                    if (hookParam.thisObject == mTile) {
                        SystemUtils.toggleMute()
                        updateTile()
                        hookParam.result = null
                    }
                }

            customTileClass
                .before("getLongClickIntent")
                .run { hookParam ->
                    if (hookParam.thisObject == mTile) {
                        if (handleVolumeLongClick()) {
                            hookParam.result = Intent()
                        }
                    }
                }
        } catch (ignored: Throwable) {
        }
    }

    private fun updateTile(value: Int) {
        val tileObj = mTile ?: return
        val tile = getObjectField(tileObj, "mTile") as? Tile ?: return
        setIcon(tile, value)
        val am = AudioManager() ?: return
        tile.state = if (value == am.getStreamMinVolume(STREAM_MUSIC)) Tile.STATE_INACTIVE else Tile.STATE_ACTIVE
        callMethod(tileObj, "refreshState", null as Any?)
    }

    private fun setIcon(tile: Tile, currentVolume: Int) {
        val am = AudioManager() ?: return
        val minVolume = am.getStreamMinVolume(STREAM_MUSIC)
        val maxVolume = am.getStreamMaxVolume(STREAM_MUSIC)

        updateBluetoothStatus()

        val muteResId = if (mLastDeviceBT) R.drawable.ic_volume_bt_mute else R.drawable.ic_volume_mute
        val filledResId = if (mLastDeviceBT) R.drawable.ic_volume_bt else R.drawable.ic_volume
        val outlineResId = if (mLastDeviceBT) R.drawable.ic_volume_bt_outline else R.drawable.ic_volume_outline

        if (currentVolume <= minVolume) {
            tile.icon = Icon.createWithResource(BuildConfig.APPLICATION_ID, muteResId)
        } else {
            val customIcon = createPercentageIcon(
                mContext,
                currentVolume,
                minVolume,
                maxVolume,
                filledResId,
                outlineResId,
                ICON_PADDING_TOP_PERCENT,
                ICON_PADDING_BOTTOM_PERCENT
            )
            tile.icon = customIcon
        }
    }

    private fun updateBluetoothStatus() {
        val am = AudioManager() ?: return
        val activeConfigurations = am.activePlaybackConfigurations

        var deviceInfoFound = false
        var bluetoothFound = false

        for (config in activeConfigurations) {
            @Suppress("DEPRECATION")
            val deviceInfo = config.audioDeviceInfo
            if (deviceInfo != null) {
                deviceInfoFound = true
                val deviceType = deviceInfo.type
                if (deviceInfo.isSink &&
                    (deviceType == AudioDeviceInfo.TYPE_BLUETOOTH_A2DP || deviceType == AudioDeviceInfo.TYPE_BLUETOOTH_SCO)
                ) {
                    bluetoothFound = true
                    break
                }
            }
        }

        if (deviceInfoFound) {
            this.mLastDeviceBT = bluetoothFound
        }
    }

    private fun updateTile() {
        val am = SystemUtils.AudioManager() ?: return
        updateTile(am.getStreamVolume(STREAM_MUSIC))
    }

    private fun handleVolumeLongClick(): Boolean {
        showAlertSlider()
        return true
    }

    private fun showAlertSlider() {
        val am = AudioManager() ?: return
        val volumeSliderCallback = object : AlertSlider.SliderEventCallback {
            override fun onStartTrackingTouch(slider: Any) {}

            override fun onStopTrackingTouch(slider: Any) {}

            override fun onValueChange(slider: Any, value: Float, fromUser: Boolean) {
                if (fromUser) {
                    changeVolume(value.roundToInt())
                }
            }

            override fun onCreate(alertSlider: AlertSlider) {
                registerVolumeChangeListener(alertSlider)
            }

            override fun onDismiss(alertSlider: AlertSlider) {
                SystemUtils.unregisterVolumeChangeListener(alertSlider)
            }
        }

        val alertSlider = object : AlertSlider(
            mContext,
            am.getStreamVolume(STREAM_MUSIC),
            am.getStreamMinVolume(STREAM_MUSIC),
            am.getStreamMaxVolume(STREAM_MUSIC),
            1,
            volumeSliderCallback
        ) {
            override fun onChanged(newVal: Int) {
                setSliderCurrentValue(newVal)
            }
        }
        alertSlider.show()
    }

    private fun changeVolume(currentValue: Int) {
        AudioManager()?.setStreamVolume(
            STREAM_MUSIC,
            currentValue,
            0 /* don't show UI */
        )
    }
}
