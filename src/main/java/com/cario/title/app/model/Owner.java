package com.cario.title.app.model;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

/**
 * Represents the owner of a vehicle title.
 *
 * <p>An owner can be either an individual (identified by {@code firstName} and {@code lastName}) or
 * an organization (identified by {@code firmName}). When {@code firmName} is provided, individual
 * name fields may be {@code null}.
 *
 * <ul>
 *   <li>{@code firstName} – Owner's given name (for individual owners)
 *   <li>{@code lastName} – Owner's family name (for individual owners)
 *   <li>{@code firmName} – Legal/registered name (for organizational owners)
 *   <li>{@code address} – Mailing or physical address of the owner
 * </ul>
 *
 * <p>Uses Lombok to generate boilerplate (getters, setters, constructors, builder).
 *
 * @author Your Name
 * @version 1.0
 * @since 2025-08-14
 */
@Data
@NoArgsConstructor
@AllArgsConstructor
@Builder
public class Owner {

  /** Owner's given name; {@code null} if {@link #firmName} is used. */
  private String firstName;

  /** Owner's family name; {@code null} if {@link #firmName} is used. */
  private String lastName;

  /** Organization name; {@code null} if individual owner fields are used. */
  private String firmName;

  /** Address of the owner (individual or organization). */
  private Address address;
}
