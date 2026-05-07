package com.ducklake.accessmanager.model;

import java.util.List;

/**
 * A named collection of email addresses. When a {@link Grant} of type
 * {@code group} references this group's name, every member's email
 * inherits the bucket access.
 *
 * {@code members} is the email list at the time of the read; it is kept
 * inline rather than via a separate endpoint so the admin UI can render
 * a group's roster in a single round-trip.
 */
public record Group(
    String name,
    String description,
    String createdBy,
    String createdAt,
    List<String> members
) {}
