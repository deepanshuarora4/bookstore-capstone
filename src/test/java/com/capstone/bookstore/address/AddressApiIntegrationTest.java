package com.capstone.bookstore.address;

import static com.capstone.bookstore.TestSupport.delete;
import static com.capstone.bookstore.TestSupport.get;
import static com.capstone.bookstore.TestSupport.post;
import static com.capstone.bookstore.TestSupport.put;
import static org.assertj.core.api.Assertions.assertThat;

import java.util.LinkedHashMap;
import java.util.Map;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;

import com.capstone.bookstore.AbstractApiTest;
import com.capstone.bookstore.user.User;
import com.fasterxml.jackson.databind.JsonNode;

/** Delivery addresses (IBM step 9): CRUD, default handling, validation and ownership (another user's is a 404). */
class AddressApiIntegrationTest extends AbstractApiTest {

    private static Map<String, Object> address(String label, boolean isDefault) {
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("label", label);
        m.put("line1", "12 MG Road");
        m.put("line2", "Indiranagar");
        m.put("city", "Bengaluru");
        m.put("state", "Karnataka");
        m.put("postalCode", "560038");
        m.put("country", "India");
        m.put("isDefault", isDefault);
        return m;
    }

    private JsonNode create(User user, Map<String, Object> request) throws Exception {
        return ok(call(post("/addresses"), token(user), request), 201);
    }

    @Test
    void createReturns201AndTheAddressWithoutAnyUserData() throws Exception {
        User user = customer("Home Owner");

        var result = call(post("/addresses"), token(user), address("Home", false));
        JsonNode a = ok(result, 201);

        assertThat(a.get("id").asLong()).isPositive();
        assertThat(a.get("label").asText()).isEqualTo("Home");
        assertThat(a.get("line1").asText()).isEqualTo("12 MG Road");
        assertThat(a.get("postalCode").asText()).isEqualTo("560038");
        assertThat(a.get("country").asText()).isEqualTo("India");
        assertThat(a.get("isDefault").asBoolean()).isTrue();                    // first address becomes the default
        assertThat(result.getResponse().getContentAsString()).doesNotContain("user").doesNotContain("deleted");
    }

    @Test
    void theFirstAddressIsDefaultAndAnExplicitDefaultReplacesThePreviousOne() throws Exception {
        User user = customer("Two Homes");
        JsonNode first = create(user, address("Home", false));
        JsonNode second = create(user, address("Office", false));
        assertThat(second.get("isDefault").asBoolean()).isFalse();

        JsonNode third = create(user, address("Parents", true));

        assertThat(third.get("isDefault").asBoolean()).isTrue();
        JsonNode list = getJson("/addresses", token(user));
        assertThat(list).hasSize(3);
        assertThat(list.get(0).get("id").asLong()).isEqualTo(third.get("id").asLong());   // default listed first
        long defaults = java.util.stream.StreamSupport.stream(list.spliterator(), false)
                .filter(n -> n.get("isDefault").asBoolean()).count();
        assertThat(defaults).isEqualTo(1);
        assertThat(first.get("id").asLong()).isNotEqualTo(third.get("id").asLong());
    }

    @Test
    void countryDefaultsToIndiaAndOptionalFieldsMayBeOmitted() throws Exception {
        User user = customer("Minimal");
        Map<String, Object> request = address(null, false);
        request.remove("label");
        request.remove("line2");
        request.remove("country");
        request.remove("isDefault");

        JsonNode a = create(user, request);

        assertThat(a.get("country").asText()).isEqualTo("India");
        assertThat(a.has("label")).isFalse();
        assertThat(a.has("line2")).isFalse();
    }

    @Test
    void getReturnsOwnAddressAndListShowsOnlyOwnAddresses() throws Exception {
        User alice = customer("Alice Addr");
        User bob = customer("Bob Addr");
        JsonNode a = create(alice, address("Alice Home", true));
        create(bob, address("Bob Home", true));

        assertThat(getJson("/addresses/" + a.get("id").asLong(), token(alice)).get("label").asText())
                .isEqualTo("Alice Home");
        JsonNode list = getJson("/addresses", token(alice));
        assertThat(list).hasSize(1);
        assertThat(list.get(0).get("label").asText()).isEqualTo("Alice Home");
    }

