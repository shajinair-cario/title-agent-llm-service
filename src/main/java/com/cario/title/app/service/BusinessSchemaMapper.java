package com.cario.title.app.service;

import com.cario.title.app.model.NlpOutput;
import java.util.*;
import java.util.stream.Collectors;

/**
 * Maps your normalized NlpOutput into the business-friendly schema: - title_information,
 * owner_information, lien_information, assignment_of_vehicle, officials - Every leaf field -> {
 * "value": ..., "confidence": int(1..5) }
 *
 * <p>Confidence is heuristic but deterministic: - 5 when a value is present and passes simple
 * validation (e.g., VIN length 17, year in range, ZIP format) - 3 when present but weak/unknown
 * validation - 1 when missing/null
 */
public final class BusinessSchemaMapper {

  private BusinessSchemaMapper() {}

  public static Map<String, Object> toBusinessSchema(NlpOutput out) {
    Map<String, Object> root = new LinkedHashMap<>();

    // ---------- title_information ----------
    Map<String, Object> titleInfo = new LinkedHashMap<>();
    // The following are mapped from what we have in NlpOutput; adjust as you enrich NlpOutput
    // later.

    // State/certificate_type: we may not have them in NlpOutput yet; mark unknown with low
    // confidence.
    titleInfo.put(
        "state",
        wrapVal(out.getPreviousStateTitle(), confFromPresent(out.getPreviousStateTitle(), 3)));
    titleInfo.put("certificate_type", wrapVal(null, 1)); // not available in NlpOutput (yet)

    // title_number is not in NlpOutput; keep null
    titleInfo.put("title_number", wrapVal(null, 1));

    // duplicate indicator unknown
    titleInfo.put("duplicate_indicator", wrapVal(null, 1));

    // VIN/year/make/model/bodyType from vehicle
    String vin = out.getVehicle() != null ? out.getVehicle().getVin() : null;
    titleInfo.put("vehicle_id_number", wrapVal(vin, confVin(vin)));

    Integer year = out.getVehicle() != null ? out.getVehicle().getYear() : null;
    titleInfo.put("year", wrapVal(year, confYear(year)));

    String make = out.getVehicle() != null ? out.getVehicle().getMake() : null;
    titleInfo.put("make", wrapVal(make, confFromPresent(make, 5)));

    String model = out.getVehicle() != null ? out.getVehicle().getModel() : null;
    titleInfo.put("model", wrapVal(model, confFromPresent(model, 4)));

    String bodyType = out.getVehicle() != null ? out.getVehicle().getBodyType() : null;
    titleInfo.put("body_type", wrapVal(bodyType, confFromPresent(bodyType, 4)));

    // fuel type unknown
    titleInfo.put("fuel_type", wrapVal(null, 1));

    // prior title state from previousStateTitle (if present)
    titleInfo.put(
        "prior_title_state",
        wrapVal(out.getPreviousStateTitle(), confFromPresent(out.getPreviousStateTitle(), 4)));

    // dates: issuingDate -> both date_pa_titled & date_of_issue (approximation)
    String issuingDate = out.getIssuingDate();
    titleInfo.put("date_pa_titled", wrapVal(issuingDate, confDate(issuingDate)));
    titleInfo.put("date_of_issue", wrapVal(issuingDate, confDate(issuingDate)));

    // odometer reading + status from mileage (vehicle.mileage)
    Integer mileage = out.getVehicle() != null ? out.getVehicle().getMileage() : null;
    titleInfo.put(
        "odometer_reading",
        wrapVal(
            mileage != null ? String.format("%,d", mileage) : null, confFromPresent(mileage, 4)));
    // We don't know the exact status; default "Actual Mileage" with lower confidence if present
    titleInfo.put(
        "odometer_status",
        wrapVal(mileage != null ? "Actual Mileage" : null, confFromPresent(mileage, 3)));
    // recorded date ~ issuingDate (best effort)
    titleInfo.put("odometer_recorded_date", wrapVal(issuingDate, confDate(issuingDate)));

    // Weights/brands unknown unless you add to NlpOutput later
    titleInfo.put("gvwr", wrapVal(null, 1));
    titleInfo.put("gcwr", wrapVal(null, 1));
    titleInfo.put("unladen_weight", wrapVal(null, 1));
    titleInfo.put("title_brands", wrapVal(Collections.emptyList(), 5));

    root.put("title_information", titleInfo);

    // ---------- owner_information ----------
    Map<String, Object> ownerInfo = new LinkedHashMap<>();
    if (out.getOwner() != null) {
      String ownerFirm = out.getOwner().getFirmName();
      String ownerFirst = out.getOwner().getFirstName();
      String ownerLast = out.getOwner().getLastName();

      // Choose a display name
      String ownerDisplay =
          nonBlank(ownerFirm)
              ? ownerFirm
              : nonBlank(ownerFirst) || nonBlank(ownerLast)
                  ? ((nonBlank(ownerFirst) ? ownerFirst : "")
                          + " "
                          + (nonBlank(ownerLast) ? ownerLast : ""))
                      .trim()
                  : null;

      ownerInfo.put("name", wrapVal(ownerDisplay, confFromPresent(ownerDisplay, 5)));

      // Address (line1, line2, city, state, zip)
      Map<String, Object> addr = new LinkedHashMap<>();
      if (out.getOwner().getAddress() != null) {
        String line1 = out.getOwner().getAddress().getLine1();
        String line2 = out.getOwner().getAddress().getLine2();
        String city = out.getOwner().getAddress().getCity();
        String state = out.getOwner().getAddress().getState();
        String zip = out.getOwner().getAddress().getZip();

        String formatted = formatAddress(line1, line2, city, state, zip);
        ownerInfo.put("address", wrapVal(formatted, confAddress(line1, city, state, zip)));
      } else {
        ownerInfo.put("address", wrapVal(null, 1));
      }
    } else {
      ownerInfo.put("name", wrapVal(null, 1));
      ownerInfo.put("address", wrapVal(null, 1));
    }
    root.put("owner_information", ownerInfo);

    // ---------- lien_information ----------
    Map<String, Object> lienInfo = new LinkedHashMap<>();
    if (out.getLienholders() != null && !out.getLienholders().isEmpty()) {
      var first = out.getLienholders().get(0);
      String firm = first.getFirmName();
      String addr =
          first.getAddress() != null
              ? formatAddress(
                  first.getAddress().getLine1(),
                  first.getAddress().getLine2(),
                  first.getAddress().getCity(),
                  first.getAddress().getState(),
                  first.getAddress().getZip())
              : null;

      Map<String, Object> lienholder = wrapValAsMap(firm, confFromPresent(firm, 4));
      // If you want the Perplexity field "first_lienholder" to be a name string only, keep above;
      // else you can include address too by changing schema (example below keeps name only for
      // parity).
      lienInfo.put("first_lienholder", lienholder);

      // Release info unknown
      Map<String, Object> firstRelease = new LinkedHashMap<>();
      firstRelease.put("status", wrapVal(null, 1));
      firstRelease.put("date", wrapVal(null, 1));
      firstRelease.put("authorized_by", wrapVal(null, 1));
      lienInfo.put("first_lien_released", firstRelease);

      lienInfo.put("second_lienholder", wrapVal(null, 1));
      lienInfo.put("second_lien_released", wrapVal(null, 1));
    } else {
      lienInfo.put("first_lienholder", wrapVal(null, 1));
      Map<String, Object> firstRelease = new LinkedHashMap<>();
      firstRelease.put("status", wrapVal(null, 1));
      firstRelease.put("date", wrapVal(null, 1));
      firstRelease.put("authorized_by", wrapVal(null, 1));
      lienInfo.put("first_lien_released", firstRelease);
      lienInfo.put("second_lienholder", wrapVal(null, 1));
      lienInfo.put("second_lien_released", wrapVal(null, 1));
    }
    root.put("lien_information", lienInfo);

    // ---------- assignment_of_vehicle ----------
    // Not available in your NlpOutput; produce empty or placeholders.
    root.put("assignment_of_vehicle", new ArrayList<>());

    // ---------- officials ----------
    Map<String, Object> officials = new LinkedHashMap<>();
    // Unknown; keep null
    officials.put("secretary_of_transportation", wrapVal(null, 1));
    root.put("officials", officials);

    return root;
  }

