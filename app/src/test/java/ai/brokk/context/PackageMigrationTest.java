package ai.brokk.context;

import static org.junit.jupiter.api.Assertions.*;

import org.junit.jupiter.api.Test;

/**
 * Test suite for verifying package name migration from io.github.jbellis.brokk to ai.brokk.
 * This addresses the package rename that occurred in November 2025.
 */
class PackageMigrationTest {

    @Test
    void testMigrateOldPackageToNew() {
        var oldFqName = "io.github.jbellis.brokk.gui.components.OverlayPanel";
        var migrated = DtoMapper.migratePackageName(oldFqName);
        assertEquals("ai.brokk.gui.components.OverlayPanel", migrated);
    }

    @Test
    void testMigrateOldPackageWithMemberToNew() {
        var oldFqName = "io.github.jbellis.brokk.gui.components.OverlayPanel.isBlocking";
        var migrated = DtoMapper.migratePackageName(oldFqName);
        assertEquals("ai.brokk.gui.components.OverlayPanel.isBlocking", migrated);
    }

    @Test
    void testNewPackageUnchanged() {
        var newFqName = "ai.brokk.gui.mop.MarkdownOutputPanel";
        var migrated = DtoMapper.migratePackageName(newFqName);
        assertEquals("ai.brokk.gui.mop.MarkdownOutputPanel", migrated);
    }

    @Test
    void testNonBrokkPackageUnchanged() {
        var otherPackage = "java.lang.String";
        var migrated = DtoMapper.migratePackageName(otherPackage);
        assertEquals("java.lang.String", migrated);
    }

    @Test
    void testSimilarButNotExactPackageUnchanged() {
        // Should not match partial package names
        var similarPackage = "io.github.jbellis.brokkabc.Something";
        var migrated = DtoMapper.migratePackageName(similarPackage);
        assertEquals("io.github.jbellis.brokkabc.Something", migrated);
    }
}
