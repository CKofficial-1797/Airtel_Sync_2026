package com.udyam.rules.recommend;

import static com.udyam.rules.RulesFixture.ELIGIBILITY;
import static com.udyam.rules.RulesFixture.RECOMMENDER;
import static org.assertj.core.api.Assertions.assertThat;

import com.udyam.TestData;
import com.udyam.dataload.CsvParsing;
import com.udyam.rules.profile.BusinessProfile;
import com.udyam.rules.recommend.Recommendation.Line;
import java.util.ArrayList;
import java.util.List;
import org.apache.commons.csv.CSVRecord;
import org.junit.jupiter.api.Test;

/** Property test: every one of the 5,000 leads gets a valid, fully eligible, priced recommendation. */
class AllLeadsRecommendationTest {

    @Test
    void everyLeadGetsAnEligiblePricedRecommendation() {
        List<CSVRecord> leads = CsvParsing.readAll(TestData.LEADS_CSV);
        assertThat(leads).hasSize(5000);
        List<String> failures = new ArrayList<>();

        for (CSVRecord r : leads) {
            BusinessProfile profile = BusinessProfile.of(r.get("business_type"), Integer.parseInt(r.get("city_tier")),
                    Integer.parseInt(r.get("employee_count")), Integer.parseInt(r.get("num_sites")),
                    "Y".equals(r.get("has_gstin")) ? r.get("gstin") : null);
            try {
                Recommendation rec = RECOMMENDER.recommend(profile);
                List<String> bundle = rec.included().stream().map(Line::productId).toList();
                for (String id : bundle) {
                    if (!ELIGIBILITY.check(rec.resolvedProfile(), id, bundle).eligible()) {
                        failures.add(r.get("lead_id") + ": " + id + " included but not eligible");
                    }
                }
                if (rec.quote().monthlyTotalInr() <= 0 || !rec.quote().removed().isEmpty()) {
                    failures.add(r.get("lead_id") + ": bad quote");
                }
            } catch (RuntimeException e) {
                failures.add(r.get("lead_id") + ": " + e);
            }
        }
        assertThat(failures).isEmpty();
    }
}
