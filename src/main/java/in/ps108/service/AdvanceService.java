package in.ps108.service;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.temporal.ChronoUnit;
import java.util.ArrayList;
import java.util.List;

public class AdvanceService {
    public record Tranche(String name, LocalDate dueDate, BigDecimal amount) {}
    public List<Tranche> schedule(BigDecimal contractValue, LocalDate confirmationDate, LocalDate eventDate) {
        if(contractValue==null||confirmationDate==null||eventDate==null) throw new IllegalArgumentException("Contract value and schedule dates are required");
        if (contractValue.signum() < 0 || confirmationDate.isAfter(eventDate)) throw new IllegalArgumentException("Invalid schedule dates/value");
        long days = ChronoUnit.DAYS.between(confirmationDate, eventDate);
        BigDecimal total = Money.round(contractValue);
        if (days <= 7) return List.of(new Tranche("FULL_AT_CONFIRMATION", confirmationDate, total));
        if (days <= 60) return List.of(
                new Tranche("MERGED_75_PERCENT", confirmationDate, Money.pct(total, "75")),
                new Tranche("BALANCE_25_PERCENT", eventDate.minusDays(7), Money.round(total.subtract(Money.pct(total, "75")))));
        BigDecimal first=Money.pct(total,"25"), second=Money.pct(total,"50");
        BigDecimal third=Money.round(total.subtract(first).subtract(second));
        return List.of(new Tranche("CONFIRMATION_25_PERCENT",confirmationDate,first),
                new Tranche("T_MINUS_60_50_PERCENT",eventDate.minusDays(60),second),
                new Tranche("T_MINUS_7_BALANCE",eventDate.minusDays(7),third));
    }
    public BigDecimal lateFee(BigDecimal overdueTranche, LocalDate dueDate, LocalDate asOfDate) {
        if(overdueTranche==null||overdueTranche.signum()<0||dueDate==null||asOfDate==null) throw new IllegalArgumentException("Late-fee amount and dates are invalid");
        long overdueDays=ChronoUnit.DAYS.between(dueDate,asOfDate);
        if(overdueDays<=5) return Money.ZERO;
        // Count each started 7-day period after the 5-day grace; avoid floating-point rounding.
        long daysAfterGrace=overdueDays-5;
        long weeks=(daysAfterGrace+6)/7;
        return Money.round(overdueTranche.multiply(new BigDecimal("0.01")).multiply(BigDecimal.valueOf(weeks)));
    }
    public boolean autoCancel(LocalDate dueDate, LocalDate asOfDate) {
        if(dueDate==null||asOfDate==null) throw new IllegalArgumentException("Auto-cancellation dates are required");
        return ChronoUnit.DAYS.between(dueDate,asOfDate) >= 21;
    }
}
