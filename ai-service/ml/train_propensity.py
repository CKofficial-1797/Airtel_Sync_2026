"""
Offline training for the lead propensity score: "how likely is this lead to activate?"

A plain logistic regression on one-hot features, so every score can be explained and the model can be scored in Java
without Python. Deterministic: same data, same numbers.

Run from ai-service/:   .venv/Scripts/python ml/train_propensity.py
Try the leakage guard:  .venv/Scripts/python ml/train_propensity.py --add-feature acquisition_cost_inr
"""
from __future__ import annotations

import argparse
import json
import sys
from dataclasses import dataclass
from pathlib import Path

import numpy as np
import pandas as pd
from sklearn.linear_model import LogisticRegression
from sklearn.metrics import average_precision_score, roc_auc_score
from sklearn.model_selection import StratifiedKFold

ROOT = Path(__file__).resolve().parents[2]
DATA = ROOT / "data"
SEED = 42
BOOTSTRAPS = 1000

# ---- leakage policy ------------------------------------------------------------------------------------------------
# Anything only known after the sales process has run says who activated, not who will.
LEAKY_COLUMNS = {
    "outcome", "stage_reached", "drop_reason", "days_lead_to_activation", "customer_follow_up_calls", "bundle_products",
    "monthly_bundle_value_inr", "one_time_setup_inr", "contract_months", "churned_within_6_months", "nps_score",
    "acquisition_cost_inr",  # partner commission is paid only on activation
}
IDENTIFIER_COLUMNS = {"lead_id", "business_name", "contact_name", "gstin", "udyam_number"}
MAX_LEVELS = 12  # more than this smells like an identifier
MAX_SINGLE_FEATURE_AUC = 0.90  # no honest single field predicts activation this well


def assert_no_leakage(features: list["Feature"]) -> None:
    """Fails the run if a forbidden column feeds the model. Checks what each feature is built from, not just its name."""
    for f in features:
        for source in f.sources:
            if source in LEAKY_COLUMNS or source.endswith("_date"):
                raise AssertionError(f"LEAKAGE: feature '{f.name}' uses '{source}', which is only known after the outcome")
            if source in IDENTIFIER_COLUMNS:
                raise AssertionError(f"LEAKAGE: feature '{f.name}' uses the identifier '{source}'")


def assert_matrix_is_sane(raw: pd.DataFrame, y: np.ndarray, features: list["Feature"]) -> None:
    """Second line of defence on the values themselves: a hidden ID or a proxy for the outcome fails here."""
    for f in features:
        levels = raw[f.name].nunique()
        if levels > MAX_LEVELS:
            raise AssertionError(f"LEAKAGE: feature '{f.name}' has {levels} levels: is it an identifier?")
        rate = pd.Series(y).groupby(raw[f.name].to_numpy()).transform("mean")
        auc = roc_auc_score(y, rate)
        if auc > MAX_SINGLE_FEATURE_AUC:
            raise AssertionError(f"LEAKAGE: feature '{f.name}' alone reaches AUC {auc:.3f}")


# ---- features ------------------------------------------------------------------------------------------------------
@dataclass(frozen=True)
class Feature:
    """One model input. reference = the level with no coefficient, which is also what a missing value falls back to."""

    name: str
    sources: tuple[str, ...]
    levels: tuple[str, ...]
    reference: str
    advisor_compatible: bool = True
    in_model: bool = True


def bucket(values: pd.Series, edges: list[int], labels: list[str]) -> pd.Series:
    """Edges are inclusive upper bounds: edges [5, 20] and 3 labels give <=5, 6-20, >20."""
    numbers = values.astype(int)
    return pd.cut(numbers, bins=[-np.inf, *edges, np.inf], labels=labels, right=True).astype(str)


EMPLOYEE_EDGES, EMPLOYEE_LEVELS = [5, 20, 50], ("1-5", "6-20", "21-50", "51+")
SITE_EDGES, SITE_LEVELS = [1, 4], ("1", "2-4", "5+")
SPEND_EDGES, SPEND_LEVELS = [850, 1350, 3300], ("up to 850", "851-1350", "1351-3300", "above 3300")  # dataset quartiles
PRODUCT_COUNT_EDGES, PRODUCT_COUNT_LEVELS = [1, 2], ("1", "2", "3+")

