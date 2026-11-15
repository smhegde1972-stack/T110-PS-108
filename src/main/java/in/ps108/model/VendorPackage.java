package in.ps108.model;

import java.math.BigDecimal;
import java.util.Objects;

/** Represents a vendor package loaded from vendor_packages.csv. */
public record VendorPackage(String vendorId, String packageCode, String packageName, BigDecimal price) {
    public VendorPackage {
        Objects.requireNonNull(vendorId, "Vendor ID is required");
        Objects.requireNonNull(packageCode, "Package code is required");
        Objects.requireNonNull(packageName, "Package name is required");
        Objects.requireNonNull(price, "Package price is required");
        if (price.signum() < 0) throw new IllegalArgumentException("Package price must be non-negative");
    }
}
