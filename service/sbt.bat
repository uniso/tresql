set SCRIPT_DIR=%~dp0
java %java_opts% -XX:+CMSClassUnloadingEnabled -XX:MaxPermSize=256m -Xmx512M -Xss2M -jar "%SCRIPT_DIR%\sbt-launcher.jar" %*
