#!/usr/bin/env bash
# Export YOLOv8n to INT8 TFLite for Android NNAPI acceleration
#
# Requirements:
#   pip install ultralytics
#
# Produces: yolov8n_int8.tflite (~4MB, ~2-3× faster on Snapdragon 865)
# Copy to: app/src/main/assets/yolov8n_int8.tflite
# The app auto-detects and prefers int8 model if present.

set -e

echo "=== YOLOv8n INT8 Export ==="
echo "Downloading model + exporting (takes 1-2 min)..."

# Export with full integer quantization
yolo export model=yolov8n.pt format=tflite int8=True imgsz=640

# Find the output file
OUTDIR="yolov8n_saved_model"
INT8_FILE=$(find . -name "*int8*" -o -name "*integer*" | head -1)

if [ -z "$INT8_FILE" ]; then
    # ultralytics puts it in yolov8n_saved_model/
    INT8_FILE=$(find "$OUTDIR" -name "*.tflite" 2>/dev/null | head -1)
fi

if [ -z "$INT8_FILE" ]; then
    echo "ERROR: INT8 model not found. Check ultralytics output above."
    exit 1
fi

# Copy to expected name
cp "$INT8_FILE" yolov8n_int8.tflite

ORIG_SIZE=$(stat -f%z yolov8n.tflite 2>/dev/null || stat -c%s yolov8n.tflite 2>/dev/null || echo "?")
INT8_SIZE=$(stat -f%z yolov8n_int8.tflite 2>/dev/null || stat -c%s yolov8n_int8.tflite 2>/dev/null || echo "?")

echo ""
echo "Done!"
echo "  yolov8n.tflite:      ${ORIG_SIZE} bytes (float32)"
echo "  yolov8n_int8.tflite: ${INT8_SIZE} bytes (int8)"
echo ""
echo "Next: cp yolov8n_int8.tflite app/src/main/assets/"
echo "The app will auto-detect and use it on next launch."
