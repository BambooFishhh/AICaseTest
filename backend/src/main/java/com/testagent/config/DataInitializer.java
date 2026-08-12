package com.testagent.config;

import com.testagent.entity.Project;
import com.testagent.entity.User;
import com.testagent.repository.ProjectRepository;
import com.testagent.repository.UserRepository;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.CommandLineRunner;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.stereotype.Component;

import java.util.List;
import java.util.UUID;

/**
 * v4.0: 启动初始化——默认管理员 + 存量项目归属迁移。
 */
@Component
public class DataInitializer implements CommandLineRunner {

    private static final Logger log = LoggerFactory.getLogger(DataInitializer.class);

    @Autowired
    private UserRepository userRepository;

    @Autowired
    private ProjectRepository projectRepository;

    @Autowired
    private PasswordEncoder passwordEncoder;

    @Value("${app.admin.password:admin123}")
    private String adminPassword;

    @Override
    public void run(String... args) {
        User admin = userRepository.findByUsername("admin").orElse(null);
        if (admin == null) {
            admin = new User();
            admin.setId(UUID.randomUUID().toString());
            admin.setUsername("admin");
            admin.setPasswordHash(passwordEncoder.encode(adminPassword));
            admin.setDisplayName("管理员");
            admin.setRole("ADMIN");
            userRepository.save(admin);
            log.info("v4.0: 已创建默认管理员 admin（请尽快修改初始密码）");
        }

        // 存量项目归属迁移：userId 为空 → 归默认管理员
        List<Project> orphanProjects = projectRepository.findAll().stream()
                .filter(p -> p.getUserId() == null || p.getUserId().isBlank())
                .toList();
        if (!orphanProjects.isEmpty()) {
            for (Project p : orphanProjects) {
                p.setUserId(admin.getId());
                projectRepository.save(p);
            }
            log.info("v4.0: 已将 {} 个存量项目归属到默认管理员", orphanProjects.size());
        }
    }
}
