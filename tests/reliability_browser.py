"""Visible, local-only acceptance test. Start an isolated dsb backend first."""
import argparse
import json
from http.server import BaseHTTPRequestHandler, ThreadingHTTPServer
from pathlib import Path
import shutil
import subprocess
import threading
import time

HTML = b"""<!doctype html><meta charset="utf-8"><title>DSB reliability acceptance</title>
<style>body{font:18px system-ui;background:#edf3fa;padding:40px;color:#17304c}
section,form{background:white;border-radius:12px;padding:24px;margin:20px 0;max-width:900px}
button,input{font:inherit;padding:10px;margin:8px}h1{color:#1763aa}output{color:#186b39}</style>
<h1>DSB visible reliability acceptance</h1><p>Local fixture: semantic targeting, business readback, upload and cancellation.</p>
<section><h2>Background form</h2><button onclick="window.wrong++">Save</button></section>
<section role="dialog" aria-label="Editor"><h2>Scoped editor</h2>
<button onclick="window.saves++;setTimeout(()=>document.querySelector('#status').textContent='Saved',350)">Save</button>
<output id="status">Pending</output></section>
<form id="form"><h2>Form diagnostics</h2><label>Region <input role="combobox" aria-controls="regions" value="Shanghai"></label>
<label>Password <input id="password" type="password"></label></form>
<div id="regions" role="listbox"><div role="option" aria-selected="true">Shanghai</div></div>
<section><button id="upload">Choose detached input</button><output id="uploaded">No file</output></section>
<script>window.saves=0;window.wrong=0;
document.querySelector('#upload').onclick=()=>{const input=document.createElement('input');input.type='file';
input.onchange=async()=>{await fetch('/upload',{method:'POST',body:input.files[0]});document.querySelector('#uploaded').textContent='Uploaded '+input.files[0].name};input.click()};</script>"""


class Fixture(BaseHTTPRequestHandler):
    def do_GET(self):
        self.send_response(200)
        self.send_header("Content-Type", "text/html; charset=utf-8")
        self.end_headers()
        self.wfile.write(HTML)

    def do_POST(self):
        self.rfile.read(int(self.headers.get("Content-Length", "0")))
        self.send_response(200)
        self.send_header("Content-Type", "application/json")
        self.end_headers()
        self.wfile.write(b'{"accepted":true}')

    def log_message(self, *args):
        pass


