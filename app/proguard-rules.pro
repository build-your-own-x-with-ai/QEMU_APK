# Add project specific ProGuard rules here.

# Keep QEMU process-related classes
-keep class com.qemuapk.qemu.** { *; }
-keep class com.qemuapk.vm.** { *; }

# Gson serialization
-keep class com.qemuapk.vm.VmConfig { *; }
-keepattributes Signature
-keepattributes *Annotation*

# Coroutines
-dontwarn kotlinx.coroutines.**
