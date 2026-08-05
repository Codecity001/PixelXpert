PKGNAME="sh.siava.pixelxpert"
PKGPATH="/system/priv-app/PixelXpert/PixelXpert.apk"
LSPDDBPATH="/data/adb/lspd/config/modules_config.db"
MAGISKDBPATH="/data/adb/magisk.db"

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
	[ -z "$1" ] && return
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
	UID=$(pm list packages -U $1 --user 0 2>/dev/null | grep -i "$1" | awk -F 'uid:' '{ print $2 }' | cut -d ' ' -f 1 | cut -d ',' -f 1)

	grantRootUID $UID $1
}

#grant root access to required apps
grantRootApps(){
	grantRootPkg $PKGNAME
}

applySepolicy(){
	if [ -f "$MODPATH/sepolicy.rule" ] && command -v magiskpolicy >/dev/null 2>&1; then
		ui_print "- 	Applying SELinux rules live..."
		magiskpolicy --live --apply "$MODPATH/sepolicy.rule"
	fi
}

migratePrefs(){
  am start -n "$PKGNAME/.ui.activities.SettingsActivity" -e migratePrefs true > /dev/null
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

prepareSQL

ui_print ''
ui_print ''

grantRootApps

applySepolicy

set_perm $MODPATH/service.sh 0 0 0755

if [ $(ls $LSPDDBPATH) = $LSPDDBPATH ]; then
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
	ui_print '  * PixelXpert team    *'
	ui_print '  **********************'
	ui_print ''
