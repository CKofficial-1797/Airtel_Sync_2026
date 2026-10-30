package com.udyam.kyc.checks;

import static com.udyam.kyc.DocType.ADDRESS_PROOF;
import static com.udyam.kyc.DocType.GST_CERTIFICATE;
import static com.udyam.kyc.DocType.SIGNATORY_ID;
import static com.udyam.kyc.DocType.UDYAM_CERTIFICATE;

import com.udyam.common.config.AppProperties;
import com.udyam.common.privacy.Masking;
import com.udyam.kyc.DocType;
import com.udyam.kyc.KycDocument;
import com.udyam.kyc.extract.ExtractResult;
import com.udyam.registry.GstinDetails;
import com.udyam.registry.GstinVerification;
import com.udyam.registry.GstinVerificationService;
import com.udyam.registry.UdyamDetails;
import com.udyam.registry.UdyamLookupService;
import com.udyam.registry.validation.GstStateCodes;
import com.udyam.registry.validation.GstinValidator;
import com.udyam.registry.validation.UdyamValidationResult;
import java.time.LocalDate;
import java.time.format.DateTimeParseException;
import java.time.temporal.ChronoUnit;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;
import org.springframework.stereotype.Service;

/**
 * The 17 KYC checks (C01..C17), pure and deterministic: documents + registries + reference date in, report out.
 * Each check is one small method below. A check whose input document is missing or unreadable is SKIPPED
 * (C01/C02/C03 already report the document problem, so the customer gets one clear fix instead of a cascade).
 */
@Service
public class KycValidationService {

    public static final int MAX_ADDRESS_PROOF_AGE_DAYS = 90;

    private static final Text SUPPORT = Text.of(
            "Please contact Accel Business support with your original documents.",
            "कृपया अपने मूल दस्तावेज़ों के साथ Accel Business सहायता से संपर्क करें।");
    private static final Text NEEDS_GST = Text.of("Skipped: needs a readable GST certificate.",
            "छोड़ा गया: पढ़ने योग्य GST प्रमाणपत्र चाहिए।");
    private static final Text NEEDS_GSTIN = Text.of("Skipped: needs a valid GSTIN (see C04).",
            "छोड़ा गया: मान्य GSTIN चाहिए (C04 देखें)।");
    private static final Text NEEDS_REGISTRY_GSTIN = Text.of("Skipped: needs a GSTIN found in the GST registry (see C05).",
            "छोड़ा गया: GST रजिस्ट्री में मिला GSTIN चाहिए (C05 देखें)।");
    private static final Text NEEDS_UDYAM = Text.of("Skipped: needs a readable Udyam certificate.",
            "छोड़ा गया: पढ़ने योग्य उद्यम प्रमाणपत्र चाहिए।");
    private static final Text NEEDS_UDYAM_NUMBER = Text.of("Skipped: needs a valid Udyam number (see C08).",
            "छोड़ा गया: मान्य उद्यम नंबर चाहिए (C08 देखें)।");
    private static final Text NEEDS_UDYAM_REGISTRY = Text.of("Skipped: the Udyam number is not in the registry (see C09).",
            "छोड़ा गया: उद्यम नंबर रजिस्ट्री में नहीं है (C09 देखें)।");
    private static final Text NEEDS_GST_AND_ID = Text.of("Skipped: needs a readable GST certificate and signatory ID.",
            "छोड़ा गया: पढ़ने योग्य GST प्रमाणपत्र और हस्ताक्षरकर्ता पहचान पत्र चाहिए।");
    private static final Text NEEDS_GST_AND_BILL = Text.of("Skipped: needs a readable GST certificate and address proof.",
            "छोड़ा गया: पढ़ने योग्य GST प्रमाणपत्र और पते का प्रमाण चाहिए।");
    private static final Text NEEDS_BILL = Text.of("Skipped: needs a readable address proof.",
            "छोड़ा गया: पढ़ने योग्य पते का प्रमाण चाहिए।");
    private static final Text NEEDS_ID = Text.of("Skipped: needs a readable signatory ID.",
            "छोड़ा गया: पढ़ने योग्य हस्ताक्षरकर्ता पहचान पत्र चाहिए।");
    private static final Text REUPLOAD_CLEAR = Text.of("Upload a clear photo of the full document.",
            "पूरे दस्तावेज़ की साफ़ फ़ोटो अपलोड करें।");

    private final GstinVerificationService gstins;
    private final UdyamLookupService udyams;
    private final LocalDate today;

    public KycValidationService(GstinVerificationService gstins, UdyamLookupService udyams, AppProperties app) {
        this.gstins = gstins;
        this.udyams = udyams;
        this.today = app.referenceDate();
    }

