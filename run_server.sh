:; # Bash portion
:; PORT=${1:-1101}
:; STORAGE=${2:-storage_A}
:; NAME=${3:-Server-A}
:; LB_HOST=${4:-localhost}
:; LB_PORT=${5:-1099}
:; java -cp bin server.FileServerImpl $PORT $STORAGE $NAME $LB_HOST $LB_PORT
:; exit $?

@echo off
:: Batch portion
set PORT=%1
if "%PORT%"=="" set PORT=1101
set STORAGE=%2
if "%STORAGE%"=="" set STORAGE=storage_A
set NAME=%3
if "%NAME%"=="" set NAME=Server-A
set LB_HOST=%4
if "%LB_HOST%"=="" set LB_HOST=localhost
set LB_PORT=%5
if "%LB_PORT%"=="" set LB_PORT=1099
java -cp bin server.FileServerImpl %PORT% %STORAGE% %NAME% %LB_HOST% %LB_PORT%
