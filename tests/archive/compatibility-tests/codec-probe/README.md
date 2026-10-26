# Platform AAC capability and encoder probe

Standalone `app_process` class, not packaged into the app. Reports AAC encoders and
decoders, declared profile levels and `isFormatSupported` for profile 5 (HE-AAC)
and 29 (HE-AAC v2 / HE_PS). If an output directory argument is supplied, an encoder
that explicitly declares support is asked to encode eight seconds of synthetic
44.1 kHz stereo tone PCM. A successful encode is not proof of HE until the pulled
file's AudioSpecificConfig is independently checked with ffprobe and decoded.
No declared encoder is an unsupported/unverified case, not a passed conversion.

Compile (JDK 17, Android SDK 35):

```sh
javac -source 8 -target 8 -cp "$ANDROID_HOME/platforms/android-35/android.jar" \
  -d classes CodecCapabilitiesProbe.java
"$ANDROID_HOME/build-tools/35.0.0/d8" \
  --lib "$ANDROID_HOME/platforms/android-35/android.jar" \
  --output dex classes/CodecCapabilitiesProbe.class
adb push dex/classes.dex /data/local/tmp/aac-codec-probe.dex
adb shell mkdir -p /data/local/tmp/aac-profile-output
adb shell 'CLASSPATH=/data/local/tmp/aac-codec-probe.dex app_process /system/bin CodecCapabilitiesProbe /data/local/tmp/aac-profile-output'
adb pull /data/local/tmp/aac-profile-output
```

The probe does not perform network calls or access user media. All output names
are hardcoded synthetic fixtures. Build products classes/ and dex/ are not source
release content; preserve Java and this README. Runtime may be restricted by a
particular device's shell/SELinux configuration; record that as a probe limitation.
