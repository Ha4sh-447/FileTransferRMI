:; # Bash portion
:; echo "Cleaning up build and temporary directories..."
:; rm -rf bin logs downloads storage_*
:; echo "✓ Cleanup complete!"
:; exit $?

@echo off
:: Batch portion
echo Cleaning up temporary directories (Windows)...
if exist bin rd /s /q bin
if exist logs rd /s /q logs
if exist downloads rd /s /q downloads
for /d %%i in (storage_*) do rd /s /q "%%i"
echo [OK] Cleanup complete!