    public ValidationReport validate(KycInput in) {
        Facts f = facts(in);
        List<CheckResult> checks = List.of(
                c01AllDocumentsPresent(in), c02ImagesReadable(in), c03DocTypesMatch(in),
                c04GstinValid(f), c05GstinInRegistry(f), c06GstinActive(f), c07GstDocMatchesRegistry(f),
                c08UdyamNumberValid(f), c09UdyamInRegistry(f), c10BusinessNameConsistent(f),
                c11ProprietorIsSignatory(f), c12AddressConsistent(f), c13StateConsistent(f),
                c14AddressProofRecent(f), c15IdValid(f), c16UdyamDetailsSoft(f), c17OrderProfileMatch(in, f));
        return ValidationReport.of(checks);
    }

    /** Values several checks share, computed once. Null = not available. */
    private record Facts(KycDocument gst, KycDocument udyam, KycDocument id, KycDocument bill,
                         GstinVerification gstin, boolean gstinUsable, GstinDetails gstRegistry,
                         UdyamValidationResult udyamNumber, UdyamDetails udyamRegistry) {
    }

    private Facts facts(KycInput in) {
        KycDocument gst = in.usable(GST_CERTIFICATE);
        KycDocument udyam = in.usable(UDYAM_CERTIFICATE);

        GstinVerification gv = gst == null || gst.text("gstin") == null ? null : gstins.verify(gst.text("gstin"));
        boolean gstinUsable = gv != null && (gv.correction() != null || (gv.formatValid() && gv.checksumValid()));
        GstinDetails registry = gstinUsable && gv.found() ? gstins.find(gv.effectiveGstin()).orElse(null) : null;

        UdyamValidationResult uv = udyam == null ? null : udyams.validate(udyam.text("udyamNumber"));
        UdyamDetails udyamRegistry = uv != null && uv.formatValid() ? udyams.find(uv.normalized()).orElse(null) : null;

        return new Facts(gst, udyam, in.usable(SIGNATORY_ID), in.usable(ADDRESS_PROOF), gv, gstinUsable, registry,
                uv, udyamRegistry);
    }

    // ---- C01-C03: the documents themselves --------------------------------------------------------------

    /** An OK document without fields counts as missing: it can never prove anything, so it must never verify. */
    CheckResult c01AllDocumentsPresent(KycInput in) {
        List<DocType> missing = new ArrayList<>();
        for (DocType t : DocType.values()) {
            KycDocument d = in.doc(t);
            if (d == null || (ExtractResult.OK.equals(d.status()) && !d.usable())) {
                missing.add(t);
            }
        }
        if (missing.isEmpty()) {
            return CheckResult.pass(CheckCode.C01, Text.of("All 4 documents are uploaded.",
                    "सभी 4 दस्तावेज़ अपलोड हो गए हैं।"), ev("documents", DocType.values().length));
        }
        Text names = docNames(missing);
        return CheckResult.fail(CheckCode.C01,
                Text.of("Missing document(s): " + names.en() + ".", "ये दस्तावेज़ नहीं मिले: " + names.hi() + "।"),
                Text.of("Upload the missing document(s): " + names.en() + ".",
                        "बचे हुए दस्तावेज़ अपलोड करें: " + names.hi() + "।"),
                ev("missing", missing.stream().map(Enum::name).toList()));
    }

    CheckResult c02ImagesReadable(KycInput in) {
        List<DocType> blurry = docsWithStatus(in, ExtractResult.UNREADABLE);
        if (blurry.isEmpty()) {
            return CheckResult.pass(CheckCode.C02, Text.of("All photos are clear enough to read.",
                    "सभी फ़ोटो पढ़ने लायक साफ़ हैं।"), ev());
        }
        Text names = docNames(blurry);
        Map<String, Object> scores = new LinkedHashMap<>();
        blurry.forEach(t -> scores.put(t.name(), in.doc(t).blurScore()));
        return CheckResult.fail(CheckCode.C02,
                Text.of("This photo is too blurry to read: " + names.en() + ".",
                        "यह फ़ोटो बहुत धुंधली है, पढ़ी नहीं जा सकती: " + names.hi() + "।"),
                Text.of("Photo is blurry. Retake it in good light, flat on a table, and upload it again.",
                        "फ़ोटो धुंधली है। अच्छी रोशनी में, दस्तावेज़ को मेज़ पर सीधा रखकर दोबारा फ़ोटो लें और अपलोड करें।"),
                ev("unreadable", blurry.stream().map(Enum::name).toList(), "blurScores", scores));
    }

