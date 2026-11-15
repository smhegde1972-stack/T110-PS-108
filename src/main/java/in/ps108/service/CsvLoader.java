package in.ps108.service;

import in.ps108.model.*;

import java.io.IOException;
import java.math.BigDecimal;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.LocalDate;
import java.util.*;

/** Loads the project's simple CSV tables using RFC 4180 quoting (including doubled quotes). */
public final class CsvLoader {
    private CsvLoader() {}

    public static Map<String, Venue> venues(Path path) throws IOException {
        Map<String, Venue> result = new LinkedHashMap<>();
        for (List<String> row : dataRows(path)) {
            if (row.size() != 5 && row.size() != 6) {
                throw new IllegalArgumentException("Invalid venue row (expected 5 or 6 columns): " + row);
            }
            String id = row.get(0);
            String name = row.get(1);
            int capacity = row.size() == 6 ? Integer.parseInt(row.get(3)) : Integer.parseInt(row.get(2));
            BigDecimal base = row.size() == 6 ? new BigDecimal(row.get(4)) : new BigDecimal(row.get(3));
            String slotsCol = row.size() == 6 ? row.get(5) : row.get(4);
            Set<Slot> slots = new HashSet<>();
            for (String slot : slotsCol.split(";")) slots.add(Slot.parse(slot));
            Venue venue = new Venue(id, name, capacity, base, slots);
            if (result.putIfAbsent(venue.id(), venue) != null)
                throw new IllegalArgumentException("Duplicate venue id " + venue.id());
        }
        return result;
    }

    public static Map<String, Vendor> vendors(Path path) throws IOException {
        return vendors(path, null);
    }

    public static Map<String, Vendor> vendors(Path vendorsPath, Path packagesPath) throws IOException {
        Map<String, Vendor> result = new LinkedHashMap<>();
        Map<String, Map<String, BigDecimal>> packagesByVendor = (packagesPath != null && Files.exists(packagesPath))
                ? vendorPackages(packagesPath) : Map.of();
        for (List<String> row : dataRows(vendorsPath)) {
            if (row.size() != 4 && row.size() != 5) {
                throw new IllegalArgumentException("Invalid vendor row (expected 4 or 5 columns): " + row);
            }
            String id = row.get(0);
            String name = row.get(1);
            Vendor.Category category = Vendor.Category.valueOf(row.get(2).toUpperCase(Locale.ROOT));
            Map<String, BigDecimal> packages = new LinkedHashMap<>();
            BigDecimal commission = null;
            if (row.size() == 5) {
                for (String pair : row.get(3).split(";")) {
                    String[] keyValue = pair.split(":", 2);
                    if (keyValue.length != 2) throw new IllegalArgumentException("Invalid package " + pair);
                    String packageName = keyValue[0].trim().toUpperCase(Locale.ROOT);
                    if (packages.putIfAbsent(packageName, new BigDecimal(keyValue[1].trim())) != null)
                        throw new IllegalArgumentException("Duplicate package " + packageName + " for vendor " + id);
                }
                commission = new BigDecimal(row.get(4).trim());
            }
            if (packagesByVendor.containsKey(id)) {
                packages.putAll(packagesByVendor.get(id));
            }
            Vendor vendor = new Vendor(id, name, category, packages, commission);
            if (result.putIfAbsent(vendor.id(), vendor) != null)
                throw new IllegalArgumentException("Duplicate vendor id " + vendor.id());
        }
        return result;
    }

    public static Map<String, Map<String, BigDecimal>> vendorPackages(Path path) throws IOException {
        Map<String, Map<String, BigDecimal>> result = new LinkedHashMap<>();
        for (List<String> row : dataRows(path)) {
            requireColumns(row, 4, "vendor package");
            String vendorId = row.get(0).trim();
            String packageCode = row.get(1).trim().toUpperCase(Locale.ROOT);
            BigDecimal price = new BigDecimal(row.get(3).trim());
            result.computeIfAbsent(vendorId, k -> new LinkedHashMap<>()).put(packageCode, price);
        }
        return result;
    }

