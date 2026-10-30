package com.udyam.kyc.consent;

import com.udyam.kyc.KycProperties;
import java.util.List;
import java.util.Set;
import org.springframework.stereotype.Component;

/**
 * The consent notice text in English and Hindi (DPDP Act 2023: notice before consent, specific purposes,
 * data minimisation, retention, withdrawal, grievance contact).
 */
@Component
public class ConsentNotices {

    public static final String KYC_VERIFICATION = "KYC_VERIFICATION";
    public static final String SERVICE_INSTALLATION = "SERVICE_INSTALLATION";
    public static final String WHATSAPP_UPDATES = "WHATSAPP_UPDATES";
    public static final Set<String> REQUIRED = Set.of(KYC_VERIFICATION, SERVICE_INSTALLATION);
    public static final Set<String> ALL = Set.of(KYC_VERIFICATION, SERVICE_INSTALLATION, WHATSAPP_UPDATES);
    public static final Set<String> LANGUAGES = Set.of("en", "hi");

    private static final String CONTACT = "Grievance Officer, Accel Telecom: privacy@accelbusiness.example, 1800-000-0000";

    private final KycProperties props;

    public ConsentNotices(KycProperties props) {
        this.props = props;
    }

    public String version() {
        return props.noticeVersion();
    }

    public ConsentNotice notice(String language) {
        return "hi".equals(language) ? hindi() : english();
    }

    private ConsentNotice english() {
        int days = props.retentionDays();
        return new ConsentNotice(version(), "en", "Consent for business KYC",
                "Accel Telecom (Accel Business)",
                List.of(
                        new ConsentNotice.Purpose(KYC_VERIFICATION, true, "Verify your business",
                                "Read your GST certificate, Udyam certificate, signatory ID and address proof and check"
                                        + " them against the GST and Udyam registries, as required for telecom"
                                        + " connections."),
                        new ConsentNotice.Purpose(SERVICE_INSTALLATION, true, "Install your services",
                                "Use your verified business address to plan and carry out the installation."),
                        new ConsentNotice.Purpose(WHATSAPP_UPDATES, false, "Order updates on WhatsApp",
                                "Send order and installation updates to your WhatsApp number. Optional.")),
                "Only the fields needed for verification: GSTIN, Udyam number, business and signatory names,"
                        + " designation, ID number and validity, address and bill date.",
                "Raw document images are deleted immediately after the fields are extracted. The extracted fields"
                        + " are kept only for the period required by telecom rules, as required by law (currently "
                        + days + " days), and then deleted.",
                days,
                List.of("Withdraw your consent at any time before your service is installed; your extracted KYC data is"
                                + " deleted and the KYC stops. Once it is installed, telecom rules require us to keep"
                                + " these records for a live connection: contact our Grievance Officer.",
                        "Ask what data we hold about you and correct it.",
                        "Raise a grievance with our Grievance Officer, and then with the Data Protection Board of"
                                + " India."),
                CONTACT);
    }

    private ConsentNotice hindi() {
        int days = props.retentionDays();
        return new ConsentNotice(version(), "hi", "व्यवसाय KYC के लिए सहमति",
                "Accel Telecom (Accel Business)",
                List.of(
                        new ConsentNotice.Purpose(KYC_VERIFICATION, true, "आपके व्यवसाय का सत्यापन",
                                "आपका GST प्रमाणपत्र, उद्यम प्रमाणपत्र, हस्ताक्षरकर्ता पहचान पत्र और पते का प्रमाण पढ़कर"
                                        + " GST और उद्यम रजिस्ट्री से मिलाना, जैसा टेलीकॉम कनेक्शन के लिए ज़रूरी है।"),
                        new ConsentNotice.Purpose(SERVICE_INSTALLATION, true, "सेवाओं का इंस्टॉलेशन",
                                "आपके सत्यापित व्यवसाय पते का उपयोग इंस्टॉलेशन की योजना बनाने और उसे पूरा करने के लिए।"),
                        new ConsentNotice.Purpose(WHATSAPP_UPDATES, false, "WhatsApp पर ऑर्डर अपडेट",
                                "आपके WhatsApp नंबर पर ऑर्डर और इंस्टॉलेशन के अपडेट भेजना। वैकल्पिक।")),
                "केवल सत्यापन के लिए ज़रूरी जानकारी: GSTIN, उद्यम नंबर, व्यवसाय और हस्ताक्षरकर्ता के नाम, पद,"
                        + " पहचान पत्र नंबर और वैधता, पता और बिल की तारीख।",
                "दस्तावेज़ों की मूल तस्वीरें जानकारी निकालने के तुरंत बाद हटा दी जाती हैं। निकाली गई जानकारी केवल"
                        + " टेलीकॉम नियमों द्वारा तय अवधि तक, कानून के अनुसार (अभी " + days + " दिन), रखी जाती है और"
                        + " फिर हटा दी जाती है।",
                days,
                List.of("सेवा इंस्टॉल होने से पहले आप कभी भी अपनी सहमति वापस ले सकते हैं; आपकी निकाली गई KYC जानकारी हटा दी"
                                + " जाएगी और KYC रुक जाएगा। इंस्टॉल होने के बाद चालू कनेक्शन के लिए टेलीकॉम नियमों के तहत"
                                + " ये रिकॉर्ड रखना ज़रूरी है: हमारे शिकायत अधिकारी से संपर्क करें।",
                        "आप पूछ सकते हैं कि हमारे पास आपकी कौन-सी जानकारी है और उसे ठीक करवा सकते हैं।",
                        "आप हमारे शिकायत अधिकारी से, और फिर भारत के डेटा संरक्षण बोर्ड से शिकायत कर सकते हैं।"),
                CONTACT);
    }
}
