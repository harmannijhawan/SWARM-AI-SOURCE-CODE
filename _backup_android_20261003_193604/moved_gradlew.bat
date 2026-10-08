@if "%DEBUG%" == "" @echo off
@rem ##########################################################################
@rem
@rem  Gradle Start Up Script for Windows
@rem
@rem ##########################################################################
@rem Set local scope for the variables with windows NT shell
if "%OS%"=="Windows_NT" setlocal

set DIRNAME=%~dp0
if "%DIRNAME%" == "" set DIRNAME=.
set APP_BASE_NAME=%~n0
set APP_HOME=%DIRNAME%

rem Add default JVM options here. You can also use JAVA_OPTS and GRADLE_OPTS to pass JVM options to this script.
set DEFAULT_JVM_OPTS=

set EXECUTABLE=%APP_HOME%\gradle\wrapper\gradle-wrapper.jar

rem Check that the specified Gradle wrapper jar exists
if exist "%EXECUTABLE%" goto :checkJar

echo Error: Could not find or load the Gradle Wrapper.
echo Please verify that your Gradle Wrapper is properly configured.
echo The wrapper jar file '%EXECUTABLE%' was not found.
exit /b 1

:checkJar
rem Execute gradle
"%JAVA_EXE%" %DEFAULT_JVM_OPTS% %JAVA_OPTS% %GRADLE_OPTS% "-Dorg.gradle.appname=%APP_BASE_NAME%" -jar "%EXECUTABLE%" %*
