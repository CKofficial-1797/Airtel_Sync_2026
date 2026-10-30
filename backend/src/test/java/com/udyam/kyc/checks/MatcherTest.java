package com.udyam.kyc.checks;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;

class MatcherTest {

    @ParameterizedTest(name = "\"{0}\" ~ \"{1}\" -> {2}")
    @CsvSource(delimiter = '|', value = {
            "Shri Physio Clinic | Neelgiri Physio Clinic | false",
            "Kaveri Stays LLP | Kaveri Stays Llp | true",
            "Vindhya Tours & Travels | Vindhya Tours and Travels | true",
            "Trinity Bake House Pvt Ltd | Trinity Bake House Private Limited | true",
            "Trinity Bake House Pvt. Ltd. | trinity bake house pvt ltd | true",
            "Balaji Realty LLP | Balaji Realty Limited Liability Partnership | true",
            "Green Leaf Legal Associates | Green Leaf Legal Asociates | true",
            "Star Electronics | Star Electronic | true",
            "Harmony Dental Care | Harmony Dental Cure | false",
            "Neha Kaur | Neha Kaul | false",
            "Joseph Shah | Joseph Shah Ji | false",
            "Saffron Dental Care LLP | Saffron Dental Care | false",
            "Ganesh Woodcraft | Ganesh Woodcraft | true",
            "Pooja  Shah | pooja shah | true"
    })
    void nameMatcher(String a, String b, boolean expected) {
        assertThat(NameMatcher.matches(a, b)).isEqualTo(expected);
        assertThat(NameMatcher.matches(b, a)).isEqualTo(expected);
    }

    @Test
    void blankNamesNeverMatch() {
        assertThat(NameMatcher.matches(null, "Star Electronics")).isFalse();
        assertThat(NameMatcher.matches("  ", "  ")).isFalse();
    }

    @Test
    void fuzzyOnlyForLongTokens() {
        assertThat(NameMatcher.tokenMatches("clinic", "clinik")).isTrue();   // 6 letters, distance 1
        assertThat(NameMatcher.tokenMatches("care", "cure")).isFalse();      // 4 letters: exact only
        assertThat(NameMatcher.tokenMatches("advisors", "advisers")).isTrue();
        assertThat(NameMatcher.tokenMatches("woodcraft", "woodcrafts")).isTrue();
        assertThat(NameMatcher.tokenMatches("neelgiri", "nilgiri")).isFalse(); // distance 2
    }

    @Test
    void splitsProprietorAndBusiness() {
        assertThat(NameMatcher.splitProprietor("Neha Kaur (Prop. Vindhya Tours & Travels)")).hasValueSatisfying(p -> {
            assertThat(p.proprietor()).isEqualTo("Neha Kaur");
            assertThat(p.business()).isEqualTo("Vindhya Tours & Travels");
        });
        assertThat(NameMatcher.splitProprietor("Joseph Rao (prop Green Leaf Legal Associates)")).isPresent();
        assertThat(NameMatcher.splitProprietor("Kaveri Stays LLP")).isEmpty();
    }

    @Test
    void identicalAddressesMatch() {
        String a = "Shop/Unit 80, Civil Lines, Nagpur, Maharashtra - 411231";
        AddressMatcher.AddressMatch m = AddressMatcher.compare(a, a);
        assertThat(m.matches()).isTrue();
        assertThat(m.tokenOverlap()).isEqualTo(1.0);
        assertThat(m.pincodeA()).isEqualTo("411231");
    }

    @Test
    void formattingDifferencesStillMatch() {
        assertThat(AddressMatcher.compare("Shop/Unit 80, Civil Lines, Nagpur, Maharashtra - 411231",
                "SHOP UNIT 80 CIVIL LINES NAGPUR MAHARASHTRA 411231").matches()).isTrue();
        // One word of seven differs (86% overlap): still the same place.
        assertThat(AddressMatcher.compare("Shop/Unit 80, Civil Lines, Nagpur, Maharashtra - 411231",
                "Shop/Unit 80, Civil Line, Nagpur, Maharashtra - 411231").matches()).isTrue();
    }

    @Test
    void differentPincodeFailsEvenWithTheSameStreet() {
        AddressMatcher.AddressMatch m = AddressMatcher.compare("Shop/Unit 80, Civil Lines, Nagpur, Maharashtra - 411231",
                "Shop/Unit 80, Civil Lines, Nagpur, Maharashtra - 411232");
        assertThat(m.matches()).isFalse();
        assertThat(m.tokenOverlap()).isEqualTo(1.0);
    }

    @Test
    void samePincodeButDifferentStreetFails() {
        AddressMatcher.AddressMatch m = AddressMatcher.compare("Shop/Unit 80, Civil Lines, Nagpur, Maharashtra - 411231",
                "Plot 12, Ring Road, Wardha Bypass, Maharashtra - 411231");
        assertThat(m.matches()).isFalse();
        assertThat(m.tokenOverlap()).isLessThan(AddressMatcher.MIN_TOKEN_OVERLAP);
    }

    @Test
    void missingPincodeFails() {
        assertThat(AddressMatcher.compare("Shop/Unit 80, Civil Lines, Nagpur", "Shop/Unit 80, Civil Lines, Nagpur")
                .matches()).isFalse();
    }

    @Test
    void stateIsTheLastPartBeforeThePincode() {
        assertThat(AddressMatcher.state("Shop/Unit 52, Transport Nagar, Delhi, Delhi - 334596")).hasValue("Delhi");
        assertThat(AddressMatcher.state("Shop/Unit 1, Tech Park Road, Visakhapatnam, Andhra Pradesh - 118383"))
                .hasValue("Andhra Pradesh");
        assertThat(AddressMatcher.state("no commas here 411231")).isEmpty();
    }
}
