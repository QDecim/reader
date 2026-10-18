"""Downloads all speaker styles from patriotyk's Space and voices one phrase with each.
  pip install huggingface_hub onnxruntime numpy pyyaml soundfile marisa-trie
  python voices_demo.py DIR "Текст." [threads] [model.onnx]     # DIR as in spike_styletts2.py
Writes DIR/voices_out/<voice>.wav and prints RTF per voice. Style .pt files are read without torch
(a .pt is a zip with raw float32 data); if a file is not exactly 256 floats it is skipped and reported."""
import sys, os, time, zipfile, numpy as np, yaml, onnxruntime as ort, soundfile as sf
from huggingface_hub import snapshot_download
from ukrainian_word_stress import Stressifier, StressSymbol, OnAmbiguity, Disambiguation
from ipa_uk import ipa

d, text = sys.argv[1], sys.argv[2]
threads = int(sys.argv[3]) if len(sys.argv) > 3 else 2
model_file = sys.argv[4] if len(sys.argv) > 4 else "model.onnx"
sp = snapshot_download("patriotyk/styletts2-ukrainian", repo_type="space", allow_patterns=["voices/*"], local_dir=os.path.join(d, "space"))
vdir = os.path.join(sp, "voices")

vocab = yaml.safe_load(open(f"{d}/config.yml", encoding="utf-8"))["model_params"]["vocab"]
idx = {c: i for i, c in enumerate(vocab)}
st = Stressifier(stress_symbol=StressSymbol.CombiningAcuteAccent, on_ambiguity=OnAmbiguity.First, disambiguation=Disambiguation.Dictionary)
ps = ipa(st(text)); tokens = np.array([idx[c] for c in ps if c in idx], dtype=np.int64)
so = ort.SessionOptions(); so.intra_op_num_threads = threads
s = ort.InferenceSession(f"{d}/{model_file}", so, providers=["CPUExecutionProvider"])

def load_style(path):
    with zipfile.ZipFile(path) as z:
        names = [n for n in z.namelist() if "/data/" in n]
        if len(names) != 1: raise ValueError(f"{len(names)} data entries")
        a = np.frombuffer(z.read(names[0]), dtype="<f4")
    if a.size != 256: raise ValueError(f"{a.size} floats")
    return a.reshape(1, 256)

out = os.path.join(d, "voices_out"); os.makedirs(out, exist_ok=True)
speed = np.array(1.0, dtype=np.float32)
for f in sorted(os.listdir(vdir)):
    if not f.endswith(".pt"): continue
    name = f[:-3]
    try: style = load_style(os.path.join(vdir, f))
    except Exception as e: print("SKIP", name, e); continue
    t0 = time.time(); wav = s.run(None, {"tokens": tokens, "speed": speed, "s_prev": style})[0].squeeze(); dt = time.time() - t0
    sf.write(os.path.join(out, name + ".wav"), wav, 24000)
    print(f"{name}: RTF {dt / (len(wav) / 24000):.2f}")
print("done ->", out)
