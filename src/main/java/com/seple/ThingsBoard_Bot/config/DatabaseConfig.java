package com.seple.ThingsBoard_Bot.config;

import lombok.Data;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.context.annotation.Configuration;

@Data
@Configuration
@ConfigurationProperties(prefix = "spring.datasource")
public class DatabaseConfig {

    private String url = "jdbc:postgresql://localhost:5432/iot_platform";
    private String username = "iot_user";
    private String password = "iot_pass";
    private String driverClassName = "org.postgresql.Driver";

    public boolean isPostgresql() {
        return url != null && url.contains("postgresql");
    }

    public String getHost() {
        if (url == null) return "localhost";
        String temp = url.replace("jdbc:postgresql://", "");
        if (temp.contains(":")) {
            return temp.substring(0, temp.indexOf(":"));
        }
        return temp.split("/")[0];
    }

    public int getPort() {
        if (url == null) return 5432;
        String temp = url.replace("jdbc:postgresql://", "");
        if (temp.contains(":")) {
            String portPart = temp.substring(temp.indexOf(":") + 1);
            return Integer.parseInt(portPart.split("/")[0]);
        }
        return 5432;
    }

    public String getDatabaseName() {
        if (url == null) return "iot_platform";
        String[] parts = url.split("/");
        return parts[parts.length - 1];
    }
}