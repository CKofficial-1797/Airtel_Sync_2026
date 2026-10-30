package com.udyam.kyc.checks;

import java.util.List;

/** Result of one KYC validation run: the overall verdict and every check in order C01..C17. */
public record ValidationReport(String overall, List<CheckResult> checks) {

    public static final String VERIFIED = "VERIFIED";
    public static final String ACTION_REQUIRED = "ACTION_REQUIRED";
    public static final String REJECTED = "REJECTED";

    /**
     * Any non-fixable blocking FAIL → REJECTED; only fixable FAILs → ACTION_REQUIRED; no FAIL → VERIFIED
     * (WARNs allowed).
     */
    public static ValidationReport of(List<CheckResult> checks) {
        boolean anyFail = checks.stream().anyMatch(CheckResult::blockingFailure);
        boolean rejected = checks.stream().anyMatch(c -> c.blockingFailure() && !c.fixable());
        return new ValidationReport(rejected ? REJECTED : anyFail ? ACTION_REQUIRED : VERIFIED, checks);
    }

    public List<String> blockingCodes() {
        return checks.stream().filter(CheckResult::blockingFailure).map(CheckResult::code).toList();
    }

    public List<String> warningCodes() {
        return checks.stream().filter(CheckResult::warning).map(CheckResult::code).toList();
    }
}