FEATURES = [
    Feature("lead_source", ("lead_source",), ("Website form", "Referral", "Inbound call", "Channel partner", "Field sales", "WhatsApp campaign"), "Website form"),
    Feature("segment", ("segment",), ("Professional offices", "Micro retail & services", "Small multi-site businesses", "Small manufacturers"), "Professional offices"),
    Feature("city_tier", ("city_tier",), ("2", "1"), "2"),
    Feature("gstin_status", ("has_gstin", "registry_status"), ("NONE", "ACTIVE", "INACTIVE"), "NONE"),
    Feature("has_udyam", ("has_udyam",), ("N", "Y"), "N"),
    Feature("employees", ("employee_count",), EMPLOYEE_LEVELS, "1-5"),
    Feature("sites", ("num_sites",), SITE_LEVELS, "1"),
    Feature("telecom_spend", ("monthly_telecom_spend_inr",), SPEND_LEVELS, "851-1350"),
    Feature("current_internet", ("current_internet",), ("Mobile hotspot", "Home broadband", "Business broadband", "Leased line", "Mixed per site", "None"), "Mobile hotspot"),
    # Left out on purpose (STOP 2 decision): it barely helps and would mean fewer calls to regional-language customers.
    # Kept here so every run re-measures that trade-off.
    Feature("language", ("preferred_language",), ("Hindi", "English", "Other"), "Hindi", in_model=False),
    Feature("products_of_interest", ("products_of_interest",), PRODUCT_COUNT_LEVELS, "2"),
    Feature("gst_product_interest", ("products_of_interest", "requires_gstin"), ("N", "Y"), "N"),
    Feature("turnover", ("annual_turnover_band",), ("40 lakh - 1.5 cr", "< 40 lakh", "1.5 - 5 cr", "5 - 25 cr", "25 - 100 cr"), "40 lakh - 1.5 cr", advisor_compatible=False),
    Feature("current_provider", ("current_provider",), ("Competitor A", "Competitor B", "Accel", "Accel (mobile only)", "Local ISP", "None"), "Competitor A", advisor_compatible=False),
    Feature("contact_role", ("contact_role",), ("Owner", "Manager", "Partner", "Office admin"), "Owner", advisor_compatible=False),
]

MODEL_FEATURES = [f for f in FEATURES if f.in_model]
VARIANTS = {
    "a": ("all features", MODEL_FEATURES),
    "b": ("advisor-compatible", [f for f in MODEL_FEATURES if f.advisor_compatible]),
    "b+language": ("b with language", [f for f in FEATURES if f.advisor_compatible]),
}
COMPARED = ("a", "b")
EXPORT_VARIANT = "b"  # chosen at STOP 2: same performance as (a), and only uses what the advisor can collect
EXPORT_CLASS_WEIGHT = None  # same ranking as balanced, but probabilities stay believable

# Live leads have no lead source: every advisor session or order counts as a website lead.
LIVE_LEAD_SOURCE = "Website form"
# These still count in the score, but aren't shown as a negative reason. Bigger businesses convert better overall
# (51+ staff 11.0%, 1-5 staff 4.6%); the negative coefficient only exists because segment already counts size.
NO_NEGATIVE_REASON = ("employees", "sites")

HUMAN_LABELS = {
    "lead_source=Referral": ("Came through a referral", "रेफ़रल से आया"),
    "lead_source=Inbound call": ("Called us directly", "खुद कॉल किया"),
    "lead_source=Channel partner": ("Came through a channel partner", "चैनल पार्टनर से आया"),
    "lead_source=Field sales": ("Found by field sales", "फ़ील्ड सेल्स से मिला"),
    "lead_source=WhatsApp campaign": ("Came from a WhatsApp campaign", "WhatsApp अभियान से आया"),
    "segment=Micro retail & services": ("Micro retail or services business", "सूक्ष्म खुदरा या सेवा व्यवसाय"),
    "segment=Small multi-site businesses": ("Small multi-site business", "कई जगहों वाला छोटा व्यवसाय"),
    "segment=Small manufacturers": ("Small manufacturer", "छोटा निर्माता"),
    "city_tier=1": ("Tier 1 city", "टियर 1 शहर"),
    "gstin_status=ACTIVE": ("Active GSTIN", "सक्रिय GSTIN"),
    "gstin_status=INACTIVE": ("GSTIN on record, but not active", "GSTIN है, पर सक्रिय नहीं"),
    "has_udyam=Y": ("Has Udyam registration", "उद्यम पंजीकरण है"),
    "employees=6-20": ("6-20 staff", "6-20 कर्मचारी"),
    "employees=21-50": ("21-50 staff", "21-50 कर्मचारी"),
    "employees=51+": ("51+ staff", "51+ कर्मचारी"),
    "sites=2-4": ("2-4 sites", "2-4 जगहें"),
    "sites=5+": ("5+ sites", "5+ जगहें"),
    "telecom_spend=up to 850": ("Telecom spend up to ₹850/month", "टेलीकॉम खर्च ₹850/माह तक"),
    "telecom_spend=1351-3300": ("Telecom spend ₹1,351-3,300/month", "टेलीकॉम खर्च ₹1,351-3,300/माह"),
    "telecom_spend=above 3300": ("Telecom spend above ₹3,300/month", "टेलीकॉम खर्च ₹3,300/माह से ज़्यादा"),
    "current_internet=Home broadband": ("Uses home broadband", "होम ब्रॉडबैंड इस्तेमाल करते हैं"),
    "current_internet=Business broadband": ("Uses business broadband", "बिज़नेस ब्रॉडबैंड इस्तेमाल करते हैं"),
    "current_internet=Leased line": ("Already has a leased line", "पहले से लीज़्ड लाइन है"),
    "current_internet=Mixed per site": ("Mixed internet across sites", "अलग-अलग जगहों पर अलग इंटरनेट"),
    "current_internet=None": ("No internet today", "अभी इंटरनेट नहीं है"),
    "products_of_interest=1": ("Interested in one product", "एक प्रोडक्ट में रुचि"),
    "products_of_interest=3+": ("Interested in 3+ products", "3 या ज़्यादा प्रोडक्ट में रुचि"),
    "gst_product_interest=Y": ("Interested in a GST-only product", "GST वाले प्रोडक्ट में रुचि"),
}


