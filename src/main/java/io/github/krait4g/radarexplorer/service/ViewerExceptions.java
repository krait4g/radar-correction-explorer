package io.github.krait4g.radarexplorer.service;

public final class ViewerExceptions {

    private ViewerExceptions() {
    }

    public static class BadRequest extends RuntimeException {
        private final String code;

        public BadRequest(String code, String message) {
            super(message);
            this.code = code;
        }

        public String code() {
            return code;
        }
    }

    public static class Unavailable extends RuntimeException {
        private final String code;

        public Unavailable(String code, String message) {
            super(message);
            this.code = code;
        }

        public Unavailable(String code, String message, Throwable cause) {
            super(message, cause);
            this.code = code;
        }

        public String code() {
            return code;
        }
    }

    public static class LimitExceeded extends RuntimeException {
        public LimitExceeded(String message) {
            super(message);
        }
    }
}
