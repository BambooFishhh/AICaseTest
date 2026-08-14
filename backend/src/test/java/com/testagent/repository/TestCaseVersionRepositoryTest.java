package com.testagent.repository;

import com.testagent.entity.TestCaseVersion;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.orm.jpa.DataJpaTest;

import java.time.LocalDateTime;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * vT2: JPA Repository 集成测试（H2）。
 */
@DataJpaTest
class TestCaseVersionRepositoryTest {

    @Autowired
    private TestCaseVersionRepository repository;

    @Test
    void saveFindAndDeleteByProject() {
        TestCaseVersion version = new TestCaseVersion();
        version.setId("v-1");
        version.setTestCaseId("TC-001");
        version.setProjectId("p1");
        version.setVersionNo(1);
        version.setSnapshot("{\"title\":\"t\"}");
        version.setAction("edit");
        version.setCreatedAt(LocalDateTime.now());
        repository.save(version);

        assertEquals(1, repository.findByTestCaseIdOrderByVersionNoDesc("TC-001").size());
        assertEquals(1, repository.countByTestCaseId("TC-001"));

        repository.deleteByProjectId("p1");
        assertTrue(repository.findByTestCaseIdOrderByVersionNoDesc("TC-001").isEmpty());
    }
}
