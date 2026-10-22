# R8 rules for the release build (proguard-android-optimize.txt plus the consumer rules shipped by
# Room, coroutines, Compose and AndroidX already cover those libraries).
#
# Deliberately empty of -keep rules until a smoke test shows one is needed; every rule added here
# defeats shrinking. The things that look like they need one, and why they do not:
#  - BtClassicTransport reflects on android.bluetooth.BluetoothDevice: a platform class, never renamed.
#  - org.json (theme and layout JSON) is a platform class; the JSON is parsed by hand, no model classes.
#  - Room instantiates its generated *_Impl classes by name: Room's own consumer rule keeps them.

# Crash reports (CrashLog) carry a stack trace; keep file and line numbers so mapping.txt can decode it.
-keepattributes SourceFile,LineNumberTable
-renamesourcefileattribute SourceFile
