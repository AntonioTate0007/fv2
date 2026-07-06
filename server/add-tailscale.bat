@echo off
REM =====================================================================
REM  Fortress - add Tailscale to the Pi.
REM
REM  Double-click .bat that SSHs into your Pi and joins it to your
REM  Tailscale account. After it finishes, you can hit the Pi from your
REM  phone or laptop on any network (cellular, hotel wifi, etc) as long
REM  as your device is signed in to Tailscale.
REM
REM  Prereqs:
REM    - Fortress already installed on the Pi (install-fortress.bat done)
REM    - Windows OpenSSH client installed (this script checks)
REM    - Tailscale account (already signed in on this PC per the operator)
REM =====================================================================

setlocal enabledelayedexpansion

echo.
echo   =====================================================
echo    Fortress  -  add Tailscale to the Pi
echo   =====================================================
echo.

REM ---- 1. OpenSSH client check ----------------------------------------
where ssh >nul 2>nul
if errorlevel 1 (
    echo   [X] Windows can't find 'ssh'. Install the OpenSSH client:
    echo     Settings ^> Apps ^> Optional Features ^> Add a feature
    echo     ^> pick 'OpenSSH Client' ^> Install
    echo.
    pause
    exit /b 1
)

REM ---- 2. Ask for Pi IP + username -------------------------------------
set "PI_IP=192.168.1.213"
set "PI_USER=admin"
set /p "PI_IP=  Pi IP address [%PI_IP%]: "
set /p "PI_USER=  Pi username    [%PI_USER%]: "

echo.
echo   Connecting to %PI_USER%@%PI_IP% ...
echo.
echo   During install you'll see:
echo     1. SSH prompts for the Pi password (twice - once for SSH, once for sudo)
echo     2. A Tailscale login URL is printed - COPY IT, paste in a browser
echo        on a device already signed in to your Tailscale account,
echo        approve the Pi.
echo     3. Summary block appears with your Pi's Tailscale IP + URLs.
echo.
pause

REM ---- 3. Run the add-tailscale installer over SSH ---------------------
ssh -tt -o StrictHostKeyChecking=accept-new %PI_USER%@%PI_IP% "curl -sSL https://raw.githubusercontent.com/AntonioTate0007/fv2/main/server/add-tailscale.sh | bash"

set "SSH_EXIT=%errorlevel%"
echo.
if "%SSH_EXIT%"=="0" (
    echo   =====================================================
    echo    DONE. Look above for the Tailscale IP + URLs.
    echo    Bookmark http://^<tailscale-ip^>:8000 on your phone.
    echo   =====================================================
) else (
    echo   Installer exited with code %SSH_EXIT%. Scroll up.
)

pause
endlocal
