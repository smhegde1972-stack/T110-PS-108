package in.ps108.model;

import java.math.BigDecimal;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.Map;

public record Quote(BigDecimal venue, BigDecimal catering, BigDecimal packages,
                    BigDecimal switchFees, BigDecimal damages, BigDecimal venueAndPackageTax,
                    BigDecimal cateringTax, BigDecimal totalBeforeAdvances, BigDecimal balance,
                    BigDecimal dateChangeCreditApplied, Map<String, BigDecimal> packageLines) {
    public Quote { packageLines = Collections.unmodifiableMap(new LinkedHashMap<>(packageLines)); }
}
