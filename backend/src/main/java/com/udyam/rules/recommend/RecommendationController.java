package com.udyam.rules.recommend;

import com.udyam.rules.profile.BusinessProfile;
import jakarta.validation.Valid;
import jakarta.validation.constraints.NotNull;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/api/recommend")
public class RecommendationController {

    private final RecommendationService recommendations;

    public RecommendationController(RecommendationService recommendations) {
        this.recommendations = recommendations;
    }

    public record RecommendRequest(@Valid @NotNull BusinessProfile profile) {
    }

    @PostMapping
    public Recommendation recommend(@Valid @RequestBody RecommendRequest request) {
        return recommendations.recommend(request.profile());
    }
}
