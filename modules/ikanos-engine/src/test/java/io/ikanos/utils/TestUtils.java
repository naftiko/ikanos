package io.ikanos.utils;

import tools.jackson.databind.ObjectMapper;
import io.ikanos.spec.IkanosSpec;
import io.ikanos.spec.util.IkanosMappers;

public class TestUtils {

    public static IkanosSpec parseYaml(String yaml) throws Exception {
        ObjectMapper mapper = IkanosMappers.lenientYaml();
        return mapper.readValue(yaml, IkanosSpec.class);
    }
}
