package io.github.jbellis.brokk.analyzer;

import io.github.jbellis.brokk.util.Json;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.File;
import java.nio.file.Files;
import java.nio.file.Path;

import static org.junit.jupiter.api.Assertions.*;

public class FileSerializationTest {

    @TempDir
    Path tempDir;

    @Test
    void testProjectFileJsonSerialization() throws Exception {
        // Create a test ProjectFile
        Path root = tempDir.resolve("project").toAbsolutePath().normalize();
        Path relPath = Path.of("src/main/java/Test.java");
        Files.createDirectories(root);
        
        ProjectFile original = new ProjectFile(root, relPath);
        
        // Serialize to JSON
        String json = Json.toJson(original);
        assertNotNull(json);
        assertTrue(json.contains("root"));
        assertTrue(json.contains("relPath"));
        
        // Deserialize from JSON
        ProjectFile deserialized = Json.fromJson(json, ProjectFile.class);
        
        // Verify equality
        assertEquals(original, deserialized);
        assertEquals(original.toString(), deserialized.toString());
        assertEquals(original.absPath(), deserialized.absPath());
        assertEquals(original.getParent(), deserialized.getParent());
    }

    @Test
    void testExternalFileJsonSerialization() throws Exception {
        // Create a test ExternalFile
        Path absolutePath = tempDir.resolve("external.txt").toAbsolutePath().normalize();
        Files.createFile(absolutePath);
        
        ExternalFile original = new ExternalFile(absolutePath);
        
        // Serialize to JSON
        String json = Json.toJson(original);
        assertNotNull(json);
        assertTrue(json.contains("path"));
        
        // Deserialize from JSON
        ExternalFile deserialized = Json.fromJson(json, ExternalFile.class);
        
        // Verify equality
        assertEquals(original.toString(), deserialized.toString());
        assertEquals(original.absPath(), deserialized.absPath());
    }

    @Test
    void testProjectFileValidationDuringDeserialization() {
        // Test that validation is enforced during JSON deserialization
        
        // Test with non-absolute root
        String invalidRootJson = """
            {
              "root" : "relative/path",
              "relPath" : "src/Test.java"
            }
            """;
        
        assertThrows(Exception.class, () -> 
            Json.fromJson(invalidRootJson, ProjectFile.class));
        
        // Test with absolute relPath
        String invalidRelPathJson = String.format("""
            {
              "root" : "%s",
              "relPath" : "%s"
            }
            """, tempDir.toAbsolutePath().toString(), tempDir.resolve("absolute.java").toAbsolutePath().toString());
        
        assertThrows(Exception.class, () -> 
            Json.fromJson(invalidRelPathJson, ProjectFile.class));
    }

    @Test
    void testExternalFileValidationDuringDeserialization() {
        // Test that validation is enforced during JSON deserialization
        
        // Test with relative path
        String invalidPathJson = """
            {
              "path" : "relative/path.txt"
            }
            """;
        
        assertThrows(Exception.class, () -> 
            Json.fromJson(invalidPathJson, ExternalFile.class));
    }

    @Test
    void testPathsWithSpecialCharacters() throws Exception {
        // Test ProjectFile with special characters
        Path root = tempDir.resolve("project with spaces").toAbsolutePath().normalize();
        Path relPath = Path.of("src/main/java/Test$Inner.java");
        Files.createDirectories(root);
        
        ProjectFile original = new ProjectFile(root, relPath);
        String json = Json.toJson(original);
        ProjectFile deserialized = Json.fromJson(json, ProjectFile.class);
        assertEquals(original, deserialized);
        
        // Test ExternalFile with special characters
        Path specialPath = tempDir.resolve("file with spaces & symbols.txt").toAbsolutePath().normalize();
        Files.createFile(specialPath);
        
        ExternalFile originalExternal = new ExternalFile(specialPath);
        String externalJson = Json.toJson(originalExternal);
        ExternalFile deserializedExternal = Json.fromJson(externalJson, ExternalFile.class);
        assertEquals(originalExternal.toString(), deserializedExternal.toString());
    }

    @Test
    void testProjectFileRoundTripWithFileOperations() throws Exception {
        // Create a ProjectFile and test that it works after round-trip serialization
        Path root = tempDir.resolve("project").toAbsolutePath().normalize();
        Path relPath = Path.of("src/Test.java");
        Files.createDirectories(root.resolve("src"));
        
        ProjectFile original = new ProjectFile(root, relPath);
        
        // Create the file and write content
        String testContent = "public class Test {}";
        original.write(testContent);
        
        // Serialize and deserialize
        String json = Json.toJson(original);
        ProjectFile deserialized = Json.fromJson(json, ProjectFile.class);
        
        // Verify the deserialized instance can read the file
        assertTrue(deserialized.exists());
        assertEquals(testContent, deserialized.read());
        assertEquals(original.absPath(), deserialized.absPath());
    }

