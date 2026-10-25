package cz.cuni.mff.releasemanager.utils;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.SerializationFeature;
import com.fasterxml.jackson.datatype.jsr310.JavaTimeModule;

/**
 * Renders machine-readable command output.
 */
public final class Json {
    private static final ObjectMapper MAPPER = new ObjectMapper()
        .registerModule(new JavaTimeModule())
        .disable(SerializationFeature.WRITE_DATES_AS_TIMESTAMPS);

    private Json() {}

    /**
     * @param value value to serialise
     * @return compact JSON
     * @throws JsonProcessingException if the value cannot be serialised
     */
    public static String write(Object value) throws JsonProcessingException {
        return MAPPER.writeValueAsString(value);
    }
}
