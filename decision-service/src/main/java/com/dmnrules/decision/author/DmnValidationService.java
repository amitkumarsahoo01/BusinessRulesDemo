package com.dmnrules.decision.author;

import org.kie.dmn.api.core.DMNMessage;
import org.kie.dmn.validation.DMNValidator;
import org.kie.dmn.validation.DMNValidator.Validation;
import org.kie.dmn.validation.DMNValidatorFactory;
import org.springframework.stereotype.Component;

import java.io.StringReader;
import java.util.List;

/**
 * Wraps Apache KIE's DMN validator: schema, FEEL compile, and the decision-table analyser for
 * gaps/overlaps/unreachable rows (CLAUDE.md / BUILD_PLAN M3 "Validate" step).
 *
 * <p>Uses the {@code Reader}-based {@code validate(...)} overload rather than
 * {@code validateUsing(...).theseModels(Definitions...)}: schema validation only works against the
 * raw XML (a {@code Definitions} object has already been parsed, so there's no schema left to
 * check) — {@code theseModels} fails every model with "Schema validation not supported for in
 * memory object" when {@code VALIDATE_SCHEMA} is requested. Confirmed against the real engine
 * output, not assumed from docs.
 */
@Component
public class DmnValidationService {

    public List<ValidationMessageDto> validate(String dmnXml) {
        DMNValidator validator = DMNValidatorFactory.newValidator();
        try {
            List<DMNMessage> messages = validator.validate(
                    new StringReader(dmnXml),
                    Validation.VALIDATE_SCHEMA, Validation.VALIDATE_MODEL,
                    Validation.VALIDATE_COMPILATION, Validation.ANALYZE_DECISION_TABLE);
            return messages.stream()
                    .map(m -> new ValidationMessageDto(m.getSeverity().name(), m.getMessage(), m.getSourceId()))
                    .toList();
        } finally {
            validator.dispose();
        }
    }

    public boolean hasErrors(List<ValidationMessageDto> messages) {
        return messages.stream().anyMatch(m -> "ERROR".equals(m.severity()));
    }
}