    public static Set<LocalDate> auspiciousDates(Path path) throws IOException {
        Set<LocalDate> dates = new HashSet<>();
        for (List<String> row : dataRows(path)) {
            if (row.size() != 1) throw new IllegalArgumentException("Invalid auspicious-date row: " + row);
            dates.add(LocalDate.parse(row.get(0).trim()));
        }
        return dates;
    }

    public static Map<String, BigDecimal> damageRates(Path path) throws IOException {
        Map<String, BigDecimal> rates = new LinkedHashMap<>();
        for (List<String> row : dataRows(path)) {
            requireColumns(row, 3, "damage rate");
            String code = row.get(0).trim().toUpperCase(Locale.ROOT);
            BigDecimal rate = new BigDecimal(row.get(2));
            if (code.isBlank() || rate.signum() < 0) throw new IllegalArgumentException("Invalid damage rate row: " + row);
            if (rates.putIfAbsent(code, rate) != null) throw new IllegalArgumentException("Duplicate damage code " + code);
        }
        return rates;
    }

    private static void requireColumns(List<String> row, int count, String type) {
        if (row.size() != count) throw new IllegalArgumentException("Invalid " + type + " row: " + row);
    }

    private static List<List<String>> dataRows(Path path) throws IOException {
        String input = Files.readString(path);
        List<List<String>> rows = new ArrayList<>();
        List<String> row = new ArrayList<>();
        StringBuilder field = new StringBuilder();
        boolean quoted = false;
        boolean afterQuote = false;
        for (int i = 0; i < input.length(); i++) {
            char c = input.charAt(i);
            if (quoted) {
                if (c == '"') {
                    if (i + 1 < input.length() && input.charAt(i + 1) == '"') {
                        field.append('"');
                        i++;
                    } else {
                        quoted = false;
                        afterQuote = true;
                    }
                } else {
                    field.append(c);
                }
            } else if (afterQuote && c != ',' && c != '\r' && c != '\n' && !Character.isWhitespace(c)) {
                throw new IllegalArgumentException("Unexpected character after closing CSV quote: " + c);
            } else if (c == '"') {
                if (field.length() != 0 || afterQuote)
                    throw new IllegalArgumentException("Unexpected quote in CSV field");
                quoted = true;
            } else if (c == ',') {
                row.add(field.toString().trim());
                field.setLength(0);
                afterQuote = false;
            } else if (c == '\r' || c == '\n') {
                if (c == '\r' && i + 1 < input.length() && input.charAt(i + 1) == '\n') i++;
                row.add(field.toString().trim());
                addDataRow(rows, row);
                row = new ArrayList<>();
                field.setLength(0);
                afterQuote = false;
            } else if (!afterQuote) {
                field.append(c);
            }
        }
        if (quoted) throw new IllegalArgumentException("Unterminated quoted CSV field");
        if (!row.isEmpty() || field.length() > 0) {
            row.add(field.toString().trim());
            addDataRow(rows, row);
        }
        return rows;
    }

    private static void addDataRow(List<List<String>> rows, List<String> row) {
        if (row.size() == 1 && row.get(0).isBlank()) return;
        if (row.size() == 1 && row.get(0).trim().startsWith("#")) return;
        if (row.size() == 1 && row.get(0).trim().equalsIgnoreCase("date")) return;
        if (!row.isEmpty() && (row.get(0).equalsIgnoreCase("venue_id") || row.get(0).equalsIgnoreCase("vendor_id") || row.get(0).equalsIgnoreCase("damage_code") || row.get(0).equalsIgnoreCase("item_code") || row.get(0).equalsIgnoreCase("package_id"))) return;
        rows.add(List.copyOf(row));
    }
}