    CheckResult c03DocTypesMatch(KycInput in) {
        List<DocType> wrong = docsWithStatus(in, ExtractResult.WRONG_DOC_TYPE);
        if (wrong.isEmpty()) {
            return CheckResult.pass(CheckCode.C03, Text.of("Each document is in the right slot.",
                    "हर दस्तावेज़ सही जगह पर अपलोड है।"), ev());
        }
        Text names = docNames(wrong);
        Map<String, Object> detected = new LinkedHashMap<>();
        wrong.forEach(t -> detected.put(t.name(), in.doc(t).detectedDocType()));
        return CheckResult.fail(CheckCode.C03,
                Text.of("A different document was uploaded in the slot for: " + names.en() + ".",
                        "इसकी जगह कोई दूसरा दस्तावेज़ अपलोड हुआ है: " + names.hi() + "।"),
                Text.of("Upload the correct document in each slot.", "हर जगह पर सही दस्तावेज़ अपलोड करें।"),
                ev("detected", detected));
    }

    // ---- C04-C07: GSTIN and the GST registry -----------------------------------------------------------

    CheckResult c04GstinValid(Facts f) {
        if (f.gst() == null) {
            return CheckResult.skipped(CheckCode.C04, NEEDS_GST);
        }
        GstinVerification gv = f.gstin();
        if (gv == null) {
            return CheckResult.fail(CheckCode.C04, true,
                    Text.of("The GSTIN could not be read on the GST certificate.",
                            "GST प्रमाणपत्र पर GSTIN पढ़ा नहीं जा सका।"), REUPLOAD_CLEAR, ev());
        }
        String read = Masking.gstin(gv.normalized());
        if (gv.correction() != null) {
            String fixed = Masking.gstin(gv.correction().correctedGstin());
            return CheckResult.pass(CheckCode.C04,
                    Text.of("GSTIN read as " + read + ", corrected to " + fixed
                                    + " (OCR character fix confirmed by the GST registry).",
                            "GSTIN " + read + " पढ़ा गया, " + fixed
                                    + " में सुधारा गया (OCR अक्षर सुधार, GST रजिस्ट्री से पुष्टि)।"),
                    ev("readAs", read, "correctedTo", fixed, "substitutions", gv.correction().substitutions().size()));
        }
        if (f.gstinUsable()) {
            return CheckResult.pass(CheckCode.C04, Text.of("GSTIN " + read + " has a valid format and checksum.",
                    "GSTIN " + read + " का फ़ॉर्मेट और चेकसम सही है।"), ev("gstin", read));
        }
        return CheckResult.fail(CheckCode.C04,
                Text.of("GSTIN " + read + " fails the format/checksum test and no safe OCR correction exists."
                                + " The document may have been altered.",
                        "GSTIN " + read + " फ़ॉर्मेट/चेकसम जाँच में विफल है और कोई सुरक्षित OCR सुधार नहीं है।"
                                + " दस्तावेज़ में बदलाव किया गया हो सकता है।"),
                SUPPORT, ev("gstin", read, "formatValid", gv.formatValid(), "checksumValid", gv.checksumValid()));
    }

    CheckResult c05GstinInRegistry(Facts f) {
        if (f.gst() == null) {
            return CheckResult.skipped(CheckCode.C05, NEEDS_GST);
        }
        if (!f.gstinUsable()) {
            return CheckResult.skipped(CheckCode.C05, NEEDS_GSTIN);
        }
        String gstin = Masking.gstin(f.gstRegistry() != null ? f.gstRegistry().gstin() : f.gstin().normalized());
        if (f.gstRegistry() != null) {
            return CheckResult.pass(CheckCode.C05, Text.of("GSTIN " + gstin + " is in the GST registry.",
                    "GSTIN " + gstin + " GST रजिस्ट्री में मौजूद है।"), ev("gstin", gstin));
        }
        return CheckResult.fail(CheckCode.C05, Text.of("GSTIN " + gstin + " is not in the GST registry.",
                "GSTIN " + gstin + " GST रजिस्ट्री में नहीं मिला।"), SUPPORT, ev("gstin", gstin));
    }

    CheckResult c06GstinActive(Facts f) {
        if (f.gstRegistry() == null) {
            return CheckResult.skipped(CheckCode.C06, f.gst() == null ? NEEDS_GST : NEEDS_REGISTRY_GSTIN);
        }
        String status = f.gstRegistry().status();
        if (f.gstRegistry().active()) {
            return CheckResult.pass(CheckCode.C06, Text.of("GST registration is Active.",
                    "GST पंजीकरण सक्रिय (Active) है।"), ev("registryStatus", status));
        }
        return CheckResult.fail(CheckCode.C06,
                Text.of("GST registration status is " + status + "; only Active registrations are accepted.",
                        "GST पंजीकरण की स्थिति " + status + " है; केवल सक्रिय (Active) पंजीकरण स्वीकार है।"),
                Text.of("Reactivate your GST registration on the GST portal, then apply again.",
                        "GST पोर्टल पर अपना GST पंजीकरण फिर से सक्रिय करें, फिर दोबारा आवेदन करें।"),
                ev("registryStatus", status));
    }

