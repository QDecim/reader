"""Makes lighter model variants next to model.onnx.
  pip install onnx onnxruntime onnxconverter-common
  python quantize_styletts2.py DIR
Step 0: ORT basic offline optimisation (folds weight-norm Mul into constants; fixes the
        'Expected ... to be an initializer' error) -> model_opt.onnx
Step 1: int8 dynamic quantisation -> model_int8.onnx (full; if that fails, only MatMul/Gemm/LSTM)
Step 2: fp16 weights, fp32 I/O -> model_fp16.onnx
Then: python spike_styletts2.py DIR "Текст." 2 model_int8.onnx   (compare RTF AND listen to the wav)."""
import sys, os, traceback
import onnxruntime as ort
d = sys.argv[1]; src = os.path.join(d, "model.onnx"); opt = os.path.join(d, "model_opt.onnx")

try:
    so = ort.SessionOptions(); so.graph_optimization_level = ort.GraphOptimizationLevel.ORT_ENABLE_BASIC
    so.optimized_model_filepath = opt
    ort.InferenceSession(src, so, providers=["CPUExecutionProvider"]); print("opt ok")
except Exception:
    traceback.print_exc(); print("opt FAILED"); opt = src

from onnxruntime.quantization import quantize_dynamic, QuantType
out8 = os.path.join(d, "model_int8.onnx")
for ops in (None, ["MatMul", "Gemm", "LSTM"]):
    try:
        kw = {} if ops is None else {"op_types_to_quantize": ops}
        quantize_dynamic(opt, out8, weight_type=QuantType.QInt8, **kw)
        print("int8 ok", "(all ops)" if ops is None else "(MatMul/Gemm/LSTM only)"); break
    except Exception:
        traceback.print_exc(); print("int8 FAILED for", ops or "all ops")

try:
    import onnx
    from onnxconverter_common.float16 import convert_float_to_float16
    onnx.save(convert_float_to_float16(onnx.load(src), keep_io_types=True), os.path.join(d, "model_fp16.onnx"))
    print("fp16 ok")
except Exception:
    traceback.print_exc(); print("fp16 FAILED")
