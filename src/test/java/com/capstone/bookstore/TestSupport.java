package com.capstone.bookstore;

import java.math.BigDecimal;
import java.nio.charset.StandardCharsets;
import java.util.function.Function;

import org.springframework.test.web.servlet.request.MockHttpServletRequestBuilder;
import org.springframework.test.web.servlet.request.MockMvcRequestBuilders;
import org.springframework.web.util.UriUtils;

import com.capstone.bookstore.config.BookstoreProperties;

/** Test helpers shared by several test classes. */
public final class TestSupport {

    /** Same context path the application uses (server.servlet.context-path, openapi.yaml servers[0].url). */
    public static final String CONTEXT_PATH = "/api/v1";

    private TestSupport() {
    }

    /** Properties with the openapi.yaml defaults and the given JWT settings. */
    public static BookstoreProperties properties(String jwtSecret, long jwtExpirySeconds) {
        return new BookstoreProperties(
                new BookstoreProperties.Points(1, new BigDecimal("10.00"), new BigDecimal("1.00"), 50),
                new BookstoreProperties.Order(48),
                new BookstoreProperties.Delivery(3),
                new BookstoreProperties.Cart(10),
                new BookstoreProperties.Payment("0002"),
                new BookstoreProperties.Security(new BookstoreProperties.Security.Jwt(jwtSecret, jwtExpirySeconds)));
    }

    public static MockHttpServletRequestBuilder get(String pathAndQuery) {
        return request(MockMvcRequestBuilders::get, pathAndQuery);
    }

    public static MockHttpServletRequestBuilder post(String pathAndQuery) {
        return request(MockMvcRequestBuilders::post, pathAndQuery);
    }

    public static MockHttpServletRequestBuilder put(String pathAndQuery) {
        return request(MockMvcRequestBuilders::put, pathAndQuery);
    }

    public static MockHttpServletRequestBuilder delete(String pathAndQuery) {
        return request(MockMvcRequestBuilders::delete, pathAndQuery);
    }

    /**
     * Builds the request from a path with an optional RAW (URL-encoded) query string, the way a client writes it:
     * {@code "/products?q=ctx%20alpha&size=5"}.
     * <p>
     * MockMvc's {@code get(String)} treats its argument as a URI template and encodes it, so a "%20" written into it
     * reaches the application as the literal text "%20" (double encoding). Here the query string is split off,
     * decoded exactly once, and set through {@code .param()}, which is what the servlet container gives Spring after
     * decoding a real request. "+" means a space in a query string, "%2B" a plus sign.
     */
    private static MockHttpServletRequestBuilder request(Function<String, MockHttpServletRequestBuilder> factory,
                                                         String pathAndQuery) {
        int questionMark = pathAndQuery.indexOf('?');
        String path = questionMark < 0 ? pathAndQuery : pathAndQuery.substring(0, questionMark);
        MockHttpServletRequestBuilder builder = factory.apply(CONTEXT_PATH + path).contextPath(CONTEXT_PATH);
        if (questionMark >= 0) {
            for (String pair : pathAndQuery.substring(questionMark + 1).split("&")) {
                if (pair.isEmpty()) {
                    continue;
                }
                int equals = pair.indexOf('=');
                String name = decode(equals < 0 ? pair : pair.substring(0, equals));
                String value = equals < 0 ? "" : decode(pair.substring(equals + 1));
                builder.param(name, value);
            }
        }
        return builder;
    }

    private static String decode(String rawQueryComponent) {
        return UriUtils.decode(rawQueryComponent.replace('+', ' '), StandardCharsets.UTF_8);
    }
}