def read_csv(name: str) -> pd.DataFrame:
    # Everything as text, and "None" stays a real category instead of becoming a missing value.
    return pd.read_csv(DATA / name, dtype=str, keep_default_na=False)


def derive_features(leads: pd.DataFrame) -> pd.DataFrame:
    registry = read_csv("mock_gstin_registry.csv").set_index("gstin")["status"]
    catalogue = read_csv("product_catalogue.csv")
    gst_products = set(catalogue.loc[catalogue["requires_gstin"] == "Y", "product_id"])

    def gst_status(gstin: str) -> str:
        if not gstin:
            return "NONE"
        return "ACTIVE" if registry.get(gstin) == "Active" else "INACTIVE"

    products = leads["products_of_interest"].str.split(";")
    raw = pd.DataFrame(index=leads.index)
    raw["lead_source"] = leads["lead_source"]
    raw["segment"] = leads["segment"]
    raw["city_tier"] = leads["city_tier"]
    raw["gstin_status"] = leads["gstin"].map(gst_status)  # only the status enters the model, never the number
    raw["has_udyam"] = leads["has_udyam"]
    raw["employees"] = bucket(leads["employee_count"], EMPLOYEE_EDGES, list(EMPLOYEE_LEVELS))
    raw["sites"] = bucket(leads["num_sites"], SITE_EDGES, list(SITE_LEVELS))
    raw["telecom_spend"] = bucket(leads["monthly_telecom_spend_inr"], SPEND_EDGES, list(SPEND_LEVELS))
    raw["current_internet"] = leads["current_internet"]
    raw["language"] = leads["preferred_language"].where(leads["preferred_language"].isin(["Hindi", "English"]), "Other")
    raw["products_of_interest"] = bucket(products.map(len), PRODUCT_COUNT_EDGES, list(PRODUCT_COUNT_LEVELS))
    raw["gst_product_interest"] = products.map(lambda ids: "Y" if gst_products.intersection(ids) else "N")
    raw["turnover"] = leads["annual_turnover_band"]
    raw["current_provider"] = leads["current_provider"]
    raw["contact_role"] = leads["contact_role"]
    return raw


def design_matrix(raw: pd.DataFrame, features: list[Feature]) -> tuple[np.ndarray, list[str]]:
    """One-hot, dropping each feature's reference level. Fails on a level the spec doesn't know."""
    columns, names = [], []
    for f in features:
        unknown = set(raw[f.name]) - set(f.levels)
        if unknown:
            raise ValueError(f"{f.name}: levels not in the spec: {sorted(unknown)}")
        for level in f.levels:
            if level != f.reference:
                columns.append((raw[f.name] == level).astype(float).to_numpy())
                names.append(f"{f.name}={level}")
    return np.column_stack(columns), names


def fit(x: np.ndarray, y: np.ndarray, class_weight: str | None) -> LogisticRegression:
    return LogisticRegression(C=1.0, class_weight=class_weight, solver="lbfgs", max_iter=5000, tol=1e-8, random_state=SEED).fit(x, y)


# ---- metrics -------------------------------------------------------------------------------------------------------
def rank(p: np.ndarray) -> np.ndarray:
    return np.argsort(-p, kind="mergesort")  # stable, so ties can't change between runs


def capture_at(y: np.ndarray, p: np.ndarray, share: float) -> float:
    top = rank(p)[: int(round(len(p) * share))]
    return float(y[top].sum() / y.sum())


