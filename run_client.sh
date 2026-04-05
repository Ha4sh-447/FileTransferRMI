:; # Bash portion
:; LB_HOST=${1:-localhost}
:; LB_PORT=${2:-1099}
:; java -cp bin client.FileTransferClient $LB_HOST $LB_PORT
:; exit $?

@echo off
:: Batch portion
set LB_HOST=%1
if "%LB_HOST%"=="" set LB_HOST=localhost
set LB_PORT=%2
if "%LB_PORT%"=="" set LB_PORT=1099
java -cp bin client.FileTransferClient %LB_HOST% %LB_PORT%
