"""Exact M05 frontend names, sharing the strict complete-suite Vitest verifier."""
import sys
from pathlib import Path
sys.path.insert(0, str(Path(__file__).resolve().parents[1]))
from tools.verify_r10_release_web_report import verify as verify_json

REQUIRED = {
    'src/assetsApi.test.ts': {
        "asset HTTP validates upload state fields ownership and rejects invalid JSON",
        "asset POST and raw PUT keep original key content upload identity and CSRF",
        "asset pages use explicit cursors and reject cross target frozen attachment",
        "asset download refuses unsafe headers and preserves real permission denial",
        "asset file preflight rejects unsupported empty and oversized files before HTTP",
    },
    'src/AttachmentsPanel.test.tsx': {
        "asset published session selects its exact revision even when another version is already current",
        "asset MEMBER creates uploads scans freezes attachment and VIEWER has no write controls",
        "asset project switch cancels upload and ignores late success without restoring filename",
        "asset project switch cancels upload and ignores late failure without restoring filename",
        "asset project switch cancels upload and ignores late abort without restoring filename",
        "asset pending create locks real file input and unknown create retries original intent key",
        "asset raw PUT unknown recovers same upload by GET without new session or repeated write",
        "asset complete timeout preserves key and GET-only recovery keeps confirmed publication after read failure",
        "asset direct permission loss null aborts pending read write and permits fresh recovery",
        "asset direct permission loss no-read aborts pending read write and permits fresh recovery",
        "asset real scope denial clears metadata while CSRF and network rejection preserve scope",
        "asset old frozen link stays revision one after asset revision two with bounded paging",
        "asset scan failed retry and infected rejection never expose download or attach",
        "asset StrictMode unmount cancels timers and stale list never restores metadata",
        "asset upload timeout aborts transport and preserves exact retry without stale finally",
        "asset attachment failure retains original key and complete content through explicit retry",
        "asset direct loss invalidates delayed metadata success and recovery uses new request",
        "asset direct loss invalidates delayed metadata failure and recovery uses new request",
        "asset direct loss invalidates delayed metadata abort and recovery uses new request",
        "asset unknown create recovers owner session by GET without making a second upload",
        "asset CSRF rejection preserves filename and permits a safe explicit retry without scope logout",
        "asset complete real timer timeout aborts and same completion key survives retry",
        "asset download creates controlled blob URL and revokes it after download or unmount",
        "asset MEMBER to VIEWER cancels original write but retains fresh authorized frozen reads",
    },
}


def verify(path):
    return verify_json(path, REQUIRED, 'M05 component/API')


if __name__ == '__main__':
    if len(sys.argv) != 2:
        raise SystemExit('usage: verify_r11_asset_web_report.py VITEST_JSON_REPORT')
    ok, summary = verify(sys.argv[1])
    print(summary, file=sys.stdout if ok else sys.stderr)
    raise SystemExit(0 if ok else 1)
