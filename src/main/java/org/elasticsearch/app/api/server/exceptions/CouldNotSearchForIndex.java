package org.elasticsearch.app.api.server.exceptions;

import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.ResponseStatus;

@ResponseStatus(HttpStatus.CONFLICT)
public class CouldNotSearchForIndex extends RuntimeException {
    public CouldNotSearchForIndex() {
    }

    public CouldNotSearchForIndex(String message) {
        super(message);
    }

    public CouldNotSearchForIndex(String message, Throwable cause) {
        super(message, cause);
    }

    public CouldNotSearchForIndex(Throwable cause) {
        super(cause);
    }

    public CouldNotSearchForIndex(String message, Throwable cause, boolean enableSuppression, boolean writableStackTrace) {
        super(message, cause, enableSuppression, writableStackTrace);
    }
}
