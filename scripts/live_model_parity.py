#!/usr/bin/env python3
from __future__ import annotations

import argparse
import hashlib
import json
from pathlib import Path

import numpy as np

import sys
sys.path.insert(0, str(Path(__file__).parent))
from pipeline_core import add_velocity, detection_rate, load_checkpoint, motion_energy, resample, torso_horizontality


ROOT = Path(__file__).resolve().parents[1]
MANIFEST = ROOT / "CareGuardCamera/app/src/main/assets/model-manifest.json"


def sha256(path: Path) -> str:
    h = hashlib.sha256()
    with path.open("rb") as f:
        for chunk in iter(lambda: f.read(1024 * 1024), b""):
            h.update(chunk)
    return h.hexdigest()


def load_manifest() -> dict:
    return json.loads(MANIFEST.read_text(encoding="utf-8"))


def verify_manifest() -> dict:
    manifest = load_manifest()
    for section in ("yolo", "gru"):
        spec = manifest[section]
        path = ROOT / "CareGuardCamera/app/src/main/assets" / spec["asset"]
        actual = sha256(path)
        if actual != spec["sha256"]:
            raise AssertionError(f"{section} hash mismatch: {actual} != {spec['sha256']}")
    if manifest["classOrder"] != ["normal", "fall", "fallen"]:
        raise AssertionError(f"class order mismatch: {manifest['classOrder']}")
    if manifest["yolo"]["orientationContract"] != "UPRIGHT_UNMIRRORED_BITMAP":
        raise AssertionError(f"orientation contract mismatch: {manifest['yolo']['orientationContract']}")
    if manifest["yolo"]["inputColorOrder"] != "RGB":
        raise AssertionError(f"color order mismatch: {manifest['yolo']['inputColorOrder']}")
    if manifest["yolo"]["rotationHandling"] != "CALLER_PROVIDES_UPRIGHT":
        raise AssertionError(f"rotation handling mismatch: {manifest['yolo']['rotationHandling']}")
    if manifest["yolo"]["mirrorHandling"] != "CALLER_PROVIDES_UNMIRRORED":
        raise AssertionError(f"mirror handling mismatch: {manifest['yolo']['mirrorHandling']}")
    if manifest["yolo"]["inputDtype"] != "float32" or manifest["yolo"]["outputDtype"] != "float32":
        raise AssertionError("YOLO dtype mismatch")
    if manifest["gru"]["inputDtype"] != "float32" or manifest["gru"]["outputDtype"] != "float32":
        raise AssertionError("GRU dtype mismatch")
    if manifest["gru"]["feature"] != "posvel":
        raise AssertionError(f"GRU feature mismatch: {manifest['gru']['feature']}")
    if manifest["stream"]["timestampMode"] != "CAPTURE_TIMESTAMP_ACTUAL_DT":
        raise AssertionError(f"timestamp mode mismatch: {manifest['stream']['timestampMode']}")
    if manifest["stream"]["resampleMode"] != "SOURCE_TIME_LINEAR":
        raise AssertionError(f"resample mode mismatch: {manifest['stream']['resampleMode']}")
    return manifest


def probe_cv2_shapes(manifest: dict) -> dict:
    import cv2

    result = {}
    for section in ("yolo", "gru"):
        spec = manifest[section]
        path = ROOT / "CareGuardCamera/app/src/main/assets" / spec["asset"]
        net = cv2.dnn.readNetFromONNX(str(path))
        blob = np.zeros(spec["inputShape"], dtype=np.float32)
        net.setInput(blob)
        out = net.forward()
        got = list(out.shape)
        expected = spec["outputShape"]
        if got != expected:
            raise AssertionError(f"{section} output shape mismatch: {got} != {expected}")
        result[section] = {
            "input": spec["inputShape"],
            "output": got,
            "outputs": list(net.getUnconnectedOutLayersNames()),
        }
    return result


