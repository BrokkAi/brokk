package dev.langchain4j.service.output;

import static dev.langchain4j.service.output.ParsingUtils.parseAsStringOrJson;
import static dev.langchain4j.service.tool.DefaultToolExecutor.getBoundedLongValue;

import java.util.Optional;

import dev.langchain4j.Internal;
import dev.langchain4j.model.chat.request.json.JsonObjectSchema;
import dev.langchain4j.model.chat.request.json.JsonSchema;

@Internal
class IntegerOutputParser implements OutputParser<Integer> {

    @Override
    public Integer parse(String text) {
        return parseAsStringOrJson(text, IntegerOutputParser::parseInteger, Integer.class);
    }

    private static Integer parseInteger(String text) {
        try {
            return Integer.parseInt(text);
        } catch (NumberFormatException nfe) {
            return (int) getBoundedLongValue(text, "int", Integer.class, Integer.MIN_VALUE, Integer.MAX_VALUE);
        }
    }

    @Override
    public Optional<JsonSchema> jsonSchema() {
        JsonSchema jsonSchema = JsonSchema.builder()
                .name("integer")
                .rootElement(JsonObjectSchema.builder()
                        .addIntegerProperty("value")
                        .required("value")
                        .build())
                .build();
        return Optional.of(jsonSchema);
    }

    @Override
    public String formatInstructions() {
        return "integer number";
    }
}