def deciles(y: np.ndarray, p: np.ndarray) -> list[dict]:
    """Best-scored tenth first."""
    rows = []
    base = y.mean()
    for i, part in enumerate(np.array_split(rank(p), 10), start=1):
        rows.append({"decile": i, "n": len(part), "activations": int(y[part].sum()), "predicted": float(p[part].mean()),
                     "actual": float(y[part].mean()), "lift": float(y[part].mean() / base)})
    return rows


def evaluate(y: np.ndarray, p: np.ndarray) -> dict:
    return {"auc": float(roc_auc_score(y, p)), "pr_auc": float(average_precision_score(y, p)),
            "top_decile_lift": deciles(y, p)[0]["lift"], "top20_capture": capture_at(y, p, 0.20), "base_rate": float(y.mean())}


def bootstrap_auc(y: np.ndarray, p: np.ndarray) -> tuple[float, float]:
    rng = np.random.default_rng(SEED)
    values = []
    for _ in range(BOOTSTRAPS):
        idx = rng.integers(0, len(y), len(y))
        if y[idx].sum() > 0:
            values.append(roc_auc_score(y[idx], p[idx]))
    low, high = np.percentile(values, [2.5, 97.5])
    return float(low), float(high)


@dataclass
class Result:
    variant: str
    class_weight: str | None
    temporal: dict
    temporal_ci: tuple[float, float]
    temporal_deciles: list[dict]
    cv: dict
    cv_fold_aucs: list[float]
    cv_deciles: list[dict]
    oof: np.ndarray


def run_config(variant: str, class_weight: str | None, raw: pd.DataFrame, y: np.ndarray, is_june: np.ndarray) -> Result:
    x, _ = design_matrix(raw, VARIANTS[variant][1])

    model = fit(x[~is_june], y[~is_june], class_weight)
    p_june = model.predict_proba(x[is_june])[:, 1]

    oof = np.zeros(len(y))
    fold_aucs = []
    for train, test in StratifiedKFold(n_splits=5, shuffle=True, random_state=SEED).split(x, y):
        oof[test] = fit(x[train], y[train], class_weight).predict_proba(x[test])[:, 1]
        fold_aucs.append(float(roc_auc_score(y[test], oof[test])))

    return Result(variant, class_weight, evaluate(y[is_june], p_june), bootstrap_auc(y[is_june], p_june),
                  deciles(y[is_june], p_june), evaluate(y, oof), fold_aucs, deciles(y, oof), oof)


# ---- sign checks ---------------------------------------------------------------------------------------------------
# Each compares against the reference level: Website form, Professional offices, tier 2, no GSTIN, and so on.
SIGN_CHECKS = {
    "gstin_status=ACTIVE": +1,
    "lead_source=Referral": +1,
    "lead_source=Inbound call": +1,
    "lead_source=WhatsApp campaign": -1,
    "segment=Micro retail & services": -1,
    "city_tier=1": +1,
    "has_udyam=Y": +1,
    "turnover=< 40 lakh": -1,
    "turnover=5 - 25 cr": +1,
}


# ---- printing ------------------------------------------------------------------------------------------------------
def print_summary(results: list[Result]) -> None:
    print("\n=== METRICS (June = trained on April-May, tested on June; CV = 5-fold out-of-fold on all leads) ===")
    print(f"{'variant':<22}{'class wt':<10}{'Jun AUC':>8}{'95% CI':>15}{'Jun PR':>8}{'Jun lift':>9}{'Jun top20':>10}{'CV AUC':>8}{'CV PR':>7}{'CV lift':>8}{'CV top20':>9}")
    for r in results:
        name = f"({r.variant}) {VARIANTS[r.variant][0]}"
        ci = f"{r.temporal_ci[0]:.3f}-{r.temporal_ci[1]:.3f}"
        print(f"{name:<22}{str(r.class_weight or 'none'):<10}{r.temporal['auc']:>8.3f}{ci:>15}{r.temporal['pr_auc']:>8.3f}"
              f"{r.temporal['top_decile_lift']:>9.2f}{r.temporal['top20_capture'] * 100:>9.1f}%{r.cv['auc']:>8.3f}{r.cv['pr_auc']:>7.3f}"
              f"{r.cv['top_decile_lift']:>8.2f}{r.cv['top20_capture'] * 100:>8.1f}%")
    print("Random would give AUC 0.500, lift 1.00, top-20% capture 20.0%.")
    print("CV fold AUCs:")
    for r in results:
        print(f"  ({r.variant}) {str(r.class_weight or 'none'):<9} " + " ".join(f"{a:.3f}" for a in r.cv_fold_aucs))


