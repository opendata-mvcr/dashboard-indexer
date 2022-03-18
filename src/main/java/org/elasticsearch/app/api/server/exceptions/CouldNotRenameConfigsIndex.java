package org.elasticsearch.app.api.server.exceptions;

import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.ResponseStatus;

@ResponseStatus(HttpStatus.CONFLICT)
public class CouldNotRenameConfigsIndex extends RuntimeException {
    public CouldNotRenameConfigsIndex() {
    }

    public CouldNotRenameConfigsIndex(String message) {
        super(message);
    }

    public CouldNotRenameConfigsIndex(String message, Throwable cause) {
        super(message, cause);
    }

    public CouldNotRenameConfigsIndex(Throwable cause) {
        super(cause);
    }

    public CouldNotRenameConfigsIndex(String message, Throwable cause, boolean enableSuppression, boolean writableStackTrace) {
        super(message, cause, enableSuppression, writableStackTrace);
    }
}