def inspect_onnxruntime_metadata(manifest: dict) -> dict:
    try:
        import onnxruntime as ort
    except ModuleNotFoundError as exc:
        raise RuntimeError("onnxruntime is not installed in this Python environment") from exc

    report = {}
    for section in ("yolo", "gru"):
        spec = manifest[section]
        path = ROOT / "CareGuardCamera/app/src/main/assets" / spec["asset"]
        session = ort.InferenceSession(str(path), providers=["CPUExecutionProvider"])
        inputs = session.get_inputs()
        outputs = session.get_outputs()
        if len(inputs) != 1 or len(outputs) != 1:
            raise AssertionError(f"{section} expected one input and one output, got {len(inputs)} inputs and {len(outputs)} outputs")
        input_meta = inputs[0]
        output_meta = outputs[0]
        if input_meta.name != spec["inputName"]:
            raise AssertionError(f"{section} input name mismatch: {input_meta.name} != {spec['inputName']}")
        if output_meta.name != spec["outputName"]:
            raise AssertionError(f"{section} output name mismatch: {output_meta.name} != {spec['outputName']}")
        report[section] = {
            "inputName": input_meta.name,
            "inputShape": input_meta.shape,
            "inputType": input_meta.type,
            "outputName": output_meta.name,
            "outputShape": output_meta.shape,
            "outputType": output_meta.type,
        }
    return report


def inspect_onnx_graph_metadata(manifest: dict) -> dict:
    try:
        import onnx
    except ModuleNotFoundError as exc:
        raise RuntimeError("onnx is not installed in this Python environment") from exc

    report = {}
    for section in ("yolo", "gru"):
        spec = manifest[section]
        path = ROOT / "CareGuardCamera/app/src/main/assets" / spec["asset"]
        model = onnx.load(str(path))
        report[section] = {
            "irVersion": model.ir_version,
            "producer": model.producer_name,
            "producerVersion": model.producer_version,
            "opsets": [
                {"domain": opset.domain or "ai.onnx", "version": opset.version}
                for opset in model.opset_import
            ],
            "inputs": [value_info(input_value) for input_value in model.graph.input],
            "outputs": [value_info(output_value) for output_value in model.graph.output],
            "metadata": {item.key: item.value for item in model.metadata_props},
        }
    return report


def value_info(node) -> dict:
    tensor = node.type.tensor_type
    return {
        "name": node.name,
        "elemType": tensor.elem_type,
        "shape": [dim.dim_param or dim.dim_value for dim in tensor.shape.dim],
    }


def ort_golden_vectors(manifest: dict) -> dict:
    try:
        import onnxruntime as ort
    except ModuleNotFoundError as exc:
        raise RuntimeError("onnxruntime is not installed in this Python environment") from exc

    yolo = manifest["yolo"]
    yolo_session = ort.InferenceSession(
        str(ROOT / "CareGuardCamera/app/src/main/assets" / yolo["asset"]),
        providers=["CPUExecutionProvider"],
    )
    yolo_input = np.zeros(yolo["inputShape"], dtype=np.float32)
    yolo_output = yolo_session.run([yolo["outputName"]], {yolo["inputName"]: yolo_input})[0]
    if list(yolo_output.shape) != yolo["outputShape"]:
        raise AssertionError(f"YOLO ORT output mismatch: {list(yolo_output.shape)} != {yolo['outputShape']}")

    return {
        "yoloZero": {
            "shape": list(yolo_output.shape),
            "min": float(yolo_output.min()),
            "max": float(yolo_output.max()),
            "mean": float(yolo_output.mean()),
            "sha256": hashlib.sha256(yolo_output.astype(np.float32).tobytes()).hexdigest(),
        },
        "gruCheckpointParity": compare_gru_onnx_to_checkpoint(manifest),
    }


