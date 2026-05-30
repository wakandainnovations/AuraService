package com.aura.service.dto;

import lombok.AllArgsConstructor;
import lombok.Data;
import lombok.NoArgsConstructor;

/**
 * Optional body for cloning a playbook. When {@code title} is omitted the clone is named
 * "Copy of &lt;source title&gt;".
 */
@Data
@NoArgsConstructor
@AllArgsConstructor
public class ClonePlaybookRequest {
    private String title;
}
