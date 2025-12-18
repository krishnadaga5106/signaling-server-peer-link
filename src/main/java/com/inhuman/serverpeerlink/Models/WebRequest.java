package com.inhuman.serverpeerlink.Models;

import lombok.Data;

@Data
public class WebRequest {
    private String roomCode;
    private String username;
    private MessageType messageType;
    private String message;
}