def print_deciles(title: str, rows: list[dict]) -> None:
    print(f"\n{title}")
    print(f"{'decile':>6}{'leads':>7}{'activ.':>8}{'predicted':>11}{'actual':>9}{'lift':>7}{'cum. capture':>14}")
    total = sum(r["activations"] for r in rows)
    running = 0
    for r in rows:
        running += r["activations"]
        print(f"{r['decile']:>6}{r['n']:>7}{r['activations']:>8}{r['predicted'] * 100:>10.1f}%{r['actual'] * 100:>8.1f}%{r['lift']:>7.2f}{running / total * 100:>13.1f}%")


def print_coefficients(variant: str, raw: pd.DataFrame, y: np.ndarray) -> LogisticRegression:
    x, names = design_matrix(raw, VARIANTS[variant][1])
    model = fit(x, y, None)
    coefs = dict(zip(names, model.coef_[0]))
    print(f"\n=== COEFFICIENTS, variant ({variant}), unweighted, trained on all leads. Intercept {model.intercept_[0]:.3f} ===")
    print("Positive = more likely to activate than the reference level.")
    for name, value in sorted(coefs.items(), key=lambda kv: -kv[1]):
        print(f"  {value:>+7.3f}  odds x{np.exp(value):>5.2f}  {name}")
    print("\nSign checks against your data signals:")
    for name, expected in SIGN_CHECKS.items():
        if name not in coefs:
            continue  # variant (b) has no turnover band
        value = coefs[name]
        print(f"  {'OK   ' if np.sign(value) == expected else 'CHECK'} {name:<36} expected {'+' if expected > 0 else '-'}  got {value:+.3f}")
    print(f"  NOTE  {'gstin_status=INACTIVE':<36} got {coefs['gstin_status=INACTIVE']:+.3f}  (vs ACTIVE {coefs['gstin_status=ACTIVE']:+.3f}): the old process sold to cancelled GSTINs; routing guards this")
    return model


def print_bands(result: Result, y: np.ndarray, full_scores: np.ndarray) -> None:
    hot, warm = np.quantile(full_scores, [0.80, 0.50])
    print(f"\n=== BANDS, variant ({result.variant}), class weight {result.class_weight or 'none'} ===")
    print(f"HOT >= {hot:.4f} (top 20%)   WARM >= {warm:.4f} (next 30%)   COLD below")
    order = rank(result.oof)  # out-of-fold, so this shows performance on leads the model didn't train on
    cuts = {"HOT": order[: int(len(y) * 0.2)], "WARM": order[int(len(y) * 0.2): int(len(y) * 0.5)], "COLD": order[int(len(y) * 0.5):]}
    total = y.sum()
    for band, idx in cuts.items():
        print(f"  {band:<5} {len(idx):>5} leads  {int(y[idx].sum()):>4} activations  rate {y[idx].mean() * 100:>5.1f}%  share of activations {y[idx].sum() / total * 100:>5.1f}%")


# ---- export --------------------------------------------------------------------------------------------------------
BUCKETS = {"employees": ("employee_count", EMPLOYEE_EDGES), "sites": ("num_sites", SITE_EDGES),
           "telecom_spend": ("monthly_telecom_spend_inr", SPEND_EDGES), "products_of_interest": ("products_of_interest_count", PRODUCT_COUNT_EDGES)}


def round_metrics(m: dict) -> dict:
    return {"auc": round(m["auc"], 4), "prAuc": round(m["pr_auc"], 4), "topDecileLift": round(m["top_decile_lift"], 3),
            "top20Capture": round(m["top20_capture"], 4), "baseRate": round(m["base_rate"], 4)}


def lift_table(rows: list[dict]) -> list[dict]:
    total = sum(r["activations"] for r in rows)
    out, running = [], 0
    for r in rows:
        running += r["activations"]
        out.append({"decile": r["decile"], "leads": r["n"], "activations": r["activations"], "predicted": round(r["predicted"], 4),
                    "actual": round(r["actual"], 4), "lift": round(r["lift"], 3), "cumulativeCapture": round(running / total, 4)})
    return out


