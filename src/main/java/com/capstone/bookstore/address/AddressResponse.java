package com.capstone.bookstore.address;

import com.fasterxml.jackson.annotation.JsonProperty;

/** Address in openapi.yaml. A DTO: the JPA entity (and its user) is never serialized. */
public record AddressResponse(
        Long id,
        String label,
        String line1,
        String line2,
        String city,
        String state,
        String postalCode,
        String country,
        @JsonProperty("isDefault") boolean isDefault) {

    public static AddressResponse from(Address a) {
        return new AddressResponse(a.getId(), a.getLabel(), a.getLine1(), a.getLine2(), a.getCity(), a.getState(),
                a.getPostalCode(), a.getCountry(), a.isDefaultAddress());
    }
}
