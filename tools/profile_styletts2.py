"""Where does the time go? Thread scaling + per-op profile of fp32 model.
  python profile_styletts2.py DIR "Текст."
Prints RTF for 1/2/4/8 threads, then the top op types by total time (4 threads)."""
import sys, json, time, collections, numpy as np, yaml, onnxruntime as ort
from ukrainian_word_stress import Stressifier, StressSymbol, OnAmbiguity, Disambiguation
from ipa_uk import ipa
d, text = sys.argv[1], sys.argv[2]
vocab = yaml.safe_load(open(f"{d}/config.yml", encoding="utf-8"))["model_params"]["vocab"]
idx = {c: i for i, c in enumerate(vocab)}
st = Stressifier(stress_symbol=StressSymbol.CombiningAcuteAccent, on_ambiguity=OnAmbiguity.First, disambiguation=Disambiguation.Dictionary)
tokens = np.array([idx[c] for c in ipa(st(text)) if c in idx], dtype=np.int64)
feed = {"tokens": tokens, "speed": np.array(1.0, np.float32), "s_prev": np.fromfile(f"{d}/style_denys.bin", "<f4").reshape(1, 256)}
def sess(n, prof=False):
    so = ort.SessionOptions(); so.intra_op_num_threads = n; so.enable_profiling = prof
    return ort.InferenceSession(f"{d}/model.onnx", so, providers=["CPUExecutionProvider"])
for n in (1, 2, 4, 8):
    s = sess(n); s.run(None, feed)
    t0 = time.time(); w = s.run(None, feed)[0]; dt = time.time() - t0
    print(f"threads {n}: {dt:.2f}s, RTF {dt / (w.size / 24000):.2f}")
s = sess(4, True); s.run(None, feed); s.run(None, feed)
tot = collections.Counter()
for e in json.load(open(s.end_profiling())):
    if e.get("cat") == "Node" and e["name"].endswith("_kernel_time"): tot[e["args"]["op_name"]] += e["dur"]
all_ = sum(tot.values())
for op, us in tot.most_common(8): print(f"{op:20s} {us / all_ * 100:5.1f}%")