def thirty_fixture_parity(manifest: dict, count: int = 30) -> dict:
    try:
        import onnxruntime as ort
    except ModuleNotFoundError as exc:
        raise RuntimeError("onnxruntime is not installed in this Python environment") from exc
    import torch

    gru = manifest["gru"]
    yolo = manifest["yolo"]
    model, classes, seq_len, kind, feat = load_checkpoint(ROOT / "models/gru.pt", "cpu")
    if {"classes": classes, "seq_len": seq_len, "kind": kind, "feat": feat} != {
        "classes": manifest["classOrder"],
        "seq_len": manifest["stream"]["sequenceLength"],
        "kind": "gru",
        "feat": "posvel",
    }:
        raise AssertionError("GRU checkpoint metadata does not match manifest")

    gru_session = ort.InferenceSession(
        str(ROOT / "CareGuardCamera/app/src/main/assets" / gru["asset"]),
        providers=["CPUExecutionProvider"],
    )
    yolo_session = ort.InferenceSession(
        str(ROOT / "CareGuardCamera/app/src/main/assets" / yolo["asset"]),
        providers=["CPUExecutionProvider"],
    )

    max_gru_logit_abs = 0.0
    max_gru_prob_abs = 0.0
    yolo_hashes = []
    with torch.no_grad():
        for i in range(count):
            fixture = synthetic_gru_input(i)
            torch_logits = model(torch.tensor(fixture)).cpu().numpy()
            ort_logits = gru_session.run([gru["outputName"]], {gru["inputName"]: fixture.reshape(1, 64, 85)})[0]
            logit_diff = float(np.max(np.abs(torch_logits - ort_logits)))
            torch_prob = softmax_np(torch_logits)
            ort_prob = softmax_np(ort_logits)
            prob_diff = float(np.max(np.abs(torch_prob - ort_prob)))
            max_gru_logit_abs = max(max_gru_logit_abs, logit_diff)
            max_gru_prob_abs = max(max_gru_prob_abs, prob_diff)

            yolo_input = synthetic_yolo_input(i, yolo["inputShape"])
            yolo_output = yolo_session.run([yolo["outputName"]], {yolo["inputName"]: yolo_input})[0]
            if list(yolo_output.shape) != yolo["outputShape"]:
                raise AssertionError(f"YOLO fixture {i} shape mismatch: {list(yolo_output.shape)}")
            if not np.isfinite(yolo_output).all():
                raise AssertionError(f"YOLO fixture {i} produced non-finite output")
            yolo_hashes.append(hashlib.sha256(yolo_output.astype(np.float32).tobytes()).hexdigest())

    if max_gru_logit_abs > 1e-4:
        raise AssertionError(f"30-fixture GRU logit parity failed: {max_gru_logit_abs}")
    if max_gru_prob_abs > 1e-3:
        raise AssertionError(f"30-fixture GRU probability parity failed: {max_gru_prob_abs}")
    return {
        "count": count,
        "gru": {
            "maxLogitAbs": max_gru_logit_abs,
            "maxProbabilityAbs": max_gru_prob_abs,
        },
        "yolo": {
            "allFinite": True,
            "shape": yolo["outputShape"],
            "firstHash": yolo_hashes[0],
            "lastHash": yolo_hashes[-1],
            "uniqueHashes": len(set(yolo_hashes)),
        },
    }


def synthetic_yolo_input(index: int, shape: list[int]) -> np.ndarray:
    _, channels, height, width = shape
    yy = np.linspace(0.0, 1.0, height, dtype=np.float32)[None, :, None]
    xx = np.linspace(0.0, 1.0, width, dtype=np.float32)[None, None, :]
    base = ((index + 1) / 31.0) * 0.5
    data = np.zeros(shape, dtype=np.float32)
    for c in range(channels):
        data[0, c] = np.mod(base + (c + 1) * xx + (index % 7 + 1) * yy / 10.0, 1.0)
    return data


def synthetic_gru_input(index: int) -> np.ndarray:
    data = np.zeros((1, 64, 17, 5), dtype=np.float32)
    for t in range(64):
        for k in range(17):
            for c in range(5):
                angle = (index + 1) * (t + 1) * 0.017 + (k + 1) * 0.13 + c * 0.37
                data[0, t, k, c] = np.sin(angle, dtype=np.float32) * np.float32(0.25)
            if index % 5 == 0:
                data[0, t, k, 2] = 0.0
            else:
                data[0, t, k, 2] = np.clip(abs(data[0, t, k, 2]), 0.05, 1.0)
    return data


def softmax_np(logits: np.ndarray) -> np.ndarray:
    shifted = logits - logits.max(axis=1, keepdims=True)
    exp = np.exp(shifted)
    return exp / exp.sum(axis=1, keepdims=True)


