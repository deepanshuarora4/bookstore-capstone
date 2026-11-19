package com.capstone.bookstore.address;

import java.util.List;

import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import com.capstone.bookstore.common.ApiException;
import com.capstone.bookstore.user.UserRepository;

/**
 * Delivery addresses (IBM step 9). Every lookup includes the owner id, so a user can never read or change
 * another user's address (it is reported as 404). Deleting is a soft delete: orders keep their own snapshot.
 * A user with at least one address always has exactly one default address.
 */
@Service
public class AddressService {

    private final AddressRepository addresses;
    private final UserRepository users;

    public AddressService(AddressRepository addresses, UserRepository users) {
        this.addresses = addresses;
        this.users = users;
    }

    @Transactional(readOnly = true)
    public List<AddressResponse> list(Long userId) {
        return addresses.findByUserIdAndDeletedFalseOrderByDefaultAddressDescIdAsc(userId).stream()
                .map(AddressResponse::from).toList();
    }

    @Transactional(readOnly = true)
    public AddressResponse get(Long userId, Long addressId) {
        return AddressResponse.from(owned(userId, addressId));
    }

    /** The first address becomes the default automatically; an explicit default replaces the previous one. */
    @Transactional
    public AddressResponse create(Long userId, AddressRequest request) {
        boolean makeDefault = request.defaultRequested() || !addresses.existsByUserIdAndDeletedFalse(userId);
        if (makeDefault) {
            clearDefaults(userId);
        }
        Address address = new Address(users.getReferenceById(userId), request.label(), request.line1(),
                request.line2(), request.city(), request.state(), request.postalCode(), request.countryOrDefault(),
                makeDefault);
        return AddressResponse.from(addresses.save(address));
    }

    /**
     * Full update. {@code isDefault: true} makes this the default address. {@code false} cannot remove the default
     * flag (there is always one default); choose another address as default instead.
     */
    @Transactional
    public AddressResponse update(Long userId, Long addressId, AddressRequest request) {
        Address address = owned(userId, addressId);
        address.setLabel(request.label());
        address.setLine1(request.line1());
        address.setLine2(request.line2());
        address.setCity(request.city());
        address.setState(request.state());
        address.setPostalCode(request.postalCode());
        address.setCountry(request.countryOrDefault());
        if (request.defaultRequested() && !address.isDefaultAddress()) {
            clearDefaults(userId);
            address.setDefaultAddress(true);
        }
        return AddressResponse.from(address);
    }

    /** Soft delete. If the default address is deleted, the oldest remaining address becomes the default. */
    @Transactional
    public void delete(Long userId, Long addressId) {
        Address address = owned(userId, addressId);
        boolean wasDefault = address.isDefaultAddress();
        address.setDeleted(true);
        address.setDefaultAddress(false);
        addresses.flush();
        if (wasDefault) {
            addresses.findByUserIdAndDeletedFalseOrderByDefaultAddressDescIdAsc(userId).stream().findFirst()
                    .ifPresent(next -> next.setDefaultAddress(true));
        }
    }

    /** Used by checkout: the address must exist, belong to the user and not be deleted. */
    @Transactional(readOnly = true)
    public Address ownedAddress(Long userId, Long addressId) {
        return owned(userId, addressId);
    }

    private Address owned(Long userId, Long addressId) {
        return addresses.findByIdAndUserIdAndDeletedFalse(addressId, userId)
                .orElseThrow(() -> ApiException.notFound("Address " + addressId + " not found"));
    }

    private void clearDefaults(Long userId) {
        addresses.findByUserIdAndDeletedFalseAndDefaultAddressTrue(userId)
                .forEach(a -> a.setDefaultAddress(false));
        addresses.flush();
    }
}
