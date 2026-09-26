**v6.0.2**
- fix(UpdateFragment): allow switching from stable to canary when canary has lower version code
- fix(EasyUnlock): securely disable Auto-Confirm PIN during FBE (Direct Boot)
- refactor(EasyUnlock): replace thread logic with native LockPatternUtils bypass
- feat(KeyGuardPinScrambler): fix Shuffle PIN for Compose Keyguard on Android 17
- fix(NotificationExpander): fix Notification Default Expansion on CP3A/CP41
- fix(StatusbarGestures): fix Quick QS panel pulldown gesture on CP3A/CP41
- fix(StatusIconTuner): fix hiding status bar icons
- fix(StatusbarMods): resolve Network Traffic mid-right visibility issue
- fix(StatusbarMods): support Android 17 Compose clock repositioning and formatting

**v6.0.1**
- feat(qs): add Caffeine quick settings tile for screen timeout
- fix(BatteryDataProvider): handle signature change of calculateChargingSpeed in Android 17 QPR
- fix(KeyguardMods): correct ReflectionConsumer type for multi-method hooks
- fix(NetworkTraffic): prevent handler freeze and massive speed spikes
- fix(NetworkTraffic): initialize totalTxBytes in update() to prevent a bogus upload speed on first tick after attach
- fix(ScreenOffKeys): fix double-tap power gesture conflict with system Wallet/Camera settings
- service.sh: restart Pixel Launcher and Google Dialer on boot to fix unhooked LSPosed mods

**v6.0.0**
- feat(AdbWifiPortPin): pin wireless ADB to a static port via internal TCP forwarder
- fix(ScreenGestures): use GO_TO_SLEEP_REASON_POWER_BUTTON to force immediate lock on double tap to sleep
- fix(StatusbarMods): implement Android 17 ClockInteractor hook to support custom clock formats and Gregorian date formatting ($G)
- fix(StatusbarMods): use system clock seconds setting from SysUI Tuner if show seconds is enabled on Compose clock
- feat(StatusbarMods): add slider preference for double-row clock start offset (0–40 dp)
- fix(StatusbarMods): restore ongoing activity chip to bottom notification row
- dialer: RecordingMessage: suppress call-recording and call-notes announcements via targeted resources and TTS
- magisk: enforce sepolicy whitelist and strip unsupported rules on KernelSU/APatch
- service.sh: restart SystemUI after boot complete to allow LSPosed to hook reliably
- {MagiskModBase,app}: add support for newer Android Canary and Beta build number formats
- fix(xposed): make runningMods thread-safe and guard preference dispatching
- fix(ScreenGestures): fix lockscreen double tap to sleep gesture support on Android 17 QPR1+
- fix(flashlight, gestures): resolve background camera restrictions and power wake detection
