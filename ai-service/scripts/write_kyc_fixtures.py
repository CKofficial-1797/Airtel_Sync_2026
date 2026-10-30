"""Writes hand-transcribed golden fixtures for the 47 KYC specimen images (no Gemini).

Fields were transcribed from the images exactly as printed (including OCR-style characters such as the zero in
19E0GXH4115Y1ZL). Quality metrics are measured live with app.quality. The JSON shape equals the ai-service
/extract response. POST /api/admin/kyc/run-specimens (with Gemini) overwrites these with model output.
Run from ai-service/:  .venv/Scripts/python scripts/write_kyc_fixtures.py
"""
import json
import sys
from pathlib import Path

ROOT = Path(__file__).resolve().parents[2]
sys.path.insert(0, str(ROOT / "ai-service"))

import cv2  # noqa: E402

from app import quality  # noqa: E402
from app.config import Settings  # noqa: E402

DOCS = ROOT / "data" / "kyc_documents"
OUT = ROOT / "backend" / "src" / "test" / "resources" / "kyc" / "fixtures"

GST = "GST_CERTIFICATE"
UDYAM = "UDYAM_CERTIFICATE"
ID = "SIGNATORY_ID"
BILL = "ADDRESS_PROOF"


def gst(gstin, legal, trade, constitution, address, liability):
    return {"gstin": gstin, "legalName": legal, "tradeName": trade, "constitution": constitution,
            "principalAddress": address, "dateOfLiability": liability, "registrationType": "Regular"}


def udyam(number, name, activity, city, state, date):
    return {"udyamNumber": number, "enterpriseName": name, "majorActivity": activity, "city": city, "state": state,
            "dateOfRegistration": date}


def sig(name, designation, business, id_no, valid):
    return {"name": name, "designation": designation, "forBusiness": business, "idNumber": id_no, "validTill": valid}


def bill(consumer, billed_to, address, date, amount):
    return {"documentKind": "Electricity bill", "consumerNumber": consumer, "billedTo": billed_to,
            "supplyAddress": address, "billDate": date, "amountDueInr": amount}


P, LLP, PVT = "Proprietorship", "Limited Liability Partnership", "Private Limited Company"