def main():
    parser = argparse.ArgumentParser()
    parser.add_argument("--port", default="10059")
    parser.add_argument("--id", default="2026101501")
    args = parser.parse_args()
    repo = Path(__file__).resolve().parents[1]
    records = repo / "logs" / "reliability-acceptance" / str(time.time_ns())
    records.mkdir(parents=True)
    cli = [shutil.which("dsb"), "--port", args.port, "--id", args.id,
           "--session", "reliability-upgrade", "--record-dir", str(records), "--no-auto-start"]
    server = ThreadingHTTPServer(("127.0.0.1", 0), Fixture)
    threading.Thread(target=server.serve_forever, daemon=True).start()
    checks = []

    def call(command, params=None, ok=True):
        cmd = cli + ["--json"] + command
        body = None
        if params is not None:
            cmd += ["--params", "-"]
            body = ("\ufeff" + json.dumps(params, ensure_ascii=False)).encode("utf-8")
        result = subprocess.run(cmd, input=body, capture_output=True, timeout=45)
        response = json.loads(result.stdout.decode("utf-8-sig"))
        assert response["ok"] is ok, response
        return response["data"]

    def passed(name, details=None):
        checks.append({"check": name, "passed": True, "details": details})
        print("PASS " + name, flush=True)

    try:
        start = call(["start", "--browser", "chrome", "--headful"])
        assert start["browser"]["headless"] is False, start
        passed("visible Chrome")
        call(["run", "go_to_url"], {"url": f"http://127.0.0.1:{server.server_port}/"})
        ambiguous = call(["run", "click_element_by_role"], {"role": "button", "name": "Save", "exact": True}, ok=False)
        assert ambiguous["matched"] == 2 and ambiguous["actionExecuted"] is False
        passed("ambiguous role rejected", ambiguous["matched"])
        saved = call(["run", "click_element_by_role"], {
            "role": "button", "name": "Save", "scope": "[role=dialog]", "exact": True,
            "expect": {"js": "document.querySelector('#status').textContent", "equals": "Saved", "timeoutMs": 3000},
            "readback": "({saves:window.saves,wrong:window.wrong})"})
        assert saved["businessSucceeded"] and saved["readback"]["result"] == {"saves": 1, "wrong": 0}, saved
        passed("scoped action + delayed expectation + single submission", saved["readback"])
        state = call(["state"])
        version = state["snapshotVersion"]
        call(["state"])
        stale = call(["run", "click_element_by_index"], {"index": 0, "snapshotVersion": version}, ok=False)
        assert stale["errorCode"] == "STALE_SNAPSHOT" and stale["actionExecuted"] is False
        passed("stale snapshot prevented dispatch")
        started = time.monotonic()
        call(["run", "wait_for_text"], {"text": "never-present-marker", "timeoutMs": 200}, ok=False)
        elapsed = time.monotonic() - started
        assert elapsed < 5, elapsed
        passed("millisecond wait bound", round(elapsed, 3))
        form = call(["run", "get_form_state"], {"selector": "#form"})
        region = next(field for field in form["fields"] if field["label"] == "Region")
        assert region["domValue"] == region["displayValue"] == "Shanghai", region
        assert region["selectedOptions"][0]["text"] == "Shanghai", region
        passed("external combobox popup readback")
        upload = records / "upload.txt"
        upload.write_text("synthetic upload fixture", encoding="utf-8")
        selected = call(["run", "upload_file"], {"trigger": "#upload", "path": str(upload), "responseUrl": "/upload", "timeoutMs": 3000,
            "expect": {"js": "document.querySelector('#uploaded').textContent", "contains": "Uploaded", "timeoutMs": 3000}})
        assert selected["fileSelected"] and selected["responseObserved"] and selected["uploadCompleted"], selected
        passed("detached file chooser and upload response", selected["response"])
        value = call(["run", "execute_js"], {"body": "'\u4e2d\u6587'"})
        assert value["result"] == "\u4e2d\u6587", value
        passed("UTF-8 BOM stdin")
        secret = "synthetic-private-value-27"
        call(["run", "input_text_by_selector"], {"selector": "#password", "text": secret})
        call(["run", "execute_js"], {"body": json.dumps(secret), "sensitive": True})
        for file in records.iterdir():
            if file.suffix in (".json", ".log"):
                assert secret not in file.read_text(encoding="utf-8"), file
        passed("CLI credential and sensitive-result redaction")
        running = subprocess.Popen(cli + ["--json", "run", "execute_js", "--params", "-"], stdin=subprocess.PIPE, stdout=subprocess.PIPE, stderr=subprocess.PIPE)
        running.stdin.write(json.dumps({"body": "new Promise(r=>setTimeout(()=>r('late'),6000))", "commandTimeoutMs": 10000}).encode())
        running.stdin.close()
        running.stdin = None
        time.sleep(0.6)
        diagnostic = call(["diagnose"])
        active = diagnostic["active"]
        assert active and active["workerRunning"], diagnostic
        cancellation = call(["cancel", active["commandId"]])
        assert cancellation["accepted"] and cancellation["cancelRequested"], cancellation
        stdout, stderr = running.communicate(timeout=15)
        assert json.loads(stdout)["ok"] is False, stdout
        time.sleep(6)
        diagnostic = call(["diagnose"])
        assert diagnostic.get("active") is None, diagnostic
        passed("diagnose and cancel active command", diagnostic["last"])
        call(["run", "get_title"])
        passed("task reusable after workers exit")
        shot = call(["run", "screenshot"], {})
        (records / "screenshot.json").write_text(json.dumps(shot, indent=2), encoding="utf-8")
        print("SCREENSHOT " + json.dumps(shot), flush=True)
    finally:
        call(["close"])
        server.shutdown()
        report = {"checks": checks, "records": str(records)}
        (records / "acceptance.json").write_text(json.dumps(report, indent=2), encoding="utf-8")
        print("REPORT " + str(records / "acceptance.json"), flush=True)


if __name__ == "__main__":
    main()
