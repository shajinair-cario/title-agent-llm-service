package com.cario.title.app.model;

import jakarta.validation.constraints.Pattern;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

/**
 * Represents vehicle details as recorded on a title.
 *
 * <p>Includes identifying information such as VIN, make, model, and specifications like body type,
 * cylinder count, and mileage.
 *
 * <ul>
 *   <li>{@code vin} – 17-character Vehicle Identification Number (validated by regex)
 *   <li>{@code make} – Manufacturer of the vehicle (e.g., Toyota, Ford)
 *   <li>{@code model} – Model name or code (e.g., Camry, F-150)
 *   <li>{@code year} – Model year
 *   <li>{@code bodyType} – Body style (e.g., Sedan, SUV)
 *   <li>{@code cylinders} – Number of engine cylinders
 *   <li>{@code mileage} – Current mileage as recorded on the title
 * </ul>
 *
 * <p>Uses Lombok for boilerplate code generation.
 *
 * @author Your Name
 * @version 1.0
 * @since 2025-08-14
 */
@Data
@NoArgsConstructor
@AllArgsConstructor
@Builder
public class Vehicle {

  /** 17-character Vehicle Identification Number (VIN). */
  @Pattern(regexp = "^[A-HJ-NPR-Z0-9]{17}$")
  private String vin;

  /** Manufacturer of the vehicle. */
  private String make;

  /** Model name or code. */
  private String model;

  /** Model year. */
  private Integer year;

  /** Body style of the vehicle. */
  private String bodyType;

  /** Number of engine cylinders. */
  private Integer cylinders;

  /** Current mileage recorded on the title. */
  private Integer mileage;
}