DATA = {
    "K01": {
        GST: gst("37IHKXV5167L1ZS", "Neha Kaur (Prop. Vindhya Tours & Travels)", "Vindhya Tours & Travels", P,
                 "Shop/Unit 1, Tech Park Road, Visakhapatnam, Andhra Pradesh - 118383", "2025-06-07"),
        UDYAM: udyam(None, "Vindhya Tours & Travels", "Services", "Visakhapatnam", "Andhra Pradesh", "2024-03-24"),
        ID: sig("Neha Kaur", "Office admin", "Vindhya Tours & Travels", "SYN-ID-346190", "2028-03-26"),
        BILL: bill("9485717625", "Vindhya Tours & Travels",
                   "Shop/Unit 1, Tech Park Road, Visakhapatnam, Andhra Pradesh - 118383", "2026-06-26", 27071),
    },
    "K02": {
        GST: gst("19E0GXH4115Y1ZL", "Joseph Shah (Prop. Harmony Dental Care)", "Harmony Dental Care", P,
                 "Shop/Unit 199, Station Road, Kolkata, West Bengal - 347550", "2022-04-17"),
        UDYAM: udyam("UDYAM-WB-00-5053330", "Harmony Dental Care", "Services", "Kolkata", "West Bengal", "2023-01-11"),
        ID: sig("Joseph Shah", "Manager", "Harmony Dental Care", "SYN-ID-816907", "2028-09-10"),
        BILL: bill("9790713533", "Harmony Dental Care", "Shop/Unit 199, Station Road, Kolkata, West Bengal - 347550",
                   "2026-05-22", 7077),
    },
    "K03": {
        GST: gst("27XUIXG0767D1ZP", "Joseph Singh (Prop. Ganesh Woodcraft)", "Ganesh Woodcraft", P,
                 "Shop/Unit 80, Civil Lines, Nagpur, Maharashtra - 411231", "2020-02-10"),
        UDYAM: udyam("UDYAM-MH-00-9516696", "Ganesh Woodcraft", "Manufacturing", "Nagpur", "Maharashtra", "2024-11-21"),
        ID: sig("Joseph Singh", "Owner", "Ganesh Woodcraft", "SYN-ID-447889", "2028-07-04"),
        BILL: bill("3438517928", "Ganesh Woodcraft", "Shop/Unit 80, Civil Lines, Nagpur, Maharashtra - 411231",
                   "2026-05-30", 33615),
    },
    "K04": {
        GST: None,  # blurred: UNREADABLE
        UDYAM: udyam("UDYAM-MH-00-6131836", "Saffron Dental Care LLP", "Services", "Nagpur", "Maharashtra",
                     "2025-05-17"),
        ID: sig("Fatima Pillai", "Partner", "Saffron Dental Care LLP", "SYN-ID-609396", "2028-08-03"),
        BILL: bill("7397844759", "Saffron Dental Care LLP",
                   "Shop/Unit 75, Lake View Colony, Nagpur, Maharashtra - 137452", "2026-05-28", 20713),
    },
    "K05": {
        GST: gst("32TZAXB5481U1Z1", "Balaji Realty LLP", "Balaji Realty LLP", LLP,
                 "Shop/Unit 38, Civil Lines, Kochi, Kerala - 752083", "2020-07-26"),
        ID: sig("Anita Shah", "Owner", "Balaji Realty LLP", "SYN-ID-587234", "2029-09-11"),
        BILL: bill("4294969054", "Balaji Realty LLP", "Shop/Unit 38, Civil Lines, Kochi, Kerala - 752083",
                   "2026-06-11", 22425),
    },
    "K06": {
        GST: gst("08YKZXS8189L1Z0", "Pooja Shah (Prop. Saffron Unisex Salon)", "Saffron Unisex Salon", P,
                 "Shop/Unit 71, Market Yard, Jaipur, Rajasthan - 194057", "2019-09-25"),
        UDYAM: udyam("UDYAM-RJ-00-6577157", "Saffron Unisex Salon", "Services", "Jaipur", "Rajasthan", "2023-01-24"),
        ID: sig("Pooja Shah", "Owner", "Saffron Unisex Salon", "SYN-ID-118354", "2028-09-15"),
        BILL: bill("9566781101", "Saffron Unisex Salon", "Shop/Unit 71, Market Yard, Jaipur, Rajasthan - 194057",
                   "2026-06-11", 27352),
    },
    "K07": {
        GST: gst("07CPCXN0398P1ZT", "Neha Banerjee (Prop. Shri Physio Clinic)", "Neelgiri Physio Clinic", P,
                 "Shop/Unit 52, Transport Nagar, Delhi, Delhi - 334596", "2024-01-13"),
        UDYAM: udyam("UDYAM-DL-00-9565547", "Neelgiri Physio Clinic", "Services", "Delhi", "Delhi", "2024-07-14"),
        ID: sig("Neha Banerjee", "Manager", "Neelgiri Physio Clinic", "SYN-ID-178237", "2030-05-05"),
        BILL: bill("7545812405", "Neelgiri Physio Clinic", "Shop/Unit 52, Transport Nagar, Delhi, Delhi - 334596",
                   "2026-07-05", 10690),
    },
    "K08": {
        GST: gst("24X0RXG9365M1Z3", "Joseph Rao (Prop. Green Leaf Legal Associates)", "Green Leaf Legal Associates", P,
                 "Shop/Unit 238, Ring Road, Vadodara, Gujarat - 348176", "2018-12-01"),
        UDYAM: udyam("UDYAM-GJ-00-8477445", "Green Leaf Legal Associates", "Services", "Vadodara", "Gujarat",
                     "2022-11-12"),
        ID: sig("Joseph Rao", "Office admin", "Green Leaf Legal Associates", "SYN-ID-393148", "2027-09-18"),
        BILL: bill("2568472785", "Green Leaf Legal Associates", "Shop/Unit 238, Ring Road, Vadodara, Gujarat - 348176",
                   "2026-05-26", 33859),
    },
    "K09": {
        GST: gst("37LDYXK0296F1ZB", "Anita Kulkarni (Prop. Kaveri Insurance Advisors)", "Kaveri Insurance Advisors", P,
                 "Shop/Unit 217, Civil Lines, Visakhapatnam, Andhra Pradesh - 405337", "2023-09-20"),
        UDYAM: udyam("UDYAM-AP-00-9751562", "Kaveri Insurance Advisors", "Services", "Visakhapatnam", "Andhra Pradesh",
                     "2023-07-04"),
        ID: sig("Anita Kulkarni", "Office admin", "Kaveri Insurance Advisors", "SYN-ID-126040", "2027-12-22"),
        BILL: bill("9375012581", "Kaveri Insurance Advisors",
                   "Shop/Unit 217, Civil Lines, Visakhapatnam, Andhra Pradesh - 405337", "2026-07-10", 28569),
    },
    "K10": {
        GST: gst("27QHJXK0658U1ZL", "Kaveri Stays LLP", "Kaveri Stays LLP", LLP,
                 "Shop/Unit 21, Sector 14, Mumbai, Maharashtra - 570043", "2022-07-13"),
        UDYAM: udyam("UDYAM-MH-00-7183141", "Kaveri Stays LLP", "Services", "Mumbai", "Maharashtra", "2024-01-09"),
        ID: sig("Nisha Arora", "Owner", "Kaveri Stays LLP", "SYN-ID-247542", "2029-06-01"),
        BILL: bill("6910330901", "Kaveri Stays LLP", "Shop/Unit 21, Sector 14, Mumbai, Maharashtra - 570043",
                   "2026-06-18", 23713),
    },
    "K11": {
        GST: gst("03KGZXT1320R1ZZ", "Trinity Bake House Pvt Ltd", "Trinity Bake House Pvt Ltd", PVT,
                 "Shop/Unit 188, Industrial Area Phase 2, Ludhiana, Punjab - 541498", "2019-08-19"),
        UDYAM: udyam("UDYAM-PB-00-9719478", "Trinity Bake House Pvt Ltd", "Services", "Ludhiana", "Punjab",
                     "2025-09-15"),
        ID: sig("Vikram Pillai", "Owner", "Trinity Bake House Pvt Ltd", "SYN-ID-440312", "2028-12-23"),
        BILL: bill("2710511786", "Trinity Bake House Pvt Ltd",
                   "Shop/Unit 188, Industrial Area Phase 2, Ludhiana, Punjab - 541498", "2025-10-31", 2768),
    },
    "K12": {
        GST: gst("37NBLXS1145E1ZU", "Pooja Agarwal (Prop. Star Electronics)", "Star Electronics", P,
                 "Shop/Unit 216, Nehru Nagar, Visakhapatnam, Andhra Pradesh - 762071", "2017-01-08"),
        UDYAM: udyam("UDYAM-AP-00-5186772", "Star Electronics", "Services", "Visakhapatnam", "Andhra Pradesh",
                     "2024-02-03"),
        ID: sig("Pooja Agarwal", "Owner", "Star Electronics", "SYN-ID-365512", "2029-03-03"),
        BILL: bill("6982457282", "Star Electronics", "Shop/Unit 216, Nehru Nagar, Visakhapatnam, Andhra Pradesh - 762071",
                   "2026-07-06", 25639),
    },
}

