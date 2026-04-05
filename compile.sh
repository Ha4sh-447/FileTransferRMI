:; # Bash portion (Linux/macOS)
:; echo "Compiling Distributed File Transfer System..."
:; mkdir -p bin
:; javac -d bin -cp src src/common/*.java src/server/*.java src/registry/*.java src/client/*.java
:; if [ $? -eq 0 ]; then
:;   echo "✓ Compilation successful! Binaries are in the 'bin' directory."
:; else
:;   echo "✗ Compilation failed."
:;   exit 1
:; fi
:; exit $?

@echo off
:: Batch portion (Windows)
echo Compiling Distributed File Transfer System (Windows)...
if not exist bin mkdir bin
javac -d bin -cp src src\common\*.java src\server\*.java src\registry\*.java src\client\*.java
if %ERRORLEVEL% EQU 0 (
    echo [OK] Compilation successful!
) else (
    echo [ERROR] Compilation failed.
    exit /b 1
)