    @Test
    void testExternalFileRoundTripWithFileOperations() throws Exception {
        // Create an ExternalFile and test that it works after round-trip serialization
        Path absolutePath = tempDir.resolve("external.txt").toAbsolutePath().normalize();
        String testContent = "External file content";
        Files.writeString(absolutePath, testContent);
        
        ExternalFile original = new ExternalFile(absolutePath);
        
        // Serialize and deserialize
        String json = Json.toJson(original);
        ExternalFile deserialized = Json.fromJson(json, ExternalFile.class);
        
        // Verify the deserialized instance can read the file
        assertTrue(deserialized.exists());
        assertEquals(testContent, deserialized.read());
        assertEquals(original.absPath(), deserialized.absPath());
    }

    @Test
    void testJsonStructure() throws Exception {
        // Verify the JSON structure is as expected
        Path root = tempDir.resolve("project").toAbsolutePath().normalize();
        ProjectFile projectFile = new ProjectFile(root, "src/Test.java");
        
        String json = Json.toJson(projectFile);
        
        // Parse back as generic object to check structure
        @SuppressWarnings("unchecked")
        var jsonMap = (java.util.Map<String, Object>) Json.getMapper().readValue(json, java.util.Map.class);
        
        assertTrue(jsonMap.containsKey("root"));
        assertTrue(jsonMap.containsKey("relPath"));
        assertEquals(root.toString(), jsonMap.get("root"));
        assertEquals(String.format("src%sTest.java", File.separator), jsonMap.get("relPath"));
        
        // Test ExternalFile structure
        Path externalPath = tempDir.resolve("external.txt").toAbsolutePath().normalize();
        Files.createFile(externalPath);
        ExternalFile externalFile = new ExternalFile(externalPath);
        
        String externalJson = Json.toJson(externalFile);
        @SuppressWarnings("unchecked")
        var externalJsonMap = (java.util.Map<String, Object>) Json.getMapper().readValue(externalJson, java.util.Map.class);
        
        assertTrue(externalJsonMap.containsKey("path"));
        assertEquals(externalPath.toString(), externalJsonMap.get("path"));
    }

    @Test
    void testCodeUnitJsonSerialization() throws Exception {
        // Create a test ProjectFile for CodeUnit
        Path root = tempDir.resolve("project").toAbsolutePath().normalize();
        Files.createDirectories(root);
        ProjectFile sourceFile = new ProjectFile(root, "src/main/java/Test.java");
        
        // Test CLASS CodeUnit
        CodeUnit classUnit = CodeUnit.cls(sourceFile, "com.example", "TestClass");
        
        String json = Json.toJson(classUnit);
        assertNotNull(json);
        assertTrue(json.contains("source"));
        assertTrue(json.contains("kind"));
        assertTrue(json.contains("packageName"));
        assertTrue(json.contains("shortName"));
        
        CodeUnit deserializedClass = Json.fromJson(json, CodeUnit.class);
        assertEquals(classUnit, deserializedClass);
        assertEquals(classUnit.fqName(), deserializedClass.fqName());
        assertEquals(classUnit.identifier(), deserializedClass.identifier());
        assertTrue(deserializedClass.isClass());
    }

    @Test
    void testCodeUnitFactoryMethodsAfterDeserialization() throws Exception {
        Path root = tempDir.resolve("project").toAbsolutePath().normalize();
        Files.createDirectories(root);
        ProjectFile sourceFile = new ProjectFile(root, "src/Test.java");
        
        // Test FUNCTION CodeUnit
        CodeUnit functionUnit = CodeUnit.fn(sourceFile, "com.example", "TestClass.testMethod");
        
        String json = Json.toJson(functionUnit);
        CodeUnit deserializedFunction = Json.fromJson(json, CodeUnit.class);
        
        assertEquals(functionUnit, deserializedFunction);
        assertEquals(functionUnit.fqName(), deserializedFunction.fqName());
        assertEquals(functionUnit.identifier(), deserializedFunction.identifier());
        assertTrue(deserializedFunction.isFunction());
        
        // Test FIELD CodeUnit
        CodeUnit fieldUnit = CodeUnit.field(sourceFile, "com.example", "TestClass.testField");
        
        String fieldJson = Json.toJson(fieldUnit);
        CodeUnit deserializedField = Json.fromJson(fieldJson, CodeUnit.class);
        
        assertEquals(fieldUnit, deserializedField);
        assertEquals(fieldUnit.fqName(), deserializedField.fqName());
        assertEquals(fieldUnit.identifier(), deserializedField.identifier());
        
        // Test MODULE CodeUnit
        CodeUnit moduleUnit = CodeUnit.module(sourceFile, "com.example", "_module_");
        
        String moduleJson = Json.toJson(moduleUnit);
        CodeUnit deserializedModule = Json.fromJson(moduleJson, CodeUnit.class);
        
        assertEquals(moduleUnit, deserializedModule);
        assertEquals(moduleUnit.fqName(), deserializedModule.fqName());
        assertEquals(moduleUnit.identifier(), deserializedModule.identifier());
        assertTrue(deserializedModule.isModule());
    }