    CheckResult c07GstDocMatchesRegistry(Facts f) {
        if (f.gstRegistry() == null) {
            return CheckResult.skipped(CheckCode.C07, f.gst() == null ? NEEDS_GST : NEEDS_REGISTRY_GSTIN);
        }
        Map<String, Object> mismatches = new LinkedHashMap<>();
        compareName(mismatches, "legalName", f.gst().text("legalName"), f.gstRegistry().legalName());
        compareName(mismatches, "tradeName", f.gst().text("tradeName"), f.gstRegistry().tradeName());
        if (mismatches.isEmpty()) {
            return CheckResult.pass(CheckCode.C07,
                    Text.of("Legal and trade names on the certificate match the GST registry.",
                            "प्रमाणपत्र पर कानूनी और व्यापारिक नाम GST रजिस्ट्री से मेल खाते हैं।"), ev());
        }
        Text fields = Text.of(String.join(", ", mismatches.keySet()).replace("legalName", "legal name")
                        .replace("tradeName", "trade name"),
                String.join(", ", mismatches.keySet()).replace("legalName", "कानूनी नाम")
                        .replace("tradeName", "व्यापारिक नाम"));
        return CheckResult.fail(CheckCode.C07,
                Text.of("The " + fields.en() + " on the GST certificate does not match the GST registry.",
                        "GST प्रमाणपत्र पर " + fields.hi() + " GST रजिस्ट्री से मेल नहीं खाता।"),
                SUPPORT, ev("mismatches", mismatches));
    }

    // ---- C08-C09, C16: Udyam ---------------------------------------------------------------------------

    CheckResult c08UdyamNumberValid(Facts f) {
        if (f.udyam() == null) {
            return CheckResult.skipped(CheckCode.C08, NEEDS_UDYAM);
        }
        Text hint = Text.of("Download a fresh Udyam certificate from udyamregistration.gov.in and upload it.",
                "udyamregistration.gov.in से नया उद्यम प्रमाणपत्र डाउनलोड करके अपलोड करें।");
        UdyamValidationResult uv = f.udyamNumber();
        if (uv.normalized().isEmpty()) {
            return CheckResult.fail(CheckCode.C08,
                    Text.of("The Udyam registration number is blank on the certificate.",
                            "उद्यम प्रमाणपत्र पर पंजीकरण नंबर खाली है।"), hint, ev("udyamNumber", null));
        }
        String masked = Masking.udyam(uv.normalized());
        if (!uv.formatValid()) {
            return CheckResult.fail(CheckCode.C08,
                    Text.of("The Udyam number on the certificate does not look like UDYAM-XX-00-1234567.",
                            "प्रमाणपत्र पर उद्यम नंबर UDYAM-XX-00-1234567 जैसा नहीं है।"), hint,
                    ev("udyamNumber", masked));
        }
        return CheckResult.pass(CheckCode.C08, Text.of("Udyam number " + masked + " has a valid format.",
                "उद्यम नंबर " + masked + " का फ़ॉर्मेट सही है।"), ev("udyamNumber", masked));
    }

    CheckResult c09UdyamInRegistry(Facts f) {
        if (f.udyam() == null) {
            return CheckResult.skipped(CheckCode.C09, NEEDS_UDYAM);
        }
        if (!f.udyamNumber().formatValid()) {
            return CheckResult.skipped(CheckCode.C09, NEEDS_UDYAM_NUMBER);
        }
        String masked = Masking.udyam(f.udyamNumber().normalized());
        if (f.udyamRegistry() == null) {
            return CheckResult.fail(CheckCode.C09, Text.of("Udyam number " + masked + " is not in the Udyam registry.",
                    "उद्यम नंबर " + masked + " उद्यम रजिस्ट्री में नहीं मिला।"), SUPPORT, ev("udyamNumber", masked));
        }
        String onDoc = f.udyam().text("enterpriseName");
        String inRegistry = f.udyamRegistry().enterpriseName();
        if (!NameMatcher.matches(onDoc, inRegistry)) {
            return CheckResult.fail(CheckCode.C09,
                    Text.of("Enterprise name '" + onDoc + "' does not match the Udyam registry ('" + inRegistry + "').",
                            "उद्यम का नाम '" + onDoc + "' उद्यम रजिस्ट्री ('" + inRegistry + "') से मेल नहीं खाता।"),
                    SUPPORT, ev("udyamNumber", masked, "onDocument", onDoc, "inRegistry", inRegistry));
        }
        return CheckResult.pass(CheckCode.C09,
                Text.of("Udyam number is in the Udyam registry and the enterprise name matches.",
                        "उद्यम नंबर उद्यम रजिस्ट्री में है और उद्यम का नाम मेल खाता है।"),
                ev("udyamNumber", masked, "enterpriseName", inRegistry));
    }

