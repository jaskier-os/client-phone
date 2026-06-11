# Photo denoise model

Shipped asset: `photo_denoise.onnx` (~3.3 MB) — **FFDNet-color** from the
HuggingFace `Swawon/denoiser` repo, exported by upstream with dynamic H/W.

- Apache-2.0 licensed.
- PyTorch 2.10 export, ONNX opset 17, pure-conv graph, ~0.5M parameters.
- Verified runnable end-to-end on ONNX Runtime CPU (CI) and via the
  `onnxruntime-android-qnn` build on the Hexagon aDSP v73 at capture time.

## Model contract

```
input:  float32 [1, 3, H, W]   RGB, range [0, 1]
sigma:  float32 [1, 1, 1, 1]   noise std dev / 255, e.g. 15/255 -> 0.0588
output: float32 [1, 3, H, W]   denoised RGB
```

`PhotoEnhancer.kt` tiles each capture at 512x512 with 32 px overlap and
linearly blends the seams. `NOISE_SIGMA = 15/255` is a sensible default for
Rokid AR Lite frames after the HAL's HQ denoise pass; bump toward 25/255
for noisier low-light shots.

## If you want to replace the model

Must match the I/O contract above. If you swap to NAFNet / SCUNet / other
single-input denoisers, remove the `sigma` tensor binding in
`PhotoEnhancer.kt` (the feed map) and update `INPUT_NAME` if needed.