def export_model(result: Result, raw: pd.DataFrame, y: np.ndarray, is_june: np.ndarray, language: Result, path: Path) -> tuple[LogisticRegression, np.ndarray, dict]:
    features = VARIANTS[EXPORT_VARIANT][1]
    x, names = design_matrix(raw, features)
    model = fit(x, y, EXPORT_CLASS_WEIGHT)  # validated above; the shipped model learns from every lead
    scores = model.predict_proba(x)[:, 1]
    hot, warm = np.quantile(scores, [0.80, 0.50])

    missing = [n for n in names if n not in HUMAN_LABELS]
    if missing:
        raise ValueError(f"Coefficients without a human label: {missing}")

    spec = []
    for f in features:
        entry = {"name": f.name, "kind": "bucket" if f.name in BUCKETS else "category", "levels": list(f.levels), "reference": f.reference}
        if f.name in BUCKETS:
            entry["inputField"], entry["edges"] = BUCKETS[f.name][0], BUCKETS[f.name][1]
        spec.append(entry)

    document = {
        "version": "v1",
        "trainedAt": pd.Timestamp.now(tz="UTC").strftime("%Y-%m-%d"),
        "variant": EXPORT_VARIANT,
        "variantName": VARIANTS[EXPORT_VARIANT][0],
        "classWeight": EXPORT_CLASS_WEIGHT,
        "seed": SEED,
        "trainedOn": {"leads": len(y), "activations": int(y.sum())},
        "liveLeadSource": LIVE_LEAD_SOURCE,
        "features": spec,
        "intercept": float(model.intercept_[0]),
        "coefficients": {n: float(c) for n, c in zip(names, model.coef_[0])},
        "bands": {"HOT": float(hot), "WARM": float(warm)},
        "scorePercentiles": [float(v) for v in np.quantile(scores, np.linspace(0, 1, 101))],
        "noNegativeReason": list(NO_NEGATIVE_REASON),
        "humanLabels": {n: {"en": HUMAN_LABELS[n][0], "hi": HUMAN_LABELS[n][1]} for n in names},
        "metrics": {
            "temporal": {**round_metrics(result.temporal), "aucCi95": [round(result.temporal_ci[0], 4), round(result.temporal_ci[1], 4)],
                         "testLeads": int(is_june.sum()), "testActivations": int(y[is_june].sum())},
            "cv": {**round_metrics(result.cv), "foldAucs": [round(a, 4) for a in result.cv_fold_aucs]},
            "liftTable": lift_table(result.cv_deciles),
            "languageCheck": {"withLanguageJuneAuc": round(language.temporal["auc"], 4), "withLanguageCvAuc": round(language.cv["auc"], 4)},
        },
    }
    path.parent.mkdir(parents=True, exist_ok=True)
    path.write_text(json.dumps(document, ensure_ascii=False, indent=2) + "\n", encoding="utf-8", newline="\n")
    return model, scores, document


def export_parity(leads: pd.DataFrame, scores: np.ndarray, raw: pd.DataFrame, path: Path) -> None:
    """200 leads with the exact probability Python gives them, so the Java scorer can be held to it."""
    picked = np.random.default_rng(SEED).choice(len(leads), size=200, replace=False)
    rows = []
    for i in sorted(picked.tolist()):
        lead = leads.iloc[i]
        rows.append({"leadId": lead["lead_id"], "inputFeatures": {
            "lead_source": lead["lead_source"], "segment": lead["segment"], "city_tier": lead["city_tier"],
            "gstin_status": raw.iloc[i]["gstin_status"], "has_udyam": lead["has_udyam"],
            "employee_count": int(lead["employee_count"]), "num_sites": int(lead["num_sites"]),
            "monthly_telecom_spend_inr": int(lead["monthly_telecom_spend_inr"]), "current_internet": lead["current_internet"],
            "products_of_interest_count": len(lead["products_of_interest"].split(";")), "gst_product_interest": raw.iloc[i]["gst_product_interest"],
        }, "expectedProbability": float(scores[i])})
    path.parent.mkdir(parents=True, exist_ok=True)
    path.write_text(json.dumps(rows, ensure_ascii=False, indent=1) + "\n", encoding="utf-8", newline="\n")


def percent(v: float) -> str:
    return f"{v * 100:.1f}%"


