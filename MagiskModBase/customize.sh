PKGNAME="sh.siava.pixelxpert"
LSPDDBPATH="/data/adb/lspd/config/modules_config.db"
MAGISKDBPATH="/data/adb/magisk.db"
PREFSFILE="/data/user_de/0/$PKGNAME/shared_prefs/${PKGNAME}_preferences.xml"

prepareSQL(){
	unzip $ZIPFILE sqlite3 -d $TMPDIR/ > /dev/null
	chmod +x $TMPDIR/sqlite3

	SQLITEPATH="$TMPDIR/sqlite3"
}

# runSQL "database path" "command" - then you can use $SQLRESULT to read the outcome
runSQL(){
	SQLRESULT=$($SQLITEPATH $DBPATH "$CMD")
}

#grant silent root access to given UID
grantRootUID(){
	if [ -z "$1" ]; then
		ui_print "- Error: No UID provided for root grant"
		return 1
	fi
	DBPATH=$MAGISKDBPATH
	
	#new record - older magisk compatibility
	CMD="insert into policies (uid, package_name, policy, until, logging, notification) values ($1, '$2', 2, 0, 1, 0);" && runSQL
	#new record
	CMD="insert into policies (uid, policy, until, logging, notification) values ($1, 2, 0, 1, 0);" && runSQL
	#previously present record
	CMD="update policies set policy = 2, until = 0, logging = 1, notification = 0 where uid = $1;" && runSQL
}

#grant root access to given package name
grantRootPkg(){
	ui_print "- 	Granting root access to $1..."
	UID=$(pm list packages -U $1 --user 0 2>/dev/null | grep ":$1 " | awk -F 'uid:' '{ print $2 }' | cut -d ',' -f 1 | cut -d ' ' -f 1)
	[ -z "$UID" ] && UID=$(pm list packages -U $1 --user 0 2>/dev/null | grep -i "$1" | awk -F 'uid:' '{ print $2 }' | cut -d ' ' -f 1 | cut -d ',' -f 1)

	grantRootUID $UID $1
}

#grant root access to required apps
grantRootApps(){
	grantRootPkg $PKGNAME
}

assertPixelRom()
{
	PixelTipsPattern="TipsPrebuilt*"
	PixelTipsParent="/product/priv-app"

	if ! find "$PixelTipsParent" -maxdepth 1 -name "$PixelTipsPattern" -print -quit | grep -q .; then
		ui_print 'Device does not seem to be a Pixel'
		ui_print 'phone, containing an original ROM.'

		abort 'Installation aborted due to incompatibility'
	fi
}

assert16QPR()
{
	BUILD_ID=$(getprop ro.build.id 2>/dev/null || /system/bin/getprop ro.build.id 2>/dev/null)
	if [ -z $(echo "$BUILD_ID" | grep -e "[BCZ][DP][1-5]") ]; then
		ui_print 'This build is not compatible with'
		ui_print 'your ROM. Please install the stable'
		ui_print 'version 4.3.x instead'

		abort 'Installation aborted due to incompatibility'
	fi
}

assertPixelRom

assert16QPR

set_perm $MODPATH/service.sh 0 0 0755

ui_print ''
ui_print ''

# Check if this is a first-time install or an update
IS_FIRST_INSTALL=true
if pm path "$PKGNAME" >/dev/null 2>&1 || pm list packages 2>/dev/null | grep -q "$PKGNAME"; then
	IS_FIRST_INSTALL=false
fi

# Backup data if migrating from legacy system app
if pm list packages -s 2>/dev/null | grep -q "package:$PKGNAME"; then
	if [ -d "/data/user_de/0/$PKGNAME/shared_prefs" ]; then
		ui_print "- Legacy system app detected. Backing up preferences for migration..."
		mkdir -p "$MODPATH/px_backup_de"
		cp -af "/data/user_de/0/$PKGNAME/shared_prefs" "$MODPATH/px_backup_de/"
	fi
	if [ -d "/data/user/0/$PKGNAME/shared_prefs" ]; then
		mkdir -p "$MODPATH/px_backup_ce"
		cp -af "/data/user/0/$PKGNAME/shared_prefs" "$MODPATH/px_backup_ce/"
	fi
	# Android wipes updated system apps on boot when the system base disappears, so ensure reinstallation on boot
	touch "$MODPATH/install_needed"
fi

ui_print '- Preparing PixelXpert app for installation...'
APK_PATH="$MODPATH/PixelXpert.apk"
[ ! -f "$APK_PATH" ] && APK_PATH=$(ls "$MODPATH"/*.apk 2>/dev/null | head -n 1)

APP_INSTALLED=false
if [ -f "$APK_PATH" ]; then
	chmod 644 "$APK_PATH"
	if [ "$(getprop sys.boot_completed)" = "1" ]; then
		ui_print "- Installing PixelXpert app via Package Manager..."
		INSTALL_OUT=$(pm install -r -d "$APK_PATH" 2>&1)
		if echo "$INSTALL_OUT" | grep -qi "success"; then
			ui_print "- App installed successfully."
			APP_INSTALLED=true
		else
			ui_print "! pm install output: $INSTALL_OUT"
			ui_print "! Will retry installation on boot."
			touch "$MODPATH/install_needed"
		fi
	else
		ui_print "- Recovery flash detected. App will be installed on boot."
		touch "$MODPATH/install_needed"
	fi
else
	ui_print "! APK not found in zip!"
fi

# Grant root access after app is installed (when UID can be determined)
if [ "$APP_INSTALLED" = "true" ] || [ "$IS_FIRST_INSTALL" = "false" ]; then
	if [ -n "$MAGISK_VER_CODE" ] && [ "$KSU" != "true" ] && [ "$APATCH" != "true" ]; then
		prepareSQL
		grantRootApps
	else
		ui_print ''
		ui_print "- Non-Magisk root detected (KernelSU/APatch)"
		ui_print "- Please ensure root is granted to PixelXpertFork in your root manager."
	fi
fi

# Preserve custom configuration props on dirty flash / update
if [ -f "/data/adb/modules/PixelXpert/system.prop" ]; then
	grep '^ro.config.media_vol_steps=' "/data/adb/modules/PixelXpert/system.prop" > "$MODPATH/system.prop" 2>/dev/null
fi

if [ -f "$PREFSFILE" ] && grep -q 'name="noCutoutEnabled" value="true"' "$PREFSFILE"; then
	echo "pixelxpert.no_cutout_enabled=true" >> "$MODPATH/system.prop"
fi

# Clean up obsolete sepolicy rules and legacy priv-app system directory
rm -f "$MODPATH/sepolicy.rule"
rm -f "/data/adb/modules/PixelXpert/sepolicy.rule"
rm -f "/data/adb/modules_update/PixelXpert/sepolicy.rule"
rm -rf "$MODPATH/system"

ui_print ''
ui_print ''
ui_print 'Installation Complete!'
if [ ! -f "$LSPDDBPATH" ]; then
	ui_print 'Note: Please ensure LSPosed is installed.'
fi
ui_print 'Please enable PixelXpert in LSPosed and Reboot your device to activate.'

ui_print ''
ui_print '  **********************'
ui_print '  * Brought to you by: *'
ui_print '  *                    *'
ui_print '  * PixelXpertFork     *'
ui_print '  **********************'
ui_print ''
