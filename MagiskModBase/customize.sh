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

migratePrefs(){
	am start -W -n "$PKGNAME/.ui.activities.SettingsActivity" -e migratePrefs true > /dev/null

	# The staged APK cannot populate Vector remote preferences until after this reboot.
	if [ -f "$PREFSFILE" ] && grep -q 'name="noCutoutEnabled" value="true"' "$PREFSFILE"; then
		echo "pixelxpert.no_cutout_enabled=true" >> "$MODPATH/system.prop"
	else
		echo "pixelxpert.no_cutout_enabled=false" >> "$MODPATH/system.prop"
	fi
}

testKernelSU()
{
	if [[ $(ksud -V 2>&1 | grep "not found" | wc -c) -eq 0 ]]; then #KSU installed
    	if [[ $(pm list packages | grep $PKGNAME | wc -c) -eq 0 ]]; then #PixelXpert NOT installed yet
    		ui_print ''
    		ui_print '*******************************'
    		ui_print 'KernelSU binaries found!'
    		ui_print ''
    		ui_print '                CAUTION!:'
    		ui_print 'Before installation, you MUST disable'
    		ui_print '"Umount modules by default"'
    		ui_print 'Otherwise, your device will fall into BOOTLOOP!'
    		ui_print ''
    		ui_print 'Do you wish to continue?'
    		ui_print 'Volume Up: Continue'
    		ui_print 'Volume Down: Abort'
    		if [[ "$(getevent -l | grep -m 1 KEY_VOLUME)" == *"VOLUMEDOWN"* ]]; then
    			abort 'Installation cancelled'
    		fi;
    	fi;
    fi;
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

testKernelSU

if [ -n "$MAGISK_VER_CODE" ] && [ "$KSU" != "true" ] && [ "$APATCH" != "true" ]; then
	prepareSQL

	ui_print ''
	ui_print ''

	grantRootApps
else
	ui_print ''
	ui_print ''
	ui_print "- Non-Magisk root detected (KernelSU/APatch)"
	ui_print "- Please ensure root is granted to PixelXpertFork in your root manager."
fi

set_perm $MODPATH/service.sh 0 0 0755

ui_print ''
ui_print ''

ui_print '- Preparing PixelXpert app for installation...'
APK_PATH="$MODPATH/PixelXpert.apk"
[ ! -f "$APK_PATH" ] && APK_PATH=$(ls "$MODPATH"/*.apk 2>/dev/null | head -n 1)

if [ -f "$APK_PATH" ]; then
	touch "$MODPATH/install_needed"
	ui_print "- App will be installed automatically on boot."
else
	ui_print "- APK not found in zip!"
fi

# Backup data for migration from system app to user app
if pm list packages -s | grep -q "package:$PKGNAME"; then
	if [ -d "/data/user_de/0/$PKGNAME/shared_prefs" ]; then
		ui_print "- System app detected. Backing up preferences for migration..."
		mkdir -p "$MODPATH/px_backup_de"
		cp -af "/data/user_de/0/$PKGNAME/shared_prefs" "$MODPATH/px_backup_de/"
	fi
	if [ -d "/data/user/0/$PKGNAME/shared_prefs" ]; then
		mkdir -p "$MODPATH/px_backup_ce"
		cp -af "/data/user/0/$PKGNAME/shared_prefs" "$MODPATH/px_backup_ce/"
	fi
fi

# Clean up obsolete sepolicy rules
rm -f "$MODPATH/sepolicy.rule"
rm -f "/data/adb/modules/PixelXpert/sepolicy.rule"
rm -f "/data/adb/modules_update/PixelXpert/sepolicy.rule"

# Make sure system folder doesn't exist so it doesn't mount as priv-app
rm -rf "$MODPATH/system"

if [ -f "$LSPDDBPATH" ]; then
	ui_print ''
	ui_print ''

	migratePrefs

	ui_print ''
	ui_print ''
	ui_print 'Installation Complete!'
	ui_print 'Please Reboot your device to activate'
else
	ui_print 'Lsposed not found!!'
	ui_print 'This module will not work without Lsposed'
	ui_print 'Please:'
	ui_print '- Install Lsposed'
	ui_print '- Reboot'
#	ui_print '- Manually enable PixelXpert in Lsposed'
#	ui_print '- Reboot'
fi

	ui_print ''
	ui_print '  **********************'
	ui_print '  * Brought to you by: *'
	ui_print '  *                    *'
	ui_print '  * PixelXpertFork     *'
	ui_print '  **********************'
	ui_print ''
