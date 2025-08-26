package com.cario.title.app.model;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

/**
 * Represents a lienholder entity for a vehicle title.
 *
 * <p>A lienholder is typically a bank, finance company, or other entity that has a legal right to
 * the vehicle until a loan or debt is paid in full.
 *
 * <ul>
 *   <li>{@code firmName} – Name of the lienholder organization
 *   <li>{@code address} – Mailing address of the lienholder
 * </ul>
 *
 * <p>Uses Lombok annotations to auto-generate getters, setters, constructors, and builder.
 *
 * @author Your Name
 * @version 1.0
 * @since 2025-08-09
 */
@Data
@NoArgsConstructor
@AllArgsConstructor
@Builder
public class Lienholder {

  /** Name of the lienholder organization. */
  private String firmName;

  /** Physical or mailing address of the lienholder. */
  private Address address;
}
