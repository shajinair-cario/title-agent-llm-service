package com.cario.title.app.model;

import jakarta.validation.constraints.Pattern;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

/**
 * Represents a standardized postal address.
 *
 * <p>Includes optional street line 2 for apartment, suite, or unit information. State codes and ZIP
 * codes are validated via {@link Pattern} constraints to ensure US-compliant formats.
 *
 * <ul>
 *   <li>{@code state} - Two uppercase letters (e.g., "CA")
 *   <li>{@code zip} - Five-digit ZIP code or ZIP+4 (e.g., "12345" or "12345-6789")
 * </ul>
 *
 * <p>Uses Lombok annotations for boilerplate code generation.
 *
 * @author Shaji Nair
 * @version 1.0
 * @since 2025-08-09
 */
@Data
@NoArgsConstructor
@AllArgsConstructor
@Builder
public class Address {

  /** Primary street address line. */
  private String line1;

  /** Secondary street address line (apartment, suite, etc.). */
  private String line2;

  /** City or locality name. */
  private String city;

  /** Two-letter uppercase state code (US). */
  @Pattern(regexp = "^[A-Z]{2}$")
  private String state;

  /** US ZIP code in 5-digit or ZIP+4 format. */
  @Pattern(regexp = "^\\d{5}(-\\d{4})?$")
  private String zip;
}
