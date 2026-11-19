package com.capstone.bookstore.address;

import java.util.List;

import org.springframework.http.HttpStatus;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;

import com.capstone.bookstore.security.AuthenticatedUser;

import jakarta.validation.Valid;
import jakarta.validation.constraints.Min;

/** /addresses: the delivery addresses of the authenticated user only (IBM step 9). */
@RestController
public class AddressController {

    private final AddressService addressService;

    public AddressController(AddressService addressService) {
        this.addressService = addressService;
    }

    @GetMapping("/addresses")
    public List<AddressResponse> list(@AuthenticationPrincipal AuthenticatedUser user) {
        return addressService.list(user.id());
    }

    @PostMapping("/addresses")
    @ResponseStatus(HttpStatus.CREATED)
    public AddressResponse create(@AuthenticationPrincipal AuthenticatedUser user,
                                  @Valid @RequestBody AddressRequest request) {
        return addressService.create(user.id(), request);
    }

    @GetMapping("/addresses/{addressId}")
    public AddressResponse get(@AuthenticationPrincipal AuthenticatedUser user,
                               @PathVariable @Min(value = 1, message = "must be at least 1") Long addressId) {
        return addressService.get(user.id(), addressId);
    }

    @PutMapping("/addresses/{addressId}")
    public AddressResponse update(@AuthenticationPrincipal AuthenticatedUser user,
                                  @PathVariable @Min(value = 1, message = "must be at least 1") Long addressId,
                                  @Valid @RequestBody AddressRequest request) {
        return addressService.update(user.id(), addressId, request);
    }

    @DeleteMapping("/addresses/{addressId}")
    @ResponseStatus(HttpStatus.NO_CONTENT)
    public void delete(@AuthenticationPrincipal AuthenticatedUser user,
                       @PathVariable @Min(value = 1, message = "must be at least 1") Long addressId) {
        addressService.delete(user.id(), addressId);
    }
}
