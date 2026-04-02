package ai.brokk.context;

import com.fasterxml.jackson.annotation.JsonSubTypes;
import com.fasterxml.jackson.annotation.JsonTypeInfo;

public class ContentDtos {
    @JsonTypeInfo(use = JsonTypeInfo.Id.NAME, include = JsonTypeInfo.As.PROPERTY, property = "type")
    @JsonSubTypes({
        @JsonSubTypes.Type(value = FullContentMetadataDto.class, name = "fullContent"),
        @JsonSubTypes.Type(value = DiffContentMetadataDto.class, name = "diff")
    })
    public sealed interface ContentMetadataDto permits FullContentMetadataDto, DiffContentMetadataDto {
        int revision();
    }

    public record FullContentMetadataDto(int revision) implements ContentMetadataDto {}

    public record DiffContentMetadataDto(int revision, String appliesTo) implements ContentMetadataDto {}
}
