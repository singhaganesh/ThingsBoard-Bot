package com.seple.ThingsBoard_Bot.config;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.BeansException;
import org.springframework.beans.factory.config.BeanFactoryPostProcessor;
import org.springframework.beans.factory.config.ConfigurableListableBeanFactory;
import org.springframework.core.env.Environment;
import org.springframework.stereotype.Component;

import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.ResultSet;
import java.sql.Statement;

@Component
public class DatabaseInitializer implements BeanFactoryPostProcessor {

    private static final Logger log = LoggerFactory.getLogger(DatabaseInitializer.class);

    @Override
    public void postProcessBeanFactory(ConfigurableListableBeanFactory beanFactory) throws BeansException {
        Environment env = beanFactory.getBean(Environment.class);
        
        boolean autoCreate = Boolean.parseBoolean(env.getProperty("app.database.create-if-not-exist", "true"));
        if (!autoCreate) {
            log.info("Database auto-creation is disabled");
            return;
        }

        String datasourceUrl = env.getProperty("spring.datasource.url");
        String username = env.getProperty("spring.datasource.username");
        String password = env.getProperty("spring.datasource.password");

        try {
            String dbName = extractDatabaseName(datasourceUrl);
            log.info("Checking if database '{}' exists on PostgreSQL server...", dbName);

            if (databaseExists(dbName, datasourceUrl, username, password)) {
                log.info("Database '{}' already exists", dbName);
                return;
            }

            createDatabase(dbName, datasourceUrl, username, password);
            log.info("✅ Database '{}' created successfully", dbName);

        } catch (Exception e) {
            log.error("❌ Failed to initialize database: {}", e.getMessage(), e);
            throw new RuntimeException("Database initialization failed", e);
        }
    }

    private String extractDatabaseName(String url) {
        int lastSlash = url.lastIndexOf('/');
        int questionMark = url.indexOf('?', lastSlash);
        if (questionMark > 0) {
            return url.substring(lastSlash + 1, questionMark);
        }
        return url.substring(lastSlash + 1);
    }

    private boolean databaseExists(String dbName, String datasourceUrl, String username, String password) throws Exception {
        String baseUrl = getBaseJdbcUrl(datasourceUrl);
        String checkSql = "SELECT 1 FROM pg_database WHERE datname = '" + dbName + "'";

        try (Connection conn = DriverManager.getConnection(baseUrl, username, password);
             Statement stmt = conn.createStatement();
             ResultSet rs = stmt.executeQuery(checkSql)) {
            return rs.next();
        }
    }

    private void createDatabase(String dbName, String datasourceUrl, String username, String password) throws Exception {
        String baseUrl = getBaseJdbcUrl(datasourceUrl);

        try (Connection conn = DriverManager.getConnection(baseUrl, username, password);
             Statement stmt = conn.createStatement()) {
            stmt.execute("CREATE DATABASE " + dbName);
        }
    }

    private String getBaseJdbcUrl(String url) {
        int lastSlash = url.lastIndexOf('/');
        String hostPort = url.substring(url.indexOf("://") + 2, lastSlash);
        String jdbcPrefix = url.substring(0, url.indexOf("://") + 2);
        return jdbcPrefix + hostPort + "/postgres";
    }
}