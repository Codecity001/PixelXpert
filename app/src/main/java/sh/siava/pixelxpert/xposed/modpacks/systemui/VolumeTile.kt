//Dynamic icon mostly made by Gemini agent

package sh.siava.pixelxpert.xposed.modpacks.systemui

import android.content.Context
import android.content.Intent
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.drawable.Drawable
import android.graphics.drawable.Icon
import android.media.AudioDeviceCallback
import android.media.AudioDeviceInfo
import android.media.AudioManager.STREAM_MUSIC
import android.media.AudioPlaybackConfiguration
import android.service.quicksettings.Tile
import androidx.core.content.res.ResourcesCompat
import de.robv.android.xposed.XposedHelpers.callMethod
import de.robv.android.xposed.XposedHelpers.getObjectField
import de.robv.android.xposed.XposedHelpers.setObjectField
import io.github.libxposed.api.XposedModuleInterface
import sh.siava.pixelxpert.BuildConfig
import sh.siava.pixelxpert.R
import sh.siava.pixelxpert.service.tileServices.VolumeTileService
import sh.siava.pixelxpert.xposed.XPLauncher
import sh.siava.pixelxpert.xposed.XposedModPack
import sh.siava.pixelxpert.xposed.annotations.SystemUIModPack
import sh.siava.pixelxpert.xposed.utils.AlertSlider
import sh.siava.pixelxpert.xposed.utils.SystemUtils
import sh.siava.pixelxpert.xposed.utils.reflection.ReflectedClass

/**
 * Gives the module's media-volume tile its behaviour inside QQS.
 *
 * The tile is backed by [VolumeTileService], so SystemUI creates it through
 * `QSFactoryImpl.createTile` as an opaque `CustomTile` rather than through
 * any factory the module could subclass. The pack captures the live tile the
 * moment the factory hands it over, then hooks the callbacks SystemUI would
 * otherwise own: a click or a long click opens SystemUI's own alert-slider
 * dialog (the widget behind the ambient-volume accessibility slider) to drag
 * the music stream, a secondary click toggles mute instead, and every refresh
 * redraws a custom icon whose filled portion shows the current volume against
 * the stream's min/max, with separate artwork for the muted, wired and
 * bluetooth states driven by audio-device callbacks and the active playback
 * configurations.
 *
 */
//noinspection DataFlowIssue
@SystemUIModPack
class VolumeTile(context: Context) : XposedModPack(context) {

	companion object {
		private const val ICON_PADDING_TOP_PERCENT = 0.125f
		private const val ICON_PADDING_BOTTOM_PERCENT = 0.125f

		private const val TAG = "VolumeTileXposed"

		/**
		 * Renders [currentValue] of the [minValue]..[maxValue] range as a
		 * solid column over the bottom of the outline artwork, so the tile
		 * icon reads as a percentage gauge of the media volume.
		 */
		@JvmStatic
		fun createPercentageIcon(
			context: Context,
			currentValue: Int,
			minValue: Int,
			maxValue: Int,
			filledResId: Int,
			outlineResId: Int,
			paddingTopPercent: Float,
			paddingBottomPercent: Float,
		): Icon? {
			try {
				val filledDrawable: Drawable = ResourcesCompat.getDrawable(XPLauncher.moduleResources!!, filledResId, context.theme)!!
				val outlineDrawable: Drawable = ResourcesCompat.getDrawable(XPLauncher.moduleResources!!, outlineResId, context.theme)!!

				val width = filledDrawable.getIntrinsicWidth()
				val height = filledDrawable.getIntrinsicHeight()

				val paddingTopPx = (height * paddingTopPercent).toInt()
				val paddingBottomPx = (height * paddingBottomPercent).toInt()

				val bitmap = Bitmap.createBitmap(width, height, Bitmap.Config.ARGB_8888)
				val canvas = Canvas(bitmap)

				val contentActualHeight = height - paddingTopPx - paddingBottomPx

				var percentage = 0f
				if (maxValue > minValue) {
					percentage = (currentValue - minValue).toFloat() / (maxValue - minValue).toFloat()
				}
				percentage = Math.max(0f, Math.min(1f, percentage))

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

				return Icon.createWithBitmap(bitmap)
			} catch (ignored: Throwable) {
				return Icon.createWithResource(BuildConfig.APPLICATION_ID, filledResId)
			}
		}
	}

