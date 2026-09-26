package com.gacs.backend.dto;

import io.swagger.v3.oas.annotations.media.Schema;

@Schema(description = "Standard generic API response envelope wrapper")
public class ApiResponse<T> {

    @Schema(description = "Indicates whether the requested operation succeeded", example = "true")
    private boolean success;

    @Schema(description = "Human-readable status or informational message", example = "Operation completed successfully")
    private String message;

    @Schema(description = "Payload data returned by the operation")
    private T data;

    public ApiResponse() {
    }

    public ApiResponse(boolean success, String message) {
        this.success = success;
        this.message = message;
    }

    public ApiResponse(boolean success, String message, T data) {
        this.success = success;
        this.message = message;
        this.data = data;
    }

    public static <T> ApiResponse<T> ok(String message) {
        return new ApiResponse<>(true, message);
    }

    public static <T> ApiResponse<T> ok(String message, T data) {
        return new ApiResponse<>(true, message, data);
    }

    public static <T> ApiResponse<T> fail(String message) {
        return new ApiResponse<>(false, message);
    }

    public static <T> ApiResponse<T> error(String message) {
        return new ApiResponse<>(false, message);
    }

    public boolean isSuccess() {
        return success;
    }

    public void setSuccess(boolean success) {
        this.success = success;
    }

    public String getMessage() {
        return message;
    }

    public void setMessage(String message) {
        this.message = message;
    }

    public T getData() {
        return data;
    }

    public void setData(T data) {
        this.data = data;
    }
}
