package in.ps108.model;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.LinkedHashMap;
import java.util.Map;

public class Booking {
    public enum Status { ENQUIRED, HELD, EXPIRED, CONVERTED, CONFIRMED, CANCELLED, COMPLETED, SETTLED }

    private final String id;
    private final String client;
    private final String venueId;
    private LocalDate eventDate;
    private Slot slot;
    private final int quotedGuests;
    private int actualGuests;
    private boolean actualsRecorded;
    private BigDecimal advancesPaid = BigDecimal.ZERO;
    private BigDecimal damages = BigDecimal.ZERO;
    private BigDecimal switchFees = BigDecimal.ZERO;
    private BigDecimal dateChangeCredit = BigDecimal.ZERO;
    private BigDecimal dateChangeCreditRedeemed = BigDecimal.ZERO;
    private BigDecimal dateChangeCreditApplied = BigDecimal.ZERO;
    private Status status = Status.ENQUIRED;
    private boolean freeDateChangeUsed;
    private LocalDateTime holdExpiresAt;
    private LocalDateTime holdCreatedAt;
    private final Map<String, String> selectedPackages = new LinkedHashMap<>();

    public Booking(String id, String client, String venueId, LocalDate eventDate, Slot slot, int quotedGuests) {
        if (id == null || id.isBlank() || client == null || client.isBlank()) throw new IllegalArgumentException("Booking id and client are required");
        if (quotedGuests < 1) throw new IllegalArgumentException("Quoted guests must be positive");
        this.id=id; this.client=client; this.venueId=venueId; this.eventDate=eventDate; this.slot=slot; this.quotedGuests=quotedGuests;
    }
    public String id(){return id;} public String client(){return client;} public String venueId(){return venueId;}
    public LocalDate eventDate(){return eventDate;} public Slot slot(){return slot;} public int quotedGuests(){return quotedGuests;}
    public int actualGuests(){return actualGuests;} public boolean actualsRecorded(){return actualsRecorded;} public BigDecimal advancesPaid(){return advancesPaid;}
    public BigDecimal damages(){return damages;} public BigDecimal switchFees(){return switchFees;}
    public BigDecimal dateChangeCredit(){return dateChangeCredit;} public Status status(){return status;}
    public BigDecimal dateChangeCreditRedeemed(){return dateChangeCreditRedeemed;}
    public BigDecimal dateChangeCreditApplied(){return dateChangeCreditApplied;}
    public boolean freeDateChangeUsed(){return freeDateChangeUsed;}
    public void markFreeDateChangeUsed(){freeDateChangeUsed=true;}
    public LocalDateTime holdExpiresAt(){return holdExpiresAt;} public LocalDateTime holdCreatedAt(){return holdCreatedAt;} public Map<String,String> selectedPackages(){return Map.copyOf(selectedPackages);}
    public void status(Status status){if(status==null)throw new IllegalArgumentException("Booking status is required");this.status=status;} public void holdExpiresAt(LocalDateTime v){holdExpiresAt=v;} public void holdCreatedAt(LocalDateTime v){holdCreatedAt=v;}
    public void addPayment(BigDecimal amount){if(amount==null)throw new IllegalArgumentException("Payment is required");amount=amount.setScale(2,RoundingMode.HALF_UP);if(amount.signum()<=0)throw new IllegalArgumentException("Payment must be positive after rounding to cents"); advancesPaid=advancesPaid.add(amount);}
    public void selectPackage(String vendorId,String packageName){selectedPackages.put(vendorId,packageName.toUpperCase());}
    public void swapPackage(String vendorId,String packageName){selectedPackages.put(vendorId,packageName.toUpperCase());}
    public void actuals(int guests,BigDecimal damages){if(guests<0||damages==null||damages.signum()<0)throw new IllegalArgumentException("Invalid actuals");this.actualGuests=guests;this.actualsRecorded=true;this.damages=damages.setScale(2,RoundingMode.HALF_UP);}
    public void addSwitchFee(BigDecimal fee){if(fee==null||fee.signum()<0)throw new IllegalArgumentException("Switch fee must be zero or greater");switchFees=switchFees.add(fee.setScale(2,RoundingMode.HALF_UP));}
    public void addDateChangeCredit(BigDecimal credit){if(credit==null||credit.signum()<0)throw new IllegalArgumentException("Date-change credit must be zero or greater");dateChangeCredit=dateChangeCredit.add(credit.setScale(2,RoundingMode.HALF_UP));}
    public BigDecimal redeemDateChangeCredit(){
        BigDecimal available=dateChangeCredit.subtract(dateChangeCreditRedeemed).max(BigDecimal.ZERO).setScale(2,RoundingMode.HALF_UP);
        if(available.signum()<=0)throw new IllegalStateException("No unused date-change credit is available");
        dateChangeCreditRedeemed=dateChangeCreditRedeemed.add(available);
        return available;
    }
    public void consumeDateChangeCredit(BigDecimal credit){
        if(credit==null||credit.signum()<=0)throw new IllegalArgumentException("Date-change credit consumption must be positive");
        BigDecimal available=dateChangeCredit.subtract(dateChangeCreditRedeemed);
        BigDecimal rounded=credit.setScale(2,RoundingMode.HALF_UP);
        if(rounded.compareTo(available)>0)throw new IllegalArgumentException("Credit consumption exceeds available date-change credit");
        dateChangeCreditRedeemed=dateChangeCreditRedeemed.add(rounded);
    }
    public void applyDateChangeCredit(BigDecimal credit){
        if(credit==null||credit.signum()<=0)throw new IllegalArgumentException("Date-change credit must be positive");
        dateChangeCreditApplied=dateChangeCreditApplied.add(credit.setScale(2,RoundingMode.HALF_UP));
    }
    public void changeDate(LocalDate date, Slot newSlot){if(date==null||newSlot==null)throw new IllegalArgumentException("Event date and slot are required");eventDate=date;slot=newSlot;}

    public void restorePersistedState(int actualGuests, boolean actualsRecorded, BigDecimal advancesPaid,
                                      BigDecimal damages, BigDecimal switchFees, BigDecimal dateChangeCredit,
                                      BigDecimal dateChangeCreditRedeemed, BigDecimal dateChangeCreditApplied,
                                      boolean freeDateChangeUsed) {
        if(actualGuests<0||advancesPaid==null||damages==null||switchFees==null||dateChangeCredit==null||dateChangeCreditRedeemed==null||dateChangeCreditApplied==null)
            throw new IllegalArgumentException("Invalid persisted booking state");
        this.actualGuests=actualGuests; this.actualsRecorded=actualsRecorded; this.advancesPaid=advancesPaid;
        this.damages=damages; this.switchFees=switchFees; this.dateChangeCredit=dateChangeCredit;
        this.dateChangeCreditRedeemed=dateChangeCreditRedeemed; this.dateChangeCreditApplied=dateChangeCreditApplied;
        this.freeDateChangeUsed=freeDateChangeUsed;
    }
}