  // ----------------- helpers -----------------

  private static Map<String, Object> wrapVal(Object value, int confidence) {
    Map<String, Object> m = new LinkedHashMap<>();
    m.put("value", value);
    m.put("confidence", confidence);
    return m;
  }

  // When caller wants to store just a scalar under a name but as {value, confidence}
  private static Map<String, Object> wrapValAsMap(Object value, int confidence) {
    return wrapVal(value, confidence);
  }

  private static int confFromPresent(Object v, int good) {
    return v == null || (v instanceof String s && s.isBlank()) ? 1 : Math.min(Math.max(good, 1), 5);
  }

  private static int confVin(String vin) {
    if (vin == null) return 1;
    String norm = vin.trim().toUpperCase(Locale.ROOT);
    return norm.length() == 17 && norm.matches("^[A-HJ-NPR-Z0-9]{17}$") ? 5 : 2;
    // (I,O,Q are excluded)
  }

  private static int confYear(Integer year) {
    if (year == null) return 1;
    return (year >= 1900 && year <= 2100) ? 5 : 2;
  }

  private static int confDate(String yyyyMmDd) {
    if (yyyyMmDd == null || yyyyMmDd.isBlank()) return 1;
    return yyyyMmDd.matches("^\\d{4}-\\d{2}-\\d{2}$") ? 5 : 2;
  }

  private static int confAddress(String line1, String city, String state, String zip) {
    int c = 1;
    if (nonBlank(line1)) c = Math.max(c, 3);
    if (nonBlank(city)) c = Math.max(c, 4);
    if (state != null && state.matches("^[A-Z]{2}$")) c = Math.max(c, 5);
    if (zip != null && zip.matches("^\\d{5}(-\\d{4})?$")) c = Math.max(c, 5);
    return c;
  }

  private static boolean nonBlank(String s) {
    return s != null && !s.isBlank();
  }

  private static String formatAddress(
      String line1, String line2, String city, String state, String zip) {
    List<String> parts = new ArrayList<>();
    if (nonBlank(line1)) parts.add(line1.trim());
    if (nonBlank(line2)) parts.add(line2.trim());
    String cityStateZip =
        Arrays.asList(
                nonBlank(city) ? city.trim() : null,
                nonBlank(state) ? state.trim() : null,
                nonBlank(zip) ? zip.trim() : null)
            .stream()
            .filter(Objects::nonNull)
            .collect(Collectors.joining(" "));
    if (!cityStateZip.isBlank()) parts.add(cityStateZip);
    return parts.isEmpty() ? null : String.join(", ", parts);
  }
}