FILES = {GST: "gst_certificate", UDYAM: "udyam_certificate", ID: "signatory_id", BILL: "address_proof"}


def main() -> None:
    settings = Settings(_env_file=None)
    OUT.mkdir(parents=True, exist_ok=True)
    written = 0
    for applicant, docs in DATA.items():
        for doc_type, fields in docs.items():
            stem = f"{applicant}_{FILES[doc_type]}"
            img = cv2.imread(str(DOCS / f"{stem}.png"))
            q, _ = quality.assess(img, settings.blur_threshold, settings.skew_threshold_deg)
            fixture = {
                "source": "hand-transcribed from the specimen image (no Gemini); run-specimens overwrites it",
                "status": "UNREADABLE" if q.is_blurry else "OK",
                "docType": doc_type,
                "detectedDocType": None if q.is_blurry else doc_type,
                "quality": {"blurScore": q.blur_score, "isBlurry": q.is_blurry, "skewDeg": q.skew_deg,
                            "deskewed": q.deskewed},
                "fields": None if q.is_blurry else fields,
                "model": None if q.is_blurry else "hand-transcribed",
                "ms": 0,
            }
            assert (fields is None) == q.is_blurry, stem
            (OUT / f"{stem}.json").write_text(json.dumps(fixture, indent=2, ensure_ascii=False) + "\n", encoding="utf-8")
            written += 1
    print(f"wrote {written} fixtures to {OUT}")


if __name__ == "__main__":
    main()