    /** Registration date and major activity differ from the registry for most specimens: WARN only, never blocking. */
    CheckResult c16UdyamDetailsSoft(Facts f) {
        if (f.udyam() == null) {
            return CheckResult.skipped(CheckCode.C16, NEEDS_UDYAM);
        }
        if (f.udyamRegistry() == null) {
            return CheckResult.skipped(CheckCode.C16, NEEDS_UDYAM_REGISTRY);
        }
        UdyamDetails r = f.udyamRegistry();
        List<String> en = new ArrayList<>();
        List<String> hi = new ArrayList<>();
        Map<String, Object> diffs = new LinkedHashMap<>();
        String docDate = f.udyam().text("dateOfRegistration");
        String regDate = r.dateOfRegistration() == null ? null : r.dateOfRegistration().toString();
        if (!same(docDate, regDate)) {
            en.add("registration date");
            hi.add("पंजीकरण तिथि");
            diffs.put("dateOfRegistration", ev("onDocument", docDate, "inRegistry", regDate));
        }
        String docActivity = f.udyam().text("majorActivity");
        if (!same(docActivity, r.majorActivity())) {
            en.add("major activity");
            hi.add("मुख्य गतिविधि");
            diffs.put("majorActivity", ev("onDocument", docActivity, "inRegistry", r.majorActivity()));
        }
        if (diffs.isEmpty()) {
            return CheckResult.pass(CheckCode.C16, Text.of("Udyam certificate details match the Udyam registry.",
                    "उद्यम प्रमाणपत्र का विवरण उद्यम रजिस्ट्री से मेल खाता है।"), ev());
        }
        return CheckResult.warn(CheckCode.C16,
                Text.of("Udyam certificate " + String.join(" and ", en)
                                + (en.size() == 1 ? " differs" : " differ") + " from the registry (known registry lag; not blocking).",
                        "उद्यम प्रमाणपत्र की " + String.join(" और ", hi)
                                + " रजिस्ट्री से अलग है (रजिस्ट्री में ज्ञात देरी; रुकावट नहीं)।"),
                null, diffs);
    }

    // ---- C10-C13: consistency across documents ---------------------------------------------------------

    CheckResult c10BusinessNameConsistent(Facts f) {
        Map<String, String> names = new LinkedHashMap<>();
        Map<String, Text> labels = new LinkedHashMap<>();
        if (f.gst() != null) {
            put(names, labels, "gstTradeName", f.gst().text("tradeName"),
                    Text.of("GST trade name", "GST व्यापारिक नाम"));
            String legal = f.gst().text("legalName");
            String business = NameMatcher.splitProprietor(legal).map(NameMatcher.ProprietorName::business)
                    .orElse(legal);
            put(names, labels, "gstLegalName", business, Text.of("GST legal name", "GST कानूनी नाम"));
        }
        if (f.udyam() != null) {
            put(names, labels, "udyamEnterpriseName", f.udyam().text("enterpriseName"),
                    Text.of("Udyam certificate", "उद्यम प्रमाणपत्र"));
        }
        if (f.bill() != null) {
            put(names, labels, "billBilledTo", f.bill().text("billedTo"), Text.of("address proof", "पते का प्रमाण"));
        }
        if (f.id() != null) {
            put(names, labels, "idForBusiness", f.id().text("forBusiness"), Text.of("signatory ID", "पहचान पत्र"));
        }
        if (names.size() < 2) {
            return CheckResult.skipped(CheckCode.C10, Text.of(
                    "Skipped: needs at least two readable documents with the business name.",
                    "छोड़ा गया: व्यवसाय के नाम वाले कम से कम दो पढ़ने योग्य दस्तावेज़ चाहिए।"));
        }
        List<String> keys = List.copyOf(names.keySet());
        for (int i = 0; i < keys.size(); i++) {
            for (int j = i + 1; j < keys.size(); j++) {
                String a = names.get(keys.get(i));
                String b = names.get(keys.get(j));
                if (!NameMatcher.matches(a, b)) {
                    Text la = labels.get(keys.get(i));
                    Text lb = labels.get(keys.get(j));
                    return CheckResult.fail(CheckCode.C10,
                            Text.of("The business name differs: the " + la.en() + " says '" + a + "' but the "
                                            + lb.en() + " says '" + b + "'.",
                                    "व्यवसाय का नाम अलग है: " + la.hi() + " में '" + a + "' लिखा है, पर " + lb.hi()
                                            + " में '" + b + "'।"),
                            SUPPORT, ev("names", names, "mismatch", List.of(keys.get(i), keys.get(j))));
                }
            }
        }
        return CheckResult.pass(CheckCode.C10, Text.of("The business name is the same on all documents.",
                "सभी दस्तावेज़ों पर व्यवसाय का नाम एक जैसा है।"), ev("names", names));
    }

