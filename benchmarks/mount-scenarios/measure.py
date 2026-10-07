#!/usr/bin/env python3
"""Run mount scenarios on an emulator with Perfetto; print frame stats as JSON."""
import json, subprocess, sys, time, pathlib, statistics
from perfetto.trace_processor import TraceProcessor

DEV = sys.argv[1]; LABEL = sys.argv[2]; RUNS = int(sys.argv[3]) if len(sys.argv) > 3 else 3
OUT = pathlib.Path(sys.argv[4] if len(sys.argv) > 4 else '.') / LABEL
OUT.mkdir(parents=True, exist_ok=True)
PKG = 'dev.pam.mountbench'
CFG = '''
buffers: { size_kb: 65536 fill_policy: RING_BUFFER }
data_sources: { config { name: "android.surfaceflinger.frametimeline" } }
data_sources: { config { name: "linux.process_stats" target_buffer: 0 process_stats_config { scan_all_processes_on_start: true } } }
data_sources: { config { name: "linux.ftrace" ftrace_config {
  ftrace_events: "sched/sched_switch"
  atrace_categories: "gfx" atrace_categories: "view" atrace_categories: "am" atrace_categories: "dalvik"
  atrace_apps: "%s" } } }
duration_ms: %d
'''

def adb(*a, timeout=60, check=True):
    return subprocess.run(['adb', '-s', DEV, *a], capture_output=True, timeout=timeout, check=check).stdout

def tap(x, y):
    adb('shell', 'input', 'tap', str(x), str(y))

def launch():
    adb('shell', 'am', 'force-stop', PKG)
    adb('shell', 'pm', 'clear', PKG)
    adb('shell', 'input', 'keyevent', 'KEYCODE_WAKEUP')
    adb('shell', 'wm', 'dismiss-keyguard', check=False)
    adb('shell', 'monkey', '-p', PKG, '-c', 'android.intent.category.LAUNCHER', '1')
    time.sleep(7)

def trace(name, duration_ms, actions):
    remote = f'/data/misc/perfetto-traces/{name}.pftrace'
    adb('shell', 'rm', '-f', remote, check=False)
    p = subprocess.Popen(['adb', '-s', DEV, 'shell', 'perfetto', '--txt', '-c', '-', '-o', remote],
                         stdin=subprocess.PIPE, stdout=subprocess.PIPE, stderr=subprocess.PIPE)
    p.stdin.write((CFG % (PKG, duration_ms)).encode()); p.stdin.close()
    time.sleep(1.5)
    marks = actions()
    p.wait(timeout=duration_ms / 1000 + 30)
    local = OUT / f'{name}.pftrace'
    adb('pull', remote, str(local), timeout=120)
    return local, marks

def frames(path):
    tp = TraceProcessor(trace=str(path))
    q = f"""select a.ts, a.dur from actual_frame_timeline_slice a join process p using(upid)
            where p.name like '{PKG}%' order by a.ts"""
    fr = [(r.ts, r.dur / 1e6) for r in tp.query(q)]
    mt = f"""select s.ts, s.dur from slice s join thread_track tt on s.track_id = tt.id join thread t using(utid)
            join process p using(upid) where p.name like '{PKG}%' and s.name GLOB 'NAME' order by s.ts"""
    mounts = [(r.ts, r.dur / 1e6) for r in tp.query(mt.replace('NAME','PamNative.mount'))]
    doframes = [(r.ts, r.dur / 1e6) for r in tp.query(mt.replace('NAME','Choreographer#doFrame [0-9]*'))]
    draws = [(r.ts, r.dur / 1e6) for r in tp.query(mt.replace('NAME','DrawFrames*'))]
    uploads = [(r.ts, r.dur / 1e6) for r in tp.query(mt.replace('NAME','*prepareToDraw*'))] + [(r.ts, r.dur / 1e6) for r in tp.query(mt.replace('NAME','*Upload*'))]
    decodes = [(r.ts, r.dur / 1e6) for r in tp.query(mt.replace('NAME','decodeBitmap'))]
    t0 = next(iter(tp.query('select start_ts from trace_bounds'))).start_ts
    tp.close()
    return fr, mounts, doframes, t0, draws, uploads, decodes

def window(items, start, end):
    return [d for (ts, d) in items if start <= ts < end]

def stats(durs):
    if not durs: return {'n': 0}
    s = sorted(durs)
    return {'n': len(s), 'max': round(s[-1], 1), 'p90': round(s[min(len(s) - 1, int(len(s) * 0.9))], 1),
            'slow': sum(1 for d in s if d > 16.7), 'sum_over': round(sum(max(0, d - 16.7) for d in s), 1)}

