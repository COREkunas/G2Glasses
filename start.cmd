@echo off
setlocal
pushd "%~dp0"
if exist ".venv\Scripts\python.exe" (
  ".venv\Scripts\python.exe" run_app.py
) else (
  py -3 run_app.py
)
set "appResult=%errorlevel%"
popd
if not "%appResult%"=="0" pause
exit /b %appResult%
