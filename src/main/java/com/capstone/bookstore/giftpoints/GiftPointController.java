package com.capstone.bookstore.giftpoints;

import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import com.capstone.bookstore.security.AuthenticatedUser;

import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;

/** GET /gift-points: balance, rules and ledger of the authenticated user. */
@RestController
public class GiftPointController {

    private final GiftPointService giftPointService;

    public GiftPointController(GiftPointService giftPointService) {
        this.giftPointService = giftPointService;
    }

    @GetMapping("/gift-points")
    public GiftPointsResponse giftPoints(
            @AuthenticationPrincipal AuthenticatedUser principal,
            @RequestParam(defaultValue = "0") @Min(value = 0, message = "must not be negative") int page,
            @RequestParam(defaultValue = "20") @Min(value = 1, message = "must be between 1 and 100")
                    @Max(value = 100, message = "must be between 1 and 100") int size) {
        return giftPointService.overview(principal.id(), page, size);
    }
}
