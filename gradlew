#!/bin/sh
export JAVA_HOME="/d/AndroidStudio/jbr"
export ANDROID_SDK_ROOT="/c/Users/Alien/AppData/Local/Android/Sdk"
export PATH="$JAVA_HOME/bin:$PATH"

cd "$(dirname "$0")"
exec java -cp "gradle/wrapper/gradle-wrapper.jar" org.gradle.wrapper.GradleWrapperMain "$@"
