package org.example.lawsrag;

import org.example.lawsrag.service.QdrantHybridMigrationService;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** 手工执行的一次性真实迁移；源集合只读，目标集合采用幂等upsert。 */
@SpringBootTest
class QdrantHybridMigrationTest {

    @Autowired
    private QdrantHybridMigrationService migrationService;

    @Test
    void migrateDenseCollectionToHybridCollection() {
        var report = migrationService.migrate();
        System.out.println("Qdrant hybrid migration report: " + report);
        assertTrue(report.scanned() > 0);
        assertEquals(report.scanned(), report.migrated() + report.skipped());
    }
}
