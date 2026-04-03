package com.seple.ThingsBoard_Bot.service.normalization;

import java.util.HashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;

import org.springframework.stereotype.Component;

import com.seple.ThingsBoard_Bot.model.domain.BranchSnapshot;

@Component
public class BranchAliasIndex {

    public Map<String, BranchSnapshot> build(List<BranchSnapshot> snapshots) {
        Map<String, BranchSnapshot> aliases = new HashMap<>();
        for (BranchSnapshot snapshot : snapshots) {
            if (snapshot.getIdentity() == null || snapshot.getIdentity().getAliases() == null) {
                continue;
            }
            for (String alias : snapshot.getIdentity().getAliases()) {
                for (String variant : aliasVariants(alias)) {
                    aliases.put(variant, snapshot);
                }
            }
        }
        return aliases;
    }

    public String normalize(String alias) {
        if (alias == null) {
            return "";
        }
        return alias.toUpperCase(Locale.ROOT)
                .replace("BOI-", "")
                .replace("BOI", "BOI ")
                .replace("BRANCH ", "")
                .replace('-', ' ')
                .replace('_', ' ')
                .replaceAll("\\s+", " ")
                .trim();
    }

    public String compact(String alias) {
        return normalize(alias).replace(" ", "");
    }

    public Set<String> aliasVariants(String alias) {
        Set<String> variants = new LinkedHashSet<>();
        String normalized = normalize(alias);
        String compact = compact(alias);
        addVariant(variants, normalized);
        addVariant(variants, compact);
        addVariant(variants, normalized.replace(" ", "-"));
        addVariant(variants, normalized.replace("-", " "));

        if (normalized.endsWith(" TESTING DEVICE")) {
            String simplified = normalized.replace(" TESTING DEVICE", "").trim();
            addVariant(variants, simplified);
            addVariant(variants, compact(simplified));
        }

        if (normalized.endsWith(" DEVICE")) {
            String simplified = normalized.replace(" DEVICE", "").trim();
            addVariant(variants, simplified);
            addVariant(variants, compact(simplified));
        }
        return variants;
    }

    private void addVariant(Set<String> variants, String value) {
        if (value != null && !value.isBlank()) {
            variants.add(value);
        }
    }
}
