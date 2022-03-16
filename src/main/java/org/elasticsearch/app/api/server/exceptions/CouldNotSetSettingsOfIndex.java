package org.elasticsearch.app.api.server.exceptions;

import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.ResponseStatus;

@ResponseStatus(HttpStatus.CONFLICT)
public class CouldNotSetSettingsOfIndex extends RuntimeException {
    public CouldNotSetSettingsOfIndex() {
    }

    public CouldNotSetSettingsOfIndex(String message) {
        super(message);
    }

    public CouldNotSetSettingsOfIndex(String message, Throwable cause) {
        super(message, cause);
    }

    public CouldNotSetSettingsOfIndex(Throwable cause) {
        super(cause);
    }

    public CouldNotSetSettingsOfIndex(String message, Throwable cause, boolean enableSuppression, boolean writableStackTrace) {
        super(message, cause, enableSuppression, writableStackTrace);
    }
}
