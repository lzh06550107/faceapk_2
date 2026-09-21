from pathlib import Path
import re

ROOT = Path(__file__).resolve().parents[2]
APP = ROOT / 'app/src/main/java/com/punch/app/PunchApplication.java'


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


def test_runtime_usable_preparation_converges_to_ready_status():
    src = read(APP)
    body = method_body(src, 'public void preparePunchRecognitionData()')
    assert 'isRuntimeFaceLibraryUsable()' in body, body
    ready_index = body.find('markPunchRecognitionReady("准备完成，可以开始打卡")')
    runtime_index = body.find('isRuntimeFaceLibraryUsable()')
    assert ready_index > runtime_index, body
    assert 'reason=runtime_face_library_already_usable' in body, body


def test_preparation_request_logs_runtime_gate_inputs():
    src = read(APP)
    body = method_body(src, 'public void preparePunchRecognitionData()')
    required = [
        'Punch preparation requested:',
        'tokenValid=',
        'preparing=',
        'sdkInitialized=',
        'loadedFaceCount=',
        'runtimeUsable=',
        'reason=token_missing_or_expired',
        'reason=already_preparing',
    ]
    for needle in required:
        assert needle in body, f'missing {needle}\n{body}'


def test_run_preparation_logs_stage_boundaries_without_loop_spam():
    src = read(APP)
    body = method_body(src, 'private void runPunchPreparation()')
    required = [
        'Punch preparation begin',
        'Punch preparation face SDK ready',
        'Punch preparation data path:',
        'activeEmployeeCount=',
        'mode=sync_employees',
        'mode=rebuild_local_face_library',
        'Punch preparation failed:',
    ]
    for needle in required:
        assert needle in body, f'missing {needle}\n{body}'
    wait_body = method_body(src, 'private boolean waitForFaceSdkReady()')
    assert 'Thread.sleep(300L)' in wait_body
    assert 'Punch preparation waiting for Face SDK' not in wait_body, 'do not log every poll iteration'


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
