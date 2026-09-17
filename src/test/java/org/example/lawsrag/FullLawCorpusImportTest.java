package org.example.lawsrag;

import org.example.lawsrag.service.FullLawCorpusImportService;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;

import java.nio.file.Path;

import static org.junit.jupiter.api.Assertions.assertTrue;

@SpringBootTest
@EnabledIfEnvironmentVariable(named = "RUN_FULL_LAW_INGEST", matches = "true")
class FullLawCorpusImportTest {

    @Autowired
    private FullLawCorpusImportService importService;

    @Test
    void importAllCurrentLaws() throws Exception {
        String source = System.getenv().getOrDefault("FULL_LAW_DOCS_PATH",
                "C:/Users/86180/WebStormProjects/just-laws/docs");
        boolean dryRun = Boolean.parseBoolean(System.getenv()
                .getOrDefault("FULL_LAW_DRY_RUN", "false"));
        var report = dryRun
                ? importService.scan(Path.of(source))
                : importService.importAll(Path.of(source));
        System.out.printf("FULL_IMPORT files=%d, filesWithArticles=%d, parsed=%d, unique=%d, "
                        + "duplicates=%d, inserted=%d%n",
                report.filesScanned(), report.filesWithArticles(), report.parsedArticles(),
                report.uniqueArticles(), report.duplicateArticles(), report.insertedArticles());
        if (!report.conflicts().isEmpty()) {
            report.conflicts().stream().limit(20).forEach(System.out::println);
        }
        assertTrue(report.uniqueArticles() > 0);
        assertTrue(report.conflicts().isEmpty(), "存在business_id正文冲突，禁止全量导入");
    }
}