def write_report(result: Result, language: Result, document: dict, leads: pd.DataFrame, raw: pd.DataFrame, y: np.ndarray, scores: np.ndarray, path: Path) -> None:
    t, cv = result.temporal, result.cv
    coefs = sorted(document["coefficients"].items(), key=lambda kv: -abs(kv[1]))
    table = document["metrics"]["liftTable"]
    top_half = table[4]["cumulativeCapture"]
    hot, warm = document["bands"]["HOT"], document["bands"]["WARM"]
    order = rank(result.oof)
    n = len(y)
    bands = {"HOT": order[: int(n * 0.2)], "WARM": order[int(n * 0.2): int(n * 0.5)], "COLD": order[int(n * 0.5):]}

    process = ["Owner had no time for a visit", "Visit not scheduled by sales", "Quote took too long to arrive", "Quote too complex to understand", "Found paperwork too long"]
    price = ["Price too high", "Competitor offered cheaper plan"]
    process_n, price_n = int(leads["drop_reason"].isin(process).sum()), int(leads["drop_reason"].isin(price).sum())
    gst_or = float(np.exp(document["coefficients"]["gstin_status=ACTIVE"]))
    referral_or = float(np.exp(document["coefficients"]["lead_source=Referral"]))
    micro_field = leads[(leads["segment"] == "Micro retail & services") & (leads["lead_source"] == "Field sales")]
    micro_field_cac = micro_field["acquisition_cost_inr"].astype(float).sum() / (micro_field["outcome"] == "Activated").sum()

    def hot_share(scored: np.ndarray, group: str) -> float:
        mask = (raw["language"] == group).to_numpy()
        return float(np.mean(scored[mask] >= np.quantile(scored, 0.8)))

    x_lang, _ = design_matrix(raw, VARIANTS["b+language"][1])
    with_lang = fit(x_lang, y, None).predict_proba(x_lang)[:, 1]

    lines = [
        "# Lead propensity model: report",
        "",
        f"Generated by `ml/train_propensity.py` (seed {SEED}). Do not edit by hand: re-run the script.",
        "",
        "## What it is",
        "",
        f"A logistic regression that estimates how likely a new lead is to activate, trained on {n:,} leads from April-June 2026 ({int(y.sum())} activations). "
        f"Variant ({EXPORT_VARIANT}): only fields the advisor or a sales form can collect. It is linear on purpose: every score can be explained, and Java scores it without Python.",
        "",
        "**What it does not tell you:** it ranks who is likely to activate, not who activates *because* you called. Finding 40% of activations in the top 20% is not 40% more sales.",
        "",
        "## Metrics",
        "",
        "| Test | Leads | Activations | ROC-AUC | PR-AUC | Top-decile lift | Top 20% capture |",
        "|---|---|---|---|---|---|---|",
        f"| Temporal: train April-May, test June | {document['metrics']['temporal']['testLeads']:,} | {document['metrics']['temporal']['testActivations']} | {t['auc']:.3f} (95% CI {result.temporal_ci[0]:.3f}-{result.temporal_ci[1]:.3f}) | {t['pr_auc']:.3f} | {t['top_decile_lift']:.2f} | {percent(t['top20_capture'])} |",
        f"| 5-fold cross-validation | {n:,} | {int(y.sum())} | {cv['auc']:.3f} | {cv['pr_auc']:.3f} | {cv['top_decile_lift']:.2f} | {percent(cv['top20_capture'])} |",
        "| Random, for comparison | | | 0.500 | base rate | 1.00 | 20.0% |",
        "",
        "## Lift and calibration (5-fold cross-validation)",
        "",
        "| Decile | Leads | Activations | Predicted | Actual | Lift | Cumulative capture |",
        "|---|---|---|---|---|---|---|",
    ]
    for r in table:
        lines.append(f"| {r['decile']} | {r['leads']} | {r['activations']} | {percent(r['predicted'])} | {percent(r['actual'])} | {r['lift']:.2f} | {percent(r['cumulativeCapture'])} |")
    lines += [
        "",
        "The top decile is somewhat over-predicted; the ranking is what to rely on.",
        "",
        "## Bands",
        "",
        "| Band | Probability | Leads (out-of-fold) | Activation rate | Share of activations |",
        "|---|---|---|---|---|",
    ]
    limits = {"HOT": f"≥ {percent(hot)}", "WARM": f"≥ {percent(warm)}", "COLD": f"< {percent(warm)}"}
    for band, idx in bands.items():
        lines.append(f"| {band} | {limits[band]} | {len(idx):,} | {percent(float(y[idx].mean()))} | {percent(float(y[idx].sum() / y.sum()))} |")
    lines += ["", "## Top 10 factors", "", "Each is compared with its reference level: Website form, Professional offices, tier 2, no GSTIN, no Udyam, 1-5 staff, 1 site, spend ₹851-1,350, mobile hotspot.", "",
              "| Factor | Coefficient | Effect on the odds |", "|---|---|---|"]
    for name, value in coefs[:10]:
        odds = float(np.exp(value))
        effect = f"{odds:.2f}× as likely" if value > 0 else f"{1 / odds:.2f}× less likely"
        lines.append(f"| {HUMAN_LABELS[name][0]} | {value:+.3f} | {effect} |")
    lines += [
        "",
        "A note on two of these: an inactive GSTIN scores almost as well as an active one because the old process sold to cancelled GSTINs (18 of 167 activated), but our KYC would now reject them, so routing sends them to fix GST first. Size coefficients can be negative only because segment already counts size; those aren't shown to staff as negative reasons.",
        "",
        "## Decisions",
        "",
        f"- **Language left out.** With it, June AUC is {language.temporal['auc']:.3f} rather than {t['auc']:.3f}, within noise, but only {percent(hot_share(with_lang, 'Other'))} of regional-language leads reach HOT, against {percent(hot_share(with_lang, 'Hindi'))} for Hindi. Without it: {percent(hot_share(scores, 'Other'))} and {percent(hot_share(scores, 'Hindi'))}. The lower rate may reflect how the old process served them, and a score shouldn't lock that in.",
        f"- **Live leads count as \"{LIVE_LEAD_SOURCE}\".** It's what the data supports, and it means live chats rarely reach HOT. Fields the advisor doesn't collect fall back to the reference level.",
        "- **Leakage:** anything known only after the sales process is banned, and the script fails if it's used. See `LEAKY_COLUMNS` in the script.",
        "",
        "## Slide-ready sentences",
        "",
        f"1. Calling the top 20% of leads by score finds {percent(cv['top20_capture'])} of all activations, about twice what random calling finds ({percent(t['top20_capture'])} on unseen June leads).",
        f"2. The bottom half of leads by score holds only {percent(1 - top_half)} of activations, so they can start digital-first and leave sales time for the rest.",
        f"3. An active GSTIN makes a lead {gst_or:.1f}× as likely to activate as having none, and a referral {referral_or:.1f}× as likely as a website form, all else being equal.",
        f"4. {process_n:,} leads were lost to process problems such as no visit or a slow quote, about twice the {price_n} lost on price.",
        f"5. Field visits cost about ₹{micro_field_cac:,.0f} per Micro activation against a bundle of about ₹2,500/month, so Micro should be digital-first.",
        "",
        "Sentences 1-3 describe who is likely to activate, not extra sales caused by calling them.",
        "",
    ]
    path.write_text("\n".join(lines), encoding="utf-8", newline="\n")


