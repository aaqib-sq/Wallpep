#!/usr/bin/env python3
"""CPU smoke test only: synthetic input, no user photo or network at inference time."""
import hashlib
from pathlib import Path

import numpy as np
import onnxruntime as ort

MODEL = Path("app/src/main/assets/models/modnet_photographic_portrait_matting.onnx")
EXPECTED = "07c308cf0fc7e6e8b2065a12ed7fc07e1de8febb7dc7839d7b7f15dd66584df9"
assert hashlib.sha256(MODEL.read_bytes()).hexdigest() == EXPECTED, "Unexpected MODNet weights"
options = ort.SessionOptions()
options.intra_op_num_threads = 2
session = ort.InferenceSession(str(MODEL), sess_options=options, providers=["CPUExecutionProvider"])
inputs, outputs = session.get_inputs(), session.get_outputs()
assert len(inputs) == len(outputs) == 1
assert inputs[0].name == "input" and inputs[0].type == "tensor(float)"
assert outputs[0].name == "output" and outputs[0].type == "tensor(float)"
for height, width in ((512, 512), (512, 256)):
    # A deterministic synthetic gradient exercises the dynamic NCHW shape without real photos.
    row = np.linspace(-1.0, 1.0, width, dtype=np.float32)
    image = np.broadcast_to(row, (1, 3, height, width)).copy()
    matte = session.run(None, {"input": image})[0]
    assert matte.shape == (1, 1, height, width), matte.shape
    assert np.isfinite(matte).all(), "Non-finite matte"
    assert matte.min() >= -1e-5 and matte.max() <= 1.00001, "Matte outside [0, 1]"
    print(f"MODNet CPU: {width}x{height} -> {matte.shape}; range {matte.min():.4f}..{matte.max():.4f}")
print(f"Verified model SHA-256: {EXPECTED}")
