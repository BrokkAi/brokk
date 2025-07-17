package dev.langchain4j.service.output;

import static dev.langchain4j.service.output.ParsingUtils.parseAsStringOrJson;

import java.util.Optional;

import dev.langchain4j.Internal;
import dev.langchain4j.model.chat.request.json.JsonObjectSchema;
import dev.langchain4j.model.chat.request.json.JsonSchema;

@Internal
class DoubleOutputParser implements OutputParser<Double> {

    @Override
    public Double parse(String text) {
        return parseAsStringOrJson(text, Double::parseDouble, Double.class);
    }

    @Override
    public Optional<JsonSchema> jsonSchema() {
        JsonSchema jsonSchema = JsonSchema.builder()
                .name("number")
                .rootElement(JsonObjectSchema.builder()
                        .addNumberProperty("value")
                        .required("value")
                        .build())
                .build();
        return Optional.of(jsonSchema);
    }

    @Override
    public String formatInstructions() {
        return "floating point number";
    }
}
