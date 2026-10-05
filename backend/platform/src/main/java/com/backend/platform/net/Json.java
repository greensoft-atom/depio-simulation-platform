package com.backend.platform.net;

import com.fasterxml.jackson.databind.DeserializationFeature;
import com.fasterxml.jackson.databind.ObjectMapper;

/** One mapper for the API, configured once. */
final class Json {

    /**
     * Unknown fields are ignored rather than rejected: a newer client sending a field this
     * build has never heard of should be served, not given a 400 it cannot act on.
     */
    static final ObjectMapper MAPPER = new ObjectMapper()
            .configure(DeserializationFeature.FAIL_ON_UNKNOWN_PROPERTIES, false);

    private Json() {
    }
}
