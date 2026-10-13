# AI Photo Editing Models

To enable AI photo editing features in GalleryApp, place the following TensorFlow Lite `.tflite` model files in this directory (`app/src/main/assets/ai/`):

1. **`zero_dce_pp.tflite`** - AI Enhance (Low-light image enhancement)
2. **`nafnet_denoise.tflite`** - Denoise
3. **`nafnet_deblur.tflite`** - Unblur
4. **`realesrgan_x2.tflite`** - Upscale (2x Super-Resolution)
5. **`selfie_segmenter.tflite`** - Portrait Blur (Person segmentation)
6. **`u2netp.tflite`** - Remove Background (General salient object/subject segmentation; falls back to `selfie_segmenter.tflite` if not present)

## Notes
- Ensure all models are exported as float TFLite models with channels-last (NHWC) input/output tensors.
- If a model file is missing, the corresponding AI tool in the editor will gracefully report that the model is missing rather than crashing.
