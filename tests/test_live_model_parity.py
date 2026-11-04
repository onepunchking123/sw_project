from __future__ import annotations

import sys
from pathlib import Path

ROOT = Path(__file__).resolve().parents[1]
sys.path.insert(0, str(ROOT / "scripts"))

from live_model_parity import (
    behavioral_fixture,
    compare_gru_onnx_to_checkpoint,
    deterministic_feature_fixture,
    inspect_onnx_graph_metadata,
    inspect_onnxruntime_metadata,
    ort_golden_vectors,
    probe_cv2_shapes,
    thirty_fixture_parity,
    verify_manifest,
)


def test_manifest_hashes_and_tensor_contracts():
    manifest = verify_manifest()

    assert manifest["classOrder"] == ["normal", "fall", "fallen"]
    assert manifest["yolo"]["inputName"] == "images"
    assert manifest["yolo"]["inputShape"] == [1, 3, 640, 640]
    assert manifest["yolo"]["inputDtype"] == "float32"
    assert manifest["yolo"]["outputDtype"] == "float32"
    assert manifest["yolo"]["inputColorOrder"] == "RGB"
    assert manifest["yolo"]["outputName"] == "output0"
    assert manifest["yolo"]["outputShape"] == [1, 56, 8400]
    assert manifest["yolo"]["orientationContract"] == "UPRIGHT_UNMIRRORED_BITMAP"
    assert manifest["yolo"]["rotationHandling"] == "CALLER_PROVIDES_UPRIGHT"
    assert manifest["yolo"]["mirrorHandling"] == "CALLER_PROVIDES_UNMIRRORED"
    assert manifest["gru"]["inputName"] == "input"
    assert manifest["gru"]["inputShape"] == [1, 64, 85]
    assert manifest["gru"]["inputDtype"] == "float32"
    assert manifest["gru"]["outputDtype"] == "float32"
    assert manifest["gru"]["feature"] == "posvel"
    assert manifest["gru"]["outputName"] == "output"
    assert manifest["gru"]["outputShape"] == [1, 3]
    assert manifest["stream"]["timestampMode"] == "CAPTURE_TIMESTAMP_ACTUAL_DT"
    assert manifest["stream"]["resampleMode"] == "SOURCE_TIME_LINEAR"


def test_cv2_can_load_models_and_observe_output_shapes():
    manifest = verify_manifest()
    report = probe_cv2_shapes(manifest)

    assert report["yolo"]["outputs"] == ["output0"]
    assert report["gru"]["outputs"] == ["output"]


def test_onnxruntime_metadata_matches_manifest():
    manifest = verify_manifest()
    report = inspect_onnxruntime_metadata(manifest)

    assert report["yolo"]["inputName"] == "images"
    assert report["yolo"]["outputName"] == "output0"
    assert report["gru"]["inputName"] == "input"
    assert report["gru"]["outputName"] == "output"


def test_onnx_graph_metadata_matches_manifest():
    manifest = verify_manifest()
    report = inspect_onnx_graph_metadata(manifest)

    assert report["yolo"]["inputs"][0]["name"] == "images"
    assert report["yolo"]["outputs"][0]["name"] == "output0"
    assert report["gru"]["inputs"][0]["name"] == "input"
    assert report["gru"]["outputs"][0]["name"] == "output"


def test_ort_golden_vectors_are_stable_and_checkpoint_backed():
    manifest = verify_manifest()
    report = ort_golden_vectors(manifest)

    assert report["yoloZero"]["shape"] == [1, 56, 8400]
    assert report["yoloZero"]["sha256"] == "a947cc0c699251489a97487e8b34141a3b3a43371a6ac71d599f1e16124cddd3"
    assert report["gruCheckpointParity"]["logitMaxAbs"] <= 1e-4


def test_thirty_synthetic_fixtures_match_runtime_contracts():
    manifest = verify_manifest()
    report = thirty_fixture_parity(manifest, count=30)

    assert report["count"] == 30
    assert report["gru"]["maxLogitAbs"] <= 1e-4
    assert report["gru"]["maxProbabilityAbs"] <= 1e-3
    assert report["yolo"]["allFinite"] is True
    assert report["yolo"]["shape"] == [1, 56, 8400]
    assert report["yolo"]["uniqueHashes"] > 1


def test_gru_onnx_matches_trained_checkpoint_logits():
    manifest = verify_manifest()
    report = compare_gru_onnx_to_checkpoint(manifest)

    assert report["checkpoint"] == {
        "classes": ["normal", "fall", "fallen"],
        "seq_len": 64,
        "kind": "gru",
        "feat": "posvel",
    }
    assert report["logitMaxAbs"] <= 1e-4


def test_feature_fixture_matches_android_velocity_contract():
    fixture = deterministic_feature_fixture()

    assert fixture["velocity_first"] == [3.0, 6.0]
    assert fixture["velocity_second"] == [3.0, 6.0]
    assert fixture["single_resample_equal"] is True


def test_rule_fixture_matches_android_threshold_contract():
    fixture = behavioral_fixture()

    assert fixture["horizontality"] > 1.0
    assert fixture["motion"] == 0.0
    assert fixture["detectionRate"] == 1.0


if __name__ == "__main__":
    for name, fn in sorted(globals().items()):
        if name.startswith("test_") and callable(fn):
            fn()
            print(f"PASS {name}")
