package com.udyam.kyc;

import com.udyam.kyc.consent.ConsentNotice;
import java.io.IOException;
import java.util.UUID;
import org.springframework.http.MediaType;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RequestPart;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.multipart.MultipartFile;

@RestController
public class KycController {

    private final KycService kyc;

    public KycController(KycService kyc) {
        this.kyc = kyc;
    }

    @GetMapping("/api/kyc/consent-notice")
    public ConsentNotice notice(@RequestParam(defaultValue = "en") String lang) {
        return kyc.notice(lang);
    }

    @PostMapping("/api/orders/{orderId}/kyc/consent")
    public KycService.KycView consent(@PathVariable UUID orderId, @RequestBody KycService.ConsentRequest request) {
        return kyc.giveConsent(orderId, request);
    }

    @PostMapping("/api/orders/{orderId}/kyc/consent/withdraw")
    public KycService.KycView withdraw(@PathVariable UUID orderId) {
        return kyc.withdrawConsent(orderId);
    }

    @PostMapping(path = "/api/orders/{orderId}/kyc/documents", consumes = MediaType.MULTIPART_FORM_DATA_VALUE)
    public KycService.DocumentView upload(@PathVariable UUID orderId, @RequestParam("docType") String docType,
                                          @RequestPart("file") MultipartFile file) throws IOException {
        return kyc.upload(orderId, docType, file.getBytes());
    }

    @PostMapping("/api/orders/{orderId}/kyc/submit")
    public KycService.SubmitResult submit(@PathVariable UUID orderId) {
        return kyc.submit(orderId);
    }

    @GetMapping("/api/orders/{orderId}/kyc")
    public KycService.KycView get(@PathVariable UUID orderId) {
        return kyc.view(orderId);
    }
}
