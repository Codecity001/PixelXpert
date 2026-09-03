PKGNAME="sh.siava.pixelxpert"
PKGPATH="/system/priv-app/PixelXpert/PixelXpert.apk"
LSPDDBPATH="/data/adb/lspd/config/modules_config.db" 
MAGISKDBPATH="/data/adb/magisk.db" 
MODDIR=${0%/*} 
 
prepareSQL(){ 
	chmod +x $MODDIR/sqlite3
	SQLITEPATH="$MODDIR/sqlite3" 
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
	echo "- 	Granting root access to $1..." 
	UID=$(pm list packages -U $1 --user 0 2>/dev/null | grep -i "$1" | awk -F 'uid:' '{ print $2 }' | cut -d ' ' -f 1 | cut -d ',' -f 1)
 
	grantRootUID $UID $1 
} 
 
#grant root access to required apps 
grantRootApps(){ 
	grantRootPkg $PKGNAME
}

applySepolicy(){
	if [ -f "$MODDIR/sepolicy.rule" ] && command -v magiskpolicy >/dev/null 2>&1; then
		echo "- 	Applying SELinux rules live..."
		magiskpolicy --live --apply "$MODDIR/sepolicy.rule"
	fi
}

applySepolicy

prepareSQL 
 
grantRootApps

# Wait for boot to finish
until [ "$(getprop sys.boot_completed)" = "1" ]; do
	sleep 1
done

# Give the system a brief moment to settle
sleep 2

# Restart SystemUI so the LSPosed hook can properly initiate
killall com.android.systemui