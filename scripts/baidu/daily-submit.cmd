@echo off
rem Daily job: submit the eval-video URLs allowed by today's Baidu quota.
rem Ledger lives in data\baidu-submit\state.json; re-running the same day is safe.
rem Use this file as the "Action" if you want to schedule it in Task Scheduler.
setlocal
cd /d "%~dp0..\.."
python scripts\baidu\submit_eval_urls.py --status
echo.
python scripts\baidu\submit_eval_urls.py --commit
echo.
python scripts\baidu\submit_eval_urls.py --status
endlocal
