package com.cario.title.app.model;

import java.util.List;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

/**
 * Represents a processed vehicle title record.
 *
 * <p>A title contains issuing authority details, vehicle information, owner details, and any
 * recorded lienholders.
 *
 * <ul>
 *   <li>{@code issuingDmv} – Name or abbreviation of the DMV or issuing authority
 *   <li>{@code issuingDate} – Date the title was issued, in ISO format {@code yyyy-MM-dd}
 *   <li>{@code titleNumber} – Unique identifier for the title
 *   <li>{@code titleType} – Type of title (e.g., Original, Duplicate, Salvage)
 *   <li>{@code previousStateTitle} – State code of a prior title, if applicable
 *   <li>{@code previousTitleNumber} – Number of a prior title, if applicable
 *   <li>{@code vehicle} – Details of the vehicle described by the title
 *   <li>{@code owner} – Current owner of the vehicle
 *   <li>{@code lienholders} – List of lienholders, if any
 * </ul>
 *
 * <p>Uses Lombok annotations for boilerplate code generation.
 *
 * @author Your Name
 * @version 1.0
 * @since 2025-08-14
 */
@Data
@NoArgsConstructor
@AllArgsConstructor
@Builder
public class Title {

  /** DMV or issuing authority name/abbreviation. */
  private String issuingDmv;

  /** Date the title was issued (ISO format {@code yyyy-MM-dd}). */
  private String issuingDate;

  /** Unique identifier for the title. */
  private String titleNumber;

  /** Type of title (Original, Duplicate, Salvage, etc.). */
  private String titleType;

  /** State code of the previous title, if applicable. */
  private String previousStateTitle;

  /** Number of the previous title, if applicable. */
  private String previousTitleNumber;

  /** Vehicle details associated with the title. */
  private Vehicle vehicle;

  /** Owner details (individual or organization). */
  private Owner owner;

  /** List of lienholders for the vehicle, if any. */
  private List<Lienholder> lienholders;
}
