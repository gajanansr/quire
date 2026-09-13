# Source this before any Gradle or adb work.
#
# Pinned per the plan's Global Constraints: JDK 21, because AGP does not support
# Homebrew's default 26. The paths below are this machine's; each is applied only
# when it actually exists, so CI and anyone on another OS keep the JDK and SDK they
# already have rather than inheriting a macOS Homebrew layout that is not there.
# Exporting them unconditionally is what made the build machine-specific.

_quire_jdk=/opt/homebrew/opt/openjdk@21/libexec/openjdk.jdk/Contents/Home
_quire_sdk=/opt/homebrew/share/android-commandlinetools

if [ -d "$_quire_jdk" ]; then
  export JAVA_HOME="$_quire_jdk"
fi

if [ -d "$_quire_sdk" ]; then
  export ANDROID_HOME="$_quire_sdk"
elif [ -n "${ANDROID_SDK_ROOT:-}" ]; then
  export ANDROID_HOME="$ANDROID_SDK_ROOT"
fi

if [ -n "${ANDROID_HOME:-}" ]; then
  export ANDROID_SDK_ROOT="$ANDROID_HOME"
  export PATH="$ANDROID_HOME/platform-tools:$ANDROID_HOME/emulator:$ANDROID_HOME/cmdline-tools/latest/bin:$PATH"
fi

if [ -n "${JAVA_HOME:-}" ]; then
  export PATH="$JAVA_HOME/bin:$PATH"
fi

unset _quire_jdk _quire_sdk