def main() -> None:
    parser = argparse.ArgumentParser(description=__doc__, formatter_class=argparse.RawDescriptionHelpFormatter)
    parser.add_argument("--add-feature", help="Add a raw column as a feature, to see the leakage guard fail")
    args = parser.parse_args()

    features = list(FEATURES)
    if args.add_feature:
        features.append(Feature(args.add_feature, (args.add_feature,), (), ""))
    assert_no_leakage(features)

    leads = read_csv("smb_leads.csv")
    y = (leads["outcome"] == "Activated").astype(int).to_numpy()
    raw = derive_features(leads)
    assert_matrix_is_sane(raw, y, FEATURES)
    print("Leakage guard passed: no forbidden column feeds the model.")

    is_june = leads["created_date"].str.startswith("2026-06").to_numpy()
    print(f"Leads {len(y)}, activations {y.sum()} ({y.mean() * 100:.1f}%). Train Apr-May {(~is_june).sum()} ({y[~is_june].sum()} activations), test June {is_june.sum()} ({y[is_june].sum()} activations).")

    results = [run_config(v, w, raw, y, is_june) for v in COMPARED for w in (None, "balanced")]
    print_summary(results)

    for r in results:
        if r.class_weight is None:
            print_deciles(f"=== LIFT + CALIBRATION, variant ({r.variant}), unweighted, June holdout ===", r.temporal_deciles)
            print_deciles(f"=== LIFT + CALIBRATION, variant ({r.variant}), unweighted, 5-fold CV ===", r.cv_deciles)
    balanced = next(r for r in results if r.variant == "b" and r.class_weight == "balanced")
    print_deciles("=== CALIBRATION with class_weight=balanced, variant (b), 5-fold CV (predicted is inflated by design) ===", balanced.cv_deciles)

    for variant in COMPARED:
        model = print_coefficients(variant, raw, y)
        x, _ = design_matrix(raw, VARIANTS[variant][1])
        print_bands(next(r for r in results if r.variant == variant and r.class_weight is None), y, model.predict_proba(x)[:, 1])

    chosen = next(r for r in results if r.variant == EXPORT_VARIANT and r.class_weight == EXPORT_CLASS_WEIGHT)
    language = run_config("b+language", EXPORT_CLASS_WEIGHT, raw, y, is_june)
    print(f"\nLanguage check: June AUC {chosen.temporal['auc']:.3f} without, {language.temporal['auc']:.3f} with. CV {chosen.cv['auc']:.3f} without, {language.cv['auc']:.3f} with.")

    _, scores, document = export_model(chosen, raw, y, is_june, language, ROOT / "backend/src/main/resources/ml/propensity-model-v1.json")
    export_parity(leads, scores, raw, ROOT / "backend/src/test/resources/ml/propensity-parity.json")
    write_report(chosen, language, document, leads, raw, y, scores, Path(__file__).with_name("REPORT.md"))
    print(f"\nExported variant ({EXPORT_VARIANT}): model JSON, parity fixture (200 leads) and ml/REPORT.md.")


if __name__ == "__main__":
    try:
        main()
    except AssertionError as e:
        print(f"\nFAILED: {e}", file=sys.stderr)
        sys.exit(1)