def collect(path, marks, span_ms):
    res = []
    for m in marks:
        # marks: host-monotonic offsets are unknown; use relative order: windows begin at
        # the first PAM mount after each tap (mounts are the reliable anchor).
        pass
    return fr, mounts, doframes, t0

def per_mount(path, span_ms=1200, min_mount_ms=0.0):
    fr, mounts, doframes, t0, draws, uploads, decodes = frames(path)
    out = []
    for ts, d in mounts:
        if d < min_mount_ms: continue
        end = ts + int(span_ms * 1e6)
        out.append({'mount': round(d, 1), 'frames': stats(window(fr, ts, end)), 'doFrame': stats(window(doframes, ts, end)), 'draw': stats(window(draws, ts, end)),
                    'upload_ms': round(sum(window(uploads, ts, end)), 1), 'decode_ms': round(sum(window(decodes, ts, end)), 1)})
    return out

def scenario_profile(i):
    launch()
    def act():
        tap(540, 262); time.sleep(4); return []
    p, _ = trace(f'profile-{i}', 7000, act)
    adb('shell', 'input', 'keyevent', 'KEYCODE_BACK')
    return per_mount(p, 1500)

def scenario_tabs(i):
    launch(); tap(540, 640); time.sleep(3)
    def act():
        for x in (540, 900, 180, 540, 900, 180):
            tap(x, 210); time.sleep(1.4)
        return []
    p, _ = trace(f'tabs-{i}', 11500, act)
    return per_mount(p, 700)

def scenario_reels(i):
    launch(); tap(540, 1017); time.sleep(4)
    def act():
        for _ in range(6):
            tap(600, 170); time.sleep(1.4)
        return []
    p, _ = trace(f'reels-{i}', 11500, act)
    return per_mount(p, 700)

def meminfo():
    launch()
    tap(540, 262); time.sleep(3); adb('shell', 'input', 'keyevent', 'KEYCODE_BACK'); time.sleep(2)
    tap(540, 640); time.sleep(2)
    for x in (900, 180, 540): tap(x, 210); time.sleep(1.2)
    adb('shell', 'input', 'keyevent', 'KEYCODE_BACK'); time.sleep(2)
    tap(540, 1017); time.sleep(3)
    for _ in range(5): tap(600, 170); time.sleep(1.5)
    adb('shell', 'input', 'keyevent', 'KEYCODE_BACK'); time.sleep(2)
    tap(540, 262); time.sleep(3)
    text = adb('shell', 'dumpsys', 'meminfo', PKG).decode()
    keep = {}
    for line in text.splitlines():
        t = line.strip()
        for k in ('Native Heap:', 'Graphics:', 'TOTAL PSS:', 'Java Heap:', 'EGL mtrack', 'GL mtrack'):
            if t.startswith(k):
                keep.setdefault(k, ' '.join(t.split()[:4]))
    return keep, text

def agg(runs):
    flat = [m for r in runs for m in r if m['frames']['n']]
    if not flat: return {}
    def col(f, key):
        v = [m[f][key] for m in flat if key in m[f]]
        return v
    return {
        'events': len(flat),
        'mount_ms_median': round(statistics.median([m['mount'] for m in flat]), 1),
        'mount_ms_max': round(max(m['mount'] for m in flat), 1),
        'frame_max_median': round(statistics.median(col('frames', 'max')), 1),
        'frame_max_p90': round(sorted(col('frames', 'max'))[int(len(flat) * 0.9) - 1 if len(flat) > 1 else 0], 1),
        'slow_frames_total': sum(col('frames', 'slow')),
        'doFrame_max_median': round(statistics.median(col('doFrame', 'max') or [0]), 1),
        'doFrame_slow_total': sum(col('doFrame', 'slow')),
        'draw_max_median': round(statistics.median(col('draw', 'max') or [0]), 1),
        'upload_ms_median': round(statistics.median([m['upload_ms'] for m in flat]), 1),
        'decode_ms_median': round(statistics.median([m['decode_ms'] for m in flat]), 1),
    }

result = {}
which = sys.argv[5].split(',') if len(sys.argv) > 5 else ['profile', 'tabs', 'reels', 'mem']
for name, fn in (('profile', scenario_profile), ('tabs', scenario_tabs), ('reels', scenario_reels)):
    if name not in which: continue
    runs = [fn(i) for i in range(RUNS)]
    (OUT / f'{name}.json').write_text(json.dumps(runs, indent=1))
    result[name] = agg(runs)
if 'mem' in which:
    mems = []
    for i in range(2):
        keep, text = meminfo(); (OUT / f'meminfo-{i}.txt').write_text(text); mems.append(keep)
    result['mem'] = mems
(OUT / 'summary.json').write_text(json.dumps(result, indent=1))
print(json.dumps(result, indent=1))
