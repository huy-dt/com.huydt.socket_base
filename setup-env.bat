@echo off
echo Installing JDK 21...

set JDK_URL=https://github.com/adoptium/temurin21-binaries/releases/latest/download/OpenJDK21U-jdk_x64_windows_hotspot.zip
set JDK_DIR=%USERPROFILE%\jdk21

echo Downloading JDK 21...
powershell -Command "Invoke-WebRequest -Uri %JDK_URL% -OutFile jdk21.zip"

echo Extracting JDK...
powershell -Command "Expand-Archive jdk21.zip -DestinationPath %USERPROFILE% -Force"

for /d %%i in (%USERPROFILE%\jdk-21*) do set JDK_DIR=%%i

echo Setting environment variables...
setx JAVA_HOME "%JDK_DIR%"
setx PATH "%JDK_DIR%\bin;%PATH%"

echo Cleaning up...
del jdk21.zip

echo Verifying installation...
"%JDK_DIR%\bin\java" -version

echo JDK 21 installation completed!