    @Test
    void anotherUsersAddressIs404ForReadUpdateAndDelete() throws Exception {
        User alice = customer("Alice Owner");
        User mallory = customer("Mallory");
        long id = create(alice, address("Private", true)).get("id").asLong();

        error(call(get("/addresses/" + id), token(mallory)), 404, "NOT_FOUND");
        error(call(put("/addresses/" + id), token(mallory), address("Hijacked", false)), 404, "NOT_FOUND");
        error(call(delete("/addresses/" + id), token(mallory)), 404, "NOT_FOUND");

        JsonNode untouched = getJson("/addresses/" + id, token(alice));
        assertThat(untouched.get("label").asText()).isEqualTo("Private");
    }

    @Test
    void unknownAddressIs404() throws Exception {
        User user = customer("Nowhere");

        error(call(get("/addresses/987654321"), token(user)), 404, "NOT_FOUND");
    }

    @Test
    void updateReplacesTheFieldsAndCanMakeTheAddressTheDefault() throws Exception {
        User user = customer("Mover");
        long first = create(user, address("Home", false)).get("id").asLong();
        long second = create(user, address("Office", false)).get("id").asLong();

        Map<String, Object> changed = address("New Office", true);
        changed.put("city", "Mysuru");
        changed.put("postalCode", "570001");
        JsonNode updated = ok(call(put("/addresses/" + second), token(user), changed), 200);

        assertThat(updated.get("city").asText()).isEqualTo("Mysuru");
        assertThat(updated.get("postalCode").asText()).isEqualTo("570001");
        assertThat(updated.get("isDefault").asBoolean()).isTrue();
        assertThat(getJson("/addresses/" + first, token(user)).get("isDefault").asBoolean()).isFalse();
    }

    @Test
    void deleteIsASoftDeleteThatHidesTheAddressAndPromotesAnotherDefault() throws Exception {
        User user = customer("Deleter");
        long first = create(user, address("Home", false)).get("id").asLong();     // default
        long second = create(user, address("Office", false)).get("id").asLong();

        assertThat(call(delete("/addresses/" + first), token(user)).getResponse().getStatus()).isEqualTo(204);

        error(call(get("/addresses/" + first), token(user)), 404, "NOT_FOUND");
        assertThat(getJson("/addresses", token(user))).hasSize(1);
        assertThat(getJson("/addresses/" + second, token(user)).get("isDefault").asBoolean()).isTrue();
        assertThat(jdbc.queryForObject("SELECT deleted FROM addresses WHERE id = ?", Boolean.class, first)).isTrue();
        error(call(delete("/addresses/" + first), token(user)), 404, "NOT_FOUND");
    }

    @ParameterizedTest
    @CsvSource(delimiter = '|', value = {
            "line1|", "line1|   ", "city|", "state|", "postalCode|", "postalCode|56003", "postalCode|5600380",
            "postalCode|060038", "postalCode|56003a", "line1|%151", "label|%51", "city|%81"})
    void invalidAddressesAreRejectedWithTheOffendingField(String field, String value) throws Exception {
        User user = customer("Invalid Address");
        Map<String, Object> request = address("Home", false);
        String actual = value == null ? "" : value.startsWith("%") ? "x".repeat(Integer.parseInt(value.substring(1))) : value;
        request.put(field, actual);

        JsonNode b = error(call(post("/addresses"), token(user), request), 400, "VALIDATION_FAILED");

        assertThat(b.get("fieldErrors").get(0).get("field").asText()).isEqualTo(field);
        assertThat(getJson("/addresses", token(user))).isEmpty();
    }

    @Test
    void missingRequiredFieldsAreAllReported() throws Exception {
        User user = customer("Empty Address");

        JsonNode b = error(call(post("/addresses"), token(user), Map.of()), 400, "VALIDATION_FAILED");

        assertThat(java.util.stream.StreamSupport.stream(b.get("fieldErrors").spliterator(), false)
                .map(e -> e.get("field").asText())).contains("line1", "city", "state", "postalCode");
    }

    @Test
    void anOwnerFieldInTheBodyCannotAssignTheAddressToSomeoneElse() throws Exception {
        User alice = customer("Alice Sneaky");
        User bob = customer("Bob Victim");
        Map<String, Object> request = address("Sneaky", true);
        request.put("userId", bob.getId());

        create(alice, request);

        assertThat(getJson("/addresses", token(bob))).isEmpty();
        assertThat(getJson("/addresses", token(alice))).hasSize(1);
    }
}
