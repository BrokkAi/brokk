package ai.brokk.context;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;

import ai.brokk.analyzer.CodeUnit;
import ai.brokk.analyzer.CodeUnitType;
import ai.brokk.analyzer.ProjectFile;

import java.nio.file.Path;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Tests to verify backward-compatible deserialization when CodeUnitDto lacks the optional 'signature' field,
 * and to assert that CodeUnit equality/hash do not depend on the optional signature.
 */
public class CodeUnitDtoBackwardCompatTest {

    @Test
    public void jacksonDeserializesMissingSignatureAsNull() throws Exception {
        var json = """
                {
                  "sourceFile": { "id": "0", "repoRoot": "/repo/root", "relPath": "src/A.cpp" },
                  "kind": "FUNCTION",
                  "packageName": "pkg",
                  "shortName": "A.foo"
                }
                """;

        ObjectMapper mapper = new ObjectMapper();
        // Allow comments in the inlined JSON test fixture (some historical fixtures may contain comments)
        mapper.configure(com.fasterxml.jackson.core.JsonParser.Feature.ALLOW_COMMENTS, true);
        // Deserialize into the DTO defined in FragmentDtos
        FragmentDtos.CodeUnitDto dto = mapper.readValue(json, FragmentDtos.CodeUnitDto.class);

        // The absent 'signature' field should map to null
        assertNull(dto.signature(), "Missing 'signature' field should deserialize to null");
    }

    @Test
    public void codeUnitEqualityAndHashDontDependOnSignature() {
        // Prepare a ProjectFile and common identity fields
        ProjectFile pf = new ProjectFile(Path.of("/repo/root"), Path.of("src/A.cpp"));
        CodeUnitType kind = CodeUnitType.FUNCTION;
        String pkg = "pkg";
        String shortName = "A.foo";

        // One CodeUnit without signature (legacy)
        CodeUnit cuLegacy = new CodeUnit(pf, kind, pkg, shortName, null);

        // Same CodeUnit but with a recorded signature (newer)
        CodeUnit cuWithSig = new CodeUnit(pf, kind, pkg, shortName, "(int)");

        // fqName should be computed solely from packageName and shortName
        assertEquals(cuLegacy.fqName(), cuWithSig.fqName(), "fqName must be identical regardless of signature");

        // equals/hashCode intentionally exclude signature; verify that contract holds
        assertEquals(cuLegacy, cuWithSig, "CodeUnit equality must ignore the optional signature field");
        assertEquals(cuLegacy.hashCode(), cuWithSig.hashCode(), "Hash codes must match when only signature differs");

        // hasSignature reflects presence/absence
        assertFalse(cuLegacy.hasSignature(), "Legacy CodeUnit should report no signature");
        assertTrue(cuWithSig.hasSignature(), "CodeUnit with signature should report presence");
    }
}
