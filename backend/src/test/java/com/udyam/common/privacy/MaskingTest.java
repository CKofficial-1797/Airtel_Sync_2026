package com.udyam.common.privacy;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.Test;

class MaskingTest {

    @Test
    void masksGstinMiddle() {
        String masked = Masking.gstin("32TTNXB7384T1ZW");
        assertThat(masked).isEqualTo("32TTN*****1ZW");
        assertThat(masked).doesNotContain("XB7384T");
        assertThat(masked).doesNotContain("7384");
    }

    @Test
    void masksUdyamDigits() {
        String masked = Masking.udyam("UDYAM-KL-00-3078801");
        assertThat(masked).isEqualTo("UDYAM-KL-00-****801");
        assertThat(masked).doesNotContain("3078");
    }

    @Test
    void idAndConsumerNumbersKeepOnlyTheLastThree() {
        assertThat(Masking.keepLast3("SYN-ID-346190")).isEqualTo("**********190");
        assertThat(Masking.keepLast3("9485717625")).isEqualTo("*******625");
    }

    @Test
    void shortOrOddValuesAreFullyMasked() {
        assertThat(Masking.gstin("ABC")).isEqualTo("*****");
        assertThat(Masking.udyam("12345")).isEqualTo("*****");
        assertThat(Masking.keepLast3("123")).isEqualTo("*****");
        assertThat(Masking.gstin(null)).isNull();
        assertThat(Masking.keepLast3(null)).isNull();
    }
}
