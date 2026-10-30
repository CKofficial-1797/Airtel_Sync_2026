"""Image checks before extraction (OpenCV, in memory only): file type, blur and skew.

Thresholds are calibrated on the 47 specimen images (scripts/calibrate_quality.py) and live in Settings.
"""
from __future__ import annotations

import math
from dataclasses import dataclass

import cv2
import numpy as np

NORMALISED_WIDTH = 1240  # every image is resized to this width before measuring, so scores are comparable

PNG_MAGIC = b"\x89PNG\r\n\x1a\n"
JPEG_MAGIC = b"\xff\xd8\xff"


class InvalidImage(ValueError):
    """Not a PNG/JPEG, too large, or cannot be decoded."""


@dataclass
class Quality:
    blur_score: float
    is_blurry: bool
    skew_deg: float
    deskewed: bool


def sniff_type(data: bytes) -> str | None:
    """'png' / 'jpeg' from the magic bytes (the file name and content type are not trusted)."""
    if data.startswith(PNG_MAGIC):
        return "png"
    if data.startswith(JPEG_MAGIC):
        return "jpeg"
    return None


def decode(data: bytes, max_bytes: int) -> np.ndarray:
    if len(data) == 0:
        raise InvalidImage("Empty file")
    if len(data) > max_bytes:
        raise InvalidImage(f"File is larger than {max_bytes // (1024 * 1024)} MB")
    if sniff_type(data) is None:
        raise InvalidImage("Only PNG or JPEG images are accepted")
    img = cv2.imdecode(np.frombuffer(data, np.uint8), cv2.IMREAD_COLOR)
    if img is None:
        raise InvalidImage("The image could not be decoded")
    return img


def _normalised_gray(img: np.ndarray) -> np.ndarray:
    gray = cv2.cvtColor(img, cv2.COLOR_BGR2GRAY)
    h, w = gray.shape
    if w != NORMALISED_WIDTH:
        gray = cv2.resize(gray, (NORMALISED_WIDTH, round(h * NORMALISED_WIDTH / w)), interpolation=cv2.INTER_AREA)
    return gray


def blur_score(img: np.ndarray) -> float:
    """Variance of the Laplacian: sharp text has many strong edges (high), a blurred photo has few (low)."""
    return float(cv2.Laplacian(_normalised_gray(img), cv2.CV_64F).var())


def _without_watermark(img: np.ndarray) -> np.ndarray:
    """Grayscale with the diagonal red 'SYNTHETIC SPECIMEN' watermark painted white, so it can't bias the angle."""
    hsv = cv2.cvtColor(img, cv2.COLOR_BGR2HSV)
    red = cv2.inRange(hsv, (0, 40, 80), (12, 255, 255)) | cv2.inRange(hsv, (165, 40, 80), (180, 255, 255))
    gray = cv2.cvtColor(img, cv2.COLOR_BGR2GRAY)
    gray[red > 0] = 255
    return gray


def skew_angle(img: np.ndarray) -> float:
    """Text/frame angle in degrees (positive = rotated clockwise as seen on screen); 0 for a straight scan.

    Long near-horizontal edges (frame, header band, text baselines) are found with a probabilistic Hough
    transform on the watermark-free image; the median of their angles is robust to a few stray lines."""
    gray = _without_watermark(img)
    h, w = gray.shape
    scale = NORMALISED_WIDTH / w
    if scale != 1:
        gray = cv2.resize(gray, (NORMALISED_WIDTH, round(h * scale)), interpolation=cv2.INTER_AREA)
    edges = cv2.Canny(gray, 50, 150)
    lines = cv2.HoughLinesP(edges, 1, np.pi / 1800, threshold=120, minLineLength=NORMALISED_WIDTH // 4, maxLineGap=12)
    if lines is None:
        return 0.0
    angles = []
    for x1, y1, x2, y2 in lines.reshape(-1, 4):  # OpenCV 4 returns (N,1,4), OpenCV 5 (N,4)
        angle = math.degrees(math.atan2(y2 - y1, x2 - x1))
        if abs(angle) <= 20:
            angles.append(angle)
    return float(np.median(angles)) if angles else 0.0


def deskew(img: np.ndarray, angle: float) -> np.ndarray:
    h, w = img.shape[:2]
    matrix = cv2.getRotationMatrix2D((w / 2, h / 2), angle, 1.0)
    return cv2.warpAffine(img, matrix, (w, h), flags=cv2.INTER_CUBIC, borderValue=(255, 255, 255))


def assess(img: np.ndarray, blur_threshold: float, skew_threshold_deg: float) -> tuple[Quality, np.ndarray]:
    """Returns the quality metrics and the image to extract from (deskewed if needed)."""
    score = blur_score(img)
    angle = skew_angle(img)
    needs_deskew = abs(angle) > skew_threshold_deg
    out = deskew(img, angle) if needs_deskew else img
    return Quality(round(score, 1), score < blur_threshold, round(angle, 2), needs_deskew), out


def encode_png(img: np.ndarray) -> bytes:
    ok, buf = cv2.imencode(".png", img)
    if not ok:
        raise InvalidImage("Could not re-encode the image")
    return buf.tobytes()
