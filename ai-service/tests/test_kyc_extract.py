"""KYC extraction: calibrated blur/skew over all 47 specimens, file validation, schema contract, 401.
No Gemini calls (a fake vision client)."""
from pathlib import Path

import cv2
import pytest
from fastapi.testclient import TestClient

from app import quality
from app.extract import FIELDS, clean_fields, response_schema
from app.kyc import KycExtractor
from app.main import create_app
from tests.conftest import TOKEN

DOCS = Path(__file__).resolve().parents[2] / "data" / "kyc_documents"
IMAGES = sorted(DOCS.glob("*.png"))
BLURRY = {"K04_gst_certificate"}
TILTED = {"K02_gst_certificate"}


class FakeVision:
    def __init__(self, response: dict | None = None):
        self.response = response or {}
        self.calls: list[str] = []

    def extract(self, image_png: bytes, doc_type: str):
        self.calls.append(doc_type)
        return dict(self.response), "fake-model"


def test_specimen_set_is_complete():
    assert len(IMAGES) == 47
    assert not (DOCS / "K05_udyam_certificate.png").exists()


@pytest.mark.parametrize("path", IMAGES, ids=lambda p: p.stem)
def test_blur_calibration(path, settings):
    score = quality.blur_score(cv2.imread(str(path)))
    if path.stem in BLURRY:
        assert score < settings.blur_threshold / 10, score
    else:
        assert score > settings.blur_threshold * 5, score  # clear margin on both sides


@pytest.mark.parametrize("path", IMAGES, ids=lambda p: p.stem)
def test_skew_calibration(path, settings):
    img = cv2.imread(str(path))
    angle = quality.skew_angle(img)
    if path.stem in TILTED:
        assert abs(angle) > 4 * settings.skew_threshold_deg
        assert abs(quality.skew_angle(quality.deskew(img, angle))) < 0.5  # straight after deskew
    else:
        assert abs(angle) < settings.skew_threshold_deg / 3


def test_file_validation_uses_magic_bytes(settings):
    with pytest.raises(quality.InvalidImage, match="PNG or JPEG"):
        quality.decode(b"%PDF-1.7 not an image", settings.max_upload_bytes)
    with pytest.raises(quality.InvalidImage, match="larger than"):
        quality.decode(quality.PNG_MAGIC + b"0" * 100, 50)
    with pytest.raises(quality.InvalidImage, match="Empty"):
        quality.decode(b"", settings.max_upload_bytes)
    with pytest.raises(quality.InvalidImage, match="decoded"):
        quality.decode(quality.PNG_MAGIC + b"garbage", settings.max_upload_bytes)
    assert quality.sniff_type(b"\xff\xd8\xff\xe0rest") == "jpeg"


def test_unreadable_image_returns_no_fields_and_skips_the_model(settings):
    vision = FakeVision({"detectedDocType": "GST_CERTIFICATE", "gstin": "SHOULD-NOT-APPEAR"})
    resp = KycExtractor(settings, vision).run((DOCS / "K04_gst_certificate.png").read_bytes(), "GST_CERTIFICATE")
    assert resp.status == "UNREADABLE"
    assert resp.fields is None and resp.model is None
    assert resp.quality.is_blurry and vision.calls == []


def test_tilted_image_is_deskewed_before_extraction(settings):
    vision = FakeVision({"detectedDocType": "GST_CERTIFICATE", "gstin": "19E0GXH4115Y1ZL"})
    resp = KycExtractor(settings, vision).run((DOCS / "K02_gst_certificate.png").read_bytes(), "GST_CERTIFICATE")
    assert resp.status == "OK" and resp.quality.deskewed and resp.quality.skew_deg > 4
    assert resp.fields["gstin"] == "19E0GXH4115Y1ZL"


def test_wrong_slot_is_reported(settings):
    vision = FakeVision({"detectedDocType": "ADDRESS_PROOF", "gstin": None})
    resp = KycExtractor(settings, vision).run((DOCS / "K03_address_proof.png").read_bytes(), "GST_CERTIFICATE")
    assert resp.status == "WRONG_DOC_TYPE" and resp.detected_doc_type == "ADDRESS_PROOF" and resp.fields is None


def test_schema_contract_per_doc_type():
    for doc_type, fields in FIELDS.items():
        schema = response_schema(doc_type)
        assert set(schema["properties"]) == set(fields) | {"detectedDocType"}
        assert set(schema["required"]) == set(schema["properties"])
        assert all("null" in schema["properties"][f]["type"] for f in fields)  # blanks must be allowed
    assert set(FIELDS["UDYAM_CERTIFICATE"]) == {"udyamNumber", "enterpriseName", "majorActivity", "city", "state",
                                                 "dateOfRegistration"}


def test_clean_fields_keeps_schema_fields_and_nulls_blanks():
    raw = {"udyamNumber": "  ", "enterpriseName": "Vindhya  Tours & Travels", "extra": "dropped"}
    assert clean_fields(raw, "UDYAM_CERTIFICATE") == {
        "udyamNumber": None, "enterpriseName": "Vindhya Tours & Travels", "majorActivity": None, "city": None,
        "state": None, "dateOfRegistration": None}


def _client(settings, vision=None):
    return TestClient(create_app(advisor=object(), settings=settings,  # type: ignore[arg-type]
                                 extractor=KycExtractor(settings, vision or FakeVision())))


def test_extract_endpoint_requires_token(settings):
    files = {"file": ("x.png", (DOCS / "K03_gst_certificate.png").read_bytes(), "image/png")}
    r = _client(settings).post("/extract", files=files, data={"docType": "GST_CERTIFICATE"})
    assert r.status_code == 401


def test_extract_endpoint_contract(settings):
    vision = FakeVision({"detectedDocType": "UDYAM_CERTIFICATE", "udyamNumber": None,
                         "enterpriseName": "Vindhya Tours & Travels"})
    files = {"file": ("K01.png", (DOCS / "K01_udyam_certificate.png").read_bytes(), "image/png")}
    r = _client(settings, vision).post("/extract", files=files, data={"docType": "UDYAM_CERTIFICATE"},
                                       headers={"X-Internal-Token": TOKEN})
    assert r.status_code == 200
    body = r.json()
    assert set(body) == {"status", "docType", "detectedDocType", "quality", "fields", "model", "ms"}
    assert set(body["quality"]) == {"blurScore", "isBlurry", "skewDeg", "deskewed"}
    assert body["status"] == "OK" and body["fields"]["udyamNumber"] is None


def test_extract_endpoint_rejects_non_images(settings):
    files = {"file": ("fake.png", b"%PDF-1.4 pretending to be png", "image/png")}
    r = _client(settings).post("/extract", files=files, data={"docType": "GST_CERTIFICATE"},
                               headers={"X-Internal-Token": TOKEN})
    assert r.status_code == 400 and r.json()["detail"]["code"] == "INVALID_IMAGE"
    r2 = _client(settings).post("/extract", files={"file": ("a.png", (DOCS / "K03_gst_certificate.png").read_bytes(),
                                                            "image/png")},
                                data={"docType": "PASSPORT"}, headers={"X-Internal-Token": TOKEN})
    assert r2.status_code == 400
