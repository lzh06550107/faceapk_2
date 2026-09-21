from pathlib import Path
import re

ROOT = Path(__file__).resolve().parents[2]
APP = ROOT / 'app/src/main/java/com/punch/app/PunchApplication.java'
FRAGMENT = ROOT / 'app/src/main/java/com/punch/app/fragment/PunchFragment.java'


def read(path):
    return path.read_text(encoding='utf-8')


def method_body(source, signature):
    start = source.index(signature)
    brace = source.index('{', start)
    depth = 0
    for i in range(brace, len(source)):
        if source[i] == '{':
            depth += 1
        elif source[i] == '}':
            depth -= 1
            if depth == 0:
                return source[brace + 1:i]
    raise AssertionError(f'unclosed method: {signature}')


def test_report_status_event_updates_current_and_history():
    src = read(APP)
    body = method_body(src, 'public void reportStatusEvent(String status, int level)')
    assert re.search(r'pushStatus\s*\(\s*status\s*,\s*level\s*,\s*true\s*,', body), body


def test_status_snapshot_has_monotonic_sequence():
    src = read(APP)
    assert 'private long punchStatusSequence' in src
    assert 'public final long sequence;' in src
    push = method_body(src, 'private void pushStatus(String status, int level, boolean keepAsCurrent, boolean requestAttention)')
    assert re.search(r'punchStatusSequence\s*\+=\s*1', push) or re.search(r'\+\+punchStatusSequence', push)


def test_duplicate_latest_history_is_refreshed_not_left_stale():
    src = read(APP)
    body = method_body(src, 'private void addHistoryLocked(PunchStatusEntry entry)')
    assert 'recentStatusEntries.removeFirst()' in body
    assert 'recentStatusEntries.addFirst(entry)' in body


def test_fragment_rejects_out_of_order_status_snapshots():
    src = read(FRAGMENT)
    assert 'lastRenderedPunchStatusSequence' in src
    body = method_body(src, 'private void renderPunchStatusSnapshot(PunchApplication.PunchStatusSnapshot snapshot)')
    assert re.search(r'snapshot\.sequence\s*<\s*lastRenderedPunchStatusSequence', body), body
    assert re.search(r'lastRenderedPunchStatusSequence\s*=\s*snapshot\.sequence', body), body


def test_face_library_completion_flows_into_current_status():
    app_src = read(APP)
    sync_src = read(ROOT / 'app/src/main/java/com/punch/app/service/SyncCoordinator.java')
    finish = method_body(app_src, 'public void finishFaceLibraryUpdate(String status)')
    report = method_body(app_src, 'public void reportStatusEvent(String status, int level)')
    assert 'reportStatusEvent(status, STATUS_LEVEL_SUCCESS)' in finish
    assert 'finishFaceLibraryUpdate("后台人脸库更新完成")' in sync_src
    assert re.search(r'pushStatus\s*\(\s*status\s*,\s*level\s*,\s*true\s*,', report), report


if __name__ == '__main__':
    tests = [name for name, value in globals().items() if name.startswith('test_') and callable(value)]
    failed = 0
    for name in tests:
        try:
            globals()[name]()
            print(f'[PASS] {name}')
        except Exception as exc:
            failed += 1
            print(f'[FAIL] {name}: {exc}')
    print(f'RESULT: {len(tests) - failed}/{len(tests)} passed')
    raise SystemExit(1 if failed else 0)
