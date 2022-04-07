package org.elasticsearch.app.api.server.exceptions;

import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.ResponseStatus;

@ResponseStatus(HttpStatus.CONFLICT)
public class CouldNotRenameConfigsIndex extends RuntimeException {

    public CouldNotRenameConfigsIndex(String message) {
        super(message);
    }
}
