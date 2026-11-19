package com.capstone.bookstore.user;

import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import com.capstone.bookstore.common.ApiException;
import com.capstone.bookstore.security.AuthenticatedUser;

/** GET /users/me: the profile of the authenticated user (needs a valid JWT). */
@RestController
@RequestMapping("/users")
public class UserController {

    private final UserRepository userRepository;

    public UserController(UserRepository userRepository) {
        this.userRepository = userRepository;
    }

    @GetMapping("/me")
    @Transactional(readOnly = true)
    public UserResponse me(@AuthenticationPrincipal AuthenticatedUser principal) {
        // A token for a user that no longer exists is treated like any other invalid token.
        return userRepository.findById(principal.id())
                .map(UserResponse::from)
                .orElseThrow(ApiException::authenticationFailed);
    }
}