	private var mTile: Any? = null
	private var mLastDeviceBT = false
	private var mNextTileIsVolume = false

	override fun onPreferenceUpdated(vararg key: String?) {
	}

	override fun onPackageLoaded(PRParam: XposedModuleInterface.PackageReadyParam) {
		val CustomTileClass = ReflectedClass.of("com.android.systemui.qs.external.CustomTile")
		val QSFactoryImplClass = ReflectedClass.of("com.android.systemui.qs.tileimpl.QSFactoryImpl")
		val QSTileImplClass = ReflectedClass.of("com.android.systemui.qs.tileimpl.QSTileImpl")

		QSFactoryImplClass
			.before("createTile")
			.run { param ->
				val arg: String = param.getArg(0)
				if (arg.contains(VolumeTileService::class.java.simpleName)) {
					mNextTileIsVolume = true
				}
			}

		QSFactoryImplClass
			.after("createTile")
			.run { param ->
				val arg = param.args[0] as String
				if (arg.contains(VolumeTileService::class.java.simpleName)) {
					mNextTileIsVolume = false
					mTile = param.getResult()
					SystemUtils.AudioManager()!!.registerAudioDeviceCallback(
						object : AudioDeviceCallback() {
							override fun onAudioDevicesAdded(addedDevices: Array<out AudioDeviceInfo>) {
								for (addedDevice in addedDevices) {
									val type = addedDevice.getType()
									if (type == AudioDeviceInfo.TYPE_BLUETOOTH_A2DP || type == AudioDeviceInfo.TYPE_BLUETOOTH_SCO) {
										mLastDeviceBT = true
										updateTile()
										return
									}
								}
							}

							override fun onAudioDevicesRemoved(removedDevices: Array<out AudioDeviceInfo>) {
								for (removedDevice in removedDevices) {
									val type = removedDevice.getType()
									if (type == AudioDeviceInfo.TYPE_BLUETOOTH_A2DP || type == AudioDeviceInfo.TYPE_BLUETOOTH_SCO) {
										mLastDeviceBT = false
										updateTile()
										return
									}
								}
							}
						},
						null,
					)

					SystemUtils.registerVolumeChangeListener { value -> updateTile(value) }
					updateTile() // Initial update
				}
			}

		CustomTileClass
			.after("newTileState")
			.run { param ->
				if (mNextTileIsVolume) {
					val state: Any? = param.getResult()
					setObjectField(state, "handlesSecondaryClick", true)
				}
			}

		CustomTileClass
			.after("handleClick")
			.run { param ->
				if (param.thisObject === mTile) {
					handleVolumeLongClick()
				}
			}

		QSTileImplClass
			.before("handleSecondaryClick")
			.run { param ->
				if (param.thisObject === mTile) {
					SystemUtils.toggleMute()
					updateTile()
					param.setResult(null)
				}
			}

		CustomTileClass
			.before("getLongClickIntent")
			.run { param ->
				if (param.thisObject === mTile) {
					if (handleVolumeLongClick()) {
						param.setResult(Intent())
					}
				}
			}
	}

	private fun updateTile(value: Int) {
		val tile = getObjectField(mTile, "mTile") as Tile
		setIcon(tile, value) // Call to non-static setIcon
		tile.setState(
			if (value == SystemUtils.AudioManager()!!.getStreamMinVolume(STREAM_MUSIC)) Tile.STATE_INACTIVE else Tile.STATE_ACTIVE,
		)
		callMethod(mTile, "refreshState", *arrayOf<Any?>(null))
	}

