package in.ps108.model;

import java.math.BigDecimal;
import java.util.LinkedHashMap;
import java.util.Map;

public record Vendor(String id, String name, Category category, Map<String, BigDecimal> packages,
                     BigDecimal commissionRate) {
    public enum Category { CATERING, DECOR, PHOTOGRAPHY }

    public Vendor {
        if (id == null || id.isBlank()) throw new IllegalArgumentException("Vendor id is required");
        if (name == null || name.isBlank()) throw new IllegalArgumentException("Vendor name is required");
        if (category == null) throw new IllegalArgumentException("Vendor category is required");
        if (packages == null || packages.isEmpty()) throw new IllegalArgumentException("At least one vendor package is required");
        Map<String,BigDecimal> normalizedPackages=new LinkedHashMap<>();
        for (var entry : packages.entrySet()) {
            if (entry.getKey() == null || entry.getKey().isBlank()) throw new IllegalArgumentException("Package name is required");
            if (entry.getValue() == null || entry.getValue().signum() < 0) throw new IllegalArgumentException("Package price must be zero or greater");
            String key=entry.getKey().trim().toUpperCase(java.util.Locale.ROOT);
            if (normalizedPackages.putIfAbsent(key,entry.getValue()) != null) throw new IllegalArgumentException("Duplicate package name: "+key);
        }
        packages = Map.copyOf(normalizedPackages);
        if (commissionRate == null || commissionRate.signum() < 0 || commissionRate.compareTo(BigDecimal.ONE) > 0)
            throw new IllegalArgumentException("Commission rate must be between 0 and 1");
        BigDecimal requiredRate=category==Category.CATERING?new BigDecimal("0.08"):new BigDecimal("0.15");
        if(commissionRate.compareTo(requiredRate)!=0) throw new IllegalArgumentException("Commission rate for "+category+" must be "+requiredRate.toPlainString());
    }

    public BigDecimal packagePrice(String packageName) {
        if (packageName == null || packageName.isBlank()) throw new IllegalArgumentException("Package name is required");
        BigDecimal price = packages.get(packageName.trim().toUpperCase(java.util.Locale.ROOT));
        if (price == null) throw new IllegalArgumentException("Unknown package " + packageName + " for vendor " + id);
        return price;
    }
}
