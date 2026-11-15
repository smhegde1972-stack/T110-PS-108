package in.ps108.service;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.*;

/** Tracks tranche payments, late fees, and the 21-day overdue auto-cancellation trigger. */
public class AdvanceLedger {
    public static final class Account {
        private final AdvanceService.Tranche tranche;
        private BigDecimal paid=BigDecimal.ZERO;
        private BigDecimal lateFeesAccrued=BigDecimal.ZERO;
        Account(AdvanceService.Tranche tranche){this.tranche=tranche;}
        public AdvanceService.Tranche tranche(){return tranche;}
        public BigDecimal paid(){return paid;}
        public BigDecimal lateFeesAccrued(){return lateFeesAccrued;}
        public BigDecimal outstanding(){return Money.round(tranche.amount().subtract(paid).max(BigDecimal.ZERO));}
        private void pay(BigDecimal amount,LocalDate paymentDate,AdvanceService service){
            if(amount.signum()<=0)throw new IllegalArgumentException("Payment must be positive");
            if(amount.compareTo(outstanding())>0)throw new IllegalArgumentException("Payment exceeds tranche balance");
            // Persist the fee assessed on the amount paid late so it does not disappear after the tranche is cleared.
            lateFeesAccrued=Money.round(lateFeesAccrued.add(service.lateFee(amount,tranche.dueDate(),paymentDate)));
            paid=Money.round(paid.add(amount));
        }
    }
    public record Status(BigDecimal totalOutstanding, BigDecimal lateFees, boolean autoCancel) {}
    private final List<Account> accounts;
    private final AdvanceService service;
    public AdvanceLedger(List<AdvanceService.Tranche> schedule,AdvanceService service){
        if(schedule==null||service==null) throw new IllegalArgumentException("Advance schedule and service are required");
        this.accounts=new ArrayList<>();schedule.forEach(t->{validateTranche(t);accounts.add(new Account(t));});this.service=service;
    }

    /** Rebuilds future instalments after a package/date change while retaining total payments and accrued late fees. */
    public void rebase(List<AdvanceService.Tranche> schedule) {
        if(schedule==null) throw new IllegalArgumentException("Advance schedule is required");
        schedule.forEach(AdvanceLedger::validateTranche);
        BigDecimal alreadyPaid = accounts.stream().map(Account::paid).reduce(BigDecimal.ZERO, BigDecimal::add);
        BigDecimal accruedFees = accounts.stream().map(Account::lateFeesAccrued).reduce(BigDecimal.ZERO, BigDecimal::add);
        accounts.clear();
        schedule.forEach(t -> accounts.add(new Account(t)));
        BigDecimal remainingPaid = alreadyPaid;
        for (Account a : accounts) {
            if (remainingPaid.signum() <= 0) break;
            BigDecimal allocation = remainingPaid.min(a.tranche.amount());
            a.paid = Money.round(allocation);
            remainingPaid = Money.round(remainingPaid.subtract(allocation));
        }
        if (!accounts.isEmpty() && accruedFees.signum() > 0) {
            Account first = accounts.get(0);
            first.lateFeesAccrued = Money.round(first.lateFeesAccrued.add(accruedFees));
        }
    }
    public List<Account> accounts(){return Collections.unmodifiableList(accounts);}
    public BigDecimal totalOutstanding(){return Money.round(accounts.stream().map(Account::outstanding).reduce(BigDecimal.ZERO,BigDecimal::add));}
    /** Allocates a payment to the earliest tranche with an outstanding balance. */
    public void pay(BigDecimal amount,LocalDate paymentDate) {
        if(amount==null||paymentDate==null)throw new IllegalArgumentException("Payment amount and date are required");
        amount=Money.round(amount);
        if(amount.signum()<=0)throw new IllegalArgumentException("Payment must be positive after rounding to cents");
        if(amount.compareTo(totalOutstanding())>0)throw new IllegalArgumentException("Payment exceeds outstanding advance schedule");
        BigDecimal remaining=Money.round(amount);
        for(Account a:accounts) {
            if(remaining.signum()==0)break;
            BigDecimal allocation=remaining.min(a.outstanding());
            if(allocation.signum()>0){a.pay(allocation,paymentDate,service);remaining=Money.round(remaining.subtract(allocation));}
        }
    }
    public Status status(LocalDate asOf) {
        if(asOf==null) throw new IllegalArgumentException("Status date is required");
        BigDecimal outstanding=BigDecimal.ZERO,fees=BigDecimal.ZERO;boolean cancel=false;
        for(Account a:accounts) {
            fees=fees.add(a.lateFeesAccrued());
            if(a.outstanding().signum()>0 && !asOf.isBefore(a.tranche().dueDate())) {
                outstanding=outstanding.add(a.outstanding());
                fees=fees.add(service.lateFee(a.outstanding(),a.tranche().dueDate(),asOf));
                if(service.autoCancel(a.tranche().dueDate(),asOf))cancel=true;
            }
        }
        return new Status(Money.round(outstanding),Money.round(fees),cancel);
    }

    private static void validateTranche(AdvanceService.Tranche tranche) {
        if(tranche==null||tranche.name()==null||tranche.name().isBlank()||tranche.dueDate()==null||tranche.amount()==null||tranche.amount().signum()<0)
            throw new IllegalArgumentException("Advance tranche is invalid");
    }
}
