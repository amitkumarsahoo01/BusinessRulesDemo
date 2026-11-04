package com.dmnrules.decision.author;

import com.dmnrules.decision.model.ModelVersionRow;

import java.util.List;

/** newModel: true when the upload created a new model key; false when it became a new draft of an existing one. */
public record ImportResultDto(ModelVersionRow version, boolean newModel, List<ValidationMessageDto> messages) {
}
