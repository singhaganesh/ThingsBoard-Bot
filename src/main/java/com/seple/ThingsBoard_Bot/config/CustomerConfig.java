package com.seple.ThingsBoard_Bot.config;

import lombok.Data;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.context.annotation.Configuration;

import java.util.List;

@Data
@Configuration
@ConfigurationProperties(prefix = "iotchatbot.customers")
public class CustomerConfig {

    private List<String> prefixes = List.of("BOI", "BOB", "SBI", "CB", "IB", "PNB", "UBI", "CBI", "IOB", "UCO");

    private String defaultCustomer = "BOI";

    public boolean isValidCustomer(String customerId) {
        return prefixes.contains(customerId);
    }

    public String extractCustomerFromDeviceName(String deviceName) {
        for (String prefix : prefixes) {
            if (deviceName.toUpperCase().startsWith(prefix + "-")) {
                return prefix;
            }
        }
        return defaultCustomer;
    }

    public String extractBranchFromDeviceName(String deviceName) {
        for (String prefix : prefixes) {
            if (deviceName.toUpperCase().startsWith(prefix + "-")) {
                return deviceName.substring(prefix.length() + 1);
            }
        }
        return deviceName;
    }
}