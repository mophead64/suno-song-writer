# Dev shortcuts. Assumes the shell setup in docs/emulator-setup.md (JAVA_HOME, ANDROID_HOME).

AVD    ?= suno_pixel
APP_ID ?= app.sunosongwriter.debug
EMULATOR := $(ANDROID_HOME)/emulator/emulator
ADB      := $(ANDROID_HOME)/platform-tools/adb

.PHONY: emu emu-cold wait install apk benchmark jank run logs test test-device lint build clean

## Boot the emulator in the background
emu:
	@$(EMULATOR) -avd $(AVD) -no-audio >/dev/null 2>&1 &
	@$(MAKE) --no-print-directory wait

## Cold boot (fixes most stuck emulators)
emu-cold:
	@$(EMULATOR) -avd $(AVD) -no-audio -no-snapshot-load >/dev/null 2>&1 &
	@$(MAKE) --no-print-directory wait

## Wait until the emulator has finished booting
wait:
	@$(ADB) wait-for-device
	@until [ "$$($(ADB) shell getprop sys.boot_completed 2>/dev/null | tr -d '\r')" = "1" ]; do sleep 2; done
	@echo "Emulator ready"

## Build, install and launch the debug app
install:
	./gradlew installDebug
	@$(ADB) shell monkey -p $(APP_ID) -c android.intent.category.LAUNCHER 1 >/dev/null 2>&1

## Build a signed release APK to share: dist/SongWriter-<version>.apk (see scripts/build-apk.sh)
apk:
	@scripts/build-apk.sh

## Build, install and launch the minified release build (debug-signed), for performance checks
benchmark:
	./gradlew installBenchmark
	@$(ADB) shell monkey -p app.sunosongwriter.benchmark -c android.intent.category.LAUNCHER 1 >/dev/null 2>&1

## Frame timing for the benchmark build since its last reset (scroll around first)
jank:
	@$(ADB) shell dumpsys gfxinfo app.sunosongwriter.benchmark | grep -E "Total frames|Janky frames|th percentile"
	@$(ADB) shell dumpsys gfxinfo app.sunosongwriter.benchmark reset >/dev/null

run: install

## Logcat for the running app only
logs:
	$(ADB) logcat --pid=$$($(ADB) shell pidof -s $(APP_ID))

## Unit tests
test:
	./gradlew testDebugUnitTest

## Instrumented tests on the running emulator
test-device:
	./gradlew connectedDebugAndroidTest

lint:
	./gradlew lintDebug

build:
	./gradlew assembleDebug

clean:
	./gradlew clean
