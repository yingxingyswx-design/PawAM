@echo off
setlocal
set DEST=%USERPROFILE%\.gradle\wrapper\dists\gradle-9.3.1-bin\manualpreload
if not exist "%DEST%" mkdir "%DEST%"
del /f /q "%DEST%\gradle-9.3.1-bin.zip" 2>nul
echo Downloading gradle 9.3.1 ...
curl -L --retry 5 --retry-delay 3 --retry-all-errors -o "%DEST%\gradle-9.3.1-bin.zip" https://services.gradle.org/distributions/gradle-9.3.1-bin.zip
if errorlevel 1 (
  echo DOWNLOAD FAILED
  exit /b 1
)
echo Extract ...
if exist "%DEST%\gradle-9.3.1" rmdir /s /q "%DEST%\gradle-9.3.1"
tar -xf "%DEST%\gradle-9.3.1-bin.zip" -C "%DEST%"
if errorlevel 1 (
  echo EXTRACT FAILED
  exit /b 1
)
type nul > "%DEST%\gradle-9.3.1-bin.zip.ok"
if exist "%DEST%\gradle-9.3.1\bin\gradle.bat" (
  echo GRADLE READY
) else (
  echo GRADLE MISSING
  exit /b 1
)
endlocal