    CheckResult c11ProprietorIsSignatory(Facts f) {
        if (f.gst() == null || f.id() == null) {
            return CheckResult.skipped(CheckCode.C11, NEEDS_GST_AND_ID);
        }
        String legal = f.gst().text("legalName");
        String constitution = Optional.ofNullable(f.gst().text("constitution")).orElse("");
        Optional<NameMatcher.ProprietorName> prop = NameMatcher.splitProprietor(legal);
        boolean proprietorship = constitution.toLowerCase(Locale.ROOT).contains("proprietor") || prop.isPresent();
        String signatory = f.id().text("name");
        if (!proprietorship) {
            String kind = constitution.isEmpty() ? "Company / LLP" : constitution;
            return CheckResult.warn(CheckCode.C11,
                    Text.of(kind + ": the signatory's authority to sign for the business cannot be proven from these"
                                    + " documents.",
                            kind + ": इन दस्तावेज़ों से यह साबित नहीं होता कि हस्ताक्षरकर्ता व्यवसाय की ओर से हस्ताक्षर"
                                    + " कर सकते हैं।"),
                    Text.of("An authorisation letter / board resolution may be required at installation.",
                            "इंस्टॉलेशन के समय प्राधिकरण पत्र / बोर्ड प्रस्ताव माँगा जा सकता है।"),
                    ev("constitution", constitution, "signatory", signatory,
                            "designation", f.id().text("designation")));
        }
        String proprietor = prop.map(NameMatcher.ProprietorName::proprietor).orElse(legal);
        if (NameMatcher.matches(proprietor, signatory)) {
            return CheckResult.pass(CheckCode.C11,
                    Text.of("The proprietor named on the GST certificate is the signatory.",
                            "GST प्रमाणपत्र पर लिखे मालिक ही हस्ताक्षरकर्ता हैं।"),
                    ev("proprietor", proprietor, "signatory", signatory));
        }
        return CheckResult.fail(CheckCode.C11,
                Text.of("The signatory '" + signatory + "' is not the proprietor '" + proprietor
                                + "' named on the GST certificate.",
                        "हस्ताक्षरकर्ता '" + signatory + "' GST प्रमाणपत्र पर लिखे मालिक '" + proprietor + "' नहीं हैं।"),
                Text.of("For a proprietorship only the proprietor can sign. " + SUPPORT.en(),
                        "प्रोप्राइटरशिप में केवल मालिक ही हस्ताक्षर कर सकते हैं। " + SUPPORT.hi()),
                ev("proprietor", proprietor, "signatory", signatory));
    }

    CheckResult c12AddressConsistent(Facts f) {
        if (f.gst() == null || f.bill() == null) {
            return CheckResult.skipped(CheckCode.C12, NEEDS_GST_AND_BILL);
        }
        AddressMatcher.AddressMatch m = AddressMatcher.compare(f.gst().text("principalAddress"),
                f.bill().text("supplyAddress"));
        Map<String, Object> evidence = ev("gstPincode", m.pincodeA(), "billPincode", m.pincodeB(),
                "tokenOverlap", m.tokenOverlap());
        if (m.matches()) {
            return CheckResult.pass(CheckCode.C12, Text.of("The GST address matches the address proof.",
                    "GST पता पते के प्रमाण से मेल खाता है।"), evidence);
        }
        int pct = (int) Math.round(m.tokenOverlap() * 100);
        return CheckResult.fail(CheckCode.C12,
                Text.of("The GST address and the bill's supply address differ (pincode " + m.pincodeA() + " vs "
                                + m.pincodeB() + ", " + pct + "% of words match).",
                        "GST पता और बिल का आपूर्ति पता अलग हैं (पिनकोड " + m.pincodeA() + " बनाम " + m.pincodeB()
                                + ", " + pct + "% शब्द मेल खाते हैं)।"),
                Text.of("The address proof must be for the GST principal place of business. " + SUPPORT.en(),
                        "पते का प्रमाण GST के मुख्य व्यवसाय स्थान का ही होना चाहिए। " + SUPPORT.hi()),
                evidence);
    }

