"""PC spike: is StyleTTS2-UA fast/good enough? Usage:
  pip install onnxruntime numpy pyyaml soundfile marisa-trie
  pip install --no-deps ukrainian-word-stress   # without stanza/torch
  pip install git+https://github.com/patriotyk/ipa-uk
  python spike_styletts2.py DIR "Текст." [threads] [model.onnx] [style.bin]   # DIR: model.onnx, config.yml, style_denys.bin
Prints real-time factor (RTF = compute time / audio time) and writes out.wav."""
import sys, time, re, numpy as np, yaml, onnxruntime as ort, soundfile as sf
from ukrainian_word_stress import Stressifier, StressSymbol, OnAmbiguity, Disambiguation
from ipa_uk import ipa

d, text = sys.argv[1], sys.argv[2]
vocab = yaml.safe_load(open(f"{d}/config.yml", encoding="utf-8"))["model_params"]["vocab"]
idx = {c: i for i, c in enumerate(vocab)}
st = Stressifier(stress_symbol=StressSymbol.CombiningAcuteAccent,
                 on_ambiguity=OnAmbiguity.First, disambiguation=Disambiguation.Dictionary)
stressed = st(text)
ps = ipa(stressed)
tokens = np.array([idx[c] for c in ps if c in idx], dtype=np.int64)
print("stressed:", stressed, "\nipa:", ps, "\ntokens:", len(tokens), "unknown:", {c for c in ps if c not in idx})
model_file = sys.argv[4] if len(sys.argv) > 4 else "model.onnx"
style_file = sys.argv[5] if len(sys.argv) > 5 else "style_denys.bin"
raw_style = np.fromfile(f"{d}/{style_file}", dtype="<f4")
so = ort.SessionOptions(); so.intra_op_num_threads = int(sys.argv[3]) if len(sys.argv) > 3 else 2
t0 = time.time(); s = ort.InferenceSession(f"{d}/{model_file}", so, providers=["CPUExecutionProvider"]); print("load s:", round(time.time() - t0, 1))
meta = {i.name: i for i in s.get_inputs()}
for i in meta.values(): print("input:", i.name, i.shape, i.type)
print("style floats in bin:", raw_style.size)
def fit(x, name):  # adapt array to the model's declared rank (scalar / [N] / [1,N])
    r = len(meta[name].shape)
    x = np.asarray(x)
    while x.ndim > r and x.shape[0] == 1: x = x[0]
    while x.ndim < r: x = x[None]
    return x
feed = {"tokens": fit(tokens, "tokens"),
        "speed": fit(np.array(1.0, dtype=np.float32), "speed"),
        "s_prev": fit(raw_style.reshape(1, -1), "s_prev")}
for k in range(3):
    t0 = time.time()
    wav = s.run(None, feed)[0].squeeze()
    dt = time.time() - t0; dur = len(wav) / 24000
    print(f"run {k}: {dt:.2f}s compute, {dur:.2f}s audio, RTF {dt/dur:.2f}")
sf.write(model_file.replace(".onnx", "") + "_" + style_file.replace(".bin", "") + ".wav", wav, 24000)
