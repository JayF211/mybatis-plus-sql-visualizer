@echo off
rem ============================================================================
rem FALLBACK ONLY -- not the canonical way to start the recording sandbox.
rem
rem Canonical (user-decided 2026-10-13):
rem   ./gradlew runIde -PgifRecord=true --args=F:/code/.../mybatis-plus-sql-visualizer-demo
rem It rebuilds+installs the plugin AND merges the sandbox's own
rem   build/MPSV-GifFresh/.../config/idea64.exe.vmoptions
rem (so anything kept there -- e.g. the third-party -javaagent -- is carried in).
rem
rem This script instead only reads record\sandbox.vmoptions, so it SILENTLY DROPS
rem whatever lives in the sandbox config's vmoptions, and it bypasses gradle so a
rem changed plugin is not reinstalled. Use it only when you cannot wait for a
rem build, then go back to the gradle command.
rem
rem Never use the command-line form "-Djb.vmOptionsFile=...": the launcher does not
rem parse it and silently falls back to the default config, which starts the DAILY
rem IDE (looks exactly like a sandbox: main window, no license dialog, normal-looking
rem project title).
rem
rem Whatever you use, verify by LOG LANDING, not by window/title:
rem   build/MPSV-GifFresh/.../log/idea.log  must be the one refreshing
rem   %LOCALAPPDATA%\JetBrains\IntelliJIdea<ver>\log\idea.log must stay untouched
rem (and `ls` must use -t, otherwise idea.log gets cut off by tail in name order).
rem ============================================================================
setlocal
set "IDEA_VM_OPTIONS=F:\code\Java\Gitee\SongRenShuo\mybatis-plus-sql-visualizer\record\sandbox.vmoptions"
start "SQLVisualizer-Sandbox" "C:\Users\USER\.gradle\caches\9.7.0\transforms\af228bcb143270d170c2bd6debbba1c3\transformed\idea-2026.2.1-win\bin\idea64.exe"
endlocal
