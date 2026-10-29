package com.udyam.rules.data;

import static org.assertj.core.api.Assertions.assertThat;

import com.udyam.rules.RulesFixture;
import com.udyam.rules.profile.Segment;
import com.udyam.rules.recommend.RecommendationProperties;
import java.util.ArrayList;
import java.util.List;
import org.junit.jupiter.api.Test;

class RulesDataTest {

    private final RulesData data = RulesFixture.DATA;

    @Test
    void catalogueHas25Products() {
        assertThat(data.catalogue().size()).isEqualTo(25);
        CatalogueProduct p03 = data.catalogue().get("P03");
        assertThat(p03.cityTiers()).containsExactly(1);
        assertThat(p03.minEmployees()).isEqualTo(5);
        assertThat(p03.requiresGstin()).isTrue();
        assertThat(data.catalogue().get("p11").requiresOneOf()).containsExactly("P01", "P02", "P03");
    }

    @Test
    void cityMappingIsUnambiguous() {
        // RulesData throws on conflicts; here we check the derived result.
        assertThat(data.cities()).hasSize(22);
        assertThat(data.city("mumbai")).contains(new CityRef("Mumbai", 1, "Maharashtra"));
        assertThat(data.city("Kochi")).contains(new CityRef("Kochi", 2, "Kerala"));
        assertThat(data.cities().stream().filter(c -> c.tier() == 1)).hasSize(8);
    }

    @Test
    void businessTypeMappingIsUnambiguous() {
        assertThat(data.businessTypes()).hasSize(35);
        assertThat(data.businessType("kirana store")).contains(new BusinessTypeRef("Kirana store", "Micro retail & services"));
        assertThat(data.businessTypes()).allSatisfy(t -> assertThat(Segment.parse(t.segment())).isPresent());
    }

    @Test
    void everyTemplateProductExistsInCatalogue() {
        RecommendationProperties t = RulesFixture.TEMPLATES;
        List<String> ids = new ArrayList<>(t.fallbackChain());
        ids.addAll(t.fibreProducts());
        t.segments().values().forEach(s -> {
            ids.add(s.primary());
            if (s.primaryIfNoFibre() != null) {
                ids.add(s.primaryIfNoFibre());
            }
            ids.addAll(s.upgrades());
            ids.addAll(s.included());
            ids.addAll(s.optional());
        });
        t.needs().forEach(n -> ids.addAll(n.products()));
        t.quantities().products().forEach(q -> ids.add(q.productId()));
        t.why().forEach(w -> ids.add(w.productId()));

        assertThat(ids).allSatisfy(id -> assertThat(data.catalogue().find(id)).as(id).isPresent());
        assertThat(t.segments().keySet()).containsExactlyInAnyOrder("micro", "prof", "multi", "mfg");
        assertThat(t.why()).hasSize(25);
    }
}
