package com.dmnrules.decision.author;

import java.util.List;

public record ValidationResult(boolean ok, String status, List<ValidationMessageDto> messages) {
}
