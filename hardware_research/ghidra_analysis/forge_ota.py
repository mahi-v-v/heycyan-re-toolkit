"""
forge_ota.py — mitmproxy addon that AUTO-forges the HeyCyan Wi-Fi OTA check.

Why this exists: Reqable's manual breakpoint has to be driven by hand on every
request, so the app's *re-polls* of last-ota during the flash slip through with
the real "no update" answer and the app bails to "finished". This addon rewrites
EVERY matching response automatically, so re-polls stay forged.

It only forges the WIFIAM01CY (Wi-Fi / V821 SoC) query — the one that flashes the
.swu. The AM01CY (BLE / nRF MCU) query is passed through untouched so the app does
NOT try to BLE-update the nRF.

Run (keep the phone's Wi-Fi proxy pointed at <PC-IP>:9000, install the mitmproxy CA):
    mitmdump -p 9000 -s forge_ota.py

Serve the firmware separately (plain HTTP) on :8080, e.g.:
    python serve_swu.py        # patched image
    python serve_stock.py      # stock image (A/B diagnostic)
"""
import json
from mitmproxy import http, ctx

OTA_HOST = "www.qlifesnap.com"
OTA_PATH = "/glasses/app-update/last-ota"

# Our plain-HTTP firmware server (separate process on :8080).
DOWNLOAD_URL = "http://192.168.29.3:8080/firmware_patched.swu"

# The forged FirmwareOtaResp.data — openOrNot=2 => release build auto-downloads.
FORGED_DATA = {
    "hardwareVersion": "WIFIAM01CY_V2.0",
    "version": "WIFIAM01CY_1.00.28_2601010000",
    "isEnforceUpdate": "0",
    "enforceUpdateFrom": "",
    "enforceUpdateTo": "",
    "downloadUrl": DOWNLOAD_URL,
    "openOrNot": 2,
    "uploadDate": "2026-07-10 10:00:00",
    "os": 1,
    "updateDesc": "custom",
}


def running():
    ctx.log.alert(f"[forge_ota] armed. Forging {OTA_HOST}{OTA_PATH} (WIFIAM01CY only)")
    ctx.log.alert(f"[forge_ota] downloadUrl -> {DOWNLOAD_URL}")


def response(flow: http.HTTPFlow) -> None:
    r = flow.request
    if r.host != OTA_HOST or OTA_PATH not in r.path:
        return

    body = r.get_text(strict=False) or ""
    # "WIFIAM01CY" contains "AM01CY", so test for the more specific WIFI prefix.
    is_wifi = "WIFIAM01CY" in body

    if not is_wifi:
        ctx.log.info(f"[forge_ota] pass-through last-ota (AM01CY/BLE or unknown) "
                     f"status={flow.response.status_code}")
        return

    forged = {"retCode": 0, "message": "success", "data": FORGED_DATA}
    flow.response = http.Response.make(
        200,
        json.dumps(forged).encode("utf-8"),
        {"Content-Type": "application/json;charset=UTF-8"},
    )
    ctx.log.alert(f"[forge_ota] FORGED WIFIAM01CY last-ota -> {DOWNLOAD_URL}")
