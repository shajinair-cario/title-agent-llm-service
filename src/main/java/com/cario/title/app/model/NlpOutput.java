package com.cario.title.app.model;

// AiNlpService.NlpOutput.java (inner static classes or separate files)
import java.util.List;
import lombok.*;

@Data
@NoArgsConstructor
@AllArgsConstructor
@Builder
public class NlpOutput {
  private Vehicle vehicle;
  private Owner owner;
  private List<Lienholder> lienholders;

  // title-level fields normalized by AI
  private String issuingDate;
  private String previousStateTitle;
  private String previousTitleNumber;

  // optional metadata
  private String s3Uri;
  private String rawJson;

  @Data
  @NoArgsConstructor
  @AllArgsConstructor
  @Builder
  public static class Vehicle {
    private String vin;
    private String make;
    private String model;
    private Integer year;
    private String bodyType;
    private Integer cylinders;
    private Integer mileage;
  }

  @Data
  @NoArgsConstructor
  @AllArgsConstructor
  @Builder
  public static class Owner {
    private String firstName;
    private String lastName;
    private String firmName;
    private Address address;
  }

  @Data
  @NoArgsConstructor
  @AllArgsConstructor
  @Builder
  public static class Lienholder {
    private String firmName;
    private Address address;
  }

  @Data
  @NoArgsConstructor
  @AllArgsConstructor
  @Builder
  public static class Address {
    private String line1;
    private String line2;
    private String city;
    private String state; // 2-letter
    private String zip; // 12345 or 12345-6789
  }
}
