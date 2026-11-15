package in.ps108.model;

import org.junit.jupiter.api.Test;

import java.math.BigDecimal;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;

class VendorTest {
    @Test void commissionsMustMatchDocumentedCategoryRates() {
        assertDoesNotThrow(() -> new Vendor("C","Catering",Vendor.Category.CATERING,Map.of("BASIC",BigDecimal.ONE),new BigDecimal("0.08")));
        assertDoesNotThrow(() -> new Vendor("D","Decor",Vendor.Category.DECOR,Map.of("BASIC",BigDecimal.ONE),new BigDecimal("0.15")));
        assertDoesNotThrow(() -> new Vendor("P","Photo",Vendor.Category.PHOTOGRAPHY,Map.of("BASIC",BigDecimal.ONE),new BigDecimal("0.15")));
        assertThrows(IllegalArgumentException.class,() -> new Vendor("D2","Decor",Vendor.Category.DECOR,Map.of("BASIC",BigDecimal.ONE),new BigDecimal("0.20")));
    }

    @Test void invalidVendorPackagesAreRejected() {
        assertThrows(IllegalArgumentException.class,() -> new Vendor("D","Decor",Vendor.Category.DECOR,Map.of("BASIC",new BigDecimal("-1")),new BigDecimal("0.15")));
        assertThrows(IllegalArgumentException.class,() -> new Vendor("D","Decor",Vendor.Category.DECOR,null,new BigDecimal("0.15")));
    }
}
