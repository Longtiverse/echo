@echo off
setlocal

set JAVA_HOME=D:\AndroidStudio\jbr
set ANDROID_SDK_ROOT=C:\Users\Alien\AppData\Local\Android\Sdk
set PATH=%JAVA_HOME%\bin;%PATH%

"%JAVA_HOME%\bin\java.exe" -cp "%~dp0gradle\wrapper\gradle-wrapper.jar" org.gradle.wrapper.GradleWrapperMain %*
