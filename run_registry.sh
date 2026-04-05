:; # Bash portion
:; PORT=${1:-1099}
:; echo "Starting Load Balancer (RMI Registry) on port $PORT..."
:; java -cp bin registry.LoadBalancer $PORT
:; exit $?

@echo off
:: Batch portion
set PORT=%1
if "%PORT%"=="" set PORT=1099
echo Starting Load Balancer (Windows) on port %PORT%...
java -cp bin registry.LoadBalancer %PORT%