def compare_gru_onnx_to_checkpoint(manifest: dict) -> dict:
    try:
        import onnxruntime as ort
    except ModuleNotFoundError as exc:
        raise RuntimeError("onnxruntime is not installed in this Python environment") from exc
    import torch

    gru = manifest["gru"]
    if gru["inputShape"] != [1, 64, 85]:
        raise AssertionError(f"unexpected GRU input shape {gru['inputShape']}")
    model, classes, seq_len, kind, feat = load_checkpoint(ROOT / "models/gru.pt", "cpu")
    expected = {
        "classes": manifest["classOrder"],
        "seq_len": manifest["stream"]["sequenceLength"],
        "kind": "gru",
        "feat": "posvel",
    }
    actual = {"classes": classes, "seq_len": seq_len, "kind": kind, "feat": feat}
    if actual != expected:
        raise AssertionError(f"checkpoint metadata mismatch: {actual} != {expected}")

    fixtures = [
        np.zeros((1, 64, 17, 5), np.float32),
        np.linspace(-0.5, 0.5, 1 * 64 * 17 * 5, dtype=np.float32).reshape(1, 64, 17, 5),
    ]
    session = ort.InferenceSession(
        str(ROOT / "CareGuardCamera/app/src/main/assets" / gru["asset"]),
        providers=["CPUExecutionProvider"],
    )
    max_abs = 0.0
    samples = []
    with torch.no_grad():
        for fixture in fixtures:
            torch_logits = model(torch.tensor(fixture)).cpu().numpy()
            ort_logits = session.run([gru["outputName"]], {gru["inputName"]: fixture.reshape(1, 64, 85)})[0]
            diff = float(np.max(np.abs(torch_logits - ort_logits)))
            max_abs = max(max_abs, diff)
            samples.append({"logitMaxAbs": diff, "torch": torch_logits[0].round(6).tolist(), "onnx": ort_logits[0].round(6).tolist()})
    if max_abs > 1e-4:
        raise AssertionError(f"GRU ONNX/checkpoint mismatch: max_abs={max_abs}")
    return {"checkpoint": actual, "logitMaxAbs": max_abs, "samples": samples}


def deterministic_feature_fixture() -> dict:
    seq = np.zeros((2, 17, 3), np.float32)
    seq[0, 0] = [1.0, 2.0, 0.1]
    seq[1, 0] = [2.0, 4.0, 0.1]
    vel = add_velocity(seq, 30.0)
    repeated = resample(vel[:1], 3)
    return {
        "velocity_first": vel[0, 0, 3:].round(6).tolist(),
        "velocity_second": vel[1, 0, 3:].round(6).tolist(),
        "single_resample_equal": bool(np.allclose(repeated[0], repeated[1]) and np.allclose(repeated[1], repeated[2])),
    }


def behavioral_fixture() -> dict:
    frame = np.zeros((17, 3), np.float32)
    frame[:, 2] = 1.0
    frame[5] = [1.0, 0.0, 1.0]
    frame[6] = [1.0, 0.0, 1.0]
    frame[11] = [0.0, 0.0, 1.0]
    frame[12] = [0.0, 0.0, 1.0]
    window = np.stack([frame, frame])
    return {
        "horizontality": torso_horizontality(frame),
        "motion": motion_energy(window),
        "detectionRate": detection_rate(window),
    }


def main() -> int:
    parser = argparse.ArgumentParser()
    parser.add_argument("--probe-cv2", action="store_true")
    parser.add_argument("--probe-ort", action="store_true")
    parser.add_argument("--probe-onnx", action="store_true")
    parser.add_argument("--golden", action="store_true")
    parser.add_argument("--thirty-fixtures", action="store_true")
    parser.add_argument("--compare-gru", action="store_true")
    args = parser.parse_args()
    manifest = verify_manifest()
    report = {
        "manifest": {
            "classes": manifest["classOrder"],
            "yolo": {k: manifest["yolo"][k] for k in ("asset", "sha256", "inputName", "inputShape", "outputName", "outputShape")},
            "gru": {k: manifest["gru"][k] for k in ("asset", "sha256", "inputName", "inputShape", "outputName", "outputShape")},
        },
        "features": deterministic_feature_fixture(),
        "rules": behavioral_fixture(),
    }
    if args.probe_cv2:
        report["cv2"] = probe_cv2_shapes(manifest)
    if args.probe_ort:
        report["onnxruntime"] = inspect_onnxruntime_metadata(manifest)
    if args.probe_onnx:
        report["onnx"] = inspect_onnx_graph_metadata(manifest)
    if args.golden:
        report["goldenVectors"] = ort_golden_vectors(manifest)
    if args.thirty_fixtures:
        report["thirtyFixtures"] = thirty_fixture_parity(manifest)
    if args.compare_gru:
        report["gruParity"] = compare_gru_onnx_to_checkpoint(manifest)
    print(json.dumps(report, indent=2, sort_keys=True))
    return 0


if __name__ == "__main__":
    raise SystemExit(main())