    /** Blocking: GSTIN state code vs the GST address state. Soft: vs the Udyam certificate state. */
    CheckResult c13StateConsistent(Facts f) {
        if (f.gst() == null) {
            return CheckResult.skipped(CheckCode.C13, NEEDS_GST);
        }
        String gstin = f.gstRegistry() != null ? f.gstRegistry().gstin()
                : GstinValidator.normalize(f.gst().text("gstin"));
        String code = gstin.length() >= 2 ? gstin.substring(0, 2) : null;
        String gstState = GstStateCodes.name(code);
        String addressState = AddressMatcher.state(f.gst().text("principalAddress")).orElse(null);
        Map<String, Object> evidence = ev("stateCode", code, "gstinState", gstState, "addressState", addressState);
        if (gstState == null || addressState == null) {
            return CheckResult.warn(CheckCode.C13, Text.of("The state could not be read from the GSTIN or the GST address.",
                    "GSTIN या GST पते से राज्य पढ़ा नहीं जा सका।"), null, evidence);
        }
        if (!same(gstState, addressState)) {
            return CheckResult.fail(CheckCode.C13,
                    Text.of("GSTIN state code " + code + " (" + gstState + ") does not match the address state "
                                    + addressState + ".",
                            "GSTIN राज्य कोड " + code + " (" + gstState + ") पते के राज्य " + addressState
                                    + " से मेल नहीं खाता।"),
                    SUPPORT, evidence);
        }
        String udyamState = f.udyam() == null ? null : f.udyam().text("state");
        evidence.put("udyamState", udyamState);
        if (udyamState != null && !same(gstState, udyamState)) {
            return CheckResult.warn(CheckCode.C13,
                    Text.of("GSTIN state " + gstState + " differs from the Udyam certificate state " + udyamState + ".",
                            "GSTIN का राज्य " + gstState + " उद्यम प्रमाणपत्र के राज्य " + udyamState + " से अलग है।"),
                    null, evidence);
        }
        return CheckResult.pass(CheckCode.C13, Text.of("The GSTIN state matches the address state.",
                "GSTIN का राज्य पते के राज्य से मेल खाता है।"), evidence);
    }

    // ---- C14-C15: dates --------------------------------------------------------------------------------

    CheckResult c14AddressProofRecent(Facts f) {
        if (f.bill() == null) {
            return CheckResult.skipped(CheckCode.C14, NEEDS_BILL);
        }
        Text hint = Text.of("Upload a utility bill from the last 90 days.",
                "पिछले 90 दिनों का बिजली/उपयोगिता बिल अपलोड करें।");
        LocalDate billDate = date(f.bill().text("billDate"));
        if (billDate == null) {
            return CheckResult.fail(CheckCode.C14, Text.of("The bill date could not be read.",
                    "बिल की तारीख पढ़ी नहीं जा सकी।"), REUPLOAD_CLEAR, ev("billDate", f.bill().text("billDate")));
        }
        long age = ChronoUnit.DAYS.between(billDate, today);
        Map<String, Object> evidence = ev("billDate", billDate.toString(), "ageDays", age,
                "maxAgeDays", MAX_ADDRESS_PROOF_AGE_DAYS, "referenceDate", today.toString());
        if (age < 0) {
            return CheckResult.fail(CheckCode.C14, Text.of("The address proof date " + billDate + " is in the future.",
                    "पते के प्रमाण की तारीख " + billDate + " भविष्य की है।"), hint, evidence);
        }
        if (age > MAX_ADDRESS_PROOF_AGE_DAYS) {
            return CheckResult.fail(CheckCode.C14,
                    Text.of("The address proof is dated " + billDate + ", " + age
                                    + " days old; it must be at most 90 days old.",
                            "पते का प्रमाण " + billDate + " का है, यानी " + age
                                    + " दिन पुराना; यह 90 दिन से ज़्यादा पुराना नहीं होना चाहिए।"),
                    hint, evidence);
        }
        return CheckResult.pass(CheckCode.C14, Text.of("The address proof is dated " + billDate + " (" + age
                + " days old).", "पते का प्रमाण " + billDate + " का है (" + age + " दिन पुराना)।"), evidence);
    }

    CheckResult c15IdValid(Facts f) {
        if (f.id() == null) {
            return CheckResult.skipped(CheckCode.C15, NEEDS_ID);
        }
        Text hint = Text.of("Upload a valid (unexpired) ID of the signatory.",
                "हस्ताक्षरकर्ता का मान्य (समाप्त न हुआ) पहचान पत्र अपलोड करें।");
        LocalDate validTill = date(f.id().text("validTill"));
        if (validTill == null) {
            return CheckResult.fail(CheckCode.C15, Text.of("The ID's validity date could not be read.",
                    "पहचान पत्र की वैधता तिथि पढ़ी नहीं जा सकी।"), hint, ev("validTill", f.id().text("validTill")));
        }
        Map<String, Object> evidence = ev("validTill", validTill.toString(), "referenceDate", today.toString());
        if (validTill.isBefore(today)) {
            return CheckResult.fail(CheckCode.C15, Text.of("The signatory ID expired on " + validTill + ".",
                    "हस्ताक्षरकर्ता पहचान पत्र " + validTill + " को समाप्त हो गया।"), hint, evidence);
        }
        return CheckResult.pass(CheckCode.C15, Text.of("The signatory ID is valid till " + validTill + ".",
                "हस्ताक्षरकर्ता पहचान पत्र " + validTill + " तक मान्य है।"), evidence);
    }

