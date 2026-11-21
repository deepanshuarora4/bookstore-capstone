package com.capstone.bookstore.recommendation;

import java.util.List;

import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import com.capstone.bookstore.security.AuthenticatedUser;

import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;

/** GET /recommendations: recommendations for the authenticated user. The user id always comes from the JWT. */
@RestController
public class RecommendationController {

    private final RecommendationService recommendationService;

    public RecommendationController(RecommendationService recommendationService) {
        this.recommendationService = recommendationService;
    }

    @GetMapping("/recommendations")
    public List<RecommendedProductResponse> recommendations(
            @AuthenticationPrincipal AuthenticatedUser user,
            @RequestParam(defaultValue = "8") @Min(value = 1, message = "must be between 1 and 50")
                    @Max(value = 50, message = "must be between 1 and 50") int limit) {
        return recommendationService.recommend(user.id(), limit);
    }
}
