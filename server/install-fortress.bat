@echo off
REM =====================================================================
REM  Fortress installer runner for Windows.
REM
REM  What this does:
REM    - Asks you for your Pi's IP and username (defaults provided)
REM    - SSHs into the Pi
REM    - Runs the one-line installer from GitHub
REM    - Streams the installer's output back to this window
REM
REM  You'll be prompted ONCE for the Pi's SSH password (by SSH itself,
REM  not this script). This script never sees or stores it.
REM
REM  Requirements:
REM    - Windows 10 (build 1809+) or 11 with the OpenSSH client feature
REM      installed. If `ssh` isn't found, this script will tell you how
REM      to install it.
REM =====================================================================

setlocal enabledelayedexpansion

echo.
echo   =====================================================
echo    Fortress installer  -  runs on your Raspberry Pi
echo   =====================================================
echo.

REM ---- 1. Check for the OpenSSH client on this Windows box -------------
where ssh >nul 2>nul
if errorlevel 1 (
    echo   [X] Windows can't find the 'ssh' command.
    echo.
    echo   Install the OpenSSH client one time:
    echo     Settings ^> Apps ^> Optional Features ^> Add a feature
    echo     ^> pick 'OpenSSH Client' ^> Install
    echo.
    echo   Then double-click this .bat again.
    echo.
    pause
    exit /b 1
)

REM ---- 2. Ask for Pi IP + username -------------------------------------
set "PI_IP=192.168.1.128"
set "PI_USER=admin"
set /p "PI_IP=  Pi IP address [%PI_IP%]: "
set /p "PI_USER=  Pi username    [%PI_USER%]: "

echo.
echo   Connecting to %PI_USER%@%PI_IP% ...
echo   SSH will prompt for the Pi's password once. Type it and press Enter.
echo.
echo   The installer usually takes 5-8 minutes on a Pi 4 (yfinance is
echo   the slow part). Watch this window for the summary at the end.
echo.
pause

REM ---- 3. Run the installer over SSH -----------------------------------
REM   -o StrictHostKeyChecking=accept-new    saves the Pi's host key on
REM                                          first connect without an
REM                                          interactive prompt
ssh -o StrictHostKeyChecking=accept-new %PI_USER%@%PI_IP% "curl -sSL https://raw.githubusercontent.com/AntonioTate0007/fv2/main/server/setup-pi.sh | bash"

set "SSH_EXIT=%errorlevel%"
echo.
if "%SSH_EXIT%"=="0" (
    echo   =====================================================
    echo    DONE. Look above for the Local + Public URLs.
    echo    Bookmark the Public URL on your phone.
    echo   =====================================================
    echo.
    echo   Next: add your Alpaca ^& Gemini keys.
    echo.
    echo     ssh %PI_USER%@%PI_IP%
    echo     nano ~/fortress/server/.env
    echo     sudo systemctl restart fortress
    echo.
) else (
    echo   =====================================================
    echo    Installer exited with code %SSH_EXIT%.
    echo    Scroll up to see what went wrong.
    echo   =====================================================
)

pause
endlocal
