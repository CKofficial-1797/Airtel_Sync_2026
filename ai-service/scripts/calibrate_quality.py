"""Prints blur score + skew angle for all specimen images (no Gemini). Run from ai-service/:
    .venv/Scripts/python scripts/calibrate_quality.py
"""
import sys
from pathlib import Path

sys.path.insert(0, str(Path(__file__).resolve().parent.parent))

import cv2  # noqa: E402

from app import quality  # noqa: E402

DOCS = Path(__file__).resolve().parents[2] / "data" / "kyc_documents"


def main() -> None:
    rows = []
    for path in sorted(DOCS.glob("*.png")):
        img = cv2.imread(str(path), cv2.IMREAD_COLOR)
        score = quality.blur_score(img)
        angle = quality.skew_angle(img)
        after = quality.skew_angle(quality.deskew(img, angle)) if abs(angle) > 1.5 else angle
        rows.append((path.stem, img.shape[1], img.shape[0], score, angle, after))
    print(f"{'image':32} {'size':>10} {'blur':>9} {'skew°':>7} {'after°':>7}")
    for name, w, h, score, angle, after in sorted(rows, key=lambda r: r[3]):
        print(f"{name:32} {w:>4}x{h:<5} {score:9.1f} {angle:7.2f} {after:7.2f}")


if __name__ == "__main__":
    main()