    // ---- C17: the order --------------------------------------------------------------------------------

    CheckResult c17OrderProfileMatch(KycInput in, Facts f) {
        if (!in.hasOrderProfile()) {
            return CheckResult.skipped(CheckCode.C17, Text.of("Skipped: this order has no advisor profile.",
                    "छोड़ा गया: इस ऑर्डर में सलाहकार प्रोफ़ाइल नहीं है।"));
        }
        boolean needsGstin = in.orderGstin() != null || !in.gstRequiredProducts().isEmpty();
        if (needsGstin && f.gst() == null) {
            return CheckResult.skipped(CheckCode.C17, NEEDS_GST);
        }
        String kycGstin = f.gstRegistry() == null ? null : f.gstRegistry().gstin();
        if (in.orderGstin() != null && kycGstin != null
                && !GstinValidator.normalize(in.orderGstin()).equals(kycGstin)) {
            String a = Masking.gstin(GstinValidator.normalize(in.orderGstin()));
            String b = Masking.gstin(kycGstin);
            return CheckResult.fail(CheckCode.C17,
                    Text.of("The GSTIN in your order (" + a + ") is not the GSTIN on the certificate (" + b + ").",
                            "आपके ऑर्डर का GSTIN (" + a + ") प्रमाणपत्र के GSTIN (" + b + ") से अलग है।"),
                    Text.of("Update the GSTIN in your order or upload the certificate for that GSTIN.",
                            "ऑर्डर में GSTIN बदलें या उसी GSTIN का प्रमाणपत्र अपलोड करें।"),
                    ev("orderGstin", a, "kycGstin", b));
        }
        if (!in.gstRequiredProducts().isEmpty() && (f.gstRegistry() == null || !f.gstRegistry().active())) {
            String products = String.join(", ", in.gstRequiredProducts());
            return CheckResult.fail(CheckCode.C17,
                    Text.of("Your order has GST-only services (" + products + ") but the GST registration is not Active.",
                            "आपके ऑर्डर में केवल-GST सेवाएँ (" + products + ") हैं, पर GST पंजीकरण सक्रिय नहीं है।"),
                    Text.of("Remove GST-only services or update your GST registration.",
                            "केवल-GST सेवाएँ हटाएँ या अपना GST पंजीकरण अपडेट करें।"),
                    ev("gstRequiredProducts", in.gstRequiredProducts(),
                            "registryStatus", f.gstRegistry() == null ? null : f.gstRegistry().status()));
        }
        return CheckResult.pass(CheckCode.C17, Text.of("The KYC matches the order.", "KYC ऑर्डर से मेल खाता है।"),
                ev("gstRequiredProducts", in.gstRequiredProducts()));
    }

    // ---- helpers ---------------------------------------------------------------------------------------

    private static List<DocType> docsWithStatus(KycInput in, String status) {
        List<DocType> list = new ArrayList<>();
        for (DocType t : DocType.values()) {
            if (in.doc(t) != null && status.equals(in.doc(t).status())) {
                list.add(t);
            }
        }
        return list;
    }

    private static Text docNames(List<DocType> types) {
        return Text.of(String.join(", ", types.stream().map(t -> t.label().en()).toList()),
                String.join(", ", types.stream().map(t -> t.label().hi()).toList()));
    }

    private static void compareName(Map<String, Object> mismatches, String field, String onDoc, String inRegistry) {
        boolean bothBlank = onDoc == null && (inRegistry == null || inRegistry.isBlank());
        if (!bothBlank && !NameMatcher.matches(onDoc, inRegistry)) {
            mismatches.put(field, ev("onDocument", onDoc, "inRegistry", inRegistry));
        }
    }

    private static void put(Map<String, String> names, Map<String, Text> labels, String key, String value, Text label) {
        if (value != null) {
            names.put(key, value);
            labels.put(key, label);
        }
    }

    private static boolean same(String a, String b) {
        return a != null && b != null && NameMatcher.normalize(a).equals(NameMatcher.normalize(b));
    }

    private static LocalDate date(String iso) {
        try {
            return iso == null ? null : LocalDate.parse(iso);
        } catch (DateTimeParseException e) {
            return null;
        }
    }

    /** Ordered evidence map from key/value pairs (values may be null). */
    static Map<String, Object> ev(Object... keyValues) {
        Map<String, Object> map = new LinkedHashMap<>();
        for (int i = 0; i < keyValues.length; i += 2) {
            map.put((String) keyValues[i], keyValues[i + 1]);
        }
        return map;
    }
}
