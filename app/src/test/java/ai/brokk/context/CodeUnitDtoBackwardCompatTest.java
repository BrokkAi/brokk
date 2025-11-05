package ai.brokk.context;

import static org.junit.jupiter.api.Assertions.*;

import ai.brokk.analyzer.CodeUnit;
import ai.brokk.analyzer.CodeUnitType;
import ai.brokk.analyzer.ProjectFile;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.nio.file.Path;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/**
 * Tests to verify backward-compatible deserialization when CodeUnitDto lacks the optional 'signature' field,
 * and to assert that CodeUnit equality/hash do not depend on the optional signature.
 */
public class CodeUnitDtoBackwardCompatTest {

    @TempDir
    Path tempDir;

    @Test
    public void jacksonDeserializesMissingSignatureAsNull() throws Exception {
        var json =
                """
                {
                  "sourceFile": { "id": "0", "repoRoot": "%s", "relPath": "src/A.cpp" },
                  "kind": "FUNCTION",
                  "packageName": "pkg",
                  "shortName": "A.foo"
                }
                """
                        .formatted(tempDir.toString().replace("\\", "\\\\"));

        ObjectMapper mapper = new ObjectMapper();
        // Allow comments in the inlined JSON test fixture (some historical fixtures may contain comments)
        mapper.configure(com.fasterxml.jackson.core.JsonParser.Feature.ALLOW_COMMENTS, true);
        // Deserialize into the DTO defined in FragmentDtos
        FragmentDtos.CodeUnitDto dto = mapper.readValue(json, FragmentDtos.CodeUnitDto.class);

        // The absent 'signature' field should map to null
        assertNull(dto.signature(), "Missing 'signature' field should deserialize to null");
    }

    @Test
    public void codeUnitEqualityIncludesSignature() {
        // Prepare a ProjectFile and common identity fields
        ProjectFile pf = new ProjectFile(tempDir, Path.of("src/A.cpp"));
        CodeUnitType kind = CodeUnitType.FUNCTION;
        String pkg = "pkg";
        String shortName = "A.foo";

        // One CodeUnit without signature (legacy)
        CodeUnit cuLegacy = new CodeUnit(pf, kind, pkg, shortName, null);

        // Same CodeUnit but with a recorded signature (newer)
        CodeUnit cuWithSig = new CodeUnit(pf, kind, pkg, shortName, "(int)");

        // fqName should be computed solely from packageName and shortName
        assertEquals(cuLegacy.fqName(), cuWithSig.fqName(), "fqName must be identical regardless of signature");

        // equals/hashCode now INCLUDE signature for overload disambiguation
        assertNotEquals(cuLegacy, cuWithSig, "CodeUnit equality must consider signature for overload distinction");
        assertNotEquals(cuLegacy.hashCode(), cuWithSig.hashCode(), "Hash codes must differ when signature differs");

        // hasSignature reflects presence/absence
        assertFalse(cuLegacy.hasSignature(), "Legacy CodeUnit should report no signature");
        assertTrue(cuWithSig.hasSignature(), "CodeUnit with signature should report presence");

        // Two CodeUnits with same signature are equal
        CodeUnit cuWithSig2 = new CodeUnit(pf, kind, pkg, shortName, "(int)");
        assertEquals(cuWithSig, cuWithSig2, "CodeUnits with same signature should be equal");
        assertEquals(cuWithSig.hashCode(), cuWithSig2.hashCode(), "Hash codes should match for same signature");
    }

    @Test
    public void codeUnitDtoRoundTripPreservesSignature() throws Exception {
        ProjectFile pf = new ProjectFile(tempDir, Path.of("src/A.cpp"));
        // Create a CodeUnit with a non-null signature
        CodeUnit original = new CodeUnit(pf, CodeUnitType.FUNCTION, "pkg", "A.foo", "(int)");

        // Build a CodeUnitDto representing it
        FragmentDtos.ProjectFileDto pfd = new FragmentDtos.ProjectFileDto(
                "0", pf.getRoot().toString(), pf.getRelPath().toString());
        FragmentDtos.CodeUnitDto dto = new FragmentDtos.CodeUnitDto(
                pfd, original.kind().name(), original.packageName(), original.shortName(), original.signature());

        ObjectMapper mapper = new ObjectMapper();
        mapper.configure(com.fasterxml.jackson.core.JsonParser.Feature.ALLOW_COMMENTS, true);

        // Serialize -> JSON -> Deserialize
        String json = mapper.writeValueAsString(dto);
        FragmentDtos.CodeUnitDto dto2 = mapper.readValue(json, FragmentDtos.CodeUnitDto.class);

        // Reconstruct a CodeUnit from the deserialized DTO
        ProjectFile pf2 = new ProjectFile(
                Path.of(dto2.sourceFile().repoRoot()), Path.of(dto2.sourceFile().relPath()));
        CodeUnit roundTripped = new CodeUnit(
                pf2, CodeUnitType.valueOf(dto2.kind()), dto2.packageName(), dto2.shortName(), dto2.signature());

        // Assertions: signature preserved, fqName still derived from shortName
        assertEquals(original.signature(), dto2.signature(), "DTO should contain the signature after round-trip");
        assertEquals(
                original.signature(), roundTripped.signature(), "Reconstructed CodeUnit should preserve the signature");
        assertEquals(
                original.fqName(),
                roundTripped.fqName(),
                "FQ name derivation must remain based on packageName+shortName");
    }

    @Test
    public void codeUnitFromDtoWithoutSignatureProducesNullSignature() throws Exception {
        var json =
                """
                {
                  "sourceFile": { "id": "0", "repoRoot": "%s", "relPath": "src/A.cpp" },
                  "kind": "FUNCTION",
                  "packageName": "pkg",
                  "shortName": "A.foo"
                }
                """
                        .formatted(tempDir.toString().replace("\\", "\\\\"));

        ObjectMapper mapper = new ObjectMapper();
        mapper.configure(com.fasterxml.jackson.core.JsonParser.Feature.ALLOW_COMMENTS, true);

        FragmentDtos.CodeUnitDto dto = mapper.readValue(json, FragmentDtos.CodeUnitDto.class);

        // Construct CodeUnit using dto.signature() which should be null for legacy JSON
        ProjectFile pf = new ProjectFile(
                Path.of(dto.sourceFile().repoRoot()), Path.of(dto.sourceFile().relPath()));
        CodeUnit cu =
                new CodeUnit(pf, CodeUnitType.valueOf(dto.kind()), dto.packageName(), dto.shortName(), dto.signature());

        assertNull(cu.signature(), "Missing signature in DTO should produce a null CodeUnit.signature()");
        // fqName should still be derived from packageName + shortName
        assertEquals("pkg.A.foo", cu.fqName(), "fqName must still be derived from packageName + shortName");
    }
}
