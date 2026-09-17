package org.example.lawsrag;

import org.example.lawsrag.service.QdrantMetadataMigrationService;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;

import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 手工执行的一次性真实Qdrant迁移测试。
 * 运行该测试会直接更新payload并删除完全重复的Point。
 */
@SpringBootTest
class QdrantMetadataMigrationTest {

    @Autowired
    private QdrantMetadataMigrationService migrationService;

    @Test
    void migrateStoredPayloads() {
        var report = migrationService.migrate(true);
        System.out.println("Qdrant migration report: " + report);
        assertTrue(report.applied());
        assertTrue(report.totalPoints() >= report.validPoints());
    }
}