	private fun setIcon(tile: Tile, currentVolume: Int) {
		val minVolume = SystemUtils.AudioManager()!!.getStreamMinVolume(STREAM_MUSIC)
		val maxVolume = SystemUtils.AudioManager()!!.getStreamMaxVolume(STREAM_MUSIC)

		var muteResId: Int
		var filledResId: Int
		var outlineResId: Int

		updateBluetoothStatus()

		if (mLastDeviceBT) {
			muteResId = R.drawable.ic_volume_bt_mute
			filledResId = R.drawable.ic_volume_bt
			outlineResId = R.drawable.ic_volume_bt_outline
		} else {
			muteResId = R.drawable.ic_volume_mute
			filledResId = R.drawable.ic_volume
			outlineResId = R.drawable.ic_volume_outline
		}

		if (currentVolume <= minVolume) {
			tile.setIcon(Icon.createWithResource(BuildConfig.APPLICATION_ID, muteResId))
		} else {
			val customIcon = createPercentageIcon(
				mContext,
				currentVolume,
				minVolume,
				maxVolume,
				filledResId,
				outlineResId,
				ICON_PADDING_TOP_PERCENT,
				ICON_PADDING_BOTTOM_PERCENT,
			)
			tile.setIcon(customIcon ?: Icon.createWithResource(BuildConfig.APPLICATION_ID, filledResId))
		}
	}

	private fun updateBluetoothStatus() {
		val activeConfigurations: List<AudioPlaybackConfiguration> = SystemUtils.AudioManager()!!.getActivePlaybackConfigurations()

		var deviceInfoFound = false
		var bluetoothFound = false

		for (config in activeConfigurations) {
			//noinspection deprecation
			val deviceInfo = config.getAudioDeviceInfo()
			if (deviceInfo != null) {
				deviceInfoFound = true
				val deviceType = deviceInfo.getType()
				if (deviceInfo.isSink() &&
					(deviceType == AudioDeviceInfo.TYPE_BLUETOOTH_A2DP || deviceType == AudioDeviceInfo.TYPE_BLUETOOTH_SCO)
				) {
					bluetoothFound = true
					break
				}
			}
		}

		if (deviceInfoFound) {
			mLastDeviceBT = bluetoothFound
		}
	}

	private fun updateTile() {
		updateTile(SystemUtils.AudioManager()!!.getStreamVolume(STREAM_MUSIC))
	}

	private fun handleVolumeLongClick(): Boolean {
		showAlertSlider()
		return true
	}

	private fun showAlertSlider() {
		val volumeSliderCallback = object : AlertSlider.SliderEventCallback {
			override fun onStartTrackingTouch(slider: Any?) {}

			override fun onStopTrackingTouch(slider: Any?) {}

			override fun onValueChange(slider: Any?, value: Float, fromUser: Boolean) {
				if (fromUser) changeVolume(Math.round(value))
			}

			override fun onCreate(alertSlider: AlertSlider) {
				SystemUtils.registerVolumeChangeListener(alertSlider)
			}

			override fun onDismiss(alertSlider: AlertSlider) {
				SystemUtils.unregisterVolumeChangeListener(alertSlider)
			}
		}

		val alertSlider = object : AlertSlider(
			mContext,
			SystemUtils.AudioManager()!!.getStreamVolume(STREAM_MUSIC).toFloat(),
			SystemUtils.AudioManager()!!.getStreamMinVolume(STREAM_MUSIC).toFloat(),
			SystemUtils.AudioManager()!!.getStreamMaxVolume(STREAM_MUSIC).toFloat(),
			1f,
			volumeSliderCallback,
		) {
			override fun onChanged(newVal: Int) {
				setSliderCurrentValue(newVal.toFloat())
			}
		}
		alertSlider.show()
	}

	private fun changeVolume(currentValue: Int) {
		SystemUtils.AudioManager()!!.setStreamVolume(
			STREAM_MUSIC,
			currentValue,
			0 /* don't show UI */,
		)
	}
}