    @Test
    void testCodeUnitWithEmptyPackageName() throws Exception {
        Path root = tempDir.resolve("project").toAbsolutePath().normalize();
        Files.createDirectories(root);
        ProjectFile sourceFile = new ProjectFile(root, "Test.java");
        
        // Test with empty package name
        CodeUnit classUnit = CodeUnit.cls(sourceFile, "", "TestClass");
        
        String json = Json.toJson(classUnit);
        CodeUnit deserialized = Json.fromJson(json, CodeUnit.class);
        
        assertEquals(classUnit, deserialized);
        assertEquals("TestClass", deserialized.fqName());
        assertEquals("", deserialized.packageName());
    }

    @Test
    void testCodeUnitClassUnit() throws Exception {
        Path root = tempDir.resolve("project").toAbsolutePath().normalize();
        Files.createDirectories(root);
        ProjectFile sourceFile = new ProjectFile(root, "src/Test.java");
        
        // Test classUnit() method works after deserialization
        CodeUnit functionUnit = CodeUnit.fn(sourceFile, "com.example", "TestClass.testMethod");
        
        String json = Json.toJson(functionUnit);
        CodeUnit deserialized = Json.fromJson(json, CodeUnit.class);
        
        var originalClassUnit = functionUnit.classUnit();
        var deserializedClassUnit = deserialized.classUnit();
        
        assertEquals(originalClassUnit.isPresent(), deserializedClassUnit.isPresent());
        if (originalClassUnit.isPresent()) {
            assertEquals(originalClassUnit.get(), deserializedClassUnit.get());
        }
    }

    @Test
    void testCodeUnitValidationDuringDeserialization() {
        // Test that validation is enforced during JSON deserialization
        
        // Test with null shortName
        String invalidShortNameJson = String.format("""
            {
              "source" : {
                "root" : "%s",
                "relPath" : "Test.java"
              },
              "kind" : "CLASS",
              "packageName" : "com.example",
              "shortName" : null
            }
            """, tempDir.toAbsolutePath().toString());
        
        assertThrows(Exception.class, () -> 
            Json.fromJson(invalidShortNameJson, CodeUnit.class));
        
        // Test with empty shortName
        String emptyShortNameJson = String.format("""
            {
              "source" : {
                "root" : "%s",
                "relPath" : "Test.java"
              },
              "kind" : "CLASS",
              "packageName" : "com.example",
              "shortName" : ""
            }
            """, tempDir.toAbsolutePath().toString());
        
        assertThrows(Exception.class, () -> 
            Json.fromJson(emptyShortNameJson, CodeUnit.class));
    }

    @Test
    void testCodeUnitJsonStructure() throws Exception {
        Path root = tempDir.resolve("project").toAbsolutePath().normalize();
        Files.createDirectories(root);
        ProjectFile sourceFile = new ProjectFile(root, "src/Test.java");
        
        CodeUnit codeUnit = CodeUnit.cls(sourceFile, "com.example", "TestClass");
        String json = Json.toJson(codeUnit);
        
        // Parse back as generic object to check structure
        @SuppressWarnings("unchecked")
        var jsonMap = (java.util.Map<String, Object>) Json.getMapper().readValue(json, java.util.Map.class);
        
        assertTrue(jsonMap.containsKey("source"));
        assertTrue(jsonMap.containsKey("kind"));
        assertTrue(jsonMap.containsKey("packageName"));
        assertTrue(jsonMap.containsKey("shortName"));
        
        assertEquals("CLASS", jsonMap.get("kind"));
        assertEquals("com.example", jsonMap.get("packageName"));
        assertEquals("TestClass", jsonMap.get("shortName"));
        
        // Verify source is a nested object with correct structure
        @SuppressWarnings("unchecked")
        var sourceMap = (java.util.Map<String, Object>) jsonMap.get("source");
        assertTrue(sourceMap.containsKey("root"));
        assertTrue(sourceMap.containsKey("relPath"));
    }
}
