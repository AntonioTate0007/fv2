@echo off
REM run_local.bat - launch the Fortress backend + dashboard + Jarvis bot on Windows.
REM
REM   1. Create a bot with @BotFather in Telegram (optional, for the bot).
REM   2. Double-click run_local.bat (or run it in a terminal) once - it makes a .env.
REM   3. Open server\.env, paste your keys, save.
REM   4. Run run_local.bat again - it starts everything.
REM
REM Then open http://127.0.0.1:8000/dashboard

setlocal
cd /d "%~dp0"

if not exist .env (
  copy .env.example .env >nul
  echo.
  echo Created server\.env from the template.
  echo Open server\.env and set:
  echo     ALPACA_API_KEY=...        ALPACA_API_SECRET=...     ALPACA_PAPER=true
  echo     GEMINI_API_KEY=...        ^(optional^)
  echo     TELEGRAM_BOT_TOKEN=...    ^(optional, for the bot^)
  echo Leave PUBLIC_BASE_URL blank for local.
  echo Then run run_local.bat again.
  echo.
  pause
  exit /b 0
)

where python >nul 2>nul
if errorlevel 1 (
  echo Python not found. Install Python 3.11+ from https://www.python.org/downloads/
  echo Make sure to tick "Add python.exe to PATH" during install.
  pause
  exit /b 1
)

if not exist .venv (
  echo Creating virtual environment...
  python -m venv .venv
)
call .venv\Scripts\activate.bat

if not exist .venv\.deps (
  echo Installing requirements ^(first run only, may take a minute^)...
  python -m pip install --quiet --upgrade pip
  pip install -r requirements.txt
  echo ok> .venv\.deps
)

echo.
echo Starting Fortress on http://127.0.0.1:8000
echo   Dashboard: http://127.0.0.1:8000/dashboard
echo   Press Ctrl+C to stop.
echo.
uvicorn main:app --host 127.0.0.1 --port 8000 --reload
