#!/usr/bin/env bash
set -euo pipefail
cd "$(dirname "$0")"

PORT="${1:-9596}"

BACKEND=$(python3 -c "import json; print(json.load(open('config.json')).get('backend','python'))" 2>/dev/null || echo "python")

echo "VAD Annotator — backend=$BACKEND, port=$PORT"

if [ "$BACKEND" = "java" ]; then
    JAR="java-backend/target/vad-annotator-1.0-SNAPSHOT.jar"
    if [ ! -f "$JAR" ]; then
        echo "Building Java backend..."
        (cd java-backend && mvn package -q -DskipTests)
    fi
    MODEL=$(find ../silero-vad/src/silero_vad/data -name "silero_vad.onnx" 2>/dev/null | head -1)
    if [ -z "$MODEL" ]; then
        echo "ERROR: silero_vad.onnx not found. Run: git submodule update --init"
        exit 1
    fi
    UNAME=$(uname -s)
    if [ "$UNAME" = "Darwin" ]; then
        LIB_DIR="../vad4j/src/main/resources/darwin"
    else
        LIB_DIR="../vad4j/src/main/resources/linux-x86-64"
    fi
    exec java -Djna.library.path="$LIB_DIR" -jar "$JAR" "$PORT" "." "$MODEL"
else
    exec ./server.py --port="$PORT"
fi
